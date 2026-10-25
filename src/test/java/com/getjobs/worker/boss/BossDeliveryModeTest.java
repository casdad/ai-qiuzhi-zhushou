package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.JobRelevancePersistenceService;
import com.getjobs.application.service.JobRelevanceService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.utils.Job;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossDeliveryModeTest {
    private Page page;
    private Boss boss;
    private BossConfig config;
    private BossDeliveryService store;
    private Job job;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        when(page.context()).thenReturn(mock(BrowserContext.class));
        store = mock(BossDeliveryService.class);
        boss = new Boss(mock(BossService.class), mock(AiService.class), mock(JobRelevanceService.class),
                mock(JobRelevancePersistenceService.class), store,
                new BossOperationGate(() -> 0, () -> 0), mock(PlaywrightManager.class), mock(BossBrowseGuard.class));
        config = new BossConfig();
        config.setSayHi("approved draft");
        boss.setConfig(config);
        boss.setPage(page);
        boss.setSessionScope("session");
        boss.setShouldStopCallback(() -> false);
        job = new Job();
        job.setHref("https://www.zhipin.com/job_detail/job-1.html");
        job.setJobName("Engineer");
        job.setCompanyName("Example");
    }

    @Test
    void confirmationIsDefaultAndPreparingNeverClicksOrNavigates() {
        assertEquals("CONFIRM", config.getDeliveryMode());
        var draft = new BossDeliveryService.DeliveryDraft("draft", job.getHref(), "session", job,
                "approved draft", false, "PENDING", null, "now", "now");
        when(store.prepare(eq("session"), any(), anyString(), eq(false))).thenReturn(draft);
        assertEquals(Boss.SubmitResult.QUEUED, boss.submitSingle("Engineer", job));
        verify(store, never()).claim(anyString());
        verify(page.context(), never()).newPage();
        verify(page, never()).navigate(anyString());
        verify(page, never()).locator(anyString());
    }

    @Test
    void automaticModeDoesNotConsumeExistingConfirmationDraft() {
        config.setDeliveryMode("AUTO");
        when(store.prepareAndClaimAutomatic(eq("session"), any(), anyString(), eq(false))).thenReturn(null);
        assertEquals(Boss.SubmitResult.SKIPPED, boss.submitSingle("Engineer", job));
        verify(store, never()).prepare(anyString(), any(), anyString(), anyBoolean());
        verify(store, never()).claim(anyString());
        verify(page.context(), never()).newPage();
    }

    @Test
    void invalidModeIsRejectedRatherThanBecomingAutomatic() {
        assertEquals("CONFIRM", BossService.normalizeDeliveryMode(null));
        assertEquals("AUTO", BossService.normalizeDeliveryMode("auto"));
        assertThrows(IllegalArgumentException.class, () -> BossService.normalizeDeliveryMode("unknown"));
        BossService.validateWaitTime(10);
        assertThrows(IllegalArgumentException.class, () -> BossService.validateWaitTime(0));
        assertThrows(IllegalArgumentException.class, () -> BossService.validateWaitTime(3601));
    }
}
