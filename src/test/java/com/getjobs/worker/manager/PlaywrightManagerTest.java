package com.getjobs.worker.manager;

import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaywrightManagerTest {
    private PlaywrightManager manager;
    private BrowserContext context;
    private Page page;
    private CookieService cookieService;

    @BeforeEach
    void setUp() {
        manager = new PlaywrightManager();
        context = mock(BrowserContext.class);
        page = mock(Page.class);
        cookieService = mock(CookieService.class);
        ReflectionTestUtils.setField(manager, "context", context);
        ReflectionTestUtils.setField(manager, "bossPage", page);
        ReflectionTestUtils.setField(manager, "playwright", mock(Playwright.class));
        ReflectionTestUtils.setField(manager, "cookieService", cookieService);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(context.pages()).thenReturn(List.of(page));
        when(context.cookies()).thenReturn(List.of());
        Locator hidden = mock(Locator.class);
        when(hidden.first()).thenReturn(hidden);
        when(page.locator(anyString())).thenReturn(hidden);
    }

    @AfterEach
    void tearDown() {
        manager.destroy();
    }

    @Test
    void startupDefaultsToBossAndDoesNotCreateUnusedPlatformTabs() {
        when(context.newPage()).thenReturn(page);
        String configured = (String) ReflectionTestUtils.getField(manager, "startupPlatforms");
        assertEquals(List.of("boss"), PlaywrightManager.parseStartupPlatforms(configured));
        manager.runOnPlaywright(() -> manager.initializePlatformPages(
                PlaywrightManager.parseStartupPlatforms(configured)));
        verify(context, times(1)).newPage();
        verify(page).navigate(eq("https://www.zhipin.com/web/geek/jobs"), any(Page.NavigateOptions.class));
        verify(page, never()).waitForLoadState(eq(com.microsoft.playwright.options.LoadState.NETWORKIDLE), any());
        assertNull(manager.getLiepinPage());
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
        assertFalse(manager.isLoggedIn("51job"));
    }

    @Test
    void startupPlatformSelectionNormalizesAndRejectsInvalidNames() {
        assertEquals(List.of("boss", "51job", "liepin", "zhilian"),
                PlaywrightManager.parseStartupPlatforms(" BOSS,51job,boss,liepin,zhilian "));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightManager.parseStartupPlatforms("boss,unknown"));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightManager.parseStartupPlatforms(" , "));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightManager.parseStartupPlatforms(null));
    }

    @Test
    void explicitlySelectedPlatformStillOpensItsOwnLoginPage() {
        when(context.newPage()).thenReturn(page);
        when(page.url()).thenReturn("https://www.liepin.com");
        manager.runOnPlaywright(() -> manager.initializePlatformPages(List.of("liepin")));
        verify(context, times(1)).newPage();
        verify(page).navigate(eq("https://www.liepin.com"), any(Page.NavigateOptions.class));
        assertSame(page, manager.getLiepinPage());
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
    }

    @Test
    void sessionBindingIsOrderIndependentAndChangesWithCredentials() {
        Cookie a = cookie("wt2", "first", ".zhipin.com");
        Cookie b = cookie("zp_at", "second", "www.zhipin.com");
        String scope = PlaywrightManager.bossSessionScope(List.of(a, b));
        assertEquals(scope, PlaywrightManager.bossSessionScope(List.of(b, a)));
        assertTrue(scope.matches("[a-f0-9]{64}"));
        assertNotEquals(scope, PlaywrightManager.bossSessionScope(
                List.of(a, cookie("zp_at", "rotated", "www.zhipin.com"))));
    }

    @Test
    void sessionBindingRejectsExpiredEmptyAndUnrelatedCookies() {
        Cookie expired = cookie("wt2", "expired", ".zhipin.com");
        expired.expires = 1.0;
        assertNull(PlaywrightManager.bossSessionScope(List.of(expired,
                cookie("wt2", "other-domain", "notzhipin.com"),
                cookie("analytics", "not-login", ".zhipin.com"),
                cookie("wt2", "", ".zhipin.com"))));
    }

    @Test
    void listNavigationCookieRefreshDoesNotChangeSessionBinding() {
        Cookie login = cookie("wt2", "same-login", ".zhipin.com");
        Cookie token = cookie("zp_at", "same-token", ".zhipin.com");
        String scope = PlaywrightManager.bossSessionScope(List.of(login, token));
        assertNotNull(scope);
        assertEquals(scope, PlaywrightManager.bossSessionScope(List.of(login, token,
                cookie("bst", "before-navigation", ".zhipin.com"))));
        assertEquals(scope, PlaywrightManager.bossSessionScope(List.of(login, token,
                cookie("bst", "after-navigation", ".zhipin.com"))));
        assertNotEquals(scope, PlaywrightManager.bossSessionScope(List.of(token,
                cookie("wt2", "different-login", ".zhipin.com"))));
    }

    @Test
    void navigationCookieAloneCannotBindOrAuthenticateASession() {
        Cookie navigation = cookie("bst", "navigation-only", ".zhipin.com");
        when(context.cookies()).thenReturn(List.of(navigation));
        Locator avatar = mock(Locator.class);
        when(avatar.first()).thenReturn(avatar);
        when(avatar.isVisible()).thenReturn(true);
        when(page.locator("li.nav-figure span.label-text, li.nav-figure")).thenReturn(avatar);
        assertNull(PlaywrightManager.bossSessionScope(List.of(navigation)));
        assertNull(manager.getBossSessionScope());
        assertFalse(manager.checkBossSession());
    }

    @Test
    void cookiesAloneDoNotProveLoginAndExpiredCacheIsCleared() {
        when(context.cookies()).thenReturn(List.of(cookie("wt2", "stale", ".zhipin.com")));
        manager.setLoginStatus("boss", true);
        assertFalse(manager.checkBossSession());
        assertFalse(manager.isLoggedIn("boss"));
        verify(page, never()).navigate(anyString());
    }

    @Test
    void explicitAvatarAllowsLoginButChallengeInDetailTabBlocksIt() {
        Locator avatar = mock(Locator.class);
        when(avatar.first()).thenReturn(avatar);
        when(avatar.isVisible()).thenReturn(true);
        when(page.locator("li.nav-figure span.label-text, li.nav-figure")).thenReturn(avatar);
        when(context.cookies()).thenReturn(List.of(cookie("wt2", "test-session", ".zhipin.com")));
        assertTrue(manager.checkBossSession());
        when(context.cookies()).thenReturn(List.of());
        assertFalse(manager.checkBossSession());
        when(context.cookies()).thenReturn(List.of(cookie("wt2", "test-session", ".zhipin.com")));

        Page detail = mock(Page.class);
        when(detail.url()).thenReturn("https://www.zhipin.com/web/common/verify/index.html");
        when(context.pages()).thenReturn(List.of(page, detail));
        assertFalse(manager.checkBossSession());
        assertFalse(manager.isLoggedIn("boss"));
    }

    @Test
    void aRestrictedPageCannotBeResumedJustBecauseAnAvatarAndCookiesRemain() {
        Locator avatar = mock(Locator.class);
        when(avatar.first()).thenReturn(avatar);
        when(avatar.isVisible()).thenReturn(true);
        when(page.locator("li.nav-figure span.label-text, li.nav-figure")).thenReturn(avatar);
        when(context.cookies()).thenReturn(List.of(cookie("wt2", "test-session", ".zhipin.com")));
        Locator body = mock(Locator.class);
        when(body.count()).thenReturn(1);
        when(body.innerText(any(Locator.InnerTextOptions.class)))
                .thenReturn("访问受限\n抱歉，您暂时无法访问此页面");
        when(page.locator("body")).thenReturn(body);

        assertFalse(manager.checkBossSession());
        verify(page, never()).navigate(anyString());
    }

    @Test
    void cookieClearIsDomainScopedAndRunsOnOwnerThread() {
        Page freshPage = mock(Page.class);
        when(context.newPage()).thenReturn(freshPage);
        when(context.newCDPSession(freshPage)).thenReturn(mock(CDPSession.class));
        AtomicReference<Thread> owner = new AtomicReference<>();
        manager.runOnPlaywright(() -> owner.set(Thread.currentThread()));
        doAnswer(invocation -> {
            assertSame(owner.get(), Thread.currentThread());
            return null;
        }).when(context).clearCookies(any(BrowserContext.ClearCookiesOptions.class));
        manager.setLoginStatus("boss", true);
        manager.clearBossCookies();
        ArgumentCaptor<BrowserContext.ClearCookiesOptions> captor =
                ArgumentCaptor.forClass(BrowserContext.ClearCookiesOptions.class);
        verify(context).clearCookies(captor.capture());
        Pattern pattern = (Pattern) ReflectionTestUtils.getField(captor.getValue(), "domain");
        assertTrue(pattern.matcher(".zhipin.com").matches());
        assertTrue(pattern.matcher(".www.zhipin.com").matches());
        assertFalse(pattern.matcher("liepin.com").matches());
        assertFalse(pattern.matcher("notzhipin.com").matches());
        verify(context, never()).clearCookies();
        assertSame(freshPage, manager.getBossPage());
        verify(context, times(1)).newPage();
        verify(freshPage, never()).close();
        verify(freshPage).navigate(eq("https://www.zhipin.com/web/user/"), any(Page.NavigateOptions.class));
        assertFalse(manager.isLoggedIn("boss"));
    }

    @Test
    void staleLoginTabDoesNotOverrideAnAuthenticatedMainPage() {
        Locator avatar = mock(Locator.class);
        when(avatar.first()).thenReturn(avatar);
        when(avatar.isVisible()).thenReturn(true);
        when(page.locator("li.nav-figure span.label-text, li.nav-figure")).thenReturn(avatar);
        when(context.cookies()).thenReturn(List.of(cookie("wt2", "current-session", ".zhipin.com")));
        Page staleLogin = mock(Page.class);
        when(staleLogin.url()).thenReturn("https://www.zhipin.com/web/user/?ka=header-login");
        Locator hidden = mock(Locator.class);
        when(hidden.first()).thenReturn(hidden);
        when(staleLogin.locator(anyString())).thenReturn(hidden);
        when(context.pages()).thenReturn(List.of(page, staleLogin));
        assertTrue(manager.checkBossSession());
    }

    @Test
    void logoutRemainsSuccessfulWhenOnlyTheLandingPageTimesOut() {
        Page freshPage = mock(Page.class);
        when(context.newPage()).thenReturn(freshPage);
        when(context.newCDPSession(freshPage)).thenReturn(mock(CDPSession.class));
        when(freshPage.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenThrow(new com.microsoft.playwright.TimeoutError("Landing page timed out"));
        manager.setLoginStatus("boss", true);
        assertDoesNotThrow(manager::clearBossCookies);
        assertFalse(manager.isLoggedIn("boss"));
        verify(context).clearCookies(any(BrowserContext.ClearCookiesOptions.class));
        ArgumentCaptor<Page.NavigateOptions> navigation = ArgumentCaptor.forClass(Page.NavigateOptions.class);
        verify(freshPage).navigate(eq("https://www.zhipin.com/web/user/"), navigation.capture());
        assertEquals(com.microsoft.playwright.options.WaitUntilState.COMMIT, navigation.getValue().waitUntil);
        assertTrue(navigation.getValue().timeout <= 5_000);
    }

    @Test
    void legacyImportDoesNotReadOrOverwriteExistingProfileCredentials() {
        when(context.cookies()).thenReturn(List.of(cookie("bst", "profile-session", ".zhipin.com")));
        assertThrows(IllegalStateException.class, manager::importLegacyBossCookies);
        verifyNoInteractions(cookieService);
        verify(context, never()).addCookies(anyList());
        assertThrows(IllegalStateException.class, manager::importLegacyBossCookies);
    }

    @Test
    void manualCookiePersistenceIsDisabledForEveryPlatform() {
        assertThrows(IllegalStateException.class, () -> manager.saveBossCookiesToDb("manual"));
        assertThrows(IllegalStateException.class, () -> manager.saveLiepinCookiesToDb("manual"));
        assertThrows(IllegalStateException.class, () -> manager.save51jobCookiesToDb("manual"));
        assertThrows(IllegalStateException.class, () -> manager.saveZhilianCookiesToDb("manual"));
        assertThrows(IllegalStateException.class, () -> manager.saveCookiesToDb("boss", "manual"));
        verifyNoInteractions(cookieService);
        verify(context, never()).cookies();
    }

    @Test
    void freshLaunchDoesNotUsePersistentProfileOrStorageState() {
        Playwright engine = mock(Playwright.class);
        BrowserType chromium = mock(BrowserType.class);
        Browser browser = mock(Browser.class);
        when(engine.chromium()).thenReturn(chromium);
        when(chromium.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenReturn(context);
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) manager.setLoginStatus(platform, true);
        assertSame(context, manager.callOnPlaywright(() -> manager.createFreshBrowserContext(engine)));
        ArgumentCaptor<Browser.NewContextOptions> options = ArgumentCaptor.forClass(Browser.NewContextOptions.class);
        verify(browser).newContext(options.capture());
        assertNull(ReflectionTestUtils.getField(options.getValue(), "storageState"));
        assertNull(ReflectionTestUtils.getField(options.getValue(), "storageStatePath"));
        verify(chromium, never()).launchPersistentContext(any(java.nio.file.Path.class), any());
        verifyNoInteractions(cookieService);
        verify(context, never()).addCookies(anyList());
        for (String platform : List.of("boss", "liepin", "51job", "zhilian")) assertFalse(manager.isLoggedIn(platform));
    }

    @Test
    void loginSuccessKeepsOnlyTheCurrentRuntimeSession() {
        manager.runOnPlaywright(() -> ReflectionTestUtils.invokeMethod(manager, "onLoginSuccess", "boss"));
        assertTrue(manager.isLoggedIn("boss"));
        manager.init();
        assertTrue(manager.isLoggedIn("boss"));
        verifyNoInteractions(cookieService);
        verify(context, never()).clearCookies();
    }

    @Test
    void nestedOwnerCallsAndClosingStayOnTheSameThread() {
        AtomicReference<Thread> owner = new AtomicReference<>();
        manager.runOnPlaywright(() -> {
            owner.set(Thread.currentThread());
            manager.runOnPlaywright(() -> assertSame(owner.get(), Thread.currentThread()));
        });
        doAnswer(invocation -> {
            assertSame(owner.get(), Thread.currentThread());
            return null;
        }).when(context).close();
        manager.destroy();
        assertFalse(manager.isInitialized());
        verify(context).close();
    }

    @Test
    void navigationChecksTheUrlAfterThePageHasSettled() {
        assertTrue(navigateBossPage());
        verify(page).navigate(eq("https://www.zhipin.com/web/geek/jobs"), any(Page.NavigateOptions.class));
        verify(page).waitForTimeout(3000);
    }

    @Test
    void navigationDoesNotReportSuccessWhenTheSiteLaterBlanksTheTab() {
        AtomicReference<String> url = new AtomicReference<>("about:blank");
        when(page.url()).thenAnswer(invocation -> url.get());
        when(page.navigate(anyString(), any(Page.NavigateOptions.class))).thenAnswer(invocation -> {
            url.set("https://www.zhipin.com/web/geek/jobs");
            return null;
        });
        doAnswer(invocation -> {
            url.set("about:blank");
            return null;
        }).when(page).waitForTimeout(3000);
        assertFalse(navigateBossPage());
        verify(page, times(3)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void pageCrashDuringThePostNavigationCheckDoesNotAbortApplicationStartup() {
        doThrow(new PlaywrightException("Page crashed")).when(page).waitForTimeout(3000);
        assertFalse(navigateBossPage());
        verify(page, times(1)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    void closedPageIsNotNavigated() {
        when(page.isClosed()).thenReturn(true);
        assertFalse(navigateBossPage());
        verify(page, never()).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    private boolean navigateBossPage() {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(manager, "navigateTo", page,
                "https://www.zhipin.com/web/geek/jobs", "zhipin.com", "Boss"));
    }

    private static Cookie cookie(String name, String value, String domain) {
        Cookie cookie = new Cookie(name, value);
        cookie.domain = domain;
        cookie.path = "/";
        return cookie;
    }
}
