import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Вся бизнес-логика: создание, переходы, лимиты, удаление, уведомления. */
public final class LinkService {
    /** Алфавит кодов без похожих символов: нет 0, O, 1, I и l — коды удобно вводить руками. */
    private static final String ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AppConfig config;
    private final Supplier<Instant> clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, ShortLink> links = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> notifications = new ConcurrentHashMap<>();

    LinkService(AppConfig config, Supplier<Instant> clock) {
        this.config = config;
        this.clock = clock;
    }

    LinkService(AppConfig config) {
        this(config, Instant::now);
    }

    /** Создаёт короткую ссылку. Бросает IllegalArgumentException при неверных данных. */
    synchronized ShortLink create(String url, UUID owner, int clickLimit) {
        String normalized = validateUrl(url);
        if (owner == null) {
            throw new IllegalArgumentException("Не указан владелец ссылки.");
        }
        if (clickLimit < 1 || clickLimit > config.maxClickLimit) {
            throw new IllegalArgumentException("Лимит переходов должен быть от 1 до " + config.maxClickLimit + ".");
        }
        String code = newUniqueCode();
        ShortLink link = new ShortLink(code, normalized, owner, clock.get(),
            Duration.ofHours(config.ttlHours), clickLimit);
        links.put(code, link);
        return link;
    }

    /** Переход по ссылке: возвращает исходный URL либо null, если ссылка недоступна. */
    synchronized String open(String code) {
        return open(code, clock.get());
    }

    synchronized String open(String code, Instant now) {
        ShortLink link = links.get(code);
        if (link == null) {
            return null;
        }
        if (link.isExpired(now)) {
            removeLinkInternal(link, "истёк срок жизни");
            return null;
        }
        if (link.isLimitReached()) {
            if (link.limitNoticeSent.compareAndSet(false, true)) {
                notifyOwner(link.owner, "Ссылка " + link.shortUrl() + " заблокирована: лимит переходов исчерпан. Создайте новую ссылку, чтобы продолжить.");
            }
            return null;
        }
        int used = link.clicks.incrementAndGet();
        if (used >= link.clickLimit.get() && link.limitNoticeSent.compareAndSet(false, true)) {
            notifyOwner(link.owner, "Ссылка " + link.shortUrl() + " больше недоступна: достигнут лимит переходов (" + link.clickLimit.get() + ").");
        }
        return link.originalUrl;
    }

    /** Меняет лимит переходов. Только владелец. Возвращает true при успехе. */
    synchronized boolean changeClickLimit(String code, UUID requester, int newLimit) {
        if (newLimit < 1 || newLimit > config.maxClickLimit) {
            throw new IllegalArgumentException("Лимит переходов должен быть от 1 до " + config.maxClickLimit + ".");
        }
        ShortLink link = links.get(code);
        if (link == null || !link.owner.equals(requester)) {
            return false;
        }
        link.clickLimit.set(newLimit);
        if (newLimit > link.clicks.get()) {
            link.limitNoticeSent.set(false);
        }
        return true;
    }

    /** Удаляет ссылку. Только владелец. */
    synchronized boolean delete(String code, UUID requester) {
        ShortLink link = links.get(code);
        if (link == null || !link.owner.equals(requester)) {
            return false;
        }
        links.remove(code);
        return true;
    }

    /** Ссылки пользователя в порядке создания. */
    synchronized List<ShortLink> linksOf(UUID owner) {
        List<ShortLink> result = new ArrayList<>();
        for (ShortLink link : links.values()) {
            if (link.owner.equals(owner)) {
                result.add(link);
            }
        }
        result.sort(Comparator.comparing(l -> l.createdAt));
        return result;
    }

    /** Удаляет истёкшие ссылки, возвращает их количество. */
    synchronized int removeExpired(Instant now) {
        int removed = 0;
        for (ShortLink link : new ArrayList<>(links.values())) {
            if (link.isExpired(now)) {
                links.remove(link.code);
                notifyOwner(link.owner, "Ссылка " + link.shortUrl() + " (" + link.originalUrl + ") удалена: истёк срок жизни.");
                removed++;
            }
        }
        return removed;
    }

    /** Забирает уведомления пользователя; после вызова список пуст. */
    List<String> takeNotifications(UUID user) {
        List<String> taken = new ArrayList<>(notifications.getOrDefault(user, List.of()));
        notifications.remove(user);
        return taken;
    }

    int notificationCount(UUID user) {
        return notifications.getOrDefault(user, List.of()).size();
    }

    ShortLink find(String code) {
        return links.get(code);
    }

    int size() {
        return links.size();
    }

    private void removeLinkInternal(ShortLink link, String reason) {
        links.remove(link.code);
        notifyOwner(link.owner, "Ссылка " + link.shortUrl() + " (" + link.originalUrl + ") удалена: " + reason + ".");
    }

    private String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL не может быть пустым.");
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("Поддерживаются только ссылки http и https.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("В ссылке нет адреса сайта.");
            }
            return trimmed;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Это не похоже на ссылку: " + e.getMessage());
        }
    }

    private String newUniqueCode() {
        while (true) {
            StringBuilder sb = new StringBuilder(config.codeLength);
            for (int i = 0; i < config.codeLength; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            if (!links.containsKey(code)) {
                return code;
            }
        }
    }

    private void notifyOwner(UUID owner, String message) {
        List<String> list = notifications.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(message)) {
            list.add(message);
        }
    }
}
