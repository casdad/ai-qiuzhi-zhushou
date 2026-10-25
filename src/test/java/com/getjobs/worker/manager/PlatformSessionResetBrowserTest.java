package com.getjobs.worker.manager;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Disposable Chrome profiles and fulfilled fixtures only; no recruitment site is contacted. */
class PlatformSessionResetBrowserTest {
    private static final String BOSS = "https://www.zhipin.com";
    private static Playwright playwright;

    @TempDir
    Path directory;

    @BeforeAll
    static void startDriver() {
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    }

    @AfterAll
    static void stopDriver() {
        if (playwright != null) playwright.close();
    }

    @Test
    void logoutClearsCookiesAndOriginStorageWithoutChangingOtherPlatforms() {
        try (Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel("chrome").setHeadless(true));
             BrowserContext context = browser.newContext()) {
            installFixtures(context);
            Page boss = context.newPage();
            seed(boss, BOSS);
            Page unrelated = context.newPage();
            seed(unrelated, "https://www.liepin.com");
            context.addCookies(List.of(new Cookie("wt2", "fixture-session").setDomain(".zhipin.com").setPath("/")
                            .setHttpOnly(true).setSecure(true),
                    new Cookie("host-session", "fixture-session").setDomain("www.zhipin.com").setPath("/web"),
                    new Cookie("sso-session", "fixture-session").setDomain(".passport.zhipin.com").setPath("/"),
                    new Cookie("suffix-lookalike", "preserved").setDomain("notzhipin.com").setPath("/"),
                    new Cookie("other-session", "preserved").setDomain(".liepin.com").setPath("/")));

            Page fresh = PlatformSessionReset.clear(context, "zhipin.com", List.of(BOSS));

            assertTrue(boss.isClosed());
            assertFalse(unrelated.isClosed());
            assertEquals("fixture-session", unrelated.evaluate("localStorage.getItem('session')"));
            assertEquals(List.of(), context.cookies().stream().filter(cookie -> cookie.domain.equals("zhipin.com")
                            || cookie.domain.endsWith(".zhipin.com"))
                    .map(cookie -> cookie.name + "=" + cookie.value + "; domain=" + cookie.domain + "; expires=" + cookie.expires)
                    .toList());
            assertTrue(context.cookies().stream().anyMatch(cookie -> cookie.name.equals("other-session")));
            assertTrue(context.cookies().stream().anyMatch(cookie -> cookie.name.equals("suffix-lookalike")));
            assertEquals("about:blank", fresh.url());
            fresh.navigate(BOSS);
            assertNull(fresh.evaluate("localStorage.getItem('session')"));
            assertNull(fresh.evaluate("sessionStorage.getItem('session')"));
            assertEquals(0, ((Number) fresh.evaluate("async () => (await indexedDB.databases()).length")).intValue());
            assertEquals(0, ((Number) fresh.evaluate("async () => (await caches.keys()).length")).intValue());
        }
    }

    @Test
    void logoutOfTheLastTabDoesNotCloseThePersistentBrowser() {
        try (BrowserContext context = playwright.chromium().launchPersistentContext(directory,
                new BrowserType.LaunchPersistentContextOptions().setChannel("chrome").setHeadless(true))) {
            installFixtures(context);
            Page boss = context.pages().getFirst();
            seed(boss, BOSS);
            context.addCookies(List.of(new Cookie("wt2", "fixture-session").setDomain(".zhipin.com").setPath("/")));
            assertEquals(1, context.pages().size());

            Page fresh = PlatformSessionReset.clear(context, "zhipin.com", List.of(BOSS));
            assertFalse(fresh.isClosed());
            assertEquals(List.of(fresh), context.pages());
            fresh.navigate(BOSS);
            assertNull(fresh.evaluate("localStorage.getItem('session')"));
            assertNull(fresh.evaluate("sessionStorage.getItem('session')"));
            assertTrue(context.cookies().isEmpty());
        }
    }

    @Test
    void managerStaysLoggedOutOnRecheckUntilNewCredentialsAreProvided() {
        PlaywrightManager manager = new PlaywrightManager();
        try {
            manager.runOnPlaywright(() -> {
                Playwright driver = Playwright.create(new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
                ReflectionTestUtils.setField(manager, "playwright", driver);
                Browser browser = driver.chromium().launch(new BrowserType.LaunchOptions()
                        .setChannel("chrome").setHeadless(true));
                ReflectionTestUtils.setField(manager, "browser", browser);
                BrowserContext context = browser.newContext();
                ReflectionTestUtils.setField(manager, "context", context);
                installFixtures(context);
                Page page = context.newPage();
                ReflectionTestUtils.setField(manager, "bossPage", page);
                page.navigate(BOSS + "/web/geek/jobs");
                context.addCookies(List.of(new Cookie("wt2", "old-fixture-session").setDomain(".zhipin.com").setPath("/")));
                assertTrue(manager.checkBossSession());

                manager.clearBossCookies();

                Page fresh = manager.getBossPage();
                assertEquals(BOSS + "/web/user/", fresh.url());
                assertFalse(manager.isLoggedIn("boss"));
                assertFalse(manager.checkBossSession());
                assertTrue(context.cookies().isEmpty());
                fresh.navigate(BOSS + "/web/geek/jobs");
                assertFalse(manager.checkBossSession());
                assertFalse(manager.isLoggedIn("boss"));

                context.addCookies(List.of(new Cookie("wt2", "new-fixture-session").setDomain(".zhipin.com").setPath("/")));
                assertTrue(manager.checkBossSession());
                assertTrue(manager.isLoggedIn("boss"));
            });
        } finally {
            manager.destroy();
        }
    }

    private static void installFixtures(BrowserContext context) {
        context.route("**/*", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html").setBody("""
                        <!doctype html><html><body><ul><li class="nav-figure">
                        <span class="label-text">Fixture user</span></li></ul></body></html>
                        """)));
    }

    private static void seed(Page page, String origin) {
        page.navigate(origin);
        page.evaluate("""
                async () => {
                    localStorage.setItem('session', 'fixture-session');
                    sessionStorage.setItem('session', 'fixture-session');
                    await caches.open('fixture-cache');
                    await new Promise((resolve, reject) => {
                        const request = indexedDB.open('fixture-db', 1);
                        request.onupgradeneeded = () => request.result.createObjectStore('tokens');
                        request.onsuccess = () => { request.result.close(); resolve(); };
                        request.onerror = () => reject(request.error);
                    });
                }
                """);
    }
}
