package com.getjobs.application.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.platform.PlatformCandidate;
import com.getjobs.worker.platform.PlatformDeliveryEngine;
import com.getjobs.worker.platform.PlatformDeliveryStore;
import com.getjobs.worker.platform.RecruitmentPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlatformDeliveryControllerTest {
    @TempDir Path directory;
    PlatformDeliveryStore store;
    PlatformDeliveryEngine engine;
    MockMvc mvc;

    @BeforeEach void setup() {
        store = new PlatformDeliveryStore(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("api.db"))), new ObjectMapper());
        store.initialize();
        engine = mock(PlatformDeliveryEngine.class);
        mvc = MockMvcBuilders.standaloneSetup(new PlatformDeliveryController(engine, store)).build();
    }

    @ParameterizedTest @EnumSource(RecruitmentPlatform.class)
    void modesAndQueueArePlatformScopedAndDoNotSend(RecruitmentPlatform p) throws Exception {
        String base = "/api/" + p.id;
        mvc.perform(get(base + "/delivery-settings")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("CONFIRM"));
        mvc.perform(put(base + "/delivery-settings").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("AUTO"));
        mvc.perform(get(base + "/deliveries")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verifyNoInteractions(engine);
    }

    @ParameterizedTest @EnumSource(RecruitmentPlatform.class)
    void confirmOpenAndRecoveryUseTheSelectedPlatformAndExposeConflicts(RecruitmentPlatform p) throws Exception {
        String base = "/api/" + p.id;
        when(engine.confirm(p, "draft-id")).thenReturn(PlatformDeliveryEngine.result(false, "UNKNOWN", "Check official page"));
        mvc.perform(post(base + "/deliveries/draft-id/confirm")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
        when(engine.openLogin(p)).thenReturn(PlatformDeliveryEngine.result(true, "opening", "Opening"));
        mvc.perform(post(base + "/session/open")).andExpect(status().isOk());
        when(engine.resume(p, false)).thenReturn(PlatformDeliveryEngine.result(false, "review_required", "Review required"));
        mvc.perform(post(base + "/resume").contentType(MediaType.APPLICATION_JSON).content("{\"acknowledged\":false}"))
                .andExpect(status().isConflict());
        verify(engine).confirm(p, "draft-id");
        verify(engine).openLogin(p);
        verify(engine).resume(p, false);
        verifyNoMoreInteractions(engine);
    }

    @Test void invalidModeAndUnrelatedRoutesCannotTriggerDelivery() throws Exception {
        mvc.perform(put("/api/liepin/delivery-settings").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/boss/deliveries")).andExpect(status().isNotFound());
        mvc.perform(post("/api/unknown/session/open")).andExpect(status().isNotFound());
        verifyNoInteractions(engine);
    }

    @Test void dismissCannotTouchAnotherPlatformOrAnInflightDraft() throws Exception {
        var p = RecruitmentPlatform.LIEPIN;
        var job = new PlatformCandidate("12345", "https://www.liepin.com/job/12345.shtml", "Engineer", "Fixture", "10K", "City",
                new PlatformCandidate.Search("https://www.liepin.com/zhaopin/", "Engineer"));
        var draft = store.prepare(p, "scope", job).orElseThrow();
        mvc.perform(post("/api/51job/deliveries/" + draft.id() + "/dismiss")).andExpect(status().isConflict());
        store.claim(p, "scope", draft.id());
        mvc.perform(post("/api/liepin/deliveries/" + draft.id() + "/dismiss")).andExpect(status().isConflict());
        verifyNoInteractions(engine);
    }
}
