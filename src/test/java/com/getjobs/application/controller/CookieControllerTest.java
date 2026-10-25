package com.getjobs.application.controller;

import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CookieControllerTest {
    @Test
    void oldSaveEndpointCannotPersistASession() {
        CookieService service = mock(CookieService.class);
        PlaywrightManager manager = mock(PlaywrightManager.class);
        var response = new CookieController(service, manager).saveCookie("boss", "manual");
        assertEquals(410, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        verifyNoInteractions(service, manager);
    }

    @Test
    void metadataNeverReturnsCookieValues() {
        CookieService service = mock(CookieService.class);
        CookieEntity cookie = new CookieEntity();
        cookie.setPlatform("boss");
        cookie.setCookieValue("test-secret-must-not-leave-server");
        when(service.getCookieByPlatform("boss")).thenReturn(cookie);
        CookieController controller = new CookieController(service, mock(PlaywrightManager.class));
        Map<?, ?> data = (Map<?, ?>) controller.getCookie("boss").getBody().get("data");
        assertEquals(true, data.get("hasCookie"));
        assertFalse(data.containsKey("cookie_value"));
        assertFalse(data.containsValue(cookie.getCookieValue()));
    }
}
