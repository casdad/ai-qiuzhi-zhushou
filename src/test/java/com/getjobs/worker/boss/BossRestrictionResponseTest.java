package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.JobRelevancePersistenceService;
import com.getjobs.application.service.JobRelevanceService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossRestrictionResponseTest {
    private Boss boss;
    private BossOperationGate gate;
    private BossBrowseGuard browsing;
    private Page page;

    @BeforeEach
    void setUp() {
        gate = new BossOperationGate(() -> 0, () -> 0);
        browsing = mock(BossBrowseGuard.class);
        PlaywrightManager manager = mock(PlaywrightManager.class);
        when(manager.getBossSessionScope()).thenReturn("session");
        page = mock(Page.class);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/jobs");
        when(page.locator(anyString())).thenReturn(mock(Locator.class));
        boss = new Boss(mock(BossService.class), mock(AiService.class), mock(JobRelevanceService.class),
                mock(JobRelevancePersistenceService.class), mock(BossDeliveryService.class), gate, manager, browsing);
        boss.setPage(page);
        boss.setSessionScope("session");
    }

    @Test
    void document405StopsBeforeTheNextBrowseOperationAndHonorsRetryAfter() {
        Response response = response("https://www.zhipin.com/web/user/safe?token=secret", 405);
        when(response.headerValue("Retry-After")).thenReturn("600");
        inspect(response);
        assertTrue(gate.holdReason("session").contains("405"));
        assertThrows(BossOperationGate.DeliveryPausedException.class, () -> gate.resume("session"));
        assertThrows(BossOperationGate.DeliveryPausedException.class,
                () -> ReflectionTestUtils.invokeMethod(boss, "beforeBrowse", page, BossBrowseGuard.Action.SCROLL, null));
        verifyNoInteractions(browsing);
    }

    @Test
    void http200BusinessRestrictionWithoutLoginOrCaptchaWordsStillStops() {
        Response response = response("https://www.zhipin.com/wapi/zpgeek/job/detail.json", 200);
        when(response.headerValue("Content-Type")).thenReturn("application/json");
        when(response.headerValue("Retry-After")).thenReturn("120");
        when(response.text()).thenReturn("{\"code\":37,\"message\":\"访问受限，抱歉，您暂时无法访问此页面\"}");
        inspect(response);
        assertNotNull(gate.holdReason("session"));
        assertThrows(BossOperationGate.DeliveryPausedException.class, () -> gate.resume("session"));
        verify(browsing, never()).cacheDetail(anyString(), any());
    }

    @Test
    void thirdParty429IsNotMisreportedAsABossRestriction() {
        inspect(response("https://ai.example.test/v1/chat/completions", 429));
        assertNull(gate.holdReason("session"));
        verifyNoInteractions(browsing);
    }

    @Test
    void initialDetailResponsesAreCachedWithoutTriggeringAnotherRequest() {
        Response response = response("https://www.zhipin.com/wapi/zpgeek/job/detail.json", 200);
        when(response.headerValue("Content-Type")).thenReturn("application/json");
        when(response.text()).thenReturn("{\"code\":0,\"zpData\":{\"jobInfo\":{\"encryptId\":\"job-1\"}}}");
        inspect(response);
        verify(browsing).cacheDetail(eq("job-1"), any(JSONObject.class));
        verify(page, never()).navigate(anyString());
        assertNull(gate.holdReason("session"));
    }

    @Test
    void diagnosticsExcludeQueryTokensAndJobIdentifiers() {
        assertEquals("/job_detail/{id}", Boss.diagnosticPath("https://www.zhipin.com/job_detail/private-id.html?token=secret#private"));
        assertEquals("/wapi/zpgeek/job/detail.json", Boss.diagnosticPath("https://www.zhipin.com/wapi/zpgeek/job/detail.json?securityId=secret"));
    }

    private void inspect(Response response) {
        ReflectionTestUtils.invokeMethod(boss, "inspectResponse", response);
    }

    private Response response(String url, int status) {
        Response response = mock(Response.class);
        Request request = mock(Request.class);
        when(request.resourceType()).thenReturn("document");
        when(response.request()).thenReturn(request);
        when(response.url()).thenReturn(url);
        when(response.status()).thenReturn(status);
        return response;
    }
}
