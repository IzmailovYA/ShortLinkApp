
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Загруженные параметры конфигурации. */
public class AppConfig {
    final int codeLength;
    final long ttlHours;
    final long cleanupPeriodSeconds;
    final int defaultClickLimit;
    final int maxClickLimit;

    private AppConfig(int codeLength, long ttlHours, long cleanupPeriodSeconds,
                      int defaultClickLimit, int maxClickLimit) {
        this.codeLength = codeLength;
        this.ttlHours = ttlHours;
        this.cleanupPeriodSeconds = cleanupPeriodSeconds;
        this.defaultClickLimit = defaultClickLimit;
        this.maxClickLimit = maxClickLimit;
    }

    /** Читает config.properties; при ошибке или отсутствии файла берёт значения по умолчанию. */
    static AppConfig load(Path file) {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Файл конфигурации не читается, беру значения по умолчанию: " + e.getMessage());
            }
        }
        int maxLimit = intValue(props, "app.max.click.limit", 10_000, 1, Integer.MAX_VALUE);
        return new AppConfig(
            intValue(props, "app.code.length", 6, 4, 12),
            longValue(props, "app.ttl.hours", 24, 1, 720),
            longValue(props, "app.cleanup.period.seconds", 30, 1, 3600),
            intValue(props, "app.default.click.limit", 100, 1, maxLimit),
            maxLimit);
    }

    /** Вариант конфигурации для тестов: произвольный TTL и лимиты. */
    static AppConfig forTests(int codeLength, long ttlHours, int defaultClickLimit, int maxClickLimit) {
        return new AppConfig(codeLength, ttlHours, 3600, defaultClickLimit, maxClickLimit);
    }

    private static int intValue(Properties props, String key, int def, int min, int max) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                System.err.println("Параметр " + key + " вне диапазона " + min + ".." + max + ", беру значение по умолчанию.");
                return def;
            }
            return value;
        } catch (NumberFormatException e) {
            System.err.println("Параметр " + key + " не является числом, беру значение по умолчанию.");
            return def;
        }
    }

    private static long longValue(Properties props, String key, long def, long min, long max) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value < min || value > max) {
                System.err.println("Параметр " + key + " вне диапазона " + min + ".." + max + ", беру значение по умолчанию.");
                return def;
            }
            return value;
        } catch (NumberFormatException e) {
            System.err.println("Параметр " + key + " не является числом, беру значение по умолчанию.");
            return def;
        }
    }
}
