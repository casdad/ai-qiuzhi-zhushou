package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.JobRelevancePersistenceService;
import com.getjobs.application.service.JobRelevanceService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.utils.Job;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class BossDeliveryIntervalTest {
    private Page page;
    private BrowserContext context;
    private PlaywrightManager manager;
    private BossOperationGate gate;
    private BossBrowseGuard browsing;
    private Boss boss;

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.locator(anyString())).thenReturn(mock(Locator.class));
        useRealShortWaits(page);
        manager = mock(PlaywrightManager.class);
        when(manager.getBossSessionScope()).thenReturn("session");
        gate = mock(BossOperationGate.class);
        browsing = mock(BossBrowseGuard.class);
        boss = spy(new Boss(mock(BossService.class), mock(AiService.class), mock(JobRelevanceService.class),
                mock(JobRelevancePersistenceService.class), mock(BossDeliveryService.class), gate, manager, browsing));
        BossConfig config = new BossConfig();
        config.setWaitTime("60");
        config.setWaitTimeMax("60");
        config.setKeywords(List.of("Engineer"));
        config.setCityCode(List.of("101010100"));
        boss.setPage(page);
        boss.setConfig(config);
        boss.setSessionScope("session");
        boss.setShouldStopCallback(() -> false);
    }

    @Test
    void missingOrMalformedIntervalUses60To120Seconds() {
        assertEquals("60", new BossConfig().getWaitTime());
        assertEquals("120", new BossConfig().getWaitTimeMax());
        for (String waitTime : new String[] {null, "", "  ", "invalid", "120"}) {
            BossConfig config = new BossConfig();
            config.setWaitTime(waitTime);
            config.setWaitTimeMax("invalid");
            boss.setConfig(config);
            ReflectionTestUtils.invokeMethod(boss, "beforeDelivery", page);
        }
        verify(gate, times(5)).awaitAfterPreparation(eq("session"), intThat(value -> value >= 60 && value <= 120), any(), any());
    }

    @Test
    void bothEndpointsAreIncludedAndEveryCallSamplesAgain() {
        boss.setConfig(new BossConfig());
        RandomGenerator random = mock(RandomGenerator.class);
        when(random.nextInt(60, 121)).thenReturn(60, 120, 97);

        assertEquals(60, boss.resolveWaitTimeSeconds(random));
        assertEquals(120, boss.resolveWaitTimeSeconds(random));
        assertEquals(97, boss.resolveWaitTimeSeconds(random));
        verify(random, times(3)).nextInt(60, 121);
    }

    @Test
    void everyPreparedMessageWaitsTheFullSampledIntervalIncludingTheFirst() {
        AtomicLong clock = new AtomicLong();
        BossOperationGate timedGate = new BossOperationGate(clock::get, () -> 0);
        doAnswer(invocation -> {
            BooleanSupplier cancelled = invocation.getArgument(2);
            timedGate.awaitAfterPreparation(invocation.getArgument(0), invocation.getArgument(1), cancelled,
                    millis -> clock.addAndGet(millis * 1_000_000));
            return null;
        }).when(gate).awaitAfterPreparation(anyString(), anyInt(), any(), any());
        doReturn(120, 200, 135).when(boss).resolveWaitTimeSeconds(any());

        ReflectionTestUtils.invokeMethod(boss, "beforeDelivery", page);
        assertEquals(120_000_000_000L, clock.get());
        clock.addAndGet(30_000_000_000L);
        ReflectionTestUtils.invokeMethod(boss, "beforeDelivery", page);
        assertEquals(350_000_000_000L, clock.get());
        ReflectionTestUtils.invokeMethod(boss, "beforeDelivery", page);
        assertEquals(485_000_000_000L, clock.get());
        verify(boss, times(3)).resolveWaitTimeSeconds(any());
    }

    @Test
    void invalidUpperBoundsCannotAcquireADeliveryPermit() {
        for (String max : new String[] {"0", "-1", "59", "3601"}) {
            BossConfig config = new BossConfig();
            config.setWaitTimeMax(max);
            boss.setConfig(config);
            assertThrows(IllegalArgumentException.class,
                    () -> ReflectionTestUtils.invokeMethod(boss, "beforeDelivery", page));
        }
        verify(gate, never()).awaitAfterPreparation(anyString(), anyInt(), any(), any());
    }

    @Test
    void browsingUsesIndependentGateWithoutConsumingDeliveryInterval() {
        AtomicBoolean cancelled = new AtomicBoolean();
        boss.setShouldStopCallback(cancelled::get);
        doAnswer(invocation -> {
            cancelled.set(true);
            return null;
        }).when(page).evaluate(contains("window.scrollBy"));

        assertThrows(CancellationException.class, boss::execute);

        verify(page).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page).evaluate(contains("window.scrollBy"));
        verify(gate, never()).awaitAfterPreparation(anyString(), anyInt(), any(), any());
        verify(browsing).await(eq(BossBrowseGuard.Action.SEARCH), anyString(), any(), any());
        verify(browsing).await(eq(BossBrowseGuard.Action.SCROLL), isNull(), any(), any());
    }

    @Test
    void browsingStillChecksCancellation() {
        boss.setShouldStopCallback(() -> true);
        assertThrows(CancellationException.class,
                () -> ReflectionTestUtils.invokeMethod(boss, "beforeOperation", page));
        verify(gate, never()).awaitAfterPreparation(anyString(), anyInt(), any(), any());
    }

    @Test
    void automaticBatchHasNoThirtyMinuteCutoffButStandaloneOperationsKeepTheirBudget() {
        BossConfig config = new BossConfig();
        config.setDeliveryMode("AUTO");
        boss.setConfig(config);
        ReflectionTestUtils.setField(boss, "createdAtNanos", System.nanoTime() - TimeUnit.MINUTES.toNanos(31));

        assertEquals(0, boss.execute());
        verify(gate, never()).hold(anyString(), anyString());
        assertThrows(BossOperationGate.DeliveryPausedException.class,
                () -> ReflectionTestUtils.invokeMethod(boss, "beforeOperation", page));
        verify(gate).hold(eq("session"), contains("30分钟"));
    }

    @Test
    void confirmationBatchRetainsItsThirtyMinuteBudget() {
        ReflectionTestUtils.setField(boss, "createdAtNanos", System.nanoTime() - TimeUnit.MINUTES.toNanos(31));
        assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);
        verify(gate).hold(eq("session"), contains("30分钟"));
    }

    @Test
    void longRunningAutomaticBatchStillHonorsSafetyHoldAndCancellation() {
        BossConfig config = new BossConfig();
        config.setDeliveryMode("AUTO");
        boss.setConfig(config);
        ReflectionTestUtils.setField(boss, "createdAtNanos", System.nanoTime() - TimeUnit.MINUTES.toNanos(31));
        when(gate.holdReason("session")).thenReturn("Verification required");

        var error = assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);
        assertEquals("Verification required", error.getMessage());
        verify(gate, never()).hold(anyString(), anyString());
        assertEquals(false, ReflectionTestUtils.getField(boss, "automaticBatch"));
        boss.setShouldStopCallback(() -> true);
        assertThrows(CancellationException.class, boss::execute);
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void failedNavigationDoesNotAutomaticallyReloadThePlatform() {
        when(page.url()).thenReturn("https://www.zhipin.com/");
        when(page.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(new com.microsoft.playwright.TimeoutError("Navigation timed out"));
        assertThrows(com.microsoft.playwright.TimeoutError.class, boss::execute);
        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(browsing, times(1)).await(eq(BossBrowseGuard.Action.SEARCH), anyString(), any(), any());
        verify(gate).hold(eq("session"), anyString());
    }

    @Test
    void aBudgetStorageFailureCannotBeMistakenForAnAlreadyLoadedPage() {
        doThrow(new IllegalStateException("Budget storage unavailable"))
                .when(browsing).await(eq(BossBrowseGuard.Action.SEARCH), anyString(), any(), any());
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(boss, "navigateTo", "https://www.zhipin.com/web/geek/jobs?query=next"));
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(page, never()).waitForLoadState(any(), any(Page.WaitForLoadStateOptions.class));
    }

    @Test
    void listPreloadingStopsAfterTwoConsecutiveScrollsWithoutNewJobs() {
        boss.setProgressCallback((message, current, total) -> {});
        assertEquals(0, boss.execute());
        verify(page, times(2)).evaluate(contains("window.scrollBy"));
        verify(browsing, times(3)).await(eq(BossBrowseGuard.Action.SCROLL), isNull(), any(), any());
    }

    @Test
    void browsingStillRequiresAnAuthenticatedSession() {
        boss.setSessionScope(null);
        when(manager.getBossSessionScope()).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(boss, "beforeOperation", page));
        verify(gate, never()).awaitAfterPreparation(anyString(), anyInt(), any(), any());
    }

    @Test
    void browsingStillChecksSessionIdentity() {
        when(manager.getBossSessionScope()).thenReturn("new-session");
        assertThrows(BossOperationGate.DeliveryPausedException.class,
                () -> ReflectionTestUtils.invokeMethod(boss, "beforeOperation", page));
        verify(gate).hold(eq("session"), anyString());
        verify(gate, never()).awaitAfterPreparation(anyString(), anyInt(), any(), any());
    }

    @Test
    void restrictionsStillBlockBrowsingAndDelivery() {
        when(gate.holdReason("session")).thenReturn("manual review required");
        for (String method : List.of("beforeOperation", "beforeDelivery")) {
            assertThrows(BossOperationGate.DeliveryPausedException.class,
                    () -> ReflectionTestUtils.invokeMethod(boss, method, page));
        }
        verify(gate, never()).awaitAfterPreparation(anyString(), anyInt(), any(), any());
    }

    @Test
    void deliveryRechecksSessionAfterWaiting() {
        doAnswer(invocation -> {
            when(manager.getBossSessionScope()).thenReturn("new-session");
            return null;
        }).when(gate).awaitAfterPreparation(eq("session"), eq(60), any(), any());

        assertThrows(BossOperationGate.DeliveryPausedException.class,
                () -> ReflectionTestUtils.invokeMethod(boss, "beforeDelivery", page));
        verify(gate).hold(eq("session"), anyString());
    }

    @Test
    void completedDeliveryOpensChatAndFillsTextBeforeWaitingAndOnlyThenClicksSend() {
        SendFixture fixture = prepareSendFixture();
        doAnswer(call -> {
            verify(fixture.chat()).click();
            verify(fixture.input()).fill("hello");
            verify(fixture.send(), never()).click();
            return null;
        }).when(gate).awaitAfterPreparation(eq("session"), eq(60), any(), any());

        assertEquals(Boss.SubmitResult.SENT, boss.submitConfirmed(fixture.job(), "hello", false));

        var order = inOrder(gate, fixture.chat(), fixture.input(), fixture.send());
        order.verify(fixture.chat()).click();
        order.verify(fixture.input()).fill("hello");
        order.verify(gate).awaitAfterPreparation(eq("session"), eq(60), any(), any());
        order.verify(fixture.send()).click();
        verify(gate, times(1)).awaitAfterPreparation(anyString(), anyInt(), any(), any());
        verify(gate, never()).await(anyString(), anyInt(), any(), any());
    }

    @Test
    void editedOrClearedComposerDuringWaitingPreventsClickingSend() {
        SendFixture fixture = prepareSendFixture();
        doAnswer(call -> {
            when(fixture.input().inputValue()).thenReturn("edited by user");
            return null;
        }).when(gate).awaitAfterPreparation(anyString(), anyInt(), any(), any());

        assertEquals(Boss.SubmitResult.UNKNOWN, boss.submitConfirmed(fixture.job(), "hello", false));

        verify(fixture.chat()).click();
        verify(fixture.input()).fill("hello");
        verify(fixture.send(), never()).click();
    }

    @Test
    void cancellingTheWaitLeavesTheSendButtonUntouched() {
        SendFixture fixture = prepareSendFixture();
        doThrow(new CancellationException("User stopped task"))
                .when(gate).awaitAfterPreparation(anyString(), anyInt(), any(), any());

        assertEquals(Boss.SubmitResult.UNKNOWN, boss.submitConfirmed(fixture.job(), "hello", false));

        verify(fixture.chat()).click();
        verify(fixture.input()).fill("hello");
        verify(fixture.send(), never()).click();
    }

    private record SendFixture(Job job, Locator chat, Locator input, Locator send) {}

    private SendFixture prepareSendFixture() {
        Page detailPage = mock(Page.class);
        when(context.newPage()).thenReturn(detailPage);
        String detailUrl = "https://www.zhipin.com/job_detail/job-1.html";
        when(detailPage.url()).thenReturn(detailUrl);
        when(detailPage.locator(anyString())).thenReturn(mock(Locator.class));
        useRealShortWaits(detailPage);
        Locator chat = visibleLocator();
        when(chat.textContent()).thenReturn("\u7acb\u5373\u6c9f\u901a");
        when(detailPage.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(chat);
        Locator input = visibleLocator();
        when(input.evaluate(anyString())).thenReturn("textarea");
        when(input.inputValue()).thenReturn("hello");
        when(detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area"))
                .thenReturn(input);
        Locator send = visibleLocator();
        when(detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send"))
                .thenReturn(send);
        doReturn(false).when(boss).hasAmbiguousOutgoingMessage(detailPage, "hello");
        doReturn(Set.of()).when(boss).snapshotMessageIds(detailPage);
        doReturn(true).when(boss).hasNewConfirmedMessage(detailPage, "hello", Set.of());
        Job job = new Job();
        job.setHref(detailUrl);
        return new SendFixture(job, chat, input, send);
    }

    private static Locator visibleLocator() {
        Locator locator = mock(Locator.class);
        when(locator.first()).thenReturn(locator);
        when(locator.count()).thenReturn(1);
        when(locator.isVisible()).thenReturn(true);
        return locator;
    }

    private static void useRealShortWaits(Page target) {
        doAnswer(invocation -> {
            Thread.sleep(Math.max(1, ((Double) invocation.getArgument(0)).longValue()));
            return null;
        }).when(target).waitForTimeout(anyDouble());
    }
}
