package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.worker.utils.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobRelevanceServiceTest {
    private AiService ai;
    private JobRelevanceService service;

    @BeforeEach
    void setUp() {
        ai = mock(AiService.class);
        service = new JobRelevanceService(ai);
    }

    @Test
    void qualifyingMultimodalCandidateIsNotDraggedDownByLegacyTotalOrTitle() {
        Job job = job();
        job.setJobName("多模态大模型训练工程师");
        job.setJobInfo("岗位职责：参与 VLM、RLHF 训练。任职要求：Python、PyTorch，计算机视觉或目标检测训练经验。"
                + "加分项：LoRA 微调经验优先。");
        when(ai.sendRequest(anyString())).thenReturn("""
                {"score":35,"requirementsScore":95,"responsibilitiesScore":40,"preferencesScore":50,
                 "matchedSkills":["Python","PyTorch","目标检测训练"],"missingSkills":[],
                 "reason":"任职要求符合，相关视觉训练经验可迁移。"}
                """);

        var result = service.evaluate(job, "计算机视觉硕士，熟悉 Python、PyTorch，有目标检测训练项目。", "", 75);

        assertEquals(85, result.score());
        assertEquals("apply", result.decision());
        assertTrue(result.aiUsed());
        assertFalse(result.fallback());
        assertTrue(result.missingSkills().isEmpty());
        assertTrue(result.reason().contains("任职要求95.0×80%"));
        assertTrue(result.reason().contains("职责40.0×15%"));
        assertTrue(result.reason().contains("偏好50.0×5%"));
        assertTrue(result.reason().contains("任职要求符合"));
    }

    @ParameterizedTest
    @CsvSource({
            "100, 0, 0, 80",
            "0, 100, 0, 15",
            "0, 0, 100, 5",
            "0, 0, 0, 0",
            "100, 100, 100, 100",
            "95, 40, 50, 85",
            "25, 100, 100, 40",
            "87.4, 63.6, 49.5, 82"
    })
    void computesWeightsLocallyAndRoundsOnlyTheTotal(double requirements, double responsibilities,
                                                     double preferences, int expected) {
        when(ai.sendRequest(anyString())).thenReturn(dimensions(requirements, responsibilities, preferences));
        assertEquals(expected, service.evaluate(job(), "Python", "", 75).score());
    }

    @Test
    void strongPreferencesCannotCompensateForMissingMandatoryRequirements() {
        when(ai.sendRequest(anyString())).thenReturn("""
                {"score":100,"requirementsScore":25,"responsibilitiesScore":100,"preferencesScore":100,
                 "missingSkills":["三年以上多模态训练经验"],"reason":"明确的必备项目经验不符。"}
                """);
        var result = service.evaluate(job(), "应届毕业生", "", 75);
        assertEquals(40, result.score());
        assertEquals("skip", result.decision());
        assertEquals(List.of("三年以上多模态训练经验"), result.missingSkills());
    }

    @ParameterizedTest
    @CsvSource({"70, apply", "69, review", "50, review", "49, skip"})
    void preservesDefaultDecisionThresholds(int score, String decision) {
        when(ai.sendRequest(anyString())).thenReturn(dimensions(score, score, score));
        assertEquals(decision, service.evaluate(job(), "Python", "", null).decision());
    }

    @Test
    void requestSeparatesMandatoryRequirementsFromResponsibilitiesAndBonusItems() {
        when(ai.sendRequest(anyString())).thenReturn(dimensions(90, 60, 50));
        service.evaluate(job(), "Python PyTorch", "优先深圳岗位", 75);
        String request = request();
        assertTrue(request.contains("requirementsScore：任职要求匹配度，权重 80%"));
        assertTrue(request.contains("responsibilitiesScore：工作职责与已有经历、可迁移能力的匹配度，权重 15%"));
        assertTrue(request.contains("preferencesScore：岗位方向、地点、薪资等求职偏好匹配度，权重 5%"));
        assertTrue(request.contains("缺少优先项不扣任职要求分"));
        assertTrue(request.contains("满足其一即可"));
        assertTrue(request.contains("不等于确定缺失，也不能虚构为已满足"));
        assertTrue(request.contains("不能用无关经历替代"));
        assertTrue(request.contains("不能根据职位名称或公司介绍自行添加门槛"));
        assertTrue(request.contains("missingSkills 只列明确不满足的必备条件"));
        assertTrue(request.contains("<CONTEXT>\n优先深圳岗位\n</CONTEXT>"));
    }

    @Test
    void keepsRequirementsAndProjectEvidenceBeyondTheOldTruncationLimits() {
        Job job = job();
        job.setJobInfo("公司介绍".repeat(800) + "\n任职要求：Python、PyTorch、视觉训练项目");
        when(ai.sendRequest(anyString())).thenReturn(dimensions(90, 60, 50));
        service.evaluate(job, "背景".repeat(450) + "\n项目证据：完成目标检测训练和消融实验", "", 75);
        String request = request();
        assertTrue(request.contains("任职要求：Python、PyTorch、视觉训练项目"));
        assertTrue(request.contains("项目证据：完成目标检测训练和消融实验"));
    }

    @Test
    void globalGreetingTemplateDoesNotOverrideScoringRules() {
        AiEntity config = new AiEntity();
        config.setIntroduce("Global candidate experience");
        config.setPrompt("GREETING_TEMPLATE_ONLY %s %s %s %s %s");
        when(ai.getAiConfig()).thenReturn(config);
        when(ai.sendRequest(anyString())).thenReturn(dimensions(90, 60, 50));
        service.evaluate(job(), 75);
        String request = request();
        assertTrue(request.contains("Global candidate experience"));
        assertFalse(request.contains("GREETING_TEMPLATE_ONLY"));
        verify(ai, times(1)).getAiConfig();
    }

    @Test
    void blankProfileFallsBackToGlobalExperienceEvenWithACustomPrompt() {
        AiEntity config = new AiEntity();
        config.setIntroduce("Global candidate experience");
        when(ai.getAiConfig()).thenReturn(config);
        when(ai.sendRequest(anyString())).thenReturn(dimensions(90, 60, 50));
        service.evaluate(job(), "  ", "Prefer Shenzhen", 75);
        assertTrue(request().contains("Global candidate experience"));
    }

    @Test
    void explicitProfileTakesPrecedenceOverGlobalConfiguration() {
        when(ai.sendRequest(anyString())).thenReturn(dimensions(90, 60, 50));
        service.evaluate(job(), "Selected profile", "", 75);
        assertTrue(request().contains("<PROFILE>\nSelected profile\n</PROFILE>"));
        verify(ai, never()).getAiConfig();
    }

    @Test
    void acceptsFencedJsonAndNumericStringsWithoutTrustingModelDecision() {
        when(ai.sendRequest(anyString())).thenReturn("""
                ```json
                {"requirementsScore":"90","responsibilitiesScore":"60","preferencesScore":"50",
                 "decision":"skip","matchedSkills":["Python","Python","PyTorch"],"missingSkills":[]}
                ```
                """);
        var result = service.evaluate(job(), "Python PyTorch", "", 75);
        assertEquals(84, result.score());
        assertEquals("apply", result.decision());
        assertEquals(List.of("Python", "PyTorch"), result.matchedSkills());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "[]", "{}", "\"NaN\"", "\"Infinity\"", "\"bad\"", "-1", "101", "1e309"})
    void invalidValueInAnyDimensionCannotProduceALocalScore(String invalid) {
        for (String field : List.of("requirementsScore", "responsibilitiesScore", "preferencesScore")) {
            String response = "{\"score\":100,\"requirementsScore\":100,\"responsibilitiesScore\":100,\"preferencesScore\":100}"
                    .replace("\"" + field + "\":100", "\"" + field + "\":" + invalid);
            when(ai.sendRequest(anyString())).thenReturn(response);
            assertThrows(JobRelevanceService.AiScoringUnavailableException.class,
                    () -> service.evaluate(job(), "Python", "", 0));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"score\":100}",
            "{\"requirementsScore\":100,\"responsibilitiesScore\":100}",
            "{\"requirementsScore\":100,\"preferencesScore\":100}",
            "{\"responsibilitiesScore\":100,\"preferencesScore\":100}",
            "not JSON"
    })
    void missingDimensionsNeverReuseAnUnweightedLegacyScore(String response) {
        when(ai.sendRequest(anyString())).thenReturn(response);
        assertThrows(JobRelevanceService.AiScoringUnavailableException.class,
                () -> service.evaluate(job(), "Python", "", 0));
    }

    @Test
    void rejectsConcatenatedJsonObjects() {
        when(ai.sendRequest(anyString())).thenReturn(dimensions(100, 100, 100) + dimensions(0, 0, 0));
        assertThrows(JobRelevanceService.AiScoringUnavailableException.class,
                () -> service.evaluate(job(), "Python", "", 0));
    }

    @Test
    void aiFailureNeverReturnsALocalScoreEvenWhenKeywordsFullyMatch() {
        when(ai.sendRequest(anyString())).thenThrow(new IllegalStateException("offline"));
        assertThrows(JobRelevanceService.AiScoringUnavailableException.class,
                () -> service.evaluate(job(), "Python", "", 0));
        verify(ai, times(1)).sendRequest(anyString());
    }

    @Test
    void intentAndExistingTechnicalStackReachTheModelAsSeparateInputs() {
        AiEntity config = new AiEntity();
        config.setIntroduce("Existing stack: Python, PyTorch; object detection project");
        when(ai.getAiConfig()).thenReturn(config);
        when(ai.sendRequest(anyString())).thenReturn(dimensions(90, 60, 100));

        var result = service.evaluate(job(), null, null, "计算机视觉方向，偏深度学习和目标检测", 75);

        String request = request();
        assertTrue(request.contains("<PROFILE>\n" + config.getIntroduce() + "\n</PROFILE>"));
        assertTrue(request.contains("<INTENT>\n计算机视觉方向，偏深度学习和目标检测\n</INTENT>"));
        assertTrue(request.contains("结合 INTENT 中用户的意向岗位描述与 PROFILE 中已填写的技术栈"));
        assertTrue(request.contains("意向描述优先于搜索关键词"));
        assertEquals(86, result.score());
    }

    @Test
    void changedIntentAffectsPreferencesWithoutOverwritingSkillsOrWeights() {
        when(ai.sendRequest(anyString())).thenAnswer(call -> {
            String request = call.getArgument(0);
            return dimensions(90, 60, request.contains("<INTENT>\nComputer vision\n</INTENT>") ? 100 : 0);
        });
        assertEquals(86, service.evaluate(job(), "Python PyTorch", "", "Computer vision", 75).score());
        assertEquals(81, service.evaluate(job(), "Python PyTorch", "", "Sales only", 75).score());
    }

    @Test
    void aiRecoveryReturnsOnlyTheNewValidScore() {
        when(ai.sendRequest(anyString())).thenThrow(new IllegalStateException("offline"))
                .thenReturn(dimensions(90, 60, 50));
        assertThrows(JobRelevanceService.AiScoringUnavailableException.class,
                () -> service.evaluate(job(), "Rust", "", "Python", 0));
        var result = service.evaluate(job(), "Rust", "", "Python", 0);
        assertEquals(84, result.score());
        assertTrue(result.aiUsed());
        assertFalse(result.fallback());
        assertTrue(result.error().isEmpty());
        verify(ai, times(2)).sendRequest(anyString());
    }

    @Test
    void blankIntentKeepsTheExistingScoringInputs() {
        when(ai.sendRequest(anyString())).thenReturn(dimensions(90, 60, 50));
        var result = service.evaluate(job(), "Python", "", "  ", 75);
        assertEquals(84, result.score());
        assertTrue(request().contains("<INTENT>\n\n</INTENT>"));
    }

    @Test
    void nullJobDoesNotCallAi() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(null, "Python", "", 0));
        verifyNoInteractions(ai);
    }

    @Test
    void cancellationIsNotConvertedIntoARetryableScoringFailure() {
        when(ai.sendRequest(anyString())).thenThrow(new CancellationException("User stopped"));
        assertThrows(CancellationException.class, () -> service.evaluate(job(), "Python", "", 0));
        verify(ai, times(1)).sendRequest(anyString());
    }

    @Test
    void interruptedThreadDoesNotStartAnotherAiRequest() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> service.evaluate(job(), "Python", "", 0));
            verifyNoInteractions(ai);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void requestErrorsDoNotExposeRawProviderDiagnostics() {
        when(ai.sendRequest(anyString())).thenThrow(new IllegalStateException("Authorization: secret-fixture"));
        var error = assertThrows(JobRelevanceService.AiScoringUnavailableException.class,
                () -> service.evaluate(job(), "Python", "", 0));
        assertFalse(error.getMessage().contains("secret-fixture"));
        assertNull(error.getCause());
    }

    private String request() {
        ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
        verify(ai).sendRequest(request.capture());
        return request.getValue();
    }

    private String dimensions(double requirements, double responsibilities, double preferences) {
        return "{\"requirementsScore\":" + requirements + ",\"responsibilitiesScore\":" + responsibilities
                + ",\"preferencesScore\":" + preferences + "}";
    }

    private Job job() {
        Job job = new Job();
        job.setJobName("Python engineer");
        job.setJobInfo("Requirements: Python");
        return job;
    }
}
