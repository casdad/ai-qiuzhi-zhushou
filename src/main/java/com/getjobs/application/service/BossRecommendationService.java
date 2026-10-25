package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.entity.JobProfileEntity;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.worker.utils.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boss 语义岗位推荐。
 *
 * <p>Boss 的浏览器抓取负责收集候选岗位；本服务不再把用户输入当作
 * ``query=`` 关键词硬过滤，而是把自然语言意图和完整岗位描述交给
 * {@link JobRelevanceService} 的 API 模型评分，最后统一按分数排序。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BossRecommendationService {
    private static final int DEFAULT_CANDIDATE_LIMIT = 50;
    // 每个候选岗位都会触发一次模型评分；限制候选量避免一次请求串行
    // 发出数百次调用，造成页面长时间等待和额度浪费。
    private static final int MAX_CANDIDATE_LIMIT = 80;

    private final BossJobDataMapper mapper;
    private final JobRelevanceService relevanceService;
    private final JobRelevancePersistenceService persistenceService;
    private final JobProfileService profileService;

    public List<Map<String, Object>> recommend(String query, Long profileId, Integer limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        int safeLimit = limit == null ? DEFAULT_CANDIDATE_LIMIT
                : Math.max(1, Math.min(MAX_CANDIDATE_LIMIT, limit));

        QueryWrapper<BossJobDataEntity> wrapper = new QueryWrapper<>();
        // 推荐与单岗投递不能绕过既有黑名单、HR/薪资等硬过滤；已投递岗位
        // 也不应再次出现在结果中。保留投递失败记录，允许用户主动重试。
        wrapper.and(w -> w.isNull("delivery_status")
                .or().eq("delivery_status", "未投递")
                .or().eq("delivery_status", "投递失败"));
        wrapper.orderByDesc("id").last("LIMIT " + safeLimit);
        List<BossJobDataEntity> rows = mapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) return List.of();

        JobProfileEntity profile = profileId == null ? profileService.getActive() : profileService.get(profileId);
        String candidateProfile = relevanceService.candidateProfileOrDefault(profile == null ? null : profile.getAiIntroduce());
        String prompt = "将用户的自然语言求职意图理解为整体偏好，而不是关键词集合。"
                + "主要按候选人经历是否符合任职要求评分，职责和求职偏好只作次要参考。"
                + "不要因为职位名称没有出现用户原词就直接判为不匹配，不把加分项当必备条件。"
                + "按统一规则返回各维度分数，并简要说明符合的任职要求与待确认项。";
        if (profile != null && profile.getAiPrompt() != null && !profile.getAiPrompt().isBlank()) {
            prompt += "\n候选人画像的补充评分要求：" + profile.getAiPrompt().trim();
        }
        if (profile != null && profile.getKeywords() != null && !profile.getKeywords().isBlank()) {
            prompt += "\n搜索方向（仅为偏好，不代表已掌握技能）：\n" + profile.getKeywords();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (BossJobDataEntity row : rows) {
            Job job = toJob(row);
            JobRelevanceService.Result score = relevanceService.evaluate(job, candidateProfile, prompt, query.trim(), 0);
            persistenceService.save(job, score);
            Map<String, Object> item = toResponse(row, score);
            results.add(item);
        }
        results.sort(Comparator.comparingInt((Map<String, Object> item) ->
                ((Number) item.getOrDefault("score", 0)).intValue()).reversed()
                .thenComparing(item -> String.valueOf(item.getOrDefault("jobName", ""))));
        return results;
    }

    private Job toJob(BossJobDataEntity row) {
        Job job = new Job();
        job.setHref(row.getJobUrl());
        job.setJobName(row.getJobName());
        // Job 的统一结构将地点/经验/学历放在 jobArea/标签字段；把结构化字段
        // 合并后交给模型，否则模型只能看到城市，无法判断用户的经验和学历限制。
        job.setJobArea(String.join(", ", java.util.stream.Stream.of(
                        row.getLocation(), row.getExperience(), row.getDegree())
                .filter(value -> value != null && !value.isBlank())
                .toList()));
        job.setJobInfo(row.getJobDescription());
        job.setSalary(row.getSalary());
        job.setRecruiter(row.getHrName());
        job.setCompanyName(row.getCompanyName());
        job.setCompanyInfo(row.getIntroduce());
        job.setCompanyTag(row.getIndustry());
        return job;
    }

    private Map<String, Object> toResponse(BossJobDataEntity row, JobRelevanceService.Result score) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getId());
        item.put("companyName", row.getCompanyName());
        item.put("jobName", row.getJobName());
        item.put("salary", row.getSalary());
        item.put("location", row.getLocation());
        item.put("experience", row.getExperience());
        item.put("degree", row.getDegree());
        item.put("jobDescription", row.getJobDescription());
        String jobUrl = row.getJobUrl();
        if ((jobUrl == null || jobUrl.isBlank()) && row.getEncryptId() != null && !row.getEncryptId().isBlank()) {
            jobUrl = "https://www.zhipin.com/job_detail/" + row.getEncryptId() + ".html";
        }
        item.put("jobUrl", jobUrl);
        item.put("deliveryStatus", row.getDeliveryStatus());
        item.put("applied", "已投递".equals(row.getDeliveryStatus()));
        item.put("score", score.score());
        item.put("aiScore", score.score());
        item.put("decision", score.decision());
        item.put("reason", score.reason());
        item.put("relevanceReason", score.reason());
        item.put("matchedSkills", score.matchedSkills());
        item.put("missingSkills", score.missingSkills());
        item.put("aiUsed", score.aiUsed());
        item.put("fallback", score.fallback());
        return item;
    }
}
