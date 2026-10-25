package com.getjobs.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossOperationGate;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.utils.Job;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class BossJobServiceTest {
    @TempDir
    Path directory;

    private PlaywrightManager manager;
    private Boss boss;
    private BossJobService service;
    private BossDeliveryService deliveries;
    private BossOperationGate gate;
    private BossConfig config;
    private ConfigService configService;
    private ObjectProvider<Boss> provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        manager = mock(PlaywrightManager.class);
        configService = mock(ConfigService.class);
        BossService bossService = mock(BossService.class);
        provider = mock(ObjectProvider.class);
        boss = mock(Boss.class);
        config = mock(BossConfig.class);
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("drafts.db"));
        gate = new BossOperationGate(dataSource);
        gate.restore();
        deliveries = new BossDeliveryService(new JdbcTemplate(dataSource), new ObjectMapper(), gate);
        deliveries.initialize();
        when(manager.callOnPlaywright(any())).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return callable.call();
        });
        when(manager.checkBossSession()).thenReturn(true);
        when(manager.getBossSessionScope()).thenReturn("scope-a");
        when(manager.getBossPage()).thenReturn(mock(Page.class));
        when(configService.getBossConfig()).thenReturn(config);
        when(config.getDeliveryMode()).thenReturn("CONFIRM");
        when(config.getCityCode()).thenReturn(List.of("101010100"));
        when(config.getKeywords()).thenReturn(List.of("Engineer"));
        when(provider.getObject()).thenReturn(boss);
        service = new BossJobService(manager, configService, bossService, provider, deliveries, gate);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void stopKeepsSlotUntilActualExitAndDoesNotResetOldCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        AtomicReference<Supplier<Boolean>> cancellation = new AtomicReference<>();
        doAnswer(invocation -> {
            cancellation.set(invocation.getArgument(0));
            return null;
        }).when(boss).setShouldStopCallback(any());
        when(boss.execute()).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(exit.await(5, TimeUnit.SECONDS));
            return 0;
        });
        try {
            var started = service.startDelivery(null);
            assertEquals(true, started.get("success"));
            assertNotNull(started.get("runId"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Supplier<Boolean> oldCancellation = cancellation.get();
            service.stopDelivery();
            assertTrue(oldCancellation.get());
            assertTrue(service.isRunning());
            assertEquals("STOPPING", service.getStatus().get("taskState"));
            assertEquals(false, service.startDelivery(null).get("success"));
            assertEquals(false, service.logout(() -> fail("must not logout while busy")).get("success"));
            exit.countDown();
            awaitStopped();
            var next = service.startDelivery(null);
            assertEquals(true, next.get("success"));
            assertNotEquals(started.get("runId"), next.get("runId"));
            awaitStopped();
            assertTrue(oldCancellation.get());
            assertEquals("IDLE", service.getStatus().get("taskState"));
        } finally {
            exit.countDown();
        }
    }

    @Test
    void confirmationUsesEditedMessageOnceEvenIfModeChanges() {
        var draft = deliveries.prepare("scope-a", job(), "original", false);
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(boss.submitConfirmed(any(), anyString(), anyBoolean())).thenReturn(Boss.SubmitResult.SENT);
        var first = service.confirmDelivery(draft.id(), "edited and approved");
        assertEquals(true, first.get("success"));
        assertEquals("SENT", first.get("status"));
        assertEquals("edited and approved", deliveries.find(draft.id()).orElseThrow().message());
        assertEquals(false, service.confirmDelivery(draft.id(), "second click").get("success"));
        verify(boss, times(1)).submitConfirmed(any(), eq("edited and approved"), eq(false));
        verify(boss, never()).submitSingle(anyString(), any());
        assertFalse(service.isRunning());
    }

    @Test
    void changedSessionCannotClaimOrSendDraft() {
        var draft = deliveries.prepare("scope-a", job(), "hello", false);
        when(manager.getBossSessionScope()).thenReturn("scope-b");
        var response = service.confirmDelivery(draft.id(), "edited");
        assertEquals("session_changed", response.get("status"));
        assertEquals("PENDING", deliveries.find(draft.id()).orElseThrow().status());
        verify(boss, never()).submitConfirmed(any(), anyString(), anyBoolean());
    }

    @Test
    void expectedSafetyPauseIsReportedClearlyWithoutRetryingOrClearingTheHold() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        String reason = "Session changed; review required";
        when(boss.execute()).thenAnswer(invocation -> {
            gate.hold("scope-a", reason);
            throw new BossOperationGate.DeliveryPausedException(reason);
        });
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        assertFalse(service.isRunning());
        assertEquals(reason, service.getStatus().get("safetyHoldReason"));
        assertTrue(messages.stream().anyMatch(message -> "warning".equals(message.getType())
                && message.getMessage().contains(reason)));
        assertTrue(messages.stream().noneMatch(message -> "error".equals(message.getType())));
        verify(boss, times(1)).execute();
        assertEquals(reason, gate.holdReason("scope-a"));
    }

    @Test
    void receiptPauseDoesNotTellTheUserThatLoginOrVerificationFailed() {
        String reason = "消息可能已经发送，请先核对官方聊天记录";
        when(boss.execute()).thenThrow(new BossOperationGate.DeliveryPausedException(reason));
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        String warning = messages.stream().filter(message -> "warning".equals(message.getType()))
                .map(JobProgressMessage::getMessage).findFirst().orElseThrow();
        assertTrue(warning.contains(reason));
        assertFalse(warning.contains("请核对登录或验证状态"));
        verify(boss, times(1)).execute();
    }

    @Test
    void unknownResultLocksOnlyItsDraftAndAllowsAnotherJob() {
        var draft = deliveries.prepare("scope-a", job(), "hello", false);
        when(boss.submitConfirmed(any(), anyString(), anyBoolean())).thenReturn(Boss.SubmitResult.UNKNOWN);
        var response = service.confirmDelivery(draft.id(), "approved");
        assertEquals(false, response.get("success"));
        assertEquals(true, response.get("needsHuman"));
        assertEquals("UNKNOWN", deliveries.find(draft.id()).orElseThrow().status());
        assertNull(service.getStatus().get("safetyHoldReason"));
        assertNull(gate.holdReason("scope-a"));
        assertEquals("UNKNOWN", deliveries.find(draft.id()).orElseThrow().status());
        assertEquals(false, service.confirmDelivery(draft.id(), "again").get("success"));
        verify(boss, times(1)).submitConfirmed(any(), anyString(), anyBoolean());

        Job nextJob = job();
        nextJob.setHref("https://www.zhipin.com/job_detail/job_2.html");
        var next = deliveries.prepare("scope-a", nextJob, "next message", false);
        when(boss.submitConfirmed(any(), anyString(), anyBoolean())).thenReturn(Boss.SubmitResult.SENT);
        assertEquals(true, service.confirmDelivery(next.id(), "next approved").get("success"));
        assertEquals("SENT", deliveries.find(next.id()).orElseThrow().status());
        verify(boss, times(2)).submitConfirmed(any(), anyString(), anyBoolean());
    }

    @Test
    void restrictionStillRequiresExplicitResumeAndDoesNotRequeueDraft() {
        var draft = deliveries.prepare("scope-a", job(), "hello", false);
        when(boss.submitConfirmed(any(), anyString(), anyBoolean())).thenReturn(Boss.SubmitResult.RESTRICTED);
        assertEquals(false, service.confirmDelivery(draft.id(), "approved").get("success"));
        assertNotNull(service.getStatus().get("safetyHoldReason"));
        assertEquals(false, service.resumeAfterReview(false).get("success"));
        assertNotNull(gate.holdReason("scope-a"));
        assertEquals(true, service.resumeAfterReview(true).get("success"));
        assertNull(gate.holdReason("scope-a"));
        assertEquals("RESTRICTED", deliveries.find(draft.id()).orElseThrow().status());
        assertEquals(false, service.confirmDelivery(draft.id(), "again").get("success"));
        verify(boss, times(1)).submitConfirmed(any(), anyString(), anyBoolean());
    }

    @Test
    void automaticCompletionReportsUnconfirmedJobsSeparatelyFromSentJobs() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(boss.execute()).thenReturn(2);
        when(boss.getUnconfirmedCount()).thenReturn(1);
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(message -> {
            messages.add(message);
            if (message.getMessage().contains("第 1 轮完成")) service.stopDelivery();
        });
        assertTrue(messages.stream().anyMatch(message -> message.getMessage().contains("已确认发送 2 条")
                && message.getMessage().contains("另有 1 条已尝试但结果待核对")));
        assertNull(gate.holdReason("scope-a"));
        assertFalse(messages.stream().anyMatch(message -> "success".equals(message.getType())));
        verify(boss, times(1)).execute();
        assertFalse(service.isRunning());
    }

    @Test
    void zeroSendRoundExplainsTheFiltersAndContinuesUntilStopped() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(boss.getRunSummary()).thenReturn("薪资排除 8，AI未通过 5，历史待核对 7");
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(message -> {
            messages.add(message);
            if (message.getMessage().contains("第 2 轮完成")) service.stopDelivery();
        });
        assertTrue(messages.stream().anyMatch(message -> message.getMessage().contains("第 1 轮完成：已确认发送 0 条")
                && message.getMessage().contains("自动继续下一轮")
                && message.getMessage().contains("历史待核对 7")));
        verify(boss, times(2)).execute();
        assertFalse(service.isRunning());
        assertNull(gate.holdReason("scope-a"));
    }

    @Test
    void automaticRoundsRefreshBossAndConfigButKeepOneRunSlotUntilStopped() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        Boss nextBoss = mock(Boss.class);
        BossConfig nextConfig = new BossConfig();
        nextConfig.setDeliveryMode("AUTO");
        nextConfig.setCityCode(List.of("101210100"));
        nextConfig.setKeywords(List.of("YOLO", "PyTorch"));
        when(provider.getObject()).thenReturn(boss, nextBoss);
        when(configService.getBossConfig()).thenReturn(config, nextConfig);
        when(boss.execute()).thenReturn(2);
        when(nextBoss.execute()).thenReturn(1);
        AtomicReference<Supplier<Boolean>> firstCancellation = new AtomicReference<>();
        AtomicReference<Supplier<Boolean>> nextCancellation = new AtomicReference<>();
        doAnswer(invocation -> {
            firstCancellation.set(invocation.getArgument(0));
            return null;
        }).when(boss).setShouldStopCallback(any());
        doAnswer(invocation -> {
            nextCancellation.set(invocation.getArgument(0));
            return null;
        }).when(nextBoss).setShouldStopCallback(any());
        AtomicReference<Object> runId = new AtomicReference<>();
        var messages = new ArrayList<JobProgressMessage>();

        service.executeDelivery(message -> {
            messages.add(message);
            assertTrue(service.isRunning());
            Object currentId = service.getStatus().get("runId");
            if (runId.get() == null) runId.set(currentId);
            assertEquals(runId.get(), currentId);
            if (message.getMessage().contains("第 1 轮完成")) {
                assertEquals(false, service.startDelivery(null).get("success"));
                assertEquals(false, service.logout(() -> fail("must not logout between rounds")).get("success"));
                assertEquals(false, firstCancellation.get().get());
            }
            if (message.getMessage().contains("第 2 轮完成")) service.stopDelivery();
        });

        assertFalse(service.isRunning());
        assertTrue(firstCancellation.get().get());
        assertTrue(nextCancellation.get().get());
        verify(provider, times(2)).getObject();
        verify(boss).prepare();
        verify(nextBoss).prepare();
        verify(boss).setConfig(config);
        verify(nextBoss).setConfig(nextConfig);
        verify(boss).execute();
        verify(nextBoss).execute();
        verify(manager, times(2)).checkBossSession();
        verify(manager, times(1)).pauseBossMonitoring();
        verify(manager, times(1)).resumeBossMonitoring();
        verify(manager, times(1)).callOnPlaywright(any());
        assertFalse(messages.stream().anyMatch(message -> "success".equals(message.getType())));
        assertTrue(messages.stream().anyMatch(message -> message.getMessage().contains("第 2 轮开始")));
        assertTrue(messages.getLast().getMessage().contains("任务已停止"));
        assertNull(gate.holdReason("scope-a"));
    }

    @Test
    void confirmModeStillFinishesAfterOneRoundSoDraftsCanBeConfirmed() {
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        verify(boss, times(1)).execute();
        assertFalse(service.isRunning());
        assertTrue(messages.stream().anyMatch(message -> "success".equals(message.getType())
                && message.getMessage().contains("确认列表")));
    }

    @Test
    void changingToConfirmStopsAutomaticRoundsWithoutClaimingDrafts() {
        when(config.getDeliveryMode()).thenReturn("AUTO", "CONFIRM");
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        verify(boss, times(1)).execute();
        assertTrue(messages.stream().anyMatch(message -> message.getMessage().contains("已关闭自动投递")));
        assertFalse(service.isRunning());
    }

    @Test
    void missingSearchConfigurationDoesNotSpinThroughEmptyRounds() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        var messages = new ArrayList<JobProgressMessage>();
        when(config.getKeywords()).thenReturn(List.of());
        service.executeDelivery(messages::add);
        when(config.getKeywords()).thenReturn(List.of(" "));
        service.executeDelivery(messages::add);
        when(config.getKeywords()).thenReturn(List.of("Engineer"));
        when(config.getCityCode()).thenReturn(null);
        service.executeDelivery(messages::add);
        assertFalse(service.isRunning());
        verify(provider, never()).getObject();
        assertEquals(3, messages.stream().filter(message -> "error".equals(message.getType())
                && message.getMessage().contains("城市和搜索关键词")).count());
    }

    @Test
    void manualStopDuringRoundDoesNotBecomeALoginErrorOrRestart() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(boss.execute()).thenAnswer(invocation -> {
            service.stopDelivery();
            throw new CancellationException("User stopped");
        });
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        verify(boss, times(1)).execute();
        assertFalse(service.isRunning());
        assertTrue(messages.getLast().getMessage().contains("任务已停止"));
        assertFalse(messages.stream().anyMatch(message -> "error".equals(message.getType())));
        assertNull(gate.holdReason("scope-a"));
    }

    @Test
    void lostLoginBetweenRoundsDoesNotStartAnotherRound() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(manager.checkBossSession()).thenReturn(true, false);
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        verify(boss, times(1)).execute();
        assertFalse(service.isRunning());
        assertTrue(messages.getLast().getMessage().contains("登录已失效"));
    }

    @Test
    void changedSessionBetweenRoundsCannotContinueUnderAnotherAccount() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(manager.getBossSessionScope()).thenReturn("scope-a", "scope-b");
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        verify(boss, times(1)).execute();
        assertFalse(service.isRunning());
        assertNotNull(gate.holdReason("scope-a"));
        assertTrue(messages.getLast().getMessage().contains("登录会话已变化"));
    }

    @Test
    void holdAtRoundEndStopsInsteadOfRepeating() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(boss.execute()).thenAnswer(invocation -> {
            gate.hold("scope-a", "Platform verification required");
            return 0;
        });
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        verify(boss, times(1)).execute();
        assertFalse(service.isRunning());
        assertTrue(messages.getLast().getMessage().contains("Platform verification required"));
        assertNotNull(gate.holdReason("scope-a"));
    }

    @Test
    void failureInLaterRoundDoesNotStartAnAutomaticRetry() {
        when(config.getDeliveryMode()).thenReturn("AUTO");
        when(boss.execute()).thenReturn(1).thenThrow(new IllegalStateException("Browser disconnected"));
        var messages = new ArrayList<JobProgressMessage>();
        service.executeDelivery(messages::add);
        verify(boss, times(2)).execute();
        assertFalse(service.isRunning());
        assertEquals("error", messages.getLast().getType());
        assertTrue(messages.getLast().getMessage().contains("不自动重试"));
    }

    private void awaitStopped() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(service.isRunning(), "task did not actually exit");
    }

    private Job job() {
        Job job = new Job();
        job.setHref("https://www.zhipin.com/job_detail/job_1.html");
        job.setJobName("Example job");
        return job;
    }
}
