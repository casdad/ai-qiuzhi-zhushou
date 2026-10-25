package com.getjobs.worker.platform;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.job51.Job51;
import com.getjobs.worker.job51.Job51Config;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local response fixtures in disposable Chrome contexts; never requests a real platform. */
class PlatformAdapterBrowserTest {
    static Playwright playwright;
    static Browser browser;

    @BeforeAll static void launch() {
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));
    }
    @AfterAll static void close() { if (browser != null) browser.close(); if (playwright != null) playwright.close(); }

    private PlatformAdapter adapter(RecruitmentPlatform p) {
        ConfigService config = mock(ConfigService.class);
        LiepinConfig liepin = new LiepinConfig(); liepin.setKeywords(List.of("C++ & R&D")); liepin.setCityCode("010");
        Job51Config job51 = new Job51Config(); job51.setKeywords(List.of("C++ & R&D")); job51.setJobArea(List.of("010000"));
        ZhilianConfig zhilian = new ZhilianConfig(); zhilian.setKeywords(List.of("C++ & R&D")); zhilian.setCityCode("530");
        when(config.getLiepinConfig()).thenReturn(liepin);
        when(config.getJob51Config()).thenReturn(job51);
        when(config.getZhilianConfig()).thenReturn(zhilian);
        return switch (p) { case LIEPIN -> new Liepin(config); case JOB51 -> new Job51(config); case ZHILIAN -> new ZhiLian(config); };
    }

    private Page fixture(BrowserContext context, RecruitmentPlatform p) {
        context.route("**/*", route -> route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html(p))));
        Page page = context.newPage();
        page.navigate("https://www." + p.domain + "/search");
        return page;
    }

    @ParameterizedTest @EnumSource(RecruitmentPlatform.class)
    void collectsStableIdentityAndSendsOnlyOneJobWithANewExplicitReceipt(RecruitmentPlatform p) {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, p);
            PlatformAdapter adapter = adapter(p);
            var jobs = adapter.collect(page, adapter.searches().getFirst());
            assertEquals(2, jobs.size());
            assertEquals("12345", jobs.getFirst().jobKey());
            assertEquals("Engineer 12345", jobs.getFirst().title());
            assertTrue(PlatformPageSafety.authenticated(p, page));
            // Reorder cards; identity must not rely on the API/card array position.
            page.evaluate("() => document.body.append(document.querySelector('[data-job-id=\"12345\"]'))");
            var prepared = adapter.prepare(page, jobs.getFirst());
            assertEquals(0, ((Number) page.evaluate("window.clicks")).intValue());
            prepared.click().run();
            assertEquals(List.of("12345"), page.evaluate("window.sentIds"));
            assertEquals("SENT", adapter.receipt(page, prepared));
        }
    }

    @ParameterizedTest @EnumSource(RecruitmentPlatform.class)
    void noReceiptOrOldReceiptCannotBeCountedAsSuccess(RecruitmentPlatform p) {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, p);
            PlatformAdapter adapter = adapter(p);
            page.evaluate("() => { window.withReceipt=false; document.querySelector('#receipt').textContent='投递成功'; }");
            var job = adapter.collect(page, adapter.searches().getFirst()).getFirst();
            var prepared = adapter.prepare(page, job);
            prepared.click().run();
            assertNull(adapter.receipt(page, prepared));
        }
    }

    @ParameterizedTest @EnumSource(RecruitmentPlatform.class)
    void changedIdentityBetweenPreparationAndClickIsRejected(RecruitmentPlatform p) {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, p);
            PlatformAdapter adapter = adapter(p);
            var job = adapter.collect(page, adapter.searches().getFirst()).getFirst();
            var prepared = adapter.prepare(page, job);
            page.locator("[data-job-id='12345'] a").first().evaluate("el => el.href=el.href.replace('12345','99999')");
            assertThrows(PlatformDeliveryStore.Paused.class, () -> prepared.click().run());
            assertEquals(0, ((Number) page.evaluate("window.clicks")).intValue());
        }
    }

    @ParameterizedTest @EnumSource(RecruitmentPlatform.class)
    void duplicateIdentityAndRestrictionFailClosed(RecruitmentPlatform p) {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, p);
            PlatformAdapter adapter = adapter(p);
            var job = adapter.collect(page, adapter.searches().getFirst()).getFirst();
            page.evaluate("() => document.body.append(document.querySelector('[data-job-id=\"12345\"]').cloneNode(true))");
            assertThrows(PlatformDeliveryStore.Paused.class, () -> adapter.prepare(page, job));
            page.setContent("<main class='error-page'>访问受限 抱歉，您暂时无法访问此页面</main>");
            assertFalse(PlatformPageSafety.authenticated(p, page));
        }
    }

    @Test void batchControlCannotSendWhenExtraSelectionAppearsBeforeClick() {
        try (BrowserContext context = browser.newContext()) {
            var p = RecruitmentPlatform.JOB51;
            Page page = fixture(context, p);
            PlatformAdapter adapter = adapter(p);
            var job = adapter.collect(page, adapter.searches().getFirst()).getFirst();
            var prepared = adapter.prepare(page, job);
            page.locator("[data-job-id='67890'] .ick").evaluate("el => el.setAttribute('aria-checked','true')");
            assertThrows(PlatformDeliveryStore.Paused.class, () -> prepared.click().run());
            assertEquals(0, ((Number) page.evaluate("window.clicks")).intValue());
        }
    }

    @Test void negativeAndAggregateNoticesCannotClaimAnIndividualSuccess() {
        for (String text : List.of("未投递成功", "投递失败", "投递成功0个", "投递成功2个", "投递成功12个", "投递成功1个，未投递1个", "点击投递")) {
            assertFalse(PlatformAdapter.confirmedNotice(text), text);
        }
        assertTrue(PlatformAdapter.confirmedNotice("投递成功1个，未投递0个"));
        assertTrue(PlatformAdapter.confirmedNotice("投递成功！"));
    }

    @Test void batchControlCannotIgnoreSelectionsOutsideTheInspectionBound() {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, RecruitmentPlatform.JOB51);
            PlatformAdapter adapter = adapter(RecruitmentPlatform.JOB51);
            var job = adapter.collect(page, adapter.searches().getFirst()).getFirst();
            page.evaluate("() => { for(let i=0;i<101;i++){ const el=document.createElement('div');el.className='ick';el.setAttribute('aria-checked','true');document.body.append(el); } }");
            assertThrows(PlatformDeliveryStore.Paused.class, () -> adapter.prepare(page, job));
            assertEquals(0, ((Number) page.evaluate("window.clicks")).intValue());
        }
    }

    @ParameterizedTest @EnumSource(RecruitmentPlatform.class)
    void buttonWhitespaceDoesNotChangeItsExactAction(RecruitmentPlatform p) {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, p);
            page.locator("button").evaluateAll("buttons => buttons.forEach(el => el.textContent='  '+el.textContent+'  ')");
            PlatformAdapter adapter = adapter(p);
            var job = adapter.collect(page, adapter.searches().getFirst()).getFirst();
            var prepared = adapter.prepare(page, job);
            prepared.click().run();
            assertEquals(List.of("12345"), page.evaluate("window.sentIds"));
        }
    }

    @Test void onlyNewPopupFromThisActionCanConfirmAndUnrelatedPagesStayOpen() {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, RecruitmentPlatform.ZHILIAN);
            context.route("**/receipt/**", route -> route.fulfill(new Route.FulfillOptions()
                    .setContentType("text/html; charset=utf-8").setBody("<div class='deliver-dialog'>申请成功</div>")));
            Page old = page.waitForPopup(() -> page.evaluate("window.open('/receipt/old')"));
            old.waitForLoadState();
            PlatformAdapter adapter = adapter(RecruitmentPlatform.ZHILIAN);
            var job = adapter.collect(page, adapter.searches().getFirst()).getFirst();
            page.locator("[data-job-id='12345'] button").evaluate("el => el.onclick=() => window.open('/receipt/new')");
            var prepared = adapter.prepare(page, job);
            assertNull(adapter.receipt(page, prepared), "An existing popup cannot confirm this attempt");
            Page fresh = page.waitForPopup(prepared.click());
            fresh.waitForLoadState();
            assertEquals("SENT", adapter.receipt(page, prepared));
            adapter.closeReceipt(page, prepared, "SENT");
            assertTrue(fresh.isClosed());
            assertFalse(old.isClosed());
            assertFalse(page.isClosed());
        }
    }

    @Test void platformSpecificDailyLimitAndUnknownLoginPageFailClosed() {
        try (BrowserContext context = browser.newContext()) {
            Page page = fixture(context, RecruitmentPlatform.JOB51);
            page.locator("#receipt").evaluate("el => el.textContent='您今日投递太多，休息一下明天再来'");
            assertFalse(PlatformPageSafety.authenticated(RecruitmentPlatform.JOB51, page));
            page.setContent("<main>Loading</main>");
            assertFalse(PlatformPageSafety.authenticated(RecruitmentPlatform.JOB51, page));
        }
    }

    @Test void keywordsAreEncodedInsteadOfBecomingExtraQueryParameters() {
        assertTrue(adapter(RecruitmentPlatform.LIEPIN).searches().getFirst().url().contains("C%2B%2B+%26+R%26D"));
        assertTrue(adapter(RecruitmentPlatform.JOB51).searches().getFirst().url().contains("C%2B%2B+%26+R%26D"));
        assertEquals("C++ & R&D", adapter(RecruitmentPlatform.ZHILIAN).searches().getFirst().keyword());
    }

    private String html(RecruitmentPlatform p) {
        String header = switch (p) {
            case LIEPIN -> "<div id='header-quick-menu-user-info'>Fixture user</div>";
            case JOB51 -> "<a class='uname e_icon at'>Fixture user</a>";
            case ZHILIAN -> "<div class='home-header__c-user'>Fixture user</div>";
        };
        StringBuilder body = new StringBuilder(header);
        body.append("<div id='receipt' role='alert'></div><input name='kw' placeholder='职位'>");
        for (String id : List.of("12345", "67890")) {
            String url = PlatformDeliveryStoreTest.job(p, id).url();
            body.append(switch (p) {
                case LIEPIN -> "<div class='jobCardPcContainer' data-job-id='" + id + "'><a href='" + url + "'><h3>Engineer " + id + "</h3></a><div class='company-name'>Fixture company</div><button onclick=\"send(['" + id + "'])\">聊一聊</button></div>";
                case JOB51 -> "<div data-job-id='" + id + "'><a class='jname text-cut' href='" + url + "'>Engineer " + id + "</a><span class='cname text-cut'>Fixture company</span><div role='checkbox' class='ick' aria-checked='" + id.equals("67890") + "' onclick=\"this.setAttribute('aria-checked',this.getAttribute('aria-checked')==='true'?'false':'true')\">Select</div></div>";
                case ZHILIAN -> "<div class='joblist-box__item' data-job-id='" + id + "'><a class='jobinfo__name' href='" + url + "'>Engineer " + id + "</a><div class='companyinfo__name'>Fixture company</div><button class='collect-and-apply__btn' onclick=\"send(['" + id + "'])\">立即投递</button></div>";
            });
        }
        if (p == RecruitmentPlatform.JOB51) body.append("<div class='tabs_in'><button class='p_but'>收藏</button><button class='p_but' onclick=\"send([...document.querySelectorAll('.ick[aria-checked=true]')].map(el=>el.parentElement.dataset.jobId))\">批量投递</button></div>");
        return "<!doctype html><html><body>" + body + "<script>window.clicks=0;window.withReceipt=true;window.sentIds=[];function send(ids){window.clicks++;window.sentIds=ids;if(window.withReceipt)document.querySelector('#receipt').textContent='投递成功';}</script></body></html>";
    }
}
