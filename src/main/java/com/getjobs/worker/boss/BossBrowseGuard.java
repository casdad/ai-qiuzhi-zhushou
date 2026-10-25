package com.getjobs.worker.boss;

import jakarta.annotation.PostConstruct;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Local request budgets deliberately survive task, login and application restarts. */
@Service
public class BossBrowseGuard {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    static final String SEARCH_COOLDOWN_REASON = "相同搜索仍在本地冷却期，请使用已抓取岗位，不要反复启动扫描";

    public enum Action { SEARCH, SCROLL, DETAIL, DETAIL_PAGE }

    record Limits(int intervalSeconds, int perHour, int perDay, int searchCooldownMinutes, int cacheHours) {
        Limits {
            if (intervalSeconds < 1 || intervalSeconds > 300 || perHour < 0 || perDay < 0
                    || searchCooldownMinutes < 0 || searchCooldownMinutes > 1440
                    || cacheHours < 1 || cacheHours > 168) {
                throw new IllegalArgumentException("Invalid Boss browsing safety limits");
            }
        }
    }

    private record Event(long at, String searchKey) {}

    private final JdbcTemplate jdbc;
    private final LongSupplier clock;
    private final Limits limits;

    @Autowired
    public BossBrowseGuard(DataSource source,
                           @Value("${boss.safety.browse-interval-seconds:15}") int intervalSeconds,
                           @Value("${boss.safety.max-browse-per-hour:0}") int perHour,
                           @Value("${boss.safety.max-browse-per-day:0}") int perDay,
                           @Value("${boss.safety.search-cooldown-minutes:0}") int searchCooldownMinutes,
                           @Value("${boss.safety.detail-cache-hours:6}") int cacheHours) {
        this(new JdbcTemplate(source), System::currentTimeMillis,
                new Limits(intervalSeconds, perHour, perDay, searchCooldownMinutes, cacheHours));
    }

