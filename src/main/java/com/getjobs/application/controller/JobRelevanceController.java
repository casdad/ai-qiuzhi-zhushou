package com.getjobs.application.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.mapper.BossJobDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/** 为分析页面提供最近的 AI 岗位匹配结果。 */
@Slf4j
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/boss/relevance")
@RequiredArgsConstructor
public class JobRelevanceController {
    private final BossJobDataMapper mapper;

    @GetMapping("/scores")
    public List<BossJobDataEntity> scores(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) String decision
    ) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        try {
            QueryWrapper<BossJobDataEntity> query = new QueryWrapper<>();
            // 排名接口必须按相关性分数排序；按 id 排序会把最新抓取结果误当成最匹配岗位。
            // id 作为次级排序保证同分结果稳定且优先展示最近抓取的岗位。
            query.isNotNull("ai_score");
            if (minScore != null) {
                query.ge("ai_score", Math.max(0, Math.min(100, minScore)));
            }
            if (decision != null && !decision.isBlank()) {
                query.eq("ai_decision", decision.trim());
            }
            query.orderByDesc("ai_score").orderByDesc("id").last("LIMIT " + safeLimit);
            return mapper.selectList(query);
        } catch (Exception e) {
            // 兼容尚未执行增量迁移的旧数据库；评分服务下一次写入时会自动迁移。
            log.debug("读取岗位相关性评分失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
