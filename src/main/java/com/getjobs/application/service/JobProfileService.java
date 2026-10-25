package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.JobProfileEntity;
import com.getjobs.application.mapper.JobProfileMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/** 岗位画像 CRUD 及活动画像解析。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JobProfileService {
    private final JobProfileMapper mapper;
    private final DataSource dataSource;

    @PostConstruct
    public void ensureTable() {
        // 项目没有统一迁移工具，启动时幂等建表以兼容已有数据库。
        String ddl = "CREATE TABLE IF NOT EXISTS job_profile (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, type TEXT, keywords TEXT, job_intent TEXT, city_code TEXT, industry TEXT, " +
                "job_type TEXT, experience TEXT, degree TEXT, salary TEXT, scale TEXT, stage TEXT, " +
                "say_hi TEXT, expected_salary_min INTEGER, expected_salary_max INTEGER, salary_unit TEXT, " +
                "enable_ai INTEGER, send_img_resume INTEGER, filter_dead_hr INTEGER, dead_status TEXT, " +
                "ai_introduce TEXT, ai_prompt TEXT, enable_ai_relevance INTEGER, min_ai_score INTEGER, " +
                "enabled INTEGER NOT NULL DEFAULT 0, " +
                "created_at TIMESTAMP, updated_at TIMESTAMP)";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            // IF NOT EXISTS 不能用于 ALTER COLUMN；兼容已创建的旧 job_profile 表。
            try { statement.execute("ALTER TABLE job_profile ADD COLUMN job_intent TEXT"); }
            catch (Exception ignored) { }
            try { statement.execute("ALTER TABLE job_profile ADD COLUMN enable_ai_relevance INTEGER"); }
            catch (Exception ignored) { }
            try { statement.execute("ALTER TABLE job_profile ADD COLUMN min_ai_score INTEGER"); }
            catch (Exception ignored) { }
            try { statement.execute("CREATE INDEX IF NOT EXISTS idx_job_profile_enabled ON job_profile(enabled, updated_at)"); }
            catch (Exception ignored) { }
        } catch (Exception e) {
            log.warn("初始化 job_profile 表失败，画像功能可能不可用: {}", e.getMessage());
        }
    }

    public List<JobProfileEntity> list() {
        return mapper.selectList(new QueryWrapper<JobProfileEntity>().orderByDesc("updated_at").orderByDesc("id"));
    }

    public JobProfileEntity get(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    public JobProfileEntity getActive() {
        QueryWrapper<JobProfileEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("enabled", 1).orderByDesc("updated_at").orderByDesc("id").last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    public JobProfileEntity save(JobProfileEntity profile) {
        if (profile == null) throw new IllegalArgumentException("画像不能为空");
        LocalDateTime now = LocalDateTime.now();
        if (profile.getEnabled() == null) profile.setEnabled(1);
        if (profile.getName() == null || profile.getName().isBlank()) profile.setName("未命名画像");
        if (profile.getJobIntent() != null) profile.setJobIntent(profile.getJobIntent().trim());
        profile.setCreatedAt(now);
        profile.setUpdatedAt(now);
        mapper.insert(profile);
        return profile;
    }

    public JobProfileEntity update(JobProfileEntity profile) {
        return update(profile, false);
    }

    /**
     * 更新画像。默认采用选择性合并；replace=true 用于前端完整表单提交，允许
     * 用 null 清空薪资上下限等字段。
     */
    public JobProfileEntity update(JobProfileEntity profile, boolean replace) {
        if (profile == null || profile.getId() == null) throw new IllegalArgumentException("画像ID不能为空");
        JobProfileEntity existing = mapper.selectById(profile.getId());
        if (existing == null) throw new IllegalArgumentException("画像不存在: " + profile.getId());
        if (replace) {
            if (profile.getName() != null && !profile.getName().isBlank()) existing.setName(profile.getName());
            existing.setType(profile.getType());
            existing.setKeywords(profile.getKeywords());
            existing.setJobIntent(profile.getJobIntent() == null ? "" : profile.getJobIntent().trim());
            existing.setCityCode(profile.getCityCode());
            existing.setIndustry(profile.getIndustry());
            existing.setJobType(profile.getJobType());
            existing.setExperience(profile.getExperience());
            existing.setDegree(profile.getDegree());
            existing.setSalary(profile.getSalary());
            existing.setScale(profile.getScale());
            existing.setStage(profile.getStage());
            existing.setSayHi(profile.getSayHi());
            existing.setExpectedSalaryMin(profile.getExpectedSalaryMin());
            existing.setExpectedSalaryMax(profile.getExpectedSalaryMax());
            existing.setSalaryUnit(profile.getSalaryUnit());
            existing.setEnableAi(profile.getEnableAi());
            existing.setSendImgResume(profile.getSendImgResume());
            existing.setFilterDeadHr(profile.getFilterDeadHr());
            existing.setDeadStatus(profile.getDeadStatus());
            existing.setAiIntroduce(profile.getAiIntroduce());
            existing.setAiPrompt(profile.getAiPrompt());
            existing.setEnableAiRelevance(profile.getEnableAiRelevance());
            existing.setMinAiScore(profile.getMinAiScore());
            if (profile.getEnabled() != null) existing.setEnabled(profile.getEnabled());
            existing.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(existing);
            return existing;
        }
        // 支持前端只提交变化字段；空字符串仍可用于主动清空文本配置。
        if (profile.getName() != null) existing.setName(profile.getName());
        if (profile.getType() != null) existing.setType(profile.getType());
        if (profile.getKeywords() != null) existing.setKeywords(profile.getKeywords());
        if (profile.getJobIntent() != null) existing.setJobIntent(profile.getJobIntent().trim());
        if (profile.getCityCode() != null) existing.setCityCode(profile.getCityCode());
        if (profile.getIndustry() != null) existing.setIndustry(profile.getIndustry());
        if (profile.getJobType() != null) existing.setJobType(profile.getJobType());
        if (profile.getExperience() != null) existing.setExperience(profile.getExperience());
        if (profile.getDegree() != null) existing.setDegree(profile.getDegree());
        if (profile.getSalary() != null) existing.setSalary(profile.getSalary());
        if (profile.getScale() != null) existing.setScale(profile.getScale());
        if (profile.getStage() != null) existing.setStage(profile.getStage());
        if (profile.getSayHi() != null) existing.setSayHi(profile.getSayHi());
        if (profile.getExpectedSalaryMin() != null) existing.setExpectedSalaryMin(profile.getExpectedSalaryMin());
        if (profile.getExpectedSalaryMax() != null) existing.setExpectedSalaryMax(profile.getExpectedSalaryMax());
        if (profile.getSalaryUnit() != null) existing.setSalaryUnit(profile.getSalaryUnit());
        if (profile.getEnableAi() != null) existing.setEnableAi(profile.getEnableAi());
        if (profile.getSendImgResume() != null) existing.setSendImgResume(profile.getSendImgResume());
        if (profile.getFilterDeadHr() != null) existing.setFilterDeadHr(profile.getFilterDeadHr());
        if (profile.getDeadStatus() != null) existing.setDeadStatus(profile.getDeadStatus());
        if (profile.getAiIntroduce() != null) existing.setAiIntroduce(profile.getAiIntroduce());
        if (profile.getAiPrompt() != null) existing.setAiPrompt(profile.getAiPrompt());
        if (profile.getEnableAiRelevance() != null) existing.setEnableAiRelevance(profile.getEnableAiRelevance());
        if (profile.getMinAiScore() != null) existing.setMinAiScore(profile.getMinAiScore());
        if (profile.getEnabled() != null) existing.setEnabled(profile.getEnabled());
        existing.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(existing);
        return existing;
    }

    public boolean delete(Long id) {
        return id != null && mapper.deleteById(id) > 0;
    }

    public JobProfileEntity activate(Long id) {
        JobProfileEntity profile = get(id);
        if (profile == null) return null;
        profile.setEnabled(1);
        profile.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(profile);
        return profile;
    }
}