    BossBrowseGuard(JdbcTemplate jdbc, LongSupplier clock, Limits limits) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.limits = limits;
    }

    @PostConstruct
    public void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS boss_browse_event (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    occurred_at INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    search_key TEXT
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_boss_browse_time ON boss_browse_event(occurred_at)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS boss_detail_cache (
                    encrypt_id TEXT PRIMARY KEY,
                    detail_json TEXT NOT NULL,
                    fetched_at INTEGER NOT NULL
                )
                """);
        jdbc.update("DELETE FROM boss_detail_cache WHERE fetched_at <= ?", clock.getAsLong() - limits.cacheHours * HOUR);
    }

    public void await(Action action, String searchUrl, BooleanSupplier cancelled, LongConsumer waitMillis) {
        String key = action == Action.SEARCH ? searchKey(searchUrl) : null;
        while (true) {
            checkCancelled(cancelled);
            long delay;
            synchronized (this) {
                long now = clock.getAsLong();
                jdbc.update("DELETE FROM boss_browse_event WHERE occurred_at <= ?", now - DAY);
                List<Event> events = jdbc.query("SELECT occurred_at, search_key FROM boss_browse_event ORDER BY occurred_at",
                        (rs, row) -> new Event(rs.getLong(1), rs.getString(2)));
                long resumeAt = 0;
                if (limits.perDay > 0 && events.size() >= limits.perDay) {
                    resumeAt = events.get(events.size() - limits.perDay).at + DAY;
                }
                if (limits.perHour > 0) {
                    List<Event> hourly = events.stream().filter(event -> event.at > now - HOUR).toList();
                    if (hourly.size() >= limits.perHour) {
                        resumeAt = Math.max(resumeAt, hourly.get(hourly.size() - limits.perHour).at + HOUR);
                    }
                }
                if (resumeAt > now) {
                    throw new BrowsePausedException("达到本地浏览预算，请等待冷却结束后人工恢复", resumeAt - now);
                }
                if (key != null && limits.searchCooldownMinutes > 0) {
                    long lastSearch = events.stream().filter(event -> key.equals(event.searchKey))
                            .mapToLong(Event::at).max().orElse(Long.MIN_VALUE);
                    if (lastSearch != Long.MIN_VALUE && now - lastSearch < limits.searchCooldownMinutes * 60_000L) {
                        throw new BrowsePausedException(SEARCH_COOLDOWN_REASON,
                                lastSearch + limits.searchCooldownMinutes * 60_000L - now);
                    }
                }
                delay = events.isEmpty() ? 0
                        : events.getLast().at + limits.intervalSeconds * 1000L - now;
                if (delay <= 0) {
                    checkCancelled(cancelled);
                    // Charge before the browser action, including operations that later fail.
                    jdbc.update("INSERT INTO boss_browse_event(occurred_at,action,search_key) VALUES(?,?,?)",
                            now, action.name(), key);
                    return;
                }
            }
            long deadline = clock.getAsLong() + delay;
            while (clock.getAsLong() < deadline) {
                checkCancelled(cancelled);
                waitMillis.accept(Math.max(1, Math.min(250, deadline - clock.getAsLong())));
            }
        }
    }

    public String cachedDetail(String encryptId) {
        long now = clock.getAsLong();
        return jdbc.query("""
                SELECT detail_json FROM boss_detail_cache
                WHERE encrypt_id = ? AND fetched_at > ? AND fetched_at <= ?
                """, (rs, row) -> rs.getString(1), encryptId, now - limits.cacheHours * HOUR, now)
                .stream().findFirst().orElse(null);
    }

    public void cacheDetail(String encryptId, JSONObject response) {
        JSONObject data = response.optJSONObject("zpData");
        JSONObject job = data == null ? null : data.optJSONObject("jobInfo");
        JSONObject brand = data == null ? null : data.optJSONObject("brandComInfo");
        if (encryptId == null || encryptId.isBlank() || response.optInt("code", -1) != 0
                || job == null || !encryptId.equals(job.optString("encryptId"))
                || job.optString("jobName").isBlank() || brand == null || brand.optString("brandName").isBlank()) {
            throw new IllegalArgumentException("Cannot cache an unsuccessful or mismatched job detail");
        }
        // Cache job metadata only, not API tokens, headers, cookies or the full response.
        JSONObject snapshot = new JSONObject().put("jobInfo", pick(job,
                "encryptId", "encryptUserId", "jobName", "salaryDesc", "locationName", "experienceName",
                "degreeName", "postDescription", "jobStatusDesc", "address"));
        snapshot.put("brandComInfo", pick(brand,
                "brandName", "industryName", "introduce", "stageName", "scaleName"));
        snapshot.put("bossInfo", pick(data.optJSONObject("bossInfo"),
                "name", "title", "activeTimeDesc", "encryptUserId", "encryptBossId"));
        String json = new JSONObject().put("code", 0).put("zpData", snapshot).toString();
        jdbc.update("""
                INSERT INTO boss_detail_cache(encrypt_id,detail_json,fetched_at) VALUES(?,?,?)
                ON CONFLICT(encrypt_id) DO UPDATE SET detail_json=excluded.detail_json,fetched_at=excluded.fetched_at
                """, encryptId, json, clock.getAsLong());
    }

    private static JSONObject pick(JSONObject source, String... keys) {
        JSONObject result = new JSONObject();
        if (source != null) {
            for (String key : keys) {
                if (source.has(key)) result.put(key, source.get(key));
            }
        }
        return result;
    }

    private static String searchKey(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Search URL is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Boss browsing cancelled");
        }
    }

    public static class BrowsePausedException extends BossOperationGate.DeliveryPausedException {
        private final long retryAfterMillis;

        BrowsePausedException(String message, long retryAfterMillis) {
            super(message);
            this.retryAfterMillis = retryAfterMillis;
        }

        public long retryAfterMillis() { return retryAfterMillis; }
    }
}
