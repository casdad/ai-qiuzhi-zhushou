package com.getjobs.worker.boss;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Shared across batch, single-job and explicitly approved deliveries. */
@Service
public class BossOperationGate {
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;
    private final LongSupplier currentTime;
    private final JdbcTemplate jdbc;
    @Value("${boss.safety.search-cooldown-minutes:0}")
    private int searchCooldownMinutes;

    @Autowired
    public BossOperationGate(DataSource dataSource) {
        this(System::nanoTime, System::currentTimeMillis, new JdbcTemplate(dataSource));
    }

    BossOperationGate(LongSupplier nanoTime, LongSupplier currentTime) {
        this(nanoTime, currentTime, null);
    }

    private BossOperationGate(LongSupplier nanoTime, LongSupplier currentTime, JdbcTemplate jdbc) {
        this.nanoTime = nanoTime;
        this.currentTime = currentTime;
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void restore() {
        if (jdbc == null) return;
        jdbc.execute("CREATE TABLE IF NOT EXISTS boss_safety_hold (session_scope TEXT PRIMARY KEY, reason TEXT NOT NULL, resume_after INTEGER NOT NULL)");
        if (searchCooldownMinutes == 0) {
            // Only retire this disabled local rule; never clear platform or unknown-send holds.
            jdbc.update("DELETE FROM boss_safety_hold WHERE reason = ?", BossBrowseGuard.SEARCH_COOLDOWN_REASON);
        }
        jdbc.query("SELECT session_scope, reason, resume_after FROM boss_safety_hold", rs -> {
            SessionState state = state(rs.getString("session_scope"));
            state.reason = rs.getString("reason");
            state.resumeAfter = rs.getLong("resume_after");
        });
    }

    public void await(String scope, int intervalSeconds, BooleanSupplier cancelled, LongConsumer waitMillis) {
        await(scope, intervalSeconds, cancelled, waitMillis, false);
    }

    public void awaitAfterPreparation(String scope, int intervalSeconds, BooleanSupplier cancelled, LongConsumer waitMillis) {
        await(scope, intervalSeconds, cancelled, waitMillis, true);
    }

    private void await(String scope, int intervalSeconds, BooleanSupplier cancelled, LongConsumer waitMillis,
                       boolean fullInterval) {
        SessionState state = state(scope);
        long interval = Math.max(1, Math.min(3600, intervalSeconds)) * 1_000_000_000L;
        long preparedAt = fullInterval ? nanoTime.getAsLong() : 0;
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Boss task cancelled");
            }
            long remaining;
            synchronized (state) {
                if (state.reason != null) throw new DeliveryPausedException(state.reason);
                long now = nanoTime.getAsLong();
                remaining = state.used ? interval - (now - state.lastPermit) : 0;
                if (fullInterval) remaining = Math.max(remaining, interval - (now - preparedAt));
                if (remaining <= 0) {
                    state.used = true;
                    state.lastPermit = now;
                    return;
                }
            }
            waitMillis.accept(Math.max(1, Math.min(250, (remaining + 999_999) / 1_000_000)));
        }
    }

    public void hold(String scope, String reason) {
        hold(scope, reason, 0L);
    }

    public void hold(String scope, String reason, long retryAfterMillis) {
        SessionState state = state(scope);
        synchronized (state) {
            state.reason = reason == null ? "Manual review required" : reason;
            long now = currentTime.getAsLong();
            long delay = Math.max(0, retryAfterMillis);
            long resumeAt = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
            state.resumeAfter = Math.max(state.resumeAfter, resumeAt);
            if (jdbc != null) jdbc.update("""
                    INSERT INTO boss_safety_hold(session_scope,reason,resume_after) VALUES(?,?,?)
                    ON CONFLICT(session_scope) DO UPDATE SET reason=excluded.reason,resume_after=excluded.resume_after
                    """, scope, state.reason, state.resumeAfter);
        }
    }

    public String holdReason(String scope) {
        if (scope == null || scope.isBlank()) return null;
        SessionState state = sessions.get(scope);
        if (state == null) return null;
        synchronized (state) { return state.reason; }
    }

    public void resume(String scope) {
        SessionState state = state(scope);
        synchronized (state) {
            if (currentTime.getAsLong() < state.resumeAfter) {
                throw new DeliveryPausedException("Retry-After has not elapsed; remain paused");
            }
            if (jdbc != null) jdbc.update("DELETE FROM boss_safety_hold WHERE session_scope=?", scope);
            state.reason = null;
            state.resumeAfter = 0;
        }
    }

    private SessionState state(String scope) {
        if (scope == null || scope.isBlank()) throw new IllegalStateException("No authenticated Boss session");
        return sessions.computeIfAbsent(scope, ignored -> new SessionState());
    }

    private static class SessionState {
        private boolean used;
        private long lastPermit;
        private String reason;
        private long resumeAfter;
    }

    public static class DeliveryPausedException extends IllegalStateException {
        public DeliveryPausedException(String message) { super(message); }
    }
}
