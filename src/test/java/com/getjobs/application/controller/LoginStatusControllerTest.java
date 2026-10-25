package com.getjobs.application.controller;

import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.Job51JobService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.function.Consumer;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginStatusControllerTest {
    @Test
    void snapshotReadsOnlyCachedFlagsWithoutWaitingForTheBrowser() throws Exception {
        PlaywrightManager manager = mock(PlaywrightManager.class);
        when(manager.isLoggedIn("boss")).thenReturn(true);
        JobController controller = controller(manager);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/jobs/login-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bossLoggedIn").value(true))
                .andExpect(jsonPath("$.job51LoggedIn").value(false));
        for (String platform : new String[]{"boss", "liepin", "51job", "zhilian"}) {
            verify(manager).isLoggedIn(platform);
        }
        verifyNoMoreInteractions(manager);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void sseConnectionsShareOneListenerAndShutdownRemovesIt() {
        PlaywrightManager manager = mock(PlaywrightManager.class);
        JobController controller = controller(manager);
        controller.registerLoginListener();
        controller.streamLoginStatus();
        controller.streamLoginStatus();
        ArgumentCaptor<Consumer<PlaywrightManager.LoginStatusChange>> listener =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(manager, times(1)).addLoginStatusListener(listener.capture());
        controller.removeLoginListener();
        verify(manager).removeLoginStatusListener(listener.getValue());
    }

    private JobController controller(PlaywrightManager manager) {
        return new JobController(mock(Job51Service.class), mock(Job51JobService.class),
                manager, mock(CookieService.class));
    }
}
