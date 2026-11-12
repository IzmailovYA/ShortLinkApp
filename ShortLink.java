
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Модель короткой ссылки. */
public class ShortLink {
    final String code;
    final String originalUrl;
    final UUID owner;
    final Instant createdAt;
    final Duration ttl;
    final AtomicInteger clicks = new AtomicInteger();
    final AtomicInteger clickLimit;
    final AtomicBoolean limitNoticeSent = new AtomicBoolean(false);

    ShortLink(String code, String originalUrl, UUID owner, Instant createdAt, Duration ttl, int clickLimit) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.owner = owner;
        this.createdAt = createdAt;
        this.ttl = ttl;
        this.clickLimit = new AtomicInteger(clickLimit);
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(createdAt.plus(ttl));
    }

    boolean isLimitReached() {
        return clicks.get() >= clickLimit.get();
    }

    int remainingClicks() {
        return Math.max(clickLimit.get() - clicks.get(), 0);
    }

    String shortUrl() {
        return "clck.ru/" + code;
    }
}
