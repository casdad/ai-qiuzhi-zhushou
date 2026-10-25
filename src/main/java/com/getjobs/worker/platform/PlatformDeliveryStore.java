package com.getjobs.worker.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlatformDeliveryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final long HOUR = 3_600_000;
    private static final Set<String> TERMINAL = Set.of("SENT", "FAILED", "UNKNOWN", "SKIPPED", "RESTRICTED");

    public record Draft(String id, String platform, String sessionScope, PlatformCandidate job,
                        String status, String reason, long createdAt, long updatedAt) { }
    public record Hold(String reason, long retryAt) { }
    public static class Paused extends RuntimeException {
        public Paused(String message) { super(message); }
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_delivery_draft (
                    id TEXT PRIMARY KEY, platform TEXT NOT NULL, scope TEXT NOT NULL, job_key TEXT NOT NULL,
                    job_json TEXT NOT NULL, status TEXT NOT NULL, reason TEXT,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                    UNIQUE(platform, scope, job_key))
                """);
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_delivery_settings (platform TEXT PRIMARY KEY, mode TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_delivery_hold (platform TEXT PRIMARY KEY, reason TEXT NOT NULL, retry_at INTEGER NOT NULL)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_delivery_event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL,
                    action TEXT NOT NULL, search_key TEXT, occurred_at INTEGER NOT NULL)
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_platform_delivery_event ON platform_delivery_event(platform, occurred_at)");
        for (String platform : jdbc.queryForList("SELECT DISTINCT platform FROM platform_delivery_draft WHERE status='IN_FLIGHT'", String.class)) {
            hold(RecruitmentPlatform.from(platform), "应用重启，有投递结果待核对；不自动重发", 0);
        }
        jdbc.update("UPDATE platform_delivery_draft SET status='UNKNOWN', reason=?, updated_at=? WHERE status='IN_FLIGHT'",
                "应用重启，发送结果未确认，不自动重发", System.currentTimeMillis());
    }

    public String mode(RecruitmentPlatform p) {
        return jdbc.queryForList("SELECT mode FROM platform_delivery_settings WHERE platform=?", String.class, p.id)
                .stream().findFirst().orElse("CONFIRM");
    }

    public void setMode(RecruitmentPlatform p, String mode) {
        if (!Set.of("CONFIRM", "AUTO").contains(mode == null ? "" : mode)) throw new IllegalArgumentException("Invalid delivery mode");
        jdbc.update("INSERT INTO platform_delivery_settings VALUES (?,?) ON CONFLICT(platform) DO UPDATE SET mode=excluded.mode", p.id, mode);
    }

    /** Only the caller which inserted a new draft may schedule its automatic send. */
    public Optional<Draft> prepare(RecruitmentPlatform p, String scope, PlatformCandidate job) {
        job.validate(p);
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("No login session");
        if (known(p, scope, job.jobKey())) return Optional.empty();
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        int count = jdbc.update("""
                INSERT OR IGNORE INTO platform_delivery_draft
                (id,platform,scope,job_key,job_json,status,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING',?,?)
                """, id, p.id, scope, job.jobKey(), json(job), now, now);
        return count == 1 ? find(p, id) : Optional.empty();
    }

    public boolean known(RecruitmentPlatform p, String scope, String jobKey) {
        // Credential rotation is not evidence that an uncertain attempt may safely be repeated.
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_delivery_draft WHERE platform=? AND job_key=?
                AND (scope=? OR status IN ('IN_FLIGHT','SENT','UNKNOWN','FAILED','RESTRICTED'))
                """, Integer.class, p.id, jobKey, scope) > 0;
    }

    public boolean attempted(RecruitmentPlatform p, String jobKey) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_delivery_draft WHERE platform=? AND job_key=?
                AND status IN ('IN_FLIGHT','SENT','UNKNOWN','FAILED','RESTRICTED')
                """, Integer.class, p.id, jobKey) > 0;
    }

    public List<Draft> list(RecruitmentPlatform p) {
        return jdbc.query("SELECT * FROM platform_delivery_draft WHERE platform=? ORDER BY (status='PENDING') DESC, updated_at DESC LIMIT 100",
                this::read, p.id);
    }

    public Optional<Draft> find(RecruitmentPlatform p, String id) {
        return jdbc.query("SELECT * FROM platform_delivery_draft WHERE platform=? AND id=?", this::read, p.id, id).stream().findFirst();
    }

    public boolean claim(RecruitmentPlatform p, String scope, String id) {
        return jdbc.update("""
                UPDATE platform_delivery_draft SET status='IN_FLIGHT', updated_at=?
                WHERE platform=? AND scope=? AND id=? AND status='PENDING'
                AND NOT EXISTS (SELECT 1 FROM platform_delivery_draft other
                    WHERE other.platform=platform_delivery_draft.platform AND other.job_key=platform_delivery_draft.job_key
                    AND other.id<>platform_delivery_draft.id AND other.status IN ('IN_FLIGHT','SENT','UNKNOWN','FAILED','RESTRICTED'))
                """,
                System.currentTimeMillis(), p.id, scope, id) == 1;
    }

    public void skip(RecruitmentPlatform p, String id, String reason) {
        jdbc.update("UPDATE platform_delivery_draft SET status='SKIPPED', reason=?, updated_at=? WHERE platform=? AND id=? AND status='PENDING'",
                reason, System.currentTimeMillis(), p.id, id);
    }

    public void finish(RecruitmentPlatform p, String id, String status, String reason) {
        if (!TERMINAL.contains(status)) throw new IllegalArgumentException("Invalid delivery outcome");
        if (jdbc.update("UPDATE platform_delivery_draft SET status=?, reason=?, updated_at=? WHERE platform=? AND id=? AND status='IN_FLIGHT'",
                status, reason, System.currentTimeMillis(), p.id, id) != 1) throw new IllegalStateException("Delivery state was not saved");
    }

    public boolean dismiss(RecruitmentPlatform p, String id) {
        return jdbc.update("UPDATE platform_delivery_draft SET status='DISMISSED', reason=?, updated_at=? WHERE platform=? AND id=? AND status='PENDING'",
                "用户已忽略", System.currentTimeMillis(), p.id, id) == 1;
    }

    public Optional<Hold> hold(RecruitmentPlatform p) {
        return jdbc.query("SELECT reason,retry_at FROM platform_delivery_hold WHERE platform=?",
                (rs, row) -> new Hold(rs.getString(1), rs.getLong(2)), p.id).stream().findFirst();
    }

    public void hold(RecruitmentPlatform p, String reason, long retryAt) {
        jdbc.update("""
                INSERT INTO platform_delivery_hold VALUES (?,?,?) ON CONFLICT(platform) DO UPDATE
                SET reason=excluded.reason, retry_at=MAX(retry_at,excluded.retry_at)
                """, p.id, reason, retryAt);
    }

    public void resume(RecruitmentPlatform p, long now) {
        Hold hold = hold(p).orElse(null);
        if (hold != null && hold.retryAt() > now) throw new Paused("冷却时间未结束");
        jdbc.update("DELETE FROM platform_delivery_hold WHERE platform=?", p.id);
    }

    /** Returns the wait before reserving, or records the action before any external operation. */
    public synchronized long reserve(RecruitmentPlatform p, String action, String search, long now) {
        Hold hold = hold(p).orElse(null);
        if (hold != null) throw new Paused(hold.reason());
        boolean send = "SEND".equals(action);
        String predicate = send ? "action='SEND'" : "action<>'SEND'";
        Long last = jdbc.queryForObject("SELECT MAX(occurred_at) FROM platform_delivery_event WHERE platform=? AND " + predicate, Long.class, p.id);
        long wait = last == null ? 0 : last + (send ? 120_000 : 15_000) - now;
        if (!send) {
            for (long[] cap : List.of(new long[]{HOUR, 60}, new long[]{24 * HOUR, 150})) {
                List<Long> times = jdbc.queryForList("SELECT occurred_at FROM platform_delivery_event WHERE platform=? AND action<>'SEND' AND occurred_at>? ORDER BY occurred_at",
                        Long.class, p.id, now - cap[0]);
                if (times.size() >= cap[1]) pause(p, "达到浏览预算，请冷却后人工恢复", times.get((int)(times.size() - cap[1])) + cap[0]);
            }
            if ("SEARCH".equals(action)) {
                Long same = jdbc.queryForObject("SELECT MAX(occurred_at) FROM platform_delivery_event WHERE platform=? AND action='SEARCH' AND search_key=?",
                        Long.class, p.id, hash(search));
                if (same != null && same + 900_000 > now) pause(p, "同一搜索需间隔 15 分钟，请稍后人工恢复", same + 900_000);
            }
        }
        if (wait > 0) return wait;
        jdbc.update("INSERT INTO platform_delivery_event(platform,action,search_key,occurred_at) VALUES (?,?,?,?)",
                p.id, action, search == null ? null : hash(search), now);
        jdbc.update("DELETE FROM platform_delivery_event WHERE platform=? AND occurred_at<?", p.id, now - 48 * HOUR);
        return 0;
    }

    public void recordAttempt(RecruitmentPlatform p, long now) {
        jdbc.update("INSERT INTO platform_delivery_event(platform,action,occurred_at) VALUES (?,'SEND',?)", p.id, now);
    }

    private void pause(RecruitmentPlatform p, String reason, long retryAt) {
        hold(p, reason, retryAt);
        throw new Paused(reason);
    }

    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String json(PlatformCandidate job) {
        try { return mapper.writeValueAsString(job); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot serialize job", e); }
    }

    private Draft read(ResultSet rs, int row) throws SQLException {
        try {
            return new Draft(rs.getString("id"), rs.getString("platform"), rs.getString("scope"),
                    mapper.readValue(rs.getString("job_json"), PlatformCandidate.class), rs.getString("status"),
                    rs.getString("reason"), rs.getLong("created_at"), rs.getLong("updated_at"));
        } catch (java.io.IOException e) { throw new SQLException("Invalid draft snapshot", e); }
    }
}
