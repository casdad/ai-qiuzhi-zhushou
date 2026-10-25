package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.JobRelevancePersistenceService;
import com.getjobs.application.service.JobRelevanceService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static com.getjobs.worker.boss.Locators.JOB_LIST_SELECTOR;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Full batch flow with isolated storage and mock browser operations; no live messages. */
@Timeout(40)
class BossAutomaticContinuationTest {
    @TempDir Path directory;
    private Boss boss;
    private BossOperationGate gate;
    private BossDeliveryService store;
    private JobRelevanceService relevance;
    private JobRelevancePersistenceService scores;
    private BossBrowseGuard browsing;
    private Page list;
    private BossConfig config;
    private BrowserContext context;
    private Page firstDetail;
    private Page secondDetail;
    private Locator firstSend;
    private Locator secondSend;
    private PlaywrightManager manager;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<String> progress = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("drafts.db"));
        AtomicLong time = new AtomicLong();
        gate = spy(new BossOperationGate(() -> time.addAndGet(60_000_000_000L), () -> 0));
        store = new BossDeliveryService(new JdbcTemplate(source), new ObjectMapper(), gate);
        store.initialize();
        manager = mock(PlaywrightManager.class);
        when(manager.getBossSessionScope()).thenReturn("session");
        relevance = mock(JobRelevanceService.class);
        scores = mock(JobRelevancePersistenceService.class);
        browsing = mock(BossBrowseGuard.class);
        boss = spy(new Boss(mock(BossService.class), mock(AiService.class), relevance,
                scores, store, gate, manager, browsing));
        config = new BossConfig();
        config.setDeliveryMode("AUTO");
        config.setWaitTime("60");
        config.setWaitTimeMax("60");
        config.setSayHi("hello");
        config.setKeywords(List.of("Engineer"));
        config.setCityCode(List.of("101010100"));
        boss.setConfig(config);
        boss.setSessionScope("session");
        boss.setShouldStopCallback(cancelled::get);
        boss.setProgressCallback((message, current, total) -> progress.add(message));

        list = page("https://www.zhipin.com/web/geek/jobs");
        context = mock(BrowserContext.class);
        when(list.context()).thenReturn(context);
        boss.setPage(list);
        Locator cards = mock(Locator.class);
        when(cards.count()).thenReturn(3);
        when(list.locator(JOB_LIST_SELECTOR)).thenReturn(cards);
        Locator footer = visibleLocator();
        when(list.locator("div#footer, #footer")).thenReturn(footer);
        String[] ids = {"job-1", "job-2", "job-1"};
        for (int i = 0; i < ids.length; i++) {
            Locator card = mock(Locator.class);
            Locator link = visibleLocator();
            when(link.getAttribute("href")).thenReturn(detailUrl(ids[i]));
            when(card.locator("a[href*='/job_detail/']")).thenReturn(link);
            when(cards.nth(i)).thenReturn(card);
        }
        var responses = List.of(detailResponse("job-1"), detailResponse("job-2"), detailResponse("job-1")).iterator();
        when(list.waitForResponse(org.mockito.ArgumentMatchers.<Predicate<Response>>any(),
                any(Page.WaitForResponseOptions.class), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return responses.next();
        });

        firstDetail = detailPage("job-1");
        secondDetail = detailPage("job-2");
        firstSend = firstDetail.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
        secondSend = secondDetail.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
        when(context.newPage()).thenReturn(firstDetail, secondDetail);
        doReturn(false).when(boss).waitForSendConfirmed(firstDetail, "hello", Set.of());
        doReturn(true).when(boss).waitForSendConfirmed(secondDetail, "hello", Set.of());
    }

    @Test
    void batchScoringIncludesSavedIntentAndTechnicalStackBeforeAnyDelivery() {
        config.setEnableAiRelevance(true);
        config.setAiIntroduce("Python PyTorch object detection projects");
        config.setAiPrompt("Prefer internships");
        config.setJobIntent("Computer vision and deep learning");
        config.setMinAiScore(75);
        var result = new JobRelevanceService.Result(40, "skip", List.of(), List.of(), "Direction mismatch", true, false, "");
        when(relevance.evaluate(any(), eq(config.getAiIntroduce()), eq(config.getAiPrompt()),
                eq(config.getJobIntent()), eq(75))).thenReturn(result);

        assertEquals(0, boss.execute());

        verify(relevance, atLeast(2)).evaluate(any(), eq(config.getAiIntroduce()), eq(config.getAiPrompt()),
                eq(config.getJobIntent()), eq(75));
        verify(scores, atLeast(2)).save(any(), eq(result));
        verify(context, never()).newPage();
        verify(gate, never()).awaitAfterPreparation(anyString(), anyInt(), any(), any());
    }

    @Test
    void repeatedAiFailuresWaitOnTheSameJobUntilAValidScoreBeforeSending() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1"));
        var valid = new JobRelevanceService.Result(90, "apply", List.of("Python"), List.of(), "AI match", true, false, "");
        AtomicInteger attempts = new AtomicInteger();
        when(relevance.evaluate(any(), any(), any(), any(), any())).thenAnswer(call -> {
            com.getjobs.worker.utils.Job job = call.getArgument(0);
            assertEquals(detailUrl("job-1"), job.getHref());
            if (attempts.incrementAndGet() <= 5) {
                throw new JobRelevanceService.AiScoringUnavailableException("AI temporarily unavailable");
            }
            return valid;
        });
        doAnswer(call -> {
            assertEquals(30_000L, (long) call.getArgument(0));
            assertTrue(store.list().isEmpty());
            verify(scores, never()).save(any(), any());
            verify(context, never()).newPage();
            assertNull(gate.holdReason("session"));
            return null;
        }).when(boss).waitForAiRetry(anyLong());
        doReturn(true).when(boss).waitForSendConfirmed(firstDetail, "hello", Set.of());

        assertEquals(1, boss.execute());

        verify(relevance, times(6)).evaluate(any(), any(), any(), any(), any());
        verify(boss, times(5)).waitForAiRetry(30_000);
        verify(scores, times(1)).save(any(), eq(valid));
        verify(firstSend, times(1)).click();
        verify(secondSend, never()).click();
        verify(list, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
        assertEquals("SENT", status("job-1"));
        assertTrue(progress.stream().anyMatch(message -> message.contains("第 5 次") && message.contains("不跳过")));
        assertTrue(progress.stream().anyMatch(message -> message.contains("AI评分已恢复")));
        assertTrue(boss.getRunSummary().contains("新检查 1/100 个"));
        assertTrue(boss.getRunSummary().contains("AI未通过 0"));
    }

    @Test
    void validLowAiScoreAfterRecoveryStillUsesTheOriginalFilter() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1"));
        var review = new JobRelevanceService.Result(60, "review", List.of(), List.of(), "Needs review", true, false, "");
        when(relevance.evaluate(any(), any(), any(), any(), any()))
                .thenThrow(new JobRelevanceService.AiScoringUnavailableException("AI unavailable"))
                .thenReturn(review);
        doNothing().when(boss).waitForAiRetry(anyLong());

        assertEquals(0, boss.execute());

        verify(boss, times(1)).waitForAiRetry(30_000);
        verify(scores, times(1)).save(any(), eq(review));
        verify(context, never()).newPage();
        assertTrue(store.list().isEmpty());
        assertTrue(boss.getRunSummary().contains("AI未通过 1"));
    }

    @Test
    void malformedAiResponseThroughTheRealScorerWaitsAndRecoversWithoutLocalMatching() {
        config.setEnableAiRelevance(true);
        config.setAiIntroduce("Python");
        installCards(List.of("job-1"));
        AiService ai = mock(AiService.class);
        when(ai.sendRequest(anyString())).thenReturn("not JSON",
                "{\"requirementsScore\":100,\"responsibilitiesScore\":100,\"preferencesScore\":100}");
        JobRelevanceService scorer = new JobRelevanceService(ai);
        when(relevance.evaluate(any(), any(), any(), any(), any())).thenAnswer(call -> scorer.evaluate(
                call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4)));
        doAnswer(call -> {
            verify(scores, never()).save(any(), any());
            verify(context, never()).newPage();
            return null;
        }).when(boss).waitForAiRetry(anyLong());
        doReturn(true).when(boss).waitForSendConfirmed(firstDetail, "hello", Set.of());

        assertEquals(1, boss.execute());

        verify(ai, times(2)).sendRequest(anyString());
        verify(boss, times(1)).waitForAiRetry(30_000);
        verify(scores, times(1)).save(any(), argThat(result -> result.aiUsed() && !result.fallback() && result.score() == 100));
        verify(firstSend, times(1)).click();
        assertEquals("SENT", status("job-1"));
    }

    @Test
    void persistentAiFailureKeepsWaitingBeyondThreeAttemptsUntilUserStops() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1", "job-2"));
        when(relevance.evaluate(any(), any(), any(), any(), any()))
                .thenThrow(new JobRelevanceService.AiScoringUnavailableException("AI unavailable"));
        AtomicInteger waits = new AtomicInteger();
        doAnswer(call -> {
            if (waits.incrementAndGet() == 4) cancelled.set(true);
            return null;
        }).when(boss).waitForAiRetry(anyLong());

        assertThrows(CancellationException.class, boss::execute);

        verify(relevance, times(4)).evaluate(argThat(job -> detailUrl("job-1").equals(job.getHref())),
                any(), any(), any(), any());
        verify(boss, times(4)).waitForAiRetry(30_000);
        verify(scores, never()).save(any(), any());
        verify(context, never()).newPage();
        assertTrue(store.list().isEmpty());
        assertNull(gate.holdReason("session"));
    }

    @Test
    void realAiRetryWaitObservesCancellationWithoutAnotherRequest() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1"));
        when(relevance.evaluate(any(), any(), any(), any(), any()))
                .thenThrow(new JobRelevanceService.AiScoringUnavailableException("AI unavailable"));
        doAnswer(call -> {
            doAnswer(wait -> {
                cancelled.set(true);
                return null;
            }).when(list).waitForTimeout(anyDouble());
            return call.callRealMethod();
        }).when(boss).waitForAiRetry(anyLong());

        assertThrows(CancellationException.class, boss::execute);

        verify(relevance, times(1)).evaluate(any(), any(), any(), any(), any());
        verify(scores, never()).save(any(), any());
        verify(context, never()).newPage();
        assertTrue(store.list().isEmpty());
        assertNull(gate.holdReason("session"));
    }

    @Test
    void realAiRetryWaitStillStopsForPlatformRestrictions() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1"));
        when(relevance.evaluate(any(), any(), any(), any(), any()))
                .thenThrow(new JobRelevanceService.AiScoringUnavailableException("AI unavailable"));
        doAnswer(call -> {
            doAnswer(wait -> {
                gate.hold("session", "Platform verification required");
                return null;
            }).when(list).waitForTimeout(anyDouble());
            return call.callRealMethod();
        }).when(boss).waitForAiRetry(anyLong());

        assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);

        verify(relevance, times(1)).evaluate(any(), any(), any(), any(), any());
        verify(scores, never()).save(any(), any());
        verify(context, never()).newPage();
        assertEquals("Platform verification required", gate.holdReason("session"));
    }

    @Test
    void sessionChangeWhileWaitingForAiCannotContinueToScoringOrSend() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1"));
        when(relevance.evaluate(any(), any(), any(), any(), any()))
                .thenThrow(new JobRelevanceService.AiScoringUnavailableException("AI unavailable"));
        doAnswer(call -> {
            when(manager.getBossSessionScope()).thenReturn("different-session");
            return null;
        }).when(boss).waitForAiRetry(anyLong());

        assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);

        verify(relevance, times(1)).evaluate(any(), any(), any(), any(), any());
        verify(scores, never()).save(any(), any());
        verify(context, never()).newPage();
        assertNotNull(gate.holdReason("session"));
    }

    @Test
    void stoppingDuringAnAiRequestDiscardsItsLateSuccessWithoutSending() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1"));
        when(relevance.evaluate(any(), any(), any(), any(), any())).thenAnswer(call -> {
            cancelled.set(true);
            return new JobRelevanceService.Result(100, "apply", List.of(), List.of(), "Match", true, false, "");
        });

        assertThrows(CancellationException.class, boss::execute);

        verify(boss, never()).waitForAiRetry(anyLong());
        verify(scores, never()).save(any(), any());
        verify(context, never()).newPage();
        assertTrue(store.list().isEmpty());
        assertNull(gate.holdReason("session"));
    }

    @Test
    void aLegacyLocalFallbackCannotBeSavedOrUsedEvenIfItClaimsApply() {
        config.setEnableAiRelevance(true);
        installCards(List.of("job-1"));
        when(relevance.evaluate(any(), any(), any(), any(), any())).thenReturn(
                new JobRelevanceService.Result(100, "apply", List.of("Python"), List.of(), "Local match", false, true, "offline"));
        doAnswer(call -> {
            cancelled.set(true);
            return null;
        }).when(boss).waitForAiRetry(anyLong());

        assertThrows(CancellationException.class, boss::execute);

        verify(boss).waitForAiRetry(30_000);
        verify(scores, never()).save(any(), any());
        verify(context, never()).newPage();
        assertTrue(store.list().isEmpty());
    }

    @Test
    void unknownReceiptContinuesToNextJobAndNeverResendsTheUnknownJob() {
        assertEquals(1, boss.execute());
        assertEquals(1, boss.getUnconfirmedCount());
        assertNull(gate.holdReason("session"));
        assertEquals(2, store.list().size());
        assertEquals("UNKNOWN", status("job-1"));
        assertEquals("SENT", status("job-2"));
        assertTrue(progress.stream().anyMatch(message -> message.contains("继续")));
        verify(firstSend, times(1)).click();
        verify(secondSend, times(1)).click();
        verify(context, times(2)).newPage();
        verify(firstDetail).close();
        verify(secondDetail).close();
        verify(gate, times(2)).awaitAfterPreparation(eq("session"), eq(60), any(), any());
        verify(list, times(2)).waitForResponse(org.mockito.ArgumentMatchers.<Predicate<Response>>any(),
                any(Page.WaitForResponseOptions.class), any());
    }

    @Test
    void freshRoundKeepsPersistentSentAndUnknownDeduplicationButResetsCounters() {
        assertEquals(1, boss.execute());
        assertEquals(1, boss.getUnconfirmedCount());
        PlaywrightManager manager = mock(PlaywrightManager.class);
        when(manager.getBossSessionScope()).thenReturn("session");
        Boss next = new Boss(mock(BossService.class), mock(AiService.class), relevance,
                scores, store, gate, manager, browsing);
        next.setConfig(config);
        next.setPage(list);
        next.setSessionScope("session");
        next.setShouldStopCallback(cancelled::get);
        next.setProgressCallback((message, current, total) -> progress.add(message));

        assertEquals(0, next.execute());

        assertEquals(0, next.getUnconfirmedCount());
        assertTrue(next.getRunSummary().contains("新检查 0/100 个"));
        assertTrue(next.getRunSummary().contains("已有投递记录 2（历史待核对 1）"));
        assertEquals("UNKNOWN", status("job-1"));
        assertEquals("SENT", status("job-2"));
        assertEquals(2, store.list().size());
        verify(list, times(2)).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(browsing, times(2)).await(eq(BossBrowseGuard.Action.SEARCH), anyString(), any(), any());
        verify(context, times(2)).newPage();
        verify(firstSend, times(1)).click();
        verify(secondSend, times(1)).click();
        verify(gate, times(2)).awaitAfterPreparation(eq("session"), eq(60), any(), any());
        assertNull(gate.holdReason("session"));
    }

    @Test
    void cachedDetailsAreRescoredWithoutClickingCardsOrRequestingDetails() {
        config.setEnableAiRelevance(true);
        config.setJobIntent("Updated intent");
        String firstCached = detailResponse("job-1").text();
        String secondCached = detailResponse("job-2").text();
        when(browsing.cachedDetail("job-1")).thenReturn(firstCached);
        when(browsing.cachedDetail("job-2")).thenReturn(secondCached);
        var result = new JobRelevanceService.Result(40, "review", List.of(), List.of(), "Review", true, false, "");
        when(relevance.evaluate(any(), any(), any(), eq("Updated intent"), any())).thenReturn(result);

        assertEquals(0, boss.execute());

        verify(relevance, times(2)).evaluate(any(), any(), any(), eq("Updated intent"), any());
        verify(list, never()).waitForResponse(org.mockito.ArgumentMatchers.<Predicate<Response>>any(),
                any(Page.WaitForResponseOptions.class), any());
        verify(browsing, never()).await(eq(BossBrowseGuard.Action.DETAIL), any(), any(), any());
        verify(context, never()).newPage();
    }

    @Test
    void existingUnknownAndPendingDraftsAreSkippedBeforeAnyDetailRead() {
        var first = store.prepareAndClaimAutomatic("session", job("job-1"), "unknown", false);
        store.finish(first.id(), "UNKNOWN", "Check manually");
        store.prepare("session", job("job-2"), "pending", false);

        assertEquals(0, boss.execute());

        verify(browsing, never()).cachedDetail(anyString());
        verify(list, never()).waitForResponse(org.mockito.ArgumentMatchers.<Predicate<Response>>any(),
                any(Page.WaitForResponseOptions.class), any());
        verify(context, never()).newPage();
        assertEquals("UNKNOWN", status("job-1"));
        assertEquals("PENDING", status("job-2"));
    }

    @Test
    void twentyHistoricalUnknownJobsDoNotConsumeTheHundredNewJobQuota() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String id = "old-" + i;
            ids.add(id);
            var draft = store.prepareAndClaimAutomatic("session", job(id), "hello", false);
            store.finish(draft.id(), "UNKNOWN", "Check manually");
        }
        ids.addAll(java.util.stream.IntStream.range(0, 102).mapToObj(i -> "new-" + i).toList());
        installCards(ids);
        rejectNewJobsUsingCachedDetails();

        assertEquals(0, boss.execute());

        verify(relevance, times(100)).evaluate(any(), any(), any(), any(), any());
        verify(browsing).cachedDetail("new-0");
        verify(browsing).cachedDetail("new-99");
        verify(browsing, never()).cachedDetail("new-100");
        verify(context, never()).newPage();
        assertTrue(boss.getRunSummary().contains("新检查 100/100 个"));
        assertTrue(boss.getRunSummary().contains("历史待核对 20"));
        assertTrue(boss.getRunSummary().contains("AI未通过 100"));
        assertTrue(store.list().stream().allMatch(draft -> "UNKNOWN".equals(draft.status())));
    }

    @Test
    void firstKeywordCannotConsumeTheEntireRunBudget() {
        config.setKeywords(List.of("Vision", "Learning"));
        rejectNewJobsUsingCachedDetails();
        when(list.navigate(anyString(), any(Page.NavigateOptions.class))).thenAnswer(call -> {
            String prefix = ((String) call.getArgument(0)).contains("query=Vision") ? "vision-" : "learning-";
            installCards(java.util.stream.IntStream.range(0, 125).mapToObj(i -> prefix + i).toList());
            return null;
        });

        assertEquals(0, boss.execute());

        verify(relevance, times(200)).evaluate(any(), any(), any(), any(), any());
        verify(browsing).cachedDetail("vision-99");
        verify(browsing, never()).cachedDetail("vision-100");
        verify(browsing).cachedDetail("learning-99");
        verify(browsing, never()).cachedDetail("learning-100");
        verify(context, never()).newPage();
        assertTrue(boss.getRunSummary().contains("新检查 200/200 个"));
        assertTrue(progress.stream().anyMatch(message -> message.contains("检查至多 100 个新岗位")));
        verify(list, never()).evaluate(contains("window.innerHeight"));
        assertTrue(progress.stream().anyMatch(message -> message.contains("继续下一个搜索")));
    }

    @Test
    void shortSearchDoesNotRaiseTheNextKeywordsHundredJobLimit() {
        config.setKeywords(List.of("Short", "Long"));
        rejectNewJobsUsingCachedDetails();
        when(list.navigate(anyString(), any(Page.NavigateOptions.class))).thenAnswer(call -> {
            if (((String) call.getArgument(0)).contains("query=Short")) installCards(List.of("short-0"));
            else installCards(java.util.stream.IntStream.range(0, 125).mapToObj(i -> "long-" + i).toList());
            return null;
        });

        assertEquals(0, boss.execute());

        verify(relevance, times(101)).evaluate(any(), any(), any(), any(), any());
        verify(browsing).cachedDetail("long-99");
        verify(browsing, never()).cachedDetail("long-100");
        assertTrue(boss.getRunSummary().contains("新检查 101/200 个"));
    }

    @Test
    void fiveKeywordsEachInspectOneHundredNewJobs() {
        config.setKeywords(List.of("Vision", "Algorithm", "Learning", "YOLO", "PyTorch"));
        rejectNewJobsUsingCachedDetails();
        when(list.navigate(anyString(), any(Page.NavigateOptions.class))).thenAnswer(call -> {
            String prefix = ((String) call.getArgument(0)).split("query=")[1];
            installCards(java.util.stream.IntStream.range(0, 125).mapToObj(i -> prefix + "-" + i).toList());
            return null;
        });

        assertEquals(0, boss.execute());

        verify(relevance, times(500)).evaluate(any(), any(), any(), any(), any());
        for (String keyword : config.getKeywords()) {
            verify(browsing).cachedDetail(keyword + "-99");
            verify(browsing, never()).cachedDetail(keyword + "-100");
        }
        assertTrue(boss.getRunSummary().contains("新检查 500/500 个"));
        verify(context, never()).newPage();
    }

    @Test
    void preloadingCanPassTwoScrollsAndStopsAtOneHundredCards() {
        AtomicInteger loaded = new AtomicInteger(25);
        installCards(java.util.stream.IntStream.range(0, loaded.get()).mapToObj(i -> "loaded-" + i).toList());
        rejectNewJobsUsingCachedDetails();
        when(list.evaluate(contains("window.innerHeight"))).thenAnswer(call -> {
            installCards(java.util.stream.IntStream.range(0, loaded.addAndGet(25)).mapToObj(i -> "loaded-" + i).toList());
            return null;
        });

        assertEquals(0, boss.execute());

        assertEquals(100, loaded.get());
        verify(list, times(3)).evaluate(contains("window.innerHeight"));
        verify(browsing, times(4)).await(eq(BossBrowseGuard.Action.SCROLL), isNull(), any(), any());
        verify(relevance, times(100)).evaluate(any(), any(), any(), any(), any());
        assertTrue(boss.getRunSummary().contains("新检查 100/100 个"));
    }

    @Test
    void slowGrowingListStillStopsPreloadingAfterTwentyScrolls() {
        AtomicInteger loaded = new AtomicInteger(10);
        installCards(java.util.stream.IntStream.range(0, loaded.get()).mapToObj(i -> "slow-" + i).toList());
        rejectNewJobsUsingCachedDetails();
        when(list.evaluate(contains("window.innerHeight"))).thenAnswer(call -> {
            installCards(java.util.stream.IntStream.range(0, loaded.incrementAndGet()).mapToObj(i -> "slow-" + i).toList());
            return null;
        });

        assertEquals(0, boss.execute());

        assertEquals(30, loaded.get());
        verify(list, times(20)).evaluate(contains("window.innerHeight"));
        verify(browsing, times(21)).await(eq(BossBrowseGuard.Action.SCROLL), isNull(), any(), any());
        verify(relevance, times(30)).evaluate(any(), any(), any(), any(), any());
        assertTrue(boss.getRunSummary().contains("新检查 30/100 个"));
    }

    @Test
    void emptyYoloSearchContinuesToNextKeywordWithoutCreatingAHold() {
        config.setKeywords(List.of("YOLO", "PyTorch"));
        rejectNewJobsUsingCachedDetails();
        when(list.navigate(anyString(), any(Page.NavigateOptions.class))).thenAnswer(call -> {
            if (((String) call.getArgument(0)).contains("query=YOLO")) {
                installCards(List.of());
                Locator empty = visibleLocator();
                when(empty.nth(0)).thenReturn(empty);
                when(list.locator(Boss.EMPTY_JOB_LIST_SELECTOR)).thenReturn(empty);
            } else {
                installCards(java.util.stream.IntStream.range(0, 125).mapToObj(i -> "pytorch-" + i).toList());
                when(list.locator(Boss.EMPTY_JOB_LIST_SELECTOR)).thenReturn(mock(Locator.class));
            }
            return null;
        });

        assertEquals(0, boss.execute());

        verify(relevance, times(100)).evaluate(any(), any(), any(), any(), any());
        verify(list, times(2)).navigate(anyString(), any(Page.NavigateOptions.class));
        verify(browsing, times(1)).await(eq(BossBrowseGuard.Action.SCROLL), isNull(), any(), any());
        assertTrue(progress.stream().anyMatch(message -> message.contains("【YOLO】没有找到相关职位")));
        assertNull(gate.holdReason("session"));
        assertTrue(boss.getRunSummary().contains("新检查 100/200 个"));
    }

    @Test
    void emptyMessageCannotOverrideAPlatformRestriction() {
        config.setKeywords(List.of("YOLO", "PyTorch"));
        installCards(List.of());
        Locator empty = visibleLocator();
        when(empty.nth(0)).thenReturn(empty);
        when(list.locator(Boss.EMPTY_JOB_LIST_SELECTOR)).thenReturn(empty);
        when(list.waitForSelector(anyString(), any(Page.WaitForSelectorOptions.class))).thenAnswer(call -> {
            gate.hold("session", "HTTP 429");
            return null;
        });

        assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);

        verify(list, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
        assertEquals("HTTP 429", gate.holdReason("session"));
        assertFalse(progress.stream().anyMatch(message -> message.contains("没有找到相关职位")));
    }

    @Test
    void missingListWithoutAnExplicitEmptyStateStillStopsInsteadOfBeingSilentlySkipped() {
        installCards(List.of());
        when(list.waitForSelector(anyString(), any(Page.WaitForSelectorOptions.class)))
                .thenThrow(new com.microsoft.playwright.TimeoutError("List did not load"));

        assertThrows(IllegalStateException.class, boss::execute);

        verify(list, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
        assertNotNull(gate.holdReason("session"));
        assertFalse(progress.stream().anyMatch(message -> message.contains("没有找到相关职位")));
    }

    private void installCards(List<String> ids) {
        Locator cards = mock(Locator.class);
        when(cards.count()).thenReturn(ids.size());
        when(list.locator(JOB_LIST_SELECTOR)).thenReturn(cards);
        for (int i = 0; i < ids.size(); i++) {
            Locator card = mock(Locator.class);
            Locator link = visibleLocator();
            when(link.getAttribute("href")).thenReturn(detailUrl(ids.get(i)));
            when(card.locator("a[href*='/job_detail/']")).thenReturn(link);
            when(cards.nth(i)).thenReturn(card);
        }
    }

    private void rejectNewJobsUsingCachedDetails() {
        config.setEnableAiRelevance(true);
        when(browsing.cachedDetail(anyString())).thenAnswer(call -> detailResponse(call.getArgument(0)).text());
        var rejected = new JobRelevanceService.Result(20, "skip", List.of(), List.of(), "Not a match", true, false, "");
        when(relevance.evaluate(any(), any(), any(), any(), any())).thenReturn(rejected);
    }

    @Test
    void browsingBudgetStopsBeforeNavigationAndPersistsACooldownHold() {
        doThrow(new BossBrowseGuard.BrowsePausedException("Local budget exhausted", 60_000))
                .when(browsing).await(eq(BossBrowseGuard.Action.SEARCH), anyString(), any(), any());
        assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);
        verify(list, never()).navigate(anyString(), any(Page.NavigateOptions.class));
        assertEquals("Local budget exhausted", gate.holdReason("session"));
        assertThrows(BossOperationGate.DeliveryPausedException.class, () -> gate.resume("session"));
    }

    private com.getjobs.worker.utils.Job job(String id) {
        var job = new com.getjobs.worker.utils.Job();
        job.setHref(detailUrl(id));
        return job;
    }

    @Test
    void platformRestrictionStillStopsTheBatchEvenWhenReceiptIsUnknown() {
        doAnswer(call -> {
            gate.hold("session", "HTTP 429", 60_000);
            return false;
        }).when(boss).waitForSendConfirmed(firstDetail, "hello", Set.of());
        assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);
        assertEquals("UNKNOWN", status("job-1"));
        assertEquals("HTTP 429", gate.holdReason("session"));
        verify(secondSend, never()).click();
        verify(context, times(1)).newPage();
        verify(firstDetail, never()).close();
    }

    @Test
    void cancellationStillStopsBeforeTheNextJobWithoutCreatingAnAccountHold() {
        doAnswer(call -> {
            cancelled.set(true);
            return false;
        }).when(boss).waitForSendConfirmed(firstDetail, "hello", Set.of());
        assertThrows(CancellationException.class, boss::execute);
        assertEquals("UNKNOWN", status("job-1"));
        assertNull(gate.holdReason("session"));
        verify(secondSend, never()).click();
        verify(context, times(1)).newPage();
    }

    @Test
    void browserExecutionFailureStillStopsBeforeTheNextJob() {
        doThrow(new IllegalStateException("Browser disconnected")).when(firstSend).click();
        assertThrows(BossOperationGate.DeliveryPausedException.class, boss::execute);
        assertNotNull(gate.holdReason("session"));
        assertEquals("UNKNOWN", status("job-1"));
        verify(secondSend, never()).click();
        verify(context, times(1)).newPage();
    }

    private String status(String id) {
        return store.list().stream().filter(draft -> draft.jobKey().equals(detailUrl(id)))
                .findFirst().orElseThrow().status();
    }

    private Page detailPage(String id) {
        Page page = page(detailUrl(id));
        Locator chat = visibleLocator();
        when(chat.textContent()).thenReturn("\u7acb\u5373\u6c9f\u901a");
        when(page.locator("a.btn-startchat, a.op-btn-chat")).thenReturn(chat);
        Locator input = visibleLocator();
        when(input.evaluate(anyString())).thenReturn("textarea");
        when(input.inputValue()).thenReturn("hello");
        when(page.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area")).thenReturn(input);
        Locator send = visibleLocator();
        when(page.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        doReturn(false).when(boss).hasAmbiguousOutgoingMessage(page, "hello");
        doReturn(Set.of()).when(boss).snapshotMessageIds(page);
        return page;
    }

    private static Page page(String url) {
        Page page = mock(Page.class);
        when(page.url()).thenReturn(url);
        when(page.locator(anyString())).thenReturn(mock(Locator.class));
        doAnswer(call -> {
            Thread.sleep(Math.max(1, ((Double) call.getArgument(0)).longValue()));
            return null;
        }).when(page).waitForTimeout(anyDouble());
        return page;
    }

    private static Locator visibleLocator() {
        Locator locator = mock(Locator.class);
        when(locator.first()).thenReturn(locator);
        when(locator.count()).thenReturn(1);
        when(locator.isVisible()).thenReturn(true);
        return locator;
    }

    private static Response detailResponse(String id) {
        Response response = mock(Response.class);
        when(response.url()).thenReturn("https://www.zhipin.com/wapi/zpgeek/job/detail.json");
        when(response.status()).thenReturn(200);
        when(response.text()).thenReturn("""
                {"code":0,"zpData":{"jobInfo":{"encryptId":"%s","jobName":"Engineer %s", "salaryDesc":"20-30K"},
                "brandComInfo":{"brandName":"Example"},"bossInfo":{"name":"Recruiter"}}}
                """.formatted(id, id));
        return response;
    }

    private static String detailUrl(String id) {
        return "https://www.zhipin.com/job_detail/" + id + ".html";
    }
}
