package com.getjobs.worker.manager;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformSessionResetTest {
    private BrowserContext context;
    private Page target;
    private Page unrelated;
    private Page probe;
    private CDPSession session;

    @BeforeEach
    void setUp() {
        context = mock(BrowserContext.class);
        target = mock(Page.class);
        unrelated = mock(Page.class);
        probe = mock(Page.class);
        session = mock(CDPSession.class);
        when(target.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(unrelated.url()).thenReturn("https://www.liepin.com/");
        when(context.pages()).thenReturn(List.of(target, unrelated));
        when(context.newPage()).thenReturn(probe);
        when(context.newCDPSession(probe)).thenReturn(session);
    }

    @Test
    void clearsTargetOriginsAndFramesWithoutNavigatingOrClosingOtherPlatform() {
        Frame httpFrame = mock(Frame.class);
        Frame otherFrame = mock(Frame.class);
        when(httpFrame.url()).thenReturn("http://passport.zhipin.com:8080/login?token=test");
        when(otherFrame.url()).thenReturn("https://www.liepin.com/frame");
        when(target.frames()).thenReturn(List.of(httpFrame, otherFrame));

        assertSame(probe, PlatformSessionReset.clear(context, "zhipin.com", List.of("https://passport.zhipin.com/")));

        ArgumentCaptor<JsonObject> parameters = ArgumentCaptor.forClass(JsonObject.class);
        verify(session, times(4)).send(eq("Storage.clearDataForOrigin"), parameters.capture());
        Set<String> origins = parameters.getAllValues().stream()
                .map(value -> value.get("origin").getAsString()).collect(Collectors.toSet());
        assertEquals(Set.of("https://zhipin.com", "https://www.zhipin.com", "https://passport.zhipin.com",
                "http://passport.zhipin.com:8080"), origins);
        parameters.getAllValues().forEach(value -> assertEquals("all", value.get("storageTypes").getAsString()));
        verify(target).close();
        verify(unrelated, never()).close();
        verify(probe, never()).navigate(anyString());
        verify(target, never()).navigate(anyString());
        verify(unrelated, never()).navigate(anyString());
        verify(session).detach();
        verify(probe, never()).close();
        InOrder order = inOrder(target, context, session);
        order.verify(context).newPage();
        order.verify(target).close();
        order.verify(context).newCDPSession(probe);
        order.verify(session, times(4)).send(eq("Storage.clearDataForOrigin"), any(JsonObject.class));
    }

    @Test
    void rejectsSuffixSpoofingAndOnlyClearsTargetCookies() {
        when(target.url()).thenReturn("https://notzhipin.com/");
        PlatformSessionReset.clear(context, "zhipin.com", List.of(
                "https://zhipin.com.evil.test", "https://notzhipin.com", "https://zhipin.com@evil.test",
                "https://evil.test@zhipin.com", "javascript:alert(1)", "broken", "https://WWW.ZHIPIN.COM:443/"));

        ArgumentCaptor<JsonObject> parameters = ArgumentCaptor.forClass(JsonObject.class);
        verify(session, times(2)).send(eq("Storage.clearDataForOrigin"), parameters.capture());
        assertEquals(Set.of("https://zhipin.com", "https://www.zhipin.com"), parameters.getAllValues().stream()
                .map(value -> value.get("origin").getAsString()).collect(Collectors.toSet()));
        ArgumentCaptor<BrowserContext.ClearCookiesOptions> options =
                ArgumentCaptor.forClass(BrowserContext.ClearCookiesOptions.class);
        verify(context).clearCookies(options.capture());
        Pattern domain = (Pattern) options.getValue().domain;
        assertFalse(domain.pattern().contains("\\Q"), "Cookie filter must be compatible with JavaScript RegExp");
        assertFalse(domain.pattern().contains("\\E"));
        assertTrue(domain.matcher(".zhipin.com").matches());
        assertTrue(domain.matcher(".passport.zhipin.com").matches());
        assertTrue(domain.matcher("WWW.ZHIPIN.COM").matches());
        assertFalse(domain.matcher("notzhipin.com").matches());
        assertFalse(domain.matcher("zhipin.com.evil.test").matches());
        assertFalse(domain.matcher("liepin.com").matches());
        verify(context, never()).clearCookies();
        verify(target, never()).close();
    }

    @Test
    void remainingCookiesAreNotReportedAsSuccessfulLogout() {
        when(context.cookies()).thenReturn(List.of(new Cookie("wt2", "fixture-session")
                .setDomain(".zhipin.com").setPath("/")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PlatformSessionReset.clear(context, "zhipin.com", List.of()));

        assertEquals("Platform cookies remain after session reset", failure.getSuppressed()[0].getMessage());
        verify(session).detach();
        verify(probe).close();
    }

    @Test
    void failingToOpenReplacementDoesNotCloseExistingTabs() {
        when(context.newPage()).thenThrow(new IllegalStateException("Cannot create replacement"));

        assertThrows(IllegalStateException.class,
                () -> PlatformSessionReset.clear(context, "zhipin.com", List.of()));

        verify(target, never()).close();
        verify(unrelated, never()).close();
        verify(context).clearCookies(any(BrowserContext.ClearCookiesOptions.class));
    }

    @Test
    void cdpFailureStillClearsCookiesDetachesAndClosesProbe() {
        doThrow(new IllegalStateException("CDP failure")).when(session)
                .send(eq("Storage.clearDataForOrigin"), any(JsonObject.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PlatformSessionReset.clear(context, "zhipin.com", List.of()));

        assertEquals("Platform session reset did not complete", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        verify(context).clearCookies(any(BrowserContext.ClearCookiesOptions.class));
        verify(session).detach();
        verify(probe).close();
    }

    @Test
    void attachFailureStillClearsCookiesAndClosesProbe() {
        when(context.newCDPSession(probe)).thenThrow(new IllegalStateException("Attach failure"));

        assertThrows(IllegalStateException.class,
                () -> PlatformSessionReset.clear(context, "zhipin.com", null));

        verify(context).clearCookies(any(BrowserContext.ClearCookiesOptions.class));
        verify(probe).close();
        verifyNoInteractions(session);
    }

    @Test
    void cookieClearFailureStillDetachesAndClosesProbe() {
        doThrow(new IllegalStateException("Cookie clear failure")).when(context)
                .clearCookies(any(BrowserContext.ClearCookiesOptions.class));

        assertThrows(IllegalStateException.class,
                () -> PlatformSessionReset.clear(context, "zhipin.com", List.of()));

        verify(session).detach();
        verify(probe).close();
    }

    @Test
    void closeAndCleanupFailuresAreNotReportedAsSuccess() {
        doThrow(new IllegalStateException("Page close failure")).when(target).close();
        doThrow(new IllegalStateException("Detach failure")).when(session).detach();
        doThrow(new IllegalStateException("Probe close failure")).when(probe).close();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PlatformSessionReset.clear(context, "zhipin.com", List.of()));

        assertEquals(3, failure.getSuppressed().length);
        verify(context).clearCookies(any(BrowserContext.ClearCookiesOptions.class));
        verify(probe).close();
        verify(unrelated, never()).close();
    }

    @Test
    void invalidDomainFailsBeforeTouchingBrowserState() {
        for (String domain : List.of("", "zhipin.com/", "zhipin.com:443", "zhipin.com?x=1", "evil.test@zhipin.com")) {
            assertThrows(IllegalArgumentException.class,
                    () -> PlatformSessionReset.clear(context, domain, List.of()));
        }
        verifyNoInteractions(context);
    }

}
