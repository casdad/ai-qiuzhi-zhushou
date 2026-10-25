package com.getjobs.worker.boss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BossOperationGateTest {
    @Test
    void intervalIsAMinimumAndSharedAcrossCallers() {
        AtomicLong clock = new AtomicLong();
        BossOperationGate gate = new BossOperationGate(clock::get, () -> 0);
        gate.await("session", 10, () -> false, millis -> clock.addAndGet(millis * 1_000_000));
        assertEquals(0, clock.get());
        gate.await("session", 10, () -> false, millis -> clock.addAndGet(millis * 1_000_000));
        assertEquals(10_000_000_000L, clock.get());
        gate.await("session", 10, () -> false, millis -> clock.addAndGet(millis * 1_000_000));
        assertEquals(20_000_000_000L, clock.get());
    }

    @Test
    void preparedMessagesAlwaysWaitAFullIntervalEvenAfterAnIdlePeriod() {
        AtomicLong clock = new AtomicLong();
        BossOperationGate gate = new BossOperationGate(clock::get, () -> 0);
        gate.awaitAfterPreparation("session", 120, () -> false, millis -> clock.addAndGet(millis * 1_000_000));
        assertEquals(120_000_000_000L, clock.get());
        clock.addAndGet(300_000_000_000L);
        gate.awaitAfterPreparation("session", 200, () -> false, millis -> clock.addAndGet(millis * 1_000_000));
        assertEquals(620_000_000_000L, clock.get());
    }

    @Test
    void firstPreparedMessageCanBeCancelledBeforeAnyPermitIsGranted() {
        AtomicLong clock = new AtomicLong();
        AtomicBoolean cancelled = new AtomicBoolean();
        BossOperationGate gate = new BossOperationGate(clock::get, () -> 0);
        assertThrows(CancellationException.class, () -> gate.awaitAfterPreparation("session", 120,
                cancelled::get, millis -> cancelled.set(true)));
        cancelled.set(false);
        assertDoesNotThrow(() -> gate.await("session", 120, cancelled::get, millis -> fail("No prior permit")));
    }

    @Test
    void restrictionDuringPreparedWaitStillStopsBeforeGrantingPermit() {
        BossOperationGate gate = new BossOperationGate(() -> 0, () -> 0);
        assertThrows(BossOperationGate.DeliveryPausedException.class,
                () -> gate.awaitAfterPreparation("session", 120, () -> false,
                        millis -> gate.hold("session", "Verification required")));
        assertEquals("Verification required", gate.holdReason("session"));
    }

    @Test
    void cancellationInterruptsWaitingWithoutGrantingPermit() {
        AtomicLong clock = new AtomicLong();
        AtomicBoolean cancelled = new AtomicBoolean();
        BossOperationGate gate = new BossOperationGate(clock::get, () -> 0);
        gate.await("session", 10, cancelled::get, millis -> fail());
        assertThrows(CancellationException.class, () -> gate.await("session", 10, cancelled::get,
                millis -> cancelled.set(true)));
    }

    @Test
    void restrictionDoesNotAutomaticallyExpireAndHonorsRetryAfter() {
        AtomicLong millis = new AtomicLong(1000);
        BossOperationGate gate = new BossOperationGate(() -> 0, millis::get);
        gate.hold("session", "rate limited", 60_000);
        assertThrows(BossOperationGate.DeliveryPausedException.class, () -> gate.resume("session"));
        millis.set(61_000);
        assertThrows(BossOperationGate.DeliveryPausedException.class,
                () -> gate.await("session", 10, () -> false, ignored -> fail()));
        gate.resume("session");
        assertNull(gate.holdReason("session"));
    }

    @Test
    void holdSurvivesRestart(@TempDir Path directory) {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("gate.db"));
        BossOperationGate first = new BossOperationGate(source);
        first.restore();
        first.hold("session", "needs review");
        BossOperationGate restarted = new BossOperationGate(source);
        restarted.restore();
        assertEquals("needs review", restarted.holdReason("session"));
        restarted.resume("session");
        BossOperationGate finalRestart = new BossOperationGate(source);
        finalRestart.restore();
        assertNull(finalRestart.holdReason("session"));
    }

    @Test
    void disabledSearchCooldownClearsOnlyItsOwnLegacyHoldsOnRestart(@TempDir Path directory) {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("gate.db"));
        BossOperationGate first = new BossOperationGate(source);
        first.restore();
        first.hold("local-search", BossBrowseGuard.SEARCH_COOLDOWN_REASON, 900_000);
        first.hold("platform", "Platform verification required", 900_000);
        first.hold("unknown-send", "Send result unknown; review required");
        first.hold("mixed-reason", BossBrowseGuard.SEARCH_COOLDOWN_REASON + "; platform restriction");

        BossOperationGate restarted = new BossOperationGate(source);
        restarted.restore();
        assertNull(restarted.holdReason("local-search"));
        assertDoesNotThrow(() -> restarted.await("local-search", 120, () -> false, ignored -> fail()));
        assertEquals("Platform verification required", restarted.holdReason("platform"));
        assertEquals("Send result unknown; review required", restarted.holdReason("unknown-send"));
        assertNotNull(restarted.holdReason("mixed-reason"));
        assertThrows(BossOperationGate.DeliveryPausedException.class, () -> restarted.resume("platform"));
        assertEquals(3, new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM boss_safety_hold", Integer.class));

        BossOperationGate again = new BossOperationGate(source);
        again.restore();
        assertNull(again.holdReason("local-search"));
        assertNotNull(again.holdReason("platform"));
    }

    @Test
    void enabledSearchCooldownKeepsItsExistingHold(@TempDir Path directory) {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("gate.db"));
        BossOperationGate first = new BossOperationGate(source);
        first.restore();
        first.hold("local-search", BossBrowseGuard.SEARCH_COOLDOWN_REASON, 900_000);

        BossOperationGate restarted = new BossOperationGate(source);
        ReflectionTestUtils.setField(restarted, "searchCooldownMinutes", 1);
        restarted.restore();
        assertEquals(BossBrowseGuard.SEARCH_COOLDOWN_REASON, restarted.holdReason("local-search"));
        assertThrows(BossOperationGate.DeliveryPausedException.class, () -> restarted.resume("local-search"));
    }

    @Test
    void parsesRetryAfterWithoutShorteningLongIntervals() {
        assertEquals(60_000L, Boss.retryAfterMillis("60"));
        assertEquals(30L * 24 * 3600 * 1000, Boss.retryAfterMillis("2592000"));
        assertEquals(0, Boss.retryAfterMillis("invalid"));
        assertEquals(0, Boss.retryAfterMillis("-1"));
    }
}
