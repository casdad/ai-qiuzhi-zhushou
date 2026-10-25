package com.getjobs.application.controller;

import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.service.BossRecommendationService;
import com.getjobs.application.service.JobRelevanceService;
import com.getjobs.worker.service.BossJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Boss 语义岗位推荐与单岗投递接口。
 *
 * <p>关键词搜索仍由 Boss 页面负责抓取数据；此接口不再把用户输入当作
 * SQL/URL 的硬关键词，而是把自然语言意图交给 {@link JobRelevanceService}
 * 对已抓取岗位逐个评分，再统一按分数排序。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/boss/recommendations")
@RequiredArgsConstructor
public class BossRecommendationController {
    private final BossJobDataMapper mapper;
    private final BossRecommendationService recommendationService;
    private final BossJobService bossJobService;

    /** recommend 接口允许的最大返回条数，防止一次请求触发成千上万次 AI 评分打爆配额 */
    private static final int MAX_LIMIT = 200;

    /**
     * 根据用户的自然语言岗位意图评分并按 score DESC 返回。
     * 请求体示例：{"query":"深圳的计算机视觉算法实习，300-500元/天，不要销售"}。
     */
    @PostMapping
    public ResponseEntity<?> recommend(@RequestBody(required = false) Map<String, Object> request) {
        String query = text(request == null ? null : request.get("query"));
        if (query.isBlank()) {
            query = text(request == null ? null : request.get("prompt"));
        }
        if (query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "query 不能为空，请描述想投递的岗位和限制条件"));
        }
        try {
            // 钳制到 [1, MAX_LIMIT]，否则 limit=1000000 会把全部岗位逐个送去 AI 评分
            int limit = Math.max(1, Math.min(MAX_LIMIT, number(request == null ? null : request.get("limit"), 50)));
            Long profileId = numberLong(request == null ? null : request.get("profileId"));
            return ResponseEntity.ok(recommendationService.recommend(query, profileId, limit));
        } catch (JobRelevanceService.AiScoringUnavailableException e) {
            log.warn("Boss语义岗位推荐暂不可用：AI评分未成功");
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "AI评分暂不可用，未使用本地匹配，也未生成替代评分；请检查AI配置或稍后重试"));
        } catch (Exception e) {
            log.warn("Boss语义岗位推荐失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "读取岗位数据失败: " + safeMessage(e)));
        }
    }

    /** 单个岗位的一键投递入口。 */
    @PostMapping("/{id}/apply")
    public ResponseEntity<Map<String, Object>> apply(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> request) {
        BossJobDataEntity entity;
        try {
            entity = mapper.selectById(id);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "status", "storage_error",
                    "message", "读取岗位失败: " + safeMessage(e)));
        }
        if (entity == null) {
            return ResponseEntity.status(404).body(Map.of(
                    "success", false,
                    "status", "not_found",
                    "message", "岗位不存在或已被清理"));
        }
        if ("已投递".equals(entity.getDeliveryStatus())) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "status", "already_sent",
                    "message", "该岗位已经投递过，无需重复投递"));
        }
        if ("已过滤".equals(entity.getDeliveryStatus())) {
            return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "status", "filtered",
                    "message", "该岗位命中黑名单或平台筛选条件，不能绕过过滤直接投递"));
        }
        // 真正的并发去重靠 BossJobService 里的运行锁（CAS 抢占）：两次并发点击
        // 只会有一次真正执行，另一次直接返回 busy。
        Map<String, Object> result = bossJobService.applySingle(entity);
        // 按业务状态映射 HTTP 码，不要一律返回 200 —— 只看状态码的调用方
        // 会把"已有任务在跑"和"投递失败"都当成成功。
        Object status = result.get("status");
        if ("busy".equals(status)) {
            return ResponseEntity.status(409).body(result);
        }
        if ("not_logged_in".equals(status)) {
            return ResponseEntity.status(401).body(result);
        }
        if ("page_unavailable".equals(status) || "invalid_job".equals(status)) {
            return ResponseEntity.status(503).body(result);
        }
        if ("error".equals(status)) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Long numberLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
