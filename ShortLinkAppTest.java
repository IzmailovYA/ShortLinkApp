import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Тесты ключевых сценариев из ТЗ. Время подменяется вручную — ждать сутки не нужно. */
class ShortLinkAppTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String URL = "https://www.baeldung.com/java-9-http-client";

    /** Управляемые часы для проверки срока жизни без ожидания. */
    private static final class FakeClock {
        Instant now = START;

        Supplier<Instant> supplier() {
            return () -> now;
        }
    }

    private static AppConfig config() {
        return AppConfig.forTests(6, 24, 10, 1000);
    }

    // --- Сокращение и уникальность ---

    @Test
    void oneUrlForDifferentUsersGetsDifferentCodes() {
        LinkService service = new LinkService(config());
        ShortLink a = service.create(URL, UUID.randomUUID(), 5);
        ShortLink b = service.create(URL, UUID.randomUUID(), 5);
        assertNotEquals(a.code, b.code);
    }

    @Test
    void codeLengthMatchesConfig() {
        LinkService service = new LinkService(config());
        ShortLink link = service.create(URL, UUID.randomUUID(), 5);
        assertEquals(6, link.code.length());
    }

    @Test
    void shortUrlHasExpectedPrefix() {
        LinkService service = new LinkService(config());
        ShortLink link = service.create(URL, UUID.randomUUID(), 5);
        assertEquals("clck.ru/" + link.code, link.shortUrl());
    }

    @Test
    void userCanHaveSeveralLinks() {
        LinkService service = new LinkService(config());
        UUID owner = UUID.randomUUID();
        service.create("https://example.com/one", owner, 5);
        service.create("https://example.com/two", owner, 5);
        service.create("https://example.com/three", owner, 5);
        assertEquals(3, service.linksOf(owner).size());
    }

    // --- Переход по ссылке ---

    @Test
    void openReturnsOriginalUrl() {
        LinkService service = new LinkService(config());
        ShortLink link = service.create(URL, UUID.randomUUID(), 5);
        assertEquals(URL, service.open(link.code));
    }

    @Test
    void unknownCodeIsNotAccessible() {
        LinkService service = new LinkService(config());
        assertNull(service.open("zzzzzz"));
    }

    // --- Лимит переходов ---

    @Test
    void opensBlockedAfterLimitReached() {
        LinkService service = new LinkService(config());
        ShortLink link = service.create(URL, UUID.randomUUID(), 2);
        assertEquals(URL, service.open(link.code));
        assertEquals(URL, service.open(link.code));
        assertNull(service.open(link.code));
    }

    @Test
    void ownerGetsLimitNotification() {
        LinkService service = new LinkService(config());
        UUID owner = UUID.randomUUID();
        ShortLink link = service.create(URL, owner, 1);
        service.open(link.code);
        List<String> notices = service.takeNotifications(owner);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("лимит переходов"));
    }

    @Test
    void limitNotificationSentOnlyOnce() {
        LinkService service = new LinkService(config());
        UUID owner = UUID.randomUUID();
        ShortLink link = service.create(URL, owner, 1);
        service.open(link.code);
        service.open(link.code);
        service.open(link.code);
        assertEquals(1, service.takeNotifications(owner).size());
        service.open(link.code);
        assertTrue(service.takeNotifications(owner).isEmpty());
    }

    // --- Срок жизни и автоудаление ---

    @Test
    void expiredLinkIsNotAccessible() {
        FakeClock clock = new FakeClock();
        LinkService service = new LinkService(
                AppConfig.forTests(6, 1, 10, 1000), clock.supplier());
        ShortLink link = service.create(URL, UUID.randomUUID(), 10);
        clock.now = START.plus(Duration.ofHours(2));
        assertNull(service.open(link.code));
    }

    @Test
    void removeExpiredDeletesOnlyExpiredLinks() {
        FakeClock clock = new FakeClock();
        LinkService service = new LinkService(
                AppConfig.forTests(6, 1, 10, 1000), clock.supplier());
        ShortLink first = service.create(URL, UUID.randomUUID(), 10);
        clock.now = START.plus(Duration.ofHours(25));
        ShortLink second = service.create("https://example.com/fresh", UUID.randomUUID(), 10);
        assertEquals(1, service.removeExpired(clock.now));
        assertNull(service.find(first.code));
        assertNotNull(service.find(second.code));
    }

    @Test
    void ownerNotifiedAboutExpiry() {
        FakeClock clock = new FakeClock();
        LinkService service = new LinkService(
                AppConfig.forTests(6, 1, 10, 1000), clock.supplier());
        UUID owner = UUID.randomUUID();
        ShortLink link = service.create(URL, owner, 10);
        clock.now = START.plus(Duration.ofHours(2));
        service.removeExpired(clock.now);
        List<String> notices = service.takeNotifications(owner);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("истёк срок жизни"));
    }

    @Test
    void shortIntervalExpiryOneHour() {
        FakeClock clock = new FakeClock();
        LinkService service = new LinkService(
                AppConfig.forTests(6, 1, 10, 1000), clock.supplier());
        ShortLink link = service.create(URL, UUID.randomUUID(), 10);
        clock.now = START.plus(Duration.ofHours(1));
        assertTrue(link.isExpired(clock.now));
    }

    // --- UUID и права на редактирование/удаление ---

    @Test
    void ownerCanChangeLimit() {
        LinkService service = new LinkService(config());
        UUID owner = UUID.randomUUID();
        ShortLink link = service.create(URL, owner, 1);
        service.open(link.code);
        assertNull(service.open(link.code));
        assertTrue(service.changeClickLimit(link.code, owner, 3));
        assertEquals(URL, service.open(link.code));
    }

    @Test
    void strangerCannotChangeLimit() {
        LinkService service = new LinkService(config());
        ShortLink link = service.create(URL, UUID.randomUUID(), 1);
        assertFalse(service.changeClickLimit(link.code, UUID.randomUUID(), 5));
        assertEquals(1, service.find(link.code).clickLimit.get());
    }

    @Test
    void ownerCanDelete() {
        LinkService service = new LinkService(config());
        UUID owner = UUID.randomUUID();
        ShortLink link = service.create(URL, owner, 5);
        assertTrue(service.delete(link.code, owner));
        assertNull(service.find(link.code));
    }

    @Test
    void strangerCannotDelete() {
        LinkService service = new LinkService(config());
        ShortLink link = service.create(URL, UUID.randomUUID(), 5);
        assertFalse(service.delete(link.code, UUID.randomUUID()));
        assertNotNull(service.find(link.code));
    }

    // --- Валидация входных данных ---

    @Test
    void invalidUrlsRejected() {
        LinkService service = new LinkService(config());
        UUID owner = UUID.randomUUID();
        String[] bad = {"", "   ", "ftp://example.com", "not a url", "https://"};
        for (String badUrl : bad) {
            String current = badUrl;
            assertThrows(IllegalArgumentException.class, () -> service.create(current, owner, 5),
                    "Ожидалась ошибка для: '" + current + "'");
        }
    }

    @Test
    void invalidLimitsRejected() {
        LinkService service = new LinkService(config());
        UUID owner = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service.create(URL, owner, 0));
        assertThrows(IllegalArgumentException.class, () -> service.create(URL, owner, -3));
        assertThrows(IllegalArgumentException.class, () -> service.create(URL, owner, 1001));
        assertThrows(IllegalArgumentException.class,
                () -> service.changeClickLimit("whatever", owner, -1));
    }
}
