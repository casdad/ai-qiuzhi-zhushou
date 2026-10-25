package com.getjobs.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.boss.BossOperationGate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BossDeliveryService {
    public static final int MAX_MESSAGE_LENGTH = 2000;
    private static final Set<String> FINAL_STATUSES = Set.of(
            "SENT", "SKIPPED", "FAILED", "UNKNOWN", "RESTRICTED");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BossOperationGate operationGate;

    public record DeliveryDraft(String id, String jobKey, String sessionScope, Job job,
                                String message, boolean sendImgResume, String status,
                                String reason, String createdAt, String updatedAt) {
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS boss_delivery_draft (
                    id TEXT PRIMARY KEY,
                    job_key TEXT NOT NULL,
                    session_scope TEXT NOT NULL,
                    job_json TEXT NOT NULL,
                    message TEXT NOT NULL,
                    send_img_resume INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    reason TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(session_scope, job_key)
                )
                """);
        // A restart cannot establish whether an in-flight remote side effect happened.
        List<String> interruptedScopes = jdbcTemplate.queryForList(
                "SELECT DISTINCT session_scope FROM boss_delivery_draft WHERE status = 'IN_FLIGHT'", String.class);
        for (String scope : interruptedScopes) {
            operationGate.hold(scope, "应用重启，存在未确认的发送结果，请在官方界面核对后显式恢复");
        }
        jdbcTemplate.update("""
                UPDATE boss_delivery_draft SET status = 'UNKNOWN', reason = ?, updated_at = ?
                WHERE status = 'IN_FLIGHT'
                """, "应用重启，发送结果未确认；请在官方界面核对，不自动重发", now());
    }

    public DeliveryDraft prepare(String sessionScope, Job job, String message, boolean sendImgResume) {
        return insertDraft(sessionScope, job, message, sendImgResume, "PENDING", false);
    }

    /** Only a new atomic insertion grants automatic sending; existing drafts remain untouched. */
    public DeliveryDraft prepareAndClaimAutomatic(String sessionScope, Job job, String message, boolean sendImgResume) {
        return insertDraft(sessionScope, job, message, sendImgResume, "IN_FLIGHT", true);
    }

    private DeliveryDraft insertDraft(String sessionScope, Job job, String message, boolean sendImgResume,
                                      String initialStatus, boolean newlyCreatedOnly) {
        if (sessionScope == null || sessionScope.isBlank() || sessionScope.length() > 128) {
            throw new IllegalArgumentException("没有可用的 Boss 登录会话");
        }
        if (job == null) {
            throw new IllegalArgumentException("岗位信息不能为空");
        }
        validateMessage(message);
        String jobKey = normalizeJobUrl(job.getHref());
        Job snapshot = objectMapper.convertValue(job, Job.class);
        snapshot.setHref(jobKey);
        String createdAt = now();
        String id = UUID.randomUUID().toString();
        int inserted = jdbcTemplate.update("""
                INSERT OR IGNORE INTO boss_delivery_draft
                (id, job_key, session_scope, job_json, message, send_img_resume, status,
                 reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """, id, jobKey, sessionScope, serialize(snapshot), message,
                sendImgResume ? 1 : 0, initialStatus, createdAt, createdAt);
        if (newlyCreatedOnly) {
            return inserted == 1 ? find(id).orElseThrow() : null;
        }
        return jdbcTemplate.query("""
                SELECT * FROM boss_delivery_draft WHERE session_scope = ? AND job_key = ?
                """, this::mapDraft, sessionScope, jobKey).stream().findFirst().orElseThrow();
    }

    public boolean hasDraftForJob(String sessionScope, String jobUrl) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM boss_delivery_draft WHERE session_scope = ? AND job_key = ?",
                Integer.class, sessionScope, normalizeJobUrl(jobUrl));
        return count != null && count > 0;
    }

    public String statusForJob(String sessionScope, String jobUrl) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM boss_delivery_draft WHERE session_scope = ? AND job_key = ?",
                String.class, sessionScope, normalizeJobUrl(jobUrl)).stream().findFirst().orElse(null);
    }

    public Optional<DeliveryDraft> find(String id) {
        return jdbcTemplate.query("SELECT * FROM boss_delivery_draft WHERE id = ?",
                this::mapDraft, id).stream().findFirst();
    }

    public List<DeliveryDraft> list() {
        return jdbcTemplate.query("""
                SELECT * FROM boss_delivery_draft
                ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, updated_at DESC
                LIMIT 100
                """, this::mapDraft);
    }

    public boolean claim(String id) {
        return jdbcTemplate.update("""
                UPDATE boss_delivery_draft SET status = 'IN_FLIGHT', reason = NULL, updated_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, now(), id) == 1;
    }

    public boolean claim(String id, String message) {
        validateMessage(message);
        // The edited message and claim are one conditional write: double clicks cannot resend.
        return jdbcTemplate.update("""
                UPDATE boss_delivery_draft
                SET message = ?, status = 'IN_FLIGHT', reason = NULL, updated_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, message, now(), id) == 1;
    }

    public void finish(String id, String status, String reason) {
        if (!FINAL_STATUSES.contains(status)) {
            throw new IllegalArgumentException("无效的投递终态");
        }
        jdbcTemplate.update("""
                UPDATE boss_delivery_draft SET status = ?, reason = ?, updated_at = ?
                WHERE id = ? AND status = 'IN_FLIGHT'
                """, status, reason, now(), id);
    }

    public boolean dismiss(String id) {
        return jdbcTemplate.update("""
                UPDATE boss_delivery_draft SET status = 'DISMISSED', reason = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, "用户已忽略", now(), id) == 1;
    }

    public static void validateMessage(String message) {
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("招呼语不能为空，且不能超过 " + MAX_MESSAGE_LENGTH + " 个字符");
        }
    }

    static String normalizeJobUrl(String href) {
        if (href == null || href.isBlank()) {
            throw new IllegalArgumentException("岗位链接不能为空");
        }
        try {
            URI uri = URI.create(href.trim());
            if (!uri.isAbsolute()) {
                uri = URI.create("https://www.zhipin.com/").resolve(uri);
            }
            String host = uri.getHost();
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !("www.zhipin.com".equalsIgnoreCase(host) || "zhipin.com".equalsIgnoreCase(host))
                    || path == null || !path.matches("/job_detail/[A-Za-z0-9_-]+\\.html")) {
                throw new IllegalArgumentException("仅允许 Boss 官方岗位详情链接");
            }
            return "https://www.zhipin.com" + path;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("岗位链接无效或不是 Boss 官方岗位详情链接");
        }
    }

    private DeliveryDraft mapDraft(ResultSet rs, int row) throws SQLException {
        try {
            return new DeliveryDraft(rs.getString("id"), rs.getString("job_key"),
                    rs.getString("session_scope"), objectMapper.readValue(rs.getString("job_json"), Job.class),
                    rs.getString("message"), rs.getInt("send_img_resume") != 0, rs.getString("status"),
                    rs.getString("reason"), rs.getString("created_at"), rs.getString("updated_at"));
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid persisted delivery draft", e);
        }
    }

    private String serialize(Job job) {
        try {
            return objectMapper.writeValueAsString(job);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法保存岗位草稿", e);
        }
    }

    private static String now() {
        return Instant.now().toString();
    }
}
