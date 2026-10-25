package com.getjobs.application.controller;

import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.service.BossRecommendationService;
import com.getjobs.application.service.JobRelevanceService;
import com.getjobs.worker.service.BossJobService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossRecommendationControllerTest {
    @Test
    void unavailableAiIsNotReportedAsADatabaseFailureOrALocalScore() {
        BossRecommendationService recommendations = mock(BossRecommendationService.class);
        BossJobService jobs = mock(BossJobService.class);
        BossRecommendationController controller = new BossRecommendationController(
                mock(BossJobDataMapper.class), recommendations, jobs);
        when(recommendations.recommend("Python", null, 50))
                .thenThrow(new JobRelevanceService.AiScoringUnavailableException("Provider diagnostic"));

        var response = controller.recommend(Map.of("query", "Python"));

        assertEquals(503, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(false, body.get("success"));
        assertTrue(body.get("message").toString().contains("AI"));
        assertFalse(body.get("message").toString().contains("Provider diagnostic"));
        assertFalse(body.containsKey("score"));
        verifyNoInteractions(jobs);
    }
}
