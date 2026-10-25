package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.worker.utils.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * 将岗位相关性评分回写到 boss_data。评分字段是增量迁移，旧数据库无需手工建表。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JobRelevancePersistenceService {
    private final BossJobDataMapper mapper;
    private final DataSource dataSource;
    private volatile boolean columnsReady;

    @PostConstruct
    public void migrateIfTableExists() {
        ensureColumns();
    }

    /** 按岗位名和公司取最近一次入库记录，避免给同名旧岗位回写评分。 */
    public void save(Job job, JobRelevanceService.Result result) {
        if (job == null || result == null) return;
        ensureColumns();
        try {
            QueryWrapper<BossJobDataEntity> query = new QueryWrapper<>();
            query.eq("job_name", safe(job.getJobName()))
                    .eq("company_name", safe(job.getCompanyName()))
                    .orderByDesc("id")
                    .last("LIMIT 1");
            BossJobDataEntity current = mapper.selectOne(query);
            if (current == null) {
                log.debug("未找到岗位入库记录，跳过AI评分回写 | company={} | job={}",
                        job.getCompanyName(), job.getJobName());
                return;
            }
            BossJobDataEntity update = new BossJobDataEntity();
            update.setId(current.getId());
            update.setAiScore(result.score());
            update.setAiDecision(result.decision());
            update.setMatchedSkills(join(result.matchedSkills()));
            update.setMissingSkills(join(result.missingSkills()));
            update.setRelevanceReason(result.reason());
            mapper.updateById(update);
        } catch (Exception e) {
            // 评分回写失败不能中断投递；日志足够支持后续迁移排查。
            log.warn("岗位相关性评分回写失败 | company={} | job={} | error={}",
                    job.getCompanyName(), job.getJobName(), e.getMessage());
        }
    }

    /** 可由 Worker 在其遗留的 boss_data 列顺序迁移之后再次调用。 */
    public synchronized void ensureColumns() {
        if (columnsReady) return;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            List<String> columns = new java.util.ArrayList<>();
            try (ResultSet rs = statement.executeQuery("PRAGMA table_info('boss_data')")) {
                while (rs.next()) columns.add(rs.getString("name"));
            }
            // boss_data 在首次启动前可能尚未初始化，下一次 save 时继续重试。
            if (columns.isEmpty()) return;
            addIfMissing(statement, columns, "ai_score", "INTEGER");
            addIfMissing(statement, columns, "ai_decision", "TEXT");
            addIfMissing(statement, columns, "matched_skills", "TEXT");
            addIfMissing(statement, columns, "missing_skills", "TEXT");
            addIfMissing(statement, columns, "relevance_reason", "TEXT");
            columnsReady = true;
        } catch (Exception e) {
            log.warn("初始化岗位相关性字段失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 表被其他遗留迁移逻辑重建后调用，强制重新检查评分列。 */
    public synchronized void refreshColumns() {
        columnsReady = false;
        ensureColumns();
    }

    private void addIfMissing(Statement statement, List<String> columns, String name, String type) throws Exception {
        if (!columns.contains(name)) {
            statement.execute("ALTER TABLE boss_data ADD COLUMN " + name + " " + type);
            columns.add(name);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(", ", values);
    }
}
