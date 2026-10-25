package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.entity.JobProfileEntity;
import com.getjobs.application.mapper.BossJobDataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BossRecommendationServiceTest {
    private AiService ai;
    private BossJobDataMapper mapper;
    private JobProfileService profiles;
    private JobRelevancePersistenceService persistence;
    private BossRecommendationService service;

    @BeforeEach
    void setUp() {
        ai = mock(AiService.class);
        mapper = mock(BossJobDataMapper.class);
        profiles = mock(JobProfileService.class);
        persistence = mock(JobRelevancePersistenceService.class);
        service = new BossRecommendationService(mapper, new JobRelevanceService(ai), persistence, profiles);
    }

    @Test
    void recommendationsRankAndPersistTheSameRequirementsWeightedScore() {
        var lowRequirements = job(1L, "Exact preferred title");
        var highRequirements = job(2L, "Multimodal training");
        when(mapper.selectList(any())).thenReturn(List.of(lowRequirements, highRequirements));
        when(ai.sendRequest(anyString())).thenReturn(
                "{\"score\":100,\"requirementsScore\":25,\"responsibilitiesScore\":100,\"preferencesScore\":100}",
                "{\"score\":35,\"requirementsScore\":95,\"responsibilitiesScore\":40,\"preferencesScore\":50}");

        var results = service.recommend("Computer vision", null, 50);

        assertEquals(List.of(2L, 1L), results.stream().map(row -> row.get("id")).toList());
        assertEquals(85, results.getFirst().get("score"));
        assertEquals(85, results.getFirst().get("aiScore"));
        assertEquals(40, results.getLast().get("score"));
        ArgumentCaptor<JobRelevanceService.Result> scores = ArgumentCaptor.forClass(JobRelevanceService.Result.class);
        verify(persistence, times(2)).save(any(), scores.capture());
        assertEquals(List.of(40, 85), scores.getAllValues().stream().map(JobRelevanceService.Result::score).toList());
    }

    @Test
    void emptyActiveProfileUsesGlobalExperienceRatherThanSearchKeywordsAsSkills() {
        JobProfileEntity profile = new JobProfileEntity();
        profile.setKeywords("VLM RLHF");
        when(profiles.getActive()).thenReturn(profile);
        AiEntity config = new AiEntity();
        config.setIntroduce("Actual experience: Python, PyTorch, object detection training");
        config.setPrompt("GREETING_TEMPLATE_ONLY");
        when(ai.getAiConfig()).thenReturn(config);
        when(mapper.selectList(any())).thenReturn(List.of(job(1L, "Multimodal training")));
        when(ai.sendRequest(anyString())).thenReturn(
                "{\"requirementsScore\":90,\"responsibilitiesScore\":60,\"preferencesScore\":50}");

        service.recommend("Computer vision internship", null, 50);

        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(ai).sendRequest(request.capture());
        assertTrue(request.getValue().contains(config.getIntroduce()));
        assertTrue(request.getValue().contains("<INTENT>\nComputer vision internship\n</INTENT>"));
        assertTrue(request.getValue().contains("<PROFILE>\n" + config.getIntroduce() + "\n</PROFILE>"));
        assertTrue(request.getValue().contains("搜索方向（仅为偏好，不代表已掌握技能）：\nVLM RLHF"));
        assertFalse(request.getValue().contains("GREETING_TEMPLATE_ONLY"));
        verify(ai, times(1)).getAiConfig();
    }

    @Test
    void selectedProfileExperienceOverridesGlobalExperience() {
        JobProfileEntity profile = new JobProfileEntity();
        profile.setAiIntroduce("Selected profile experience");
        profile.setJobIntent("Previous intent");
        when(profiles.get(3L)).thenReturn(profile);
        when(mapper.selectList(any())).thenReturn(List.of(job(1L, "Multimodal training")));
        when(ai.sendRequest(anyString())).thenReturn(
                "{\"requirementsScore\":90,\"responsibilitiesScore\":60,\"preferencesScore\":50}");

        service.recommend("Computer vision internship", 3L, 50);

        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(ai).sendRequest(request.capture());
        assertTrue(request.getValue().contains("Selected profile experience"));
        assertFalse(request.getValue().contains("Previous intent"));
        assertTrue(request.getValue().contains("<INTENT>\nComputer vision internship\n</INTENT>"));
        verify(ai, never()).getAiConfig();
        verify(profiles, never()).getActive();
    }

    @Test
    void aiFailureDoesNotPersistOrReturnLocalRecommendationScores() {
        when(mapper.selectList(any())).thenReturn(List.of(job(1L, "Python engineer")));
        when(ai.sendRequest(anyString())).thenThrow(new IllegalStateException("offline"));

        assertThrows(JobRelevanceService.AiScoringUnavailableException.class,
                () -> service.recommend("Python", null, 50));

        verifyNoInteractions(persistence);
        verify(ai, times(1)).sendRequest(anyString());
    }

    private BossJobDataEntity job(Long id, String name) {
        BossJobDataEntity job = new BossJobDataEntity();
        job.setId(id);
        job.setJobName(name);
        job.setJobDescription("Requirements: Python, PyTorch, computer vision training");
        return job;
    }
}
