package com.getjobs.worker.boss;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated blank Chrome context: all network requests are aborted. */
class BossMessageReceiptTest {
    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    private final Boss boss = new Boss(null, null, null, null, null, null, null, null);

    @BeforeAll
    static void startBrowser() {
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel("chrome").setHeadless(true));
    }

    @AfterAll
    static void stopBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void reset() {
        page = browser.newContext().newPage();
        page.context().route("**/*", route -> route.abort());
    }

    @org.junit.jupiter.api.AfterEach
    void closePage() { page.context().close(); }

    @Test
    void requiresANewStableOutgoingIdentityAndExplicitAcceptedStatus() {
        page.setContent("""
                <div class="message-item item-myself" data-message-id="old">
                  <span>hello</span><span class="message-status">sent</span>
                </div>
                """);
        Set<String> baseline = boss.snapshotMessageIds(page);
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", baseline));
        page.locator("body").evaluate("""
                el => el.insertAdjacentHTML('beforeend', '<div class="message-item item-myself" data-message-id="new"><span>hello</span><span class="message-status" data-status="sent"></span></div>')
                """);
        assertTrue(boss.hasNewConfirmedMessage(page, "hello", baseline));
    }

    @Test
    void recognizesDeliveredLabelWithoutTheAlreadyPrefix() {
        page.setContent("<main></main>");
        Set<String> baseline = boss.snapshotMessageIds(page);
        page.locator("main").evaluate("""
                el => el.innerHTML = '<div class="message-item item-myself" data-message-id="new"><span>hello</span><span class="message-status">送达</span></div>'
                """);
        assertTrue(boss.hasNewConfirmedMessage(page, "hello", baseline));
        assertEquals(1, boss.sendConfirmationEvidence(page, "hello", baseline).get("confirmed"));
    }

    @Test
    void recognizesObservedBossDomWithSidebarPreviewAndDataMid() {
        // Structure observed in an existing conversation; no account data or network calls.
        page.setContent("""
                <aside><span>hello</span></aside>
                <div class="chat-message"><ul class="im-list">
                  <li class="message-item item-myself" data-mid="fixture-id">
                    <div class="message-content"><div class="text">
                      <i class="message-status status-delivery"> 送达 </i>
                      <p><span class="text-content">hello</span></p>
                    </div></div>
                  </li>
                </ul></div>
                """);
        assertEquals(Map.of("textMatches", 2, "missingContainer", 1, "confirmed", 1),
                boss.sendConfirmationEvidence(page, "hello", Set.of()));
        assertEquals(Set.of("fixture-id"), boss.snapshotMessageIds(page));
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", boss.snapshotMessageIds(page)));
        page.locator(".message-status").evaluate("el => el.textContent='已读'");
        assertTrue(boss.hasNewConfirmedMessage(page, "hello", Set.of()));
        page.locator(".message-status").evaluate("el => { el.className='sending'; el.textContent='发送中'; }");
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", Set.of()));
    }

    @Test
    void deliveredLabelDoesNotConfirmOldOrIncomingMessages() {
        page.setContent("""
                <div class="message-item item-myself" data-message-id="old">
                  <span>hello</span><span class="message-status">送达</span>
                </div>
                """);
        Set<String> baseline = boss.snapshotMessageIds(page);
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", baseline));
        page.locator(".message-item").evaluate("el => { el.className='message-item'; el.setAttribute('data-message-id','incoming'); }");
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", baseline));
    }

    @Test
    void deliveredLabelDoesNotOverrideFailuresOrMissingIdentity() {
        page.setContent("""
                <div class="message-item item-myself" data-message-id="new">
                  <span>hello</span><span class="message-status">送达</span><i class="send-failed"></i>
                </div>
                """);
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", Set.of()));
        page.locator(".message-item").evaluate("el => { el.querySelector('.send-failed').remove(); el.removeAttribute('data-message-id'); }");
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", Set.of()));
        assertEquals(1, boss.sendConfirmationEvidence(page, "hello", Set.of()).get("missingIdentity"));
    }

    @Test
    void diagnosticsReportOnlyCountsNotChatTextOrIdentities() {
        page.setContent("""
                <div class="message-item item-myself" data-message-id="private-id">
                  <span>private-message</span><span class="message-status">other</span>
                </div>
                """);
        Map<String, Integer> evidence = boss.sendConfirmationEvidence(page, "private-message", Set.of());
        assertEquals(Map.of("textMatches", 1, "unrecognizedReceipt", 1), evidence);
        assertFalse(evidence.toString().contains("private"));
    }

    @Test
    void incomingTextAndClearedComposerAreNotDeliveryConfirmation() {
        page.setContent("""
                <div contenteditable="true"></div>
                <div class="message-item" data-message-id="incoming">
                  <span>hello</span><span class="message-status" data-status="read"></span>
                </div>
                """);
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", Set.of()));
    }

    @Test
    void oldPendingMessageBecomingSentDoesNotConfirmANewSend() {
        page.setContent("""
                <div class="message-item item-myself" data-message-id="old">
                  <span>hello</span><span class="sending">pending</span>
                </div>
                """);
        Set<String> baseline = boss.snapshotMessageIds(page);
        assertTrue(boss.hasAmbiguousOutgoingMessage(page, "hello"));
        page.locator(".sending").evaluate("el => { el.className='message-status'; el.setAttribute('data-status','sent'); }");
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", baseline));
    }

    @Test
    void missingIdentityOrReceiptCannotBeGuessedAsSuccess() {
        page.setContent("""
                <div class="message-item item-myself"><span>hello</span>
                  <span class="message-status" data-status="sent"></span></div>
                """);
        assertTrue(boss.hasAmbiguousOutgoingMessage(page, "hello"));
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", Set.of()));
        page.locator(".message-item").evaluate("el => { el.setAttribute('data-message-id','new'); el.querySelector('.message-status').remove(); }");
        assertFalse(boss.hasNewConfirmedMessage(page, "hello", Set.of()));
    }

    @Test
    void securityWordsInJobDescriptionsAreNotRestrictionNotices() {
        page.setContent("<article>负责账号异常与安全风险分析</article><div role='alert' style='display:none'>操作过于频繁</div>");
        assertFalse(boss.hasVisibleRestriction(page));
        page.locator("[role='alert']").evaluate("el => el.style.display='block'");
        assertTrue(boss.hasVisibleRestriction(page));
    }

    @Test
    void recognizesTheReportedRestrictionInAPlainHtmlDocument() {
        page.setContent("<h1>访问受限</h1><p>抱歉，您暂时无法访问此页面</p>");
        assertTrue(boss.hasVisibleRestriction(page));
    }

    @Test
    void recognizesRestrictionWithoutLoginWordsInAnErrorContainer() {
        page.setContent("<div class='error-content'>抱歉，您暂时无法访问此页面</div>");
        assertTrue(boss.hasVisibleRestriction(page));
    }

    @Test
    void hiddenRestrictionsAndQuotedJobTextDoNotBlockBrowsing() {
        page.setContent("<article>负责处理访问受限问题，排查暂时无法访问此页面的故障</article>"
                + "<div class='error-content' style='display:none'>访问受限，抱歉，您暂时无法访问此页面</div>");
        assertFalse(boss.hasVisibleRestriction(page));
    }

    @Test
    void recognizesTheReportedEmptySearchMessageWithoutJobCards() {
        page.setContent("""
                <div class="job-recommend-result"><div class="empty-result">
                  <img alt=""><p>没有找到相关职位，打开 APP，查看全部职位库，优质职位随心聊。</p>
                </div></div>
                """);
        page.waitForSelector(Boss.EMPTY_JOB_LIST_SELECTOR, new Page.WaitForSelectorOptions().setTimeout(1000));
        assertTrue(boss.hasNoJobResults(page));
    }

    @Test
    void hiddenEmptyMessageOrExistingJobCardsAreNotAnEmptySearch() {
        page.setContent("<p style='display:none'>没有找到相关职位，打开 APP</p>");
        assertFalse(boss.hasNoJobResults(page));
        page.setContent("<p>没有找到相关职位，打开 APP</p><ul><li class='job-card-box'>Job</li></ul>");
        assertFalse(boss.hasNoJobResults(page));
    }

    @Test
    void blankLoadingPageAndQuotedEmptyTextAreNotSuccessfulEmptyResults() {
        page.setContent("<main></main>");
        assertFalse(boss.hasNoJobResults(page));
        page.setContent("<article>文案示例：没有找到相关职位，打开 APP</article>");
        assertFalse(boss.hasNoJobResults(page));
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void realBrowserFillsChatThenWaitsBeforeClickingSendWithoutAnyExternalRequests() {
        var events = new ArrayList<org.json.JSONObject>();
        page.context().onPage(opened -> opened.onConsoleMessage(message -> {
            if (message.text().startsWith("fixture:")) {
                events.add(new org.json.JSONObject(message.text().substring("fixture:".length())));
            }
        }));
        page.context().unroute("**/*");
        page.context().route("**/*", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200).setContentType("text/html; charset=utf-8").setBody("""
                        <a class="btn-startchat" href="javascript:;">立即沟通</a>
                        <style>#chat-input { min-height: 40px; width: 300px; border: 1px solid; }</style>
                        <section id="conversation" hidden>
                          <div id="chat-input" class="chat-input" contenteditable="true"></div>
                          <button class="btn-send" type="send">发送</button>
                          <ul id="messages"></ul>
                        </section>
                        <script>
                          const record = type => console.log('fixture:' + JSON.stringify({type, at: performance.now()}));
                          const input = document.querySelector('#chat-input');
                          document.querySelector('.btn-startchat').onclick = () => {
                            document.querySelector('#conversation').hidden = false;
                            record('chat');
                          };
                          input.oninput = () => record('fill');
                          document.querySelector('.btn-send').onclick = () => {
                            record('send');
                            const item = document.createElement('li');
                            item.className = 'message-item item-myself';
                            item.dataset.mid = 'fixture-message';
                            const text = document.createElement('span');
                            text.textContent = input.textContent;
                            const receipt = document.createElement('i');
                            receipt.className = 'message-status';
                            receipt.textContent = '送达';
                            item.append(text, receipt);
                            document.querySelector('#messages').append(item);
                            input.textContent = '';
                          };
                        </script>
                        """)));
        var service = mock(com.getjobs.application.service.BossService.class);
        var manager = mock(com.getjobs.worker.manager.PlaywrightManager.class);
        when(manager.getBossSessionScope()).thenReturn("fixture-session");
        var flow = new Boss(service, null, null, null, null,
                new BossOperationGate(System::nanoTime, System::currentTimeMillis), manager, mock(BossBrowseGuard.class));
        BossConfig config = new BossConfig();
        config.setWaitTime("1");
        config.setWaitTimeMax("1");
        flow.setConfig(config);
        flow.setPage(page);
        flow.setSessionScope("fixture-session");
        var job = new com.getjobs.worker.utils.Job();
        job.setHref("https://www.zhipin.com/job_detail/fixture.html");

        assertEquals(Boss.SubmitResult.SENT, flow.submitConfirmed(job, "hello", false));

        var sequence = events.stream().map(event -> event.getString("type")).toList();
        assertTrue(sequence.indexOf("chat") < sequence.indexOf("fill"));
        assertTrue(sequence.indexOf("fill") < sequence.indexOf("send"));
        assertEquals(1, sequence.stream().filter("send"::equals).count());
        double filledAt = events.get(sequence.indexOf("fill")).getDouble("at");
        double sentAt = events.get(sequence.indexOf("send")).getDouble("at");
        assertTrue(sentAt - filledAt >= 1000, "The first message must wait in the prepared conversation too");
    }
}
