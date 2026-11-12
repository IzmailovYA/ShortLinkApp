import java.net.URI;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ShortLinkApp {

        private static final Scanner SCANNER = new Scanner(System.in);

    public static void main(String[] args) {
        AppConfig config = AppConfig.load(Paths.get("config.properties"));
        LinkService service = new LinkService(config);

        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "expiry-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        cleaner.scheduleAtFixedRate(() -> service.removeExpired(Instant.now()),
                config.cleanupPeriodSeconds, config.cleanupPeriodSeconds, TimeUnit.SECONDS);

        UUID currentUser = askUserId();
        printHelp();
        boolean running = true;
        while (running) {
            System.out.print("> ");
            String line = SCANNER.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                running = handle(line, service, currentUser, config);
            } catch (Exception e) {
                System.out.println("Ошибка: " + e.getMessage() + ". Попробуйте ещё раз.");
            }
        }
        cleaner.shutdownNow();
        System.out.println("До встречи! Сохраните свой UUID, чтобы вернуться к ссылкам: " + currentUser);
    }

    private static UUID askUserId() {
        System.out.println("Здравствуйте! Это сервис сокращения ссылок.");
        while (true) {
            System.out.print("Введите ваш UUID (Enter — создать новый): ");
            String line = SCANNER.nextLine().trim();
            if (line.isEmpty()) {
                UUID fresh = UUID.randomUUID();
                System.out.println("Создан новый профиль. Сохраните UUID — по нему вы вернётесь к своим ссылкам:");
                System.out.println("  " + fresh);
                return fresh;
            }
            try {
                UUID id = UUID.fromString(line);
                System.out.println("Профиль принят: " + id);
                return id;
            } catch (IllegalArgumentException e) {
                System.out.println("UUID выглядит как 550e8400-e29b-41d4-a716-446655440000. Попробуйте ещё раз или нажмите Enter.");
            }
        }
    }

    private static boolean handle(String input, LinkService service, UUID user, AppConfig config) {
        switch (input) {
            case "0":
            case "выход":
                return false;
            case "1":
                createFlow(service, user, config);
                break;
            case "2":
                openFlow(service, user);
                break;
            case "3":
                changeLimitFlow(service, user, config);
                break;
            case "4":
                deleteFlow(service, user);
                break;
            case "5":
                listFlow(service, user);
                break;
            case "6":
                notificationsFlow(service, user);
                break;
            case "7":
            case "помощь":
                printHelp();
                break;
            default:
                System.out.println("Неизвестная команда. Введите 7, чтобы увидеть список команд.");
        }
        return true;
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("Команды:");
        System.out.println("  1 — сократить ссылку (пример: https://www.baeldung.com/java-9-http-client)");
        System.out.println("  2 — перейти по короткой ссылке (пример: clck.ru/3DZHeG)");
        System.out.println("  3 — изменить лимит переходов своей ссылки");
        System.out.println("  4 — удалить свою ссылку");
        System.out.println("  5 — показать мои ссылки");
        System.out.println("  6 — показать уведомления");
        System.out.println("  7 — помощь");
        System.out.println("  0 — выход");
    }

    private static void createFlow(LinkService service, UUID user, AppConfig config) {
        System.out.print("Длинная ссылка: ");
        String url = SCANNER.nextLine();
        System.out.print("Лимит переходов (Enter — по умолчанию " + config.defaultClickLimit + "): ");
        String limitLine = SCANNER.nextLine().trim();
        int limit = config.defaultClickLimit;
        if (!limitLine.isEmpty()) {
            try {
                limit = Integer.parseInt(limitLine);
            } catch (NumberFormatException e) {
                System.out.println("Лимит должен быть числом. Прерываю создание.");
                return;
            }
        }
        try {
            ShortLink link = service.create(url, user, limit);
            System.out.println("Готово! Короткая ссылка: " + link.shortUrl());
            System.out.println("Исходная: " + link.originalUrl);
            System.out.println("Лимит переходов: " + link.clickLimit.get() + ", срок жизни: " + config.ttlHours + " ч.");
        } catch (IllegalArgumentException e) {
            System.out.println("Не получилось: " + e.getMessage());
        }
    }

    private static void openFlow(LinkService service, UUID user) {
        System.out.print("Короткая ссылка или код: ");
        String code = stripPrefix(SCANNER.nextLine());
        String url = service.open(code);
        if (url == null) {
            System.out.println("Ссылка недоступна: не найдена, лимит исчерпан или срок жизни истёк.");
            showNewNotifications(service, user);
        } else {
            openInBrowser(url);
        }
    }

    private static void changeLimitFlow(LinkService service, UUID user, AppConfig config) {
        System.out.print("Код ссылки: ");
        String code = stripPrefix(SCANNER.nextLine());
        System.out.print("Новый лимит переходов: ");
        String line = SCANNER.nextLine().trim();
        try {
            int limit = Integer.parseInt(line);
            if (service.changeClickLimit(code, user, limit)) {
                System.out.println("Лимит обновлён: теперь " + limit + " переходов.");
            } else {
                System.out.println("Не удалось: ссылка не найдена или принадлежит другому пользователю.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Лимит должен быть целым числом.");
        } catch (IllegalArgumentException e) {
            System.out.println("Не получилось: " + e.getMessage());
        }
    }

    private static void deleteFlow(LinkService service, UUID user) {
        System.out.print("Код ссылки: ");
        String code = stripPrefix(SCANNER.nextLine());
        ShortLink link = service.find(code);
        if (link == null || !link.owner.equals(user)) {
            System.out.println("Не удалось: ссылка не найдена или принадлежит другому пользователю.");
            return;
        }
        System.out.print("Удалить " + link.shortUrl() + " (" + link.originalUrl + ")? y/n: ");
        String answer = SCANNER.nextLine().trim().toLowerCase(Locale.ROOT);
        if (answer.equals("y") || answer.equals("yes") || answer.equals("д") || answer.equals("да")) {
            service.delete(code, user);
            System.out.println("Ссылка удалена.");
        } else {
            System.out.println("Отменено.");
        }
    }

    private static void listFlow(LinkService service, UUID user) {
        List<ShortLink> links = service.linksOf(user);
        if (links.isEmpty()) {
            System.out.println("У вас пока нет ссылок. Введите 1, чтобы создать первую.");
            return;
        }
        Instant now = Instant.now();
        System.out.println("Ваши ссылки:");
        for (ShortLink link : links) {
            String status;
            if (link.isExpired(now)) {
                status = "истёк срок жизни";
            } else if (link.isLimitReached()) {
                status = "лимит исчерпан";
            } else {
                status = "активна, осталось переходов: " + link.remainingClicks();
            }
            System.out.println("  " + link.shortUrl() + " -> " + link.originalUrl + " [" + status + "]");
        }
    }

    private static void notificationsFlow(LinkService service, UUID user) {
        List<String> items = service.takeNotifications(user);
        if (items.isEmpty()) {
            System.out.println("Новых уведомлений нет.");
            return;
        }
        System.out.println("Уведомления:");
        for (String item : items) {
            System.out.println("  - " + item);
        }
    }

    /** Показывает уведомления, только если они есть (без пустой строки-отписи). */
    private static void showNewNotifications(LinkService service, UUID user) {
        List<String> items = service.takeNotifications(user);
        if (!items.isEmpty()) {
            System.out.println("Уведомления:");
            for (String item : items) {
                System.out.println("  - " + item);
            }
        }
    }

    /** Принимает и короткий код, и полную короткую ссылку вида clck.ru/AbC123. */
    private static String stripPrefix(String line) {
        String trimmed = line.trim();
        int idx = trimmed.indexOf("clck.ru/");
        if (idx >= 0) {
            return trimmed.substring(idx + "clck.ru/".length()).trim();
        }
        return trimmed;
    }

    private static void openInBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new URI(url));
                System.out.println("Ссылка открыта в браузере: " + url);
                return;
            }
        } catch (Exception e) {
            System.out.println("Не удалось открыть браузер: " + e.getMessage());
        }
        System.out.println("Откройте ссылку вручную: " + url);
    }
}
