package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.boss.BossOperationGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BossDeliveryServiceTest {
    @TempDir
    Path directory;

    private BossDeliveryService service;
    private JdbcTemplate jdbc;
    private BossOperationGate gate;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("drafts.db"));
        jdbc = new JdbcTemplate(dataSource);
        gate = new BossOperationGate(dataSource);
        gate.restore();
        service = new BossDeliveryService(jdbc, new ObjectMapper(), gate);
        service.initialize();
    }

    @Test
    void normalizedUrlDeduplicatesAcrossQueryAndFragment() {
        var first = service.prepare("scope-a", job("/job_detail/job_1.html?source=a"), "first", false);
        var second = service.prepare("scope-a", job("https://zhipin.com/job_detail/job_1.html#part"), "second", true);
        assertEquals(first.id(), second.id());
        assertEquals("first", second.message());
        assertFalse(second.sendImgResume());
        assertEquals("https://www.zhipin.com/job_detail/job_1.html", second.job().getHref());
        assertNotEquals(first.id(), service.prepare("scope-b", job("/job_detail/job_1.html"), "other", false).id());
    }

    @Test
    void atomicClaimSavesOnlyWinningEditedMessage() throws Exception {
        var draft = service.prepare("scope", job("/job_detail/job.html"), "original", false);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return service.claim(draft.id(), "edited-a"); });
            var b = executor.submit(() -> { start.await(); return service.claim(draft.id(), "edited-b"); });
            start.countDown();
            boolean wonA = a.get(5, TimeUnit.SECONDS);
            boolean wonB = b.get(5, TimeUnit.SECONDS);
            assertNotEquals(wonA, wonB);
            var claimed = service.find(draft.id()).orElseThrow();
            assertEquals("IN_FLIGHT", claimed.status());
            assertEquals(wonA ? "edited-a" : "edited-b", claimed.message());
            assertFalse(service.dismiss(draft.id()));
        }
    }

    @Test
    void restartMarksInFlightUnknownAndNeverRequeues() {
        var draft = service.prepare("scope", job("/job_detail/job.html"), "hello", false);
        assertTrue(service.claim(draft.id()));
        var restarted = new BossDeliveryService(jdbc, new ObjectMapper(), gate);
        restarted.initialize();
        assertEquals("UNKNOWN", restarted.find(draft.id()).orElseThrow().status());
        assertNotNull(gate.holdReason("scope"));
        assertFalse(restarted.claim(draft.id()));
        assertEquals("UNKNOWN", restarted.prepare("scope", draft.job(), "new", false).status());
        restarted.finish(draft.id(), "SENT", "late result");
        assertEquals("UNKNOWN", restarted.find(draft.id()).orElseThrow().status());
    }

    @Test
    void processedAndDismissedDraftsCannotBeClaimedAgain() {
        var draft = service.prepare("scope", job("/job_detail/job.html"), "hello", false);
        assertTrue(service.dismiss(draft.id()));
        assertFalse(service.claim(draft.id(), "changed"));
        assertEquals("DISMISSED", service.prepare("scope", draft.job(), "again", false).status());
        assertEquals("hello", service.find(draft.id()).orElseThrow().message());
    }

    @Test
    void requestPreflightFindsExistingDraftsRegardlessOfStatusOrUrlTrackingParameters() {
        assertFalse(service.hasDraftForJob("scope", "/job_detail/job.html"));
        var draft = service.prepare("scope", job("/job_detail/job.html"), "hello", false);
        assertTrue(service.hasDraftForJob("scope", "/job_detail/job.html?securityId=tracking"));
        assertTrue(service.claim(draft.id()));
        service.finish(draft.id(), "UNKNOWN", "Check manually");
        assertTrue(service.hasDraftForJob("scope", "/job_detail/job.html"));
        assertFalse(service.hasDraftForJob("different-session", "/job_detail/job.html"));
    }

    @Test
    void preflightReportsHistoricalStatusWithoutChangingOrRequeueingIt() {
        assertNull(service.statusForJob("scope", "/job_detail/job.html"));
        var draft = service.prepareAndClaimAutomatic("scope", job("/job_detail/job.html"), "hello", false);
        service.finish(draft.id(), "UNKNOWN", "Check manually");
        assertEquals("UNKNOWN", service.statusForJob("scope", "/job_detail/job.html?tracking=ignored"));
        assertNull(service.statusForJob("different-session", "/job_detail/job.html"));
        assertEquals("UNKNOWN", service.find(draft.id()).orElseThrow().status());
    }

    @Test
    void switchingToAutoNeverConsumesAnExistingConfirmationDraft() {
        var pending = service.prepare("scope", job("/job_detail/job.html"), "needs approval", true);
        assertNull(service.prepareAndClaimAutomatic("scope", pending.job(), "auto text", false));
        var unchanged = service.find(pending.id()).orElseThrow();
        assertEquals("PENDING", unchanged.status());
        assertEquals("needs approval", unchanged.message());
        assertTrue(unchanged.sendImgResume());
    }

    @Test
    void automaticClaimOnlyAllowsNewJobsOnce() {
        var draft = service.prepareAndClaimAutomatic("scope", job("/job_detail/job.html"), "auto", false);
        assertNotNull(draft);
        assertEquals("IN_FLIGHT", draft.status());
        assertNull(service.prepareAndClaimAutomatic("scope", draft.job(), "again", false));
        service.finish(draft.id(), "SENT", "confirmed");
        assertNull(service.prepareAndClaimAutomatic("scope", draft.job(), "again", false));
        assertEquals("SENT", service.find(draft.id()).orElseThrow().status());
    }

    @Test
    void unknownJobCannotBeReclaimedButDoesNotBlockTheNextAutomaticJob() {
        var first = service.prepareAndClaimAutomatic("scope", job("/job_detail/job_1.html"), "first", false);
        service.finish(first.id(), "UNKNOWN", "receipt unavailable");
        assertNull(service.prepareAndClaimAutomatic("scope", first.job(), "do not resend", false));
        assertNotNull(service.prepareAndClaimAutomatic("scope", job("/job_detail/job_2.html"), "next", false));
        assertEquals("UNKNOWN", service.find(first.id()).orElseThrow().status());
        assertNull(gate.holdReason("scope"));
    }

    @Test
    void rejectsExternalOrAmbiguousJobLinksAndInvalidMessages() {
        assertThrows(IllegalArgumentException.class, () -> service.prepare("scope", job("https://example.com/job_detail/job.html"), "hello", false));
        assertThrows(IllegalArgumentException.class, () -> service.prepare("scope", job("https://www.zhipin.com@other.example/job_detail/job.html"), "hello", false));
        assertThrows(IllegalArgumentException.class, () -> service.prepare("scope", job("http://www.zhipin.com/job_detail/job.html"), "hello", false));
        assertThrows(IllegalArgumentException.class, () -> service.prepare("scope", job("/job_detail/job.html"), " ", false));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(null, job("/job_detail/job.html"), "hello", false));
    }

    @Test
    void listingIsBounded() {
        for (int i = 0; i < 103; i++) {
            service.prepare("scope", job("/job_detail/job_" + i + ".html"), "hello", false);
        }
        assertEquals(100, service.list().size());
    }

    private Job job(String href) {
        Job job = new Job();
        job.setHref(href);
        job.setJobName("Example job");
        job.setCompanyName("Example company");
        return job;
    }
}
