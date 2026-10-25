package com.getjobs.worker.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.getjobs.worker.platform.RecruitmentPlatform.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformDeliveryEngineTest {
    @TempDir Path directory;
    PlatformDeliveryEngine engine;
    PlatformDeliveryStore store;
    PlaywrightManager manager;
    PlatformHistory history;
    Page page;
    BrowserContext context;
    List<PlatformAdapter> adapters;
    Runnable click;

    @BeforeEach void setup() {
        store = spy(new PlatformDeliveryStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("test.db"))), new ObjectMapper()));
        store.initialize();
        // Interval semantics are tested independently; orchestration tests must not sleep for minutes.
        doReturn(0L).when(store).reserve(any(), anyString(), nullable(String.class), anyLong());
        manager = mock(PlaywrightManager.class);
        history = mock(PlatformHistory.class);
        page = mock(Page.class);
        context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(manager.callOnPlaywright(any())).thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call());
        when(manager.checkRecruitmentSession(anyString())).thenReturn(true);
        when(manager.recruitmentSessionScope(anyString())).thenReturn("scope");
        when(manager.recruitmentPage(anyString())).thenReturn(page);
        click = mock(Runnable.class);
        adapters = new ArrayList<>();
        for (var p : values()) {
            PlatformAdapter adapter = mock(PlatformAdapter.class);
            when(adapter.platform()).thenReturn(p);
            var candidate = PlatformDeliveryStoreTest.job(p, "12345");
            when(adapter.searches()).thenReturn(List.of(candidate.search()));
            when(adapter.collect(any(), any())).thenReturn(List.of(candidate));
            when(adapter.prepare(any(), any())).thenReturn(new PlatformAdapter.Prepared(click, Set.of(), Set.of()));
            when(adapter.receipt(any(), any())).thenReturn("SENT");
            adapters.add(adapter);
        }
        engine = spy(new PlatformDeliveryEngine(manager, store, history, adapters));
    }

    @AfterEach void stop() { engine.shutdown(); }

    @Test void confirmationModeNeverClicksAndManualConfirmationSendsOnceForAllPlatforms() {
        for (var p : values()) {
            engine.execute(p, null);
            var draft = store.list(p).getFirst();
            assertEquals("PENDING", draft.status());
            verify(click, never()).run();
        }
        for (var p : values()) {
            var draft = store.list(p).getFirst();
            assertEquals(true, engine.confirm(p, draft.id()).get("success"));
            assertEquals(false, engine.confirm(p, draft.id()).get("success"));
            assertEquals("SENT", store.find(p, draft.id()).orElseThrow().status());
        }
        verify(click, times(3)).run();
        verify(history, times(3)).markSent(any(), anyString());
    }

    @Test void autoModeDoesNotReplayOldPendingOrKnownResults() {
        engine.execute(LIEPIN, null);
        store.setMode(LIEPIN, "AUTO");
        engine.execute(LIEPIN, null);
        verify(click, never()).run();
        store.setMode(JOB51, "AUTO");
        engine.execute(JOB51, null);
        engine.execute(JOB51, null);
        verify(click, times(1)).run();
    }

    @Test void modeIsCapturedWhenRunIsAcceptedNotWhenItsBrowserWorkStarts() {
        when(manager.checkRecruitmentSession("liepin")).thenAnswer(invocation -> {
            store.setMode(LIEPIN, "AUTO");
            return true;
        });
        engine.execute(LIEPIN, null);
        assertEquals("PENDING", store.list(LIEPIN).getFirst().status());
        verify(click, never()).run();
    }

    @Test void unknownReceiptDoesNotClaimSuccessAndDoesNotResend() {
        doReturn(0L).when(engine).receiptTimeoutMillis();
        store.setMode(ZHILIAN, "AUTO");
        engine.execute(ZHILIAN, null);
        assertEquals("UNKNOWN", store.list(ZHILIAN).getFirst().status());
        verify(history, never()).markSent(any(), anyString());
        assertTrue(store.hold(ZHILIAN).isEmpty());
        engine.execute(ZHILIAN, null);
        verify(click, times(1)).run();
    }

    @Test void newCredentialsCannotClaimOldDraft() {
        engine.execute(LIEPIN, null);
        var draft = store.list(LIEPIN).getFirst();
        when(manager.recruitmentSessionScope("liepin")).thenReturn("other-account");
        assertEquals("session_changed", engine.confirm(LIEPIN, draft.id()).get("status"));
        assertEquals("PENDING", store.find(LIEPIN, draft.id()).orElseThrow().status());
        verify(click, never()).run();
    }

    @Test void credentialRotationDoesNotResendUnknownAndUncertainJobsDoNotBlockDifferentJobs() {
        var adapter = adapters.getFirst();
        when(adapter.collect(any(), any())).thenReturn(List.of(PlatformDeliveryStoreTest.job(LIEPIN, "12345"), PlatformDeliveryStoreTest.job(LIEPIN, "67890")));
        doReturn(0L).when(engine).receiptTimeoutMillis();
        store.setMode(LIEPIN, "AUTO");
        engine.execute(LIEPIN, null);
        assertEquals(2, store.list(LIEPIN).size());
        assertTrue(store.list(LIEPIN).stream().allMatch(row -> row.status().equals("UNKNOWN")));
        when(manager.recruitmentSessionScope("liepin")).thenReturn("rotated");
        engine.execute(LIEPIN, null);
        verify(click, times(2)).run();
    }

    @Test void existingHistorySkipsDraftBeforeAnyNavigation() {
        engine.execute(LIEPIN, null);
        var draft = store.list(LIEPIN).getFirst();
        clearInvocations(adapters.getFirst());
        when(history.sent(LIEPIN, "12345")).thenReturn(true);
        assertEquals("SKIPPED", engine.confirm(LIEPIN, draft.id()).get("status"));
        assertEquals("SKIPPED", store.find(LIEPIN, draft.id()).orElseThrow().status());
        verify(adapters.getFirst(), never()).open(any(), any(), any());
        verify(click, never()).run();
    }

    @Test void anotherSessionAttemptSkipsAnOldPendingDraftBeforeNavigation() {
        engine.execute(LIEPIN, null);
        var old = store.list(LIEPIN).getFirst();
        var fresh = store.prepare(LIEPIN, "fresh", old.job()).orElseThrow();
        store.claim(LIEPIN, "fresh", fresh.id());
        store.finish(LIEPIN, fresh.id(), "UNKNOWN", "fixture");
        clearInvocations(adapters.getFirst());
        assertEquals("SKIPPED", engine.confirm(LIEPIN, old.id()).get("status"));
        verify(adapters.getFirst(), never()).open(any(), any(), any());
        verify(click, never()).run();
    }

    @Test @SuppressWarnings("unchecked") void businessRestrictionAtHttp200PreventsClick() {
        AtomicReference<Consumer<Response>> observer = new AtomicReference<>();
        doAnswer(invocation -> { observer.set(invocation.getArgument(0)); return null; }).when(context).onResponse(any());
        Response response = mock(Response.class);
        when(response.url()).thenReturn("https://www.liepin.com/api/search");
        when(response.status()).thenReturn(200);
        when(response.headerValue("content-type")).thenReturn("application/json");
        when(response.text()).thenReturn("{\"message\":\"访问受限 抱歉，您暂时无法访问此页面\"}");
        doAnswer(invocation -> { observer.get().accept(response); return null; }).when(adapters.getFirst()).open(any(), any(), any());
        engine.execute(LIEPIN, null);
        assertTrue(store.hold(LIEPIN).isPresent());
        verify(click, never()).run();
        verify(context).offResponse(observer.get());
    }

    @Test void invalidKeywordsPauseWithoutNavigatingAndHugeRetryAfterCannotShortenCooldown() {
        when(adapters.getFirst().searches()).thenReturn(List.of());
        engine.execute(LIEPIN, null);
        assertTrue(store.hold(LIEPIN).isPresent());
        verify(adapters.getFirst(), never()).open(any(), any(), any());
        assertEquals(Long.MAX_VALUE, PlatformDeliveryEngine.retryAt("9999999999999999999999"));
        assertEquals(0, PlatformDeliveryEngine.retryAt("-1"));
    }

    @Test void loginFailureAndPersistentHoldPreventAnyAction() {
        when(manager.checkRecruitmentSession("liepin")).thenReturn(false);
        engine.execute(LIEPIN, null);
        assertTrue(store.hold(LIEPIN).isPresent());
        verify(adapters.getFirst(), never()).open(any(), any(), any());
        verify(click, never()).run();
    }

    @Test void clickExceptionIsUnknownAndStopsInsteadOfRetrying() {
        store.setMode(JOB51, "AUTO");
        doThrow(new IllegalStateException("click interrupted")).when(click).run();
        engine.execute(JOB51, null);
        assertEquals("UNKNOWN", store.list(JOB51).getFirst().status());
        assertTrue(store.hold(JOB51).isPresent());
        verify(click, times(1)).run();
        verify(history, never()).markSent(any(), anyString());
    }

    @Test @SuppressWarnings("unchecked") void httpRestrictionHonorsRetryAfterAndRemovesListener() {
        AtomicReference<Consumer<Response>> observer = new AtomicReference<>();
        doAnswer(invocation -> { observer.set(invocation.getArgument(0)); return null; }).when(context).onResponse(any());
        Response response = mock(Response.class);
        when(response.url()).thenReturn("https://www.liepin.com/api/apply");
        when(response.status()).thenReturn(429);
        when(response.headerValue("retry-after")).thenReturn("600");
        doAnswer(invocation -> { observer.get().accept(response); return null; }).when(adapters.getFirst()).open(any(), any(), any());
        engine.execute(LIEPIN, null);
        assertTrue(store.hold(LIEPIN).orElseThrow().retryAt() > System.currentTimeMillis() + 590_000);
        verify(click, never()).run();
        verify(context).offResponse(observer.get());
        assertEquals(false, engine.resume(LIEPIN, true).get("success"));
    }

    @Test void stopKeepsTaskSlotAndLogoutBlockedUntilBrowserWorkExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; })
                .when(adapters.getFirst()).open(any(), any(), any());
        try {
            assertEquals(true, engine.start(LIEPIN, null).get("success"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            engine.stop(LIEPIN);
            assertEquals("STOPPING", engine.status(LIEPIN).get("taskState"));
            assertEquals(false, engine.start(LIEPIN, null).get("success"));
            assertEquals(false, engine.logout(LIEPIN, () -> fail("must not clear a running session")).get("success"));
        } finally { release.countDown(); }
        long deadline = System.currentTimeMillis() + 5000;
        while (Boolean.TRUE.equals(engine.status(LIEPIN).get("isRunning")) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals("IDLE", engine.status(LIEPIN).get("taskState"));
        verify(click, never()).run();
    }
}
