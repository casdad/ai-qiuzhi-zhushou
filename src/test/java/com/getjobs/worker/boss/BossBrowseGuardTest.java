package com.getjobs.worker.boss;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import static com.getjobs.worker.boss.BossBrowseGuard.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class BossBrowseGuardTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private AtomicLong now;
    private BossBrowseGuard guard;
    private final BossBrowseGuard.Limits defaults = new BossBrowseGuard.Limits(15, 0, 0, 0, 6);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("browse.db")));
        now = new AtomicLong(1_000_000);
        guard = create(defaults);
    }

    private BossBrowseGuard create(BossBrowseGuard.Limits limits) {
        BossBrowseGuard instance = new BossBrowseGuard(jdbc, now::get, limits);
        instance.initialize();
        return instance;
    }

    private void acquire(BossBrowseGuard.Action action, String search) {
        guard.await(action, search, () -> false, now::addAndGet);
    }

    @Test
    void allReadKindsShareAMinimumIntervalAcrossNewInstances() {
        acquire(SEARCH, "https://www.zhipin.com/web/geek/jobs?query=java");
        long first = now.get();
        guard = create(defaults);
        acquire(SCROLL, null);
        assertEquals(first + 15_000, now.get());
        acquire(DETAIL, null);
        assertEquals(first + 30_000, now.get());
        acquire(DETAIL_PAGE, null);
        assertEquals(first + 45_000, now.get());
        assertEquals(4, count());
    }

    @Test
    void repeatedSearchIsRejectedBeforeChargingAnotherEventAndSurvivesRestart() {
        var enabledCooldown = new BossBrowseGuard.Limits(15, 0, 0, 15, 6);
        guard = create(enabledCooldown);
        String search = "https://www.zhipin.com/web/geek/jobs?query=private-query";
        acquire(SEARCH, search);
        long first = now.get();
        guard = create(enabledCooldown);
        var paused = assertThrows(BossBrowseGuard.BrowsePausedException.class, () -> acquire(SEARCH, search));
        assertEquals(15 * 60_000, paused.retryAfterMillis());
        assertEquals(1, count());
        String stored = jdbc.queryForObject("SELECT search_key FROM boss_browse_event", String.class);
        assertNotNull(stored);
        assertTrue(stored.matches("[a-f0-9]{64}"));
        now.set(first + 15 * 60_000);
        acquire(SEARCH, search);
        assertEquals(2, count());
    }

    @Test
    void disablingSearchCooldownAllowsTheSameSearchButKeepsTheBrowsingInterval() {
        String search = "https://www.zhipin.com/web/geek/jobs?query=same-query";
        guard = create(new BossBrowseGuard.Limits(15, 0, 0, 15, 6));
        acquire(SEARCH, search);
        long first = now.get();
        assertThrows(BossBrowseGuard.BrowsePausedException.class, () -> acquire(SEARCH, search));

        guard = create(defaults);
        acquire(SEARCH, search);
        assertEquals(first + 15_000, now.get());
        guard = create(defaults);
        acquire(SEARCH, search);
        assertEquals(first + 30_000, now.get());
        assertEquals(3, count());
    }

    @Test
    void disablingSearchCooldownDoesNotDisableExplicitBrowseBudgets() {
        guard = create(new BossBrowseGuard.Limits(15, 1, 0, 0, 6));
        String search = "https://www.zhipin.com/web/geek/jobs?query=same-query";
        acquire(SEARCH, search);
        var paused = assertThrows(BossBrowseGuard.BrowsePausedException.class, () -> acquire(SEARCH, search));
        assertEquals(3_600_000, paused.retryAfterMillis());
        assertEquals(1, count());
    }

    @Test
    void zeroBudgetsAllowMoreThanPreviousHourlyAndDailyLimitsAcrossRestarts() {
        long first = now.get();
        for (int i = 0; i < 151; i++) {
            if (i == 60 || i == 150) {
                guard = create(defaults);
            }
            acquire(DETAIL, null);
        }
        assertEquals(151, count());
        assertEquals(first + 150 * 15_000L, now.get());
    }

    @Test
    void changingSearchTermsDoesNotResetTheHourlyBudget() {
        guard = create(new BossBrowseGuard.Limits(1, 2, 0, 15, 6));
        acquire(SEARCH, "https://www.zhipin.com/web/geek/jobs?query=a");
        long first = now.get();
        acquire(SEARCH, "https://www.zhipin.com/web/geek/jobs?query=b");
        guard = create(new BossBrowseGuard.Limits(1, 2, 0, 15, 6));
        var paused = assertThrows(BossBrowseGuard.BrowsePausedException.class,
                () -> acquire(SEARCH, "https://www.zhipin.com/web/geek/jobs?query=c"));
        assertEquals(first + 3_600_000 - now.get(), paused.retryAfterMillis());
        now.set(first + 3_600_000);
        acquire(DETAIL, null);
        assertEquals(3, count());
    }

    @Test
    void dailyBudgetUsesARollingWindowNotMidnightOrSessionBoundaries() {
        guard = create(new BossBrowseGuard.Limits(1, 0, 2, 15, 6));
        acquire(DETAIL, null);
        long first = now.get();
        now.addAndGet(3_600_000);
        acquire(DETAIL_PAGE, null);
        var paused = assertThrows(BossBrowseGuard.BrowsePausedException.class, () -> acquire(SCROLL, null));
        assertEquals(23 * 3_600_000L, paused.retryAfterMillis());
        now.set(first + 24 * 3_600_000L);
        acquire(SCROLL, null);
        assertEquals(2, count());
    }

    @Test
    void cancellationOrRestrictionDuringWaitingDoesNotGrantAnotherPermit() {
        acquire(DETAIL, null);
        assertThrows(CancellationException.class,
                () -> guard.await(SCROLL, null, () -> true, millis -> fail("Must not wait")));
        assertThrows(BossOperationGate.DeliveryPausedException.class,
                () -> guard.await(SCROLL, null, () -> false, millis -> {
                    throw new BossOperationGate.DeliveryPausedException("Platform restriction appeared");
                }));
        assertEquals(1, count());
    }

    @Test
    void cacheSurvivesRestartExpiresAndContainsNoApiTokens() {
        JSONObject response = detail("job-1");
        response.put("token", "top-level-secret");
        response.getJSONObject("zpData").getJSONObject("jobInfo").put("securityId", "private-token");
        guard.cacheDetail("job-1", response);
        guard = create(defaults);
        String cached = guard.cachedDetail("job-1");
        assertNotNull(cached);
        assertFalse(cached.contains("secret"));
        assertFalse(cached.contains("private-token"));
        assertTrue(cached.contains("Engineer"));
        assertEquals(0, count());
        now.addAndGet(6 * 3_600_000L);
        assertNull(guard.cachedDetail("job-1"));
    }

    @Test
    void unsuccessfulOrMismatchedDetailsNeverPoisonTheCache() {
        assertThrows(IllegalArgumentException.class, () -> guard.cacheDetail("job-1", detail("job-2")));
        assertThrows(IllegalArgumentException.class, () -> guard.cacheDetail("job-1", detail("job-1").put("code", 37)));
        JSONObject incomplete = detail("job-1");
        incomplete.getJSONObject("zpData").remove("brandComInfo");
        assertThrows(IllegalArgumentException.class, () -> guard.cacheDetail("job-1", incomplete));
        assertNull(guard.cachedDetail("job-1"));
        assertNull(guard.cachedDetail("job-2"));
    }

    @Test
    void movingTheClockBackDoesNotMakeAFutureCacheEntryFresh() {
        guard.cacheDetail("job-1", detail("job-1"));
        now.addAndGet(-1000);
        assertNull(guard.cachedDetail("job-1"));
    }

    @Test
    void invalidIntervalsAndNegativeBudgetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BossBrowseGuard.Limits(0, 60, 150, 15, 6));
        assertThrows(IllegalArgumentException.class, () -> new BossBrowseGuard.Limits(15, -1, 0, 15, 6));
        assertThrows(IllegalArgumentException.class, () -> new BossBrowseGuard.Limits(15, 0, -1, 15, 6));
        assertThrows(IllegalArgumentException.class, () -> new BossBrowseGuard.Limits(15, 60, 150, -1, 6));
        assertThrows(IllegalArgumentException.class, () -> new BossBrowseGuard.Limits(15, 60, 150, 1441, 6));
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM boss_browse_event", Integer.class);
    }

    private static JSONObject detail(String id) {
        return new JSONObject("""
                {"code":0,"zpData":{"jobInfo":{"encryptId":"%s","jobName":"Engineer","postDescription":"Java"},
                "brandComInfo":{"brandName":"Example"},"bossInfo":{"name":"Recruiter"}}}
                """.formatted(id));
    }
}
