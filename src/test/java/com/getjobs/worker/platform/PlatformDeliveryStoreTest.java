package com.getjobs.worker.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static com.getjobs.worker.platform.RecruitmentPlatform.*;

class PlatformDeliveryStoreTest {
    @TempDir Path directory;
    PlatformDeliveryStore store;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("ledger.db")));
        store = new PlatformDeliveryStore(jdbc, new ObjectMapper());
        store.initialize();
    }

    static PlatformCandidate job(RecruitmentPlatform p, String id) {
        String url = switch (p) {
            case LIEPIN -> "https://www.liepin.com/job/" + id + ".shtml";
            case JOB51 -> "https://we.51job.com/pc/jobdetail?jobId=" + id;
            case ZHILIAN -> "https://www.zhaopin.com/jobdetail/" + id + ".htm";
        };
        return new PlatformCandidate(id, url, "Engineer " + id, "Fixture company", "10K", "Test city",
                new PlatformCandidate.Search("https://www." + p.domain + "/search", "C++ & R&D"));
    }

    @Test void defaultsAreConfirmationAndSettingsArePlatformScoped() {
        for (var p : values()) assertEquals("CONFIRM", store.mode(p));
        store.setMode(LIEPIN, "AUTO");
        assertEquals("AUTO", store.mode(LIEPIN));
        assertEquals("CONFIRM", store.mode(JOB51));
        assertThrows(IllegalArgumentException.class, () -> store.setMode(JOB51, "auto-ish"));
    }

    @Test void scopesAndPlatformsDoNotShareClaimsOrDrafts() {
        for (var p : values()) {
            var draft = store.prepare(p, "account-a", job(p, "12345")).orElseThrow();
            assertTrue(store.prepare(p, "account-a", job(p, "12345")).isEmpty());
            assertFalse(store.claim(p, "account-b", draft.id()));
            assertTrue(store.claim(p, "account-a", draft.id()));
            assertFalse(store.claim(p, "account-a", draft.id()));
            store.finish(p, draft.id(), "UNKNOWN", "fixture");
            assertTrue(store.known(p, "account-a", "12345"));
            assertTrue(store.prepare(p, "account-a", job(p, "12345")).isEmpty());
            assertEquals(1, store.list(p).size());
        }
        String id = store.list(LIEPIN).getFirst().id();
        assertTrue(store.find(JOB51, id).isEmpty());
        assertFalse(store.dismiss(JOB51, id));
    }

    @Test void modeChangeCannotClaimOldPendingDraftsImplicitly() {
        var draft = store.prepare(LIEPIN, "scope", job(LIEPIN, "12345")).orElseThrow();
        store.setMode(LIEPIN, "AUTO");
        assertTrue(store.prepare(LIEPIN, "scope", job(LIEPIN, "12345")).isEmpty());
        assertEquals("PENDING", store.find(LIEPIN, draft.id()).orElseThrow().status());
    }

    @Test void credentialRotationCannotReplayAttemptedJobsOrClaimAnOlderPendingCopy() {
        var old = store.prepare(LIEPIN, "old", job(LIEPIN, "12345")).orElseThrow();
        var fresh = store.prepare(LIEPIN, "fresh", job(LIEPIN, "12345")).orElseThrow();
        assertTrue(store.claim(LIEPIN, "fresh", fresh.id()));
        store.finish(LIEPIN, fresh.id(), "UNKNOWN", "fixture");
        assertFalse(store.claim(LIEPIN, "old", old.id()));
        assertTrue(store.known(LIEPIN, "rotated", "12345"));
        assertTrue(store.prepare(LIEPIN, "rotated", job(LIEPIN, "12345")).isEmpty());
        assertTrue(store.prepare(JOB51, "rotated", job(JOB51, "12345")).isPresent());
    }

    @Test void dailyBudgetPersistsAcrossRestartsAndManualRecoveryDoesNotResetIt() {
        long start = 100_000_000;
        for (int i = 0; i < 150; i++) assertEquals(0, store.reserve(LIEPIN, "BROWSE", null, start + i * 120_000L));
        var restarted = new PlatformDeliveryStore(jdbc, new ObjectMapper());
        long now = start + 150 * 120_000L;
        assertThrows(PlatformDeliveryStore.Paused.class, () -> restarted.reserve(LIEPIN, "BROWSE", null, now));
        assertEquals(start + 86_400_000, restarted.hold(LIEPIN).orElseThrow().retryAt());
        assertThrows(PlatformDeliveryStore.Paused.class, () -> restarted.resume(LIEPIN, now));
        restarted.resume(LIEPIN, start + 86_400_000);
        assertEquals(0, restarted.reserve(LIEPIN, "BROWSE", null, start + 86_400_000));
    }

    @Test void restartConvertsInflightToUnknownAndRequiresReview() {
        var draft = store.prepare(ZHILIAN, "scope", job(ZHILIAN, "CC12345")).orElseThrow();
        assertTrue(store.claim(ZHILIAN, "scope", draft.id()));
        store.initialize();
        assertEquals("UNKNOWN", store.find(ZHILIAN, draft.id()).orElseThrow().status());
        assertTrue(store.hold(ZHILIAN).isPresent());
        store.resume(ZHILIAN, System.currentTimeMillis());
        assertFalse(store.claim(ZHILIAN, "scope", draft.id()));
    }

    @Test void concurrentConfirmationClaimsExactlyOnce() throws Exception {
        var draft = store.prepare(JOB51, "scope", job(JOB51, "12345")).orElseThrow();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> store.claim(JOB51, "scope", draft.id()));
            var b = executor.submit(() -> store.claim(JOB51, "scope", draft.id()));
            assertNotEquals(a.get(), b.get());
        }
    }

    @Test void browsingAndSendingHaveSeparatePersistentIntervals() {
        long now = 10_000_000;
        assertEquals(0, store.reserve(LIEPIN, "BROWSE", null, now));
        assertEquals(15_000, store.reserve(LIEPIN, "BROWSE", null, now));
        assertEquals(0, store.reserve(LIEPIN, "SEND", null, now));
        assertEquals(120_000, store.reserve(LIEPIN, "SEND", null, now));
        store.recordAttempt(LIEPIN, now + 50_000);
        var restarted = new PlatformDeliveryStore(jdbc, new ObjectMapper());
        assertEquals(70_000, restarted.reserve(LIEPIN, "SEND", null, now + 100_000));
        assertEquals(0, store.reserve(JOB51, "BROWSE", null, now));
    }

    @Test void repeatSearchAndCapsRequireCooldownAndExplicitResume() {
        long now = 10_000_000;
        store.reserve(LIEPIN, "SEARCH", "fixture-query", now);
        assertThrows(PlatformDeliveryStore.Paused.class, () -> store.reserve(LIEPIN, "SEARCH", "fixture-query", now + 16_000));
        assertThrows(PlatformDeliveryStore.Paused.class, () -> store.resume(LIEPIN, now + 16_000));
        store.resume(LIEPIN, now + 900_000);
        assertEquals(0, store.reserve(LIEPIN, "SEARCH", "fixture-query", now + 900_000));
        for (int i = 0; i < 60; i++) store.reserve(JOB51, "BROWSE", null, now + i * 15_000L);
        assertThrows(PlatformDeliveryStore.Paused.class, () -> store.reserve(JOB51, "BROWSE", null, now + 900_000));
        assertTrue(store.hold(JOB51).orElseThrow().retryAt() >= now + 3_600_000);
    }

    @Test void invalidIdentityCannotBePersisted() {
        for (var p : values()) {
            for (String url : List.of("https://not" + p.domain + "/job/12345.shtml", "https://" + p.domain + ".evil.test/", "javascript:alert(1)")) {
                assertFalse(p.owns(url));
            }
            var source = job(p, "12345");
            var invalid = new PlatformCandidate("different-id", source.url(), source.title(), source.company(), "", "", source.search());
            assertThrows(IllegalArgumentException.class, () -> store.prepare(p, "scope", invalid));
        }
    }

    @Test void restrictionCannotBeClearedByOverwritingWithAnEarlierDeadline() {
        store.hold(LIEPIN, "first", 10_000);
        store.hold(LIEPIN, "second", 0);
        assertEquals(10_000, store.hold(LIEPIN).orElseThrow().retryAt());
        assertThrows(PlatformDeliveryStore.Paused.class, () -> store.reserve(LIEPIN, "SEND", null, 50_000));
    }
}
