package com.getjobs.worker.platform;

import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlatformDeliveryEngine {
    private final PlaywrightManager manager;
    private final PlatformDeliveryStore store;
    private final PlatformHistory history;
    private final Map<RecruitmentPlatform, PlatformAdapter> adapters;
    private final Map<RecruitmentPlatform, Run> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(3), task -> { Thread t = new Thread(task, "platform-delivery"); t.setDaemon(true); return t; });

    public PlatformDeliveryEngine(PlaywrightManager manager, PlatformDeliveryStore store,
                                  PlatformHistory history, List<PlatformAdapter> adapters) {
        this.manager = manager;
        this.store = store;
        this.history = history;
        this.adapters = adapters.stream().collect(Collectors.toMap(PlatformAdapter::platform, a -> a));
    }

    private static class Run {
        final String id = UUID.randomUUID().toString();
        final long started = System.currentTimeMillis();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final String mode;

        Run(String mode) { this.mode = mode; }
    }
    private static class Cancelled extends RuntimeException { }

    private Run reserve(RecruitmentPlatform p) {
        Run run = new Run(store.mode(p));
        return active.putIfAbsent(p, run) == null ? run : null;
    }

    public Map<String, Object> start(RecruitmentPlatform p, Consumer<JobProgressMessage> callback) {
        Run run = reserve(p);
        if (run == null) return result(false, "busy", "任务仍在运行或停止中");
        try {
            executor.execute(() -> execute(p, run, callback));
            var result = result(true, "started", "任务已启动，发送方式以已保存模式为准");
            result.put("runId", run.id);
            return result;
        } catch (RuntimeException e) {
            active.remove(p, run);
            return result(false, "unavailable", "任务执行器不可用");
        }
    }

    public void execute(RecruitmentPlatform p, Consumer<JobProgressMessage> callback) {
        Run run = reserve(p);
        if (run == null) { emit(callback, JobProgressMessage.warning(p.id, "任务仍在运行或停止中")); return; }
        execute(p, run, callback);
    }

    private void execute(RecruitmentPlatform p, Run run, Consumer<JobProgressMessage> callback) {
        try {
            onOwner(p, run, () -> {
                checkCancelled(run);
                Session session = session(p, run);
                PlatformAdapter adapter = adapters.get(p);
                String mode = run.mode;
                int prepared = 0, sent = 0, unknown = 0, inspected = 0;
                try (session) {
                    List<PlatformCandidate.Search> searches = adapter.searches();
                    if (searches.isEmpty()) throw new PlatformDeliveryStore.Paused("请先配置至少一个有效搜索关键词并保存");
                    session.check();
                    for (PlatformCandidate.Search search : searches) {
                        if (inspected >= 20) break;
                        adapter.open(session.page, search, key -> session.waitFor(key == null ? "BROWSE" : "SEARCH", key));
                        session.check();
                        List<PlatformCandidate> jobs = adapter.collect(session.page, search);
                        if (jobs.isEmpty()) throw new PlatformDeliveryStore.Paused("未识别到有效岗位，请核对搜索条件及页面结构");
                        for (PlatformCandidate job : jobs) {
                            if (inspected++ >= 20) break;
                            session.check();
                            if (history.sent(p, job.jobKey()) || store.known(p, session.scope, job.jobKey())) continue;
                            history.save(p, job);
                            var draft = store.prepare(p, session.scope, job);
                            if (draft.isEmpty()) continue;
                            prepared++;
                            if ("AUTO".equals(mode)) {
                                String outcome = submit(session, draft.get(), false);
                                if (outcome.equals("SENT")) sent++;
                                if (outcome.equals("UNKNOWN")) unknown++;
                            }
                            emit(callback, JobProgressMessage.info(p.id, "已准备 " + prepared + " 个岗位，已确认 " + sent + " 个，待核对 " + unknown + " 个"));
                        }
                    }
                }
                emit(callback, JobProgressMessage.success(p.id, "CONFIRM".equals(mode)
                        ? "岗位已准备，请在待确认列表逐条确认" : "任务结束：已确认 " + sent + " 个，结果待核对 " + unknown + " 个（不重发）"));
                return null;
            });
        } catch (Cancelled e) {
            emit(callback, JobProgressMessage.info(p.id, "任务已停止，已尝试的岗位不会自动重发"));
        } catch (Exception e) {
            pause(p, e);
            emit(callback, JobProgressMessage.warning(p.id, "任务已暂停：" + store.hold(p).map(PlatformDeliveryStore.Hold::reason).orElse("请核对日志")));
        }
    }

    public Map<String, Object> confirm(RecruitmentPlatform p, String id) {
        Run run = reserve(p);
        if (run == null) return result(false, "busy", "请等待当前任务实际退出后再确认");
        try {
            return onOwner(p, run, () -> {
                var draft = store.find(p, id).orElseThrow(() -> new IllegalArgumentException("未找到本平台草稿"));
                if (!draft.status().equals("PENDING")) return result(false, draft.status(), "草稿已处理，不重复发送");
                try (Session session = session(p, run)) {
                    if (!session.scope.equals(draft.sessionScope())) return result(false, "session_changed", "登录会话已变化，请重新准备草稿，不能跨会话发送");
                    String outcome = submit(session, draft, true);
                    return result(outcome.equals("SENT"), outcome,
                            outcome.equals("SENT") ? "平台已明确确认操作成功" : "结果为 " + outcome + "，请在官方界面核对，不自动重发");
                }
            });
        } catch (Cancelled e) { return result(false, "stopped", "操作已停止，请刷新队列核对结果"); }
        catch (Exception e) { pause(p, e); return result(false, "paused", "操作已暂停，请核对队列及暂停原因，不自动重试"); }
    }

    private String submit(Session s, PlatformDeliveryStore.Draft draft, boolean reopen) {
        PlatformAdapter adapter = adapters.get(s.p);
        s.check();
        if (history.sent(s.p, draft.job().jobKey()) || store.attempted(s.p, draft.job().jobKey())) {
            store.skip(s.p, draft.id(), "已有投递尝试记录，不重复发送");
            return "SKIPPED";
        }
        if (reopen) adapter.open(s.page, draft.job().search(), key -> s.waitFor("BROWSE", null));
        s.waitFor("SEND", null);
        s.waitFor("BROWSE", null);
        PlatformAdapter.Prepared prepared = adapter.prepare(s.page, draft.job());
        s.check();
        if (history.sent(s.p, draft.job().jobKey()) || !store.claim(s.p, s.scope, draft.id())) {
            store.skip(s.p, draft.id(), "已有投递记录或草稿已处理，不重复发送");
            return "SKIPPED";
        }
        String outcome = "UNKNOWN";
        try {
            store.recordAttempt(s.p, System.currentTimeMillis());
            prepared.click().run();
            long deadline = System.currentTimeMillis() + receiptTimeoutMillis();
            while (System.currentTimeMillis() < deadline) {
                s.check();
                String receipt = adapter.receipt(s.page, prepared);
                if (receipt != null) { s.check(); outcome = receipt; break; }
                s.page.waitForTimeout(250);
            }
        } catch (Cancelled e) {
            store.finish(s.p, draft.id(), "UNKNOWN", "停止时操作结果尚未确认，不自动重发");
            throw e;
        } catch (Exception e) {
            store.finish(s.p, draft.id(), "UNKNOWN", "操作已尝试但结果未确认，不自动重发");
            throw e;
        }
        store.finish(s.p, draft.id(), outcome, outcome.equals("UNKNOWN") ? "未看到新的明确成功回执，不自动重发" : "已识别平台回执");
        if (outcome.equals("SENT")) {
            try { history.markSent(s.p, draft.job().jobKey()); }
            catch (Exception e) { log.warn("Analytics update failed; delivery ledger retained | platform={}", s.p.id); }
        }
        adapter.closeReceipt(s.page, prepared, outcome);
        return outcome;
    }

    public void stop(RecruitmentPlatform p) { Run run = active.get(p); if (run != null) run.cancelled.set(true); }

    protected long receiptTimeoutMillis() { return 15_000; }

    public Map<String, Object> status(RecruitmentPlatform p) {
        Run run = active.get(p);
        var result = new HashMap<String, Object>();
        result.put("platform", p.id);
        result.put("isRunning", run != null);
        result.put("taskState", run == null ? "IDLE" : run.cancelled.get() ? "STOPPING" : "RUNNING");
        result.put("isLoggedIn", manager.isLoggedIn(p.id));
        result.put("runId", run == null ? null : run.id);
        result.put("safetyHoldReason", store.hold(p).map(PlatformDeliveryStore.Hold::reason).orElse(""));
        return result;
    }

    public Map<String, Object> logout(RecruitmentPlatform p, Runnable action) {
        Run run = reserve(p);
        if (run == null) return result(false, "busy", "请先停止任务并等待实际退出，再退出登录");
        return onOwner(p, run, () -> { checkCancelled(run); action.run(); return result(true, "logged_out", "平台会话已清除"); });
    }

    public Map<String, Object> openLogin(RecruitmentPlatform p) {
        Run run = reserve(p);
        if (run == null) return result(false, "busy", "任务运行中，不能切换登录页面");
        try {
            executor.execute(() -> {
                try { onOwner(p, run, () -> { checkCancelled(run); manager.openRecruitmentPlatform(p.id); return null; }); }
                catch (Exception e) { log.warn("Login page unavailable | platform={} | error={}", p.id, e.getClass().getSimpleName()); }
            });
            return result(true, "opening", "已请求打开平台页面，请在浏览器手动完成登录");
        } catch (RuntimeException e) { active.remove(p, run); return result(false, "unavailable", "无法打开登录页面"); }
    }

    public Map<String, Object> resume(RecruitmentPlatform p, boolean acknowledged) {
        if (!acknowledged) return result(false, "review_required", "请先确认已核对未知结果并处理平台限制");
        Run run = reserve(p);
        if (run == null) return result(false, "busy", "请等待当前任务退出");
        try {
            return onOwner(p, run, () -> {
                checkCancelled(run);
                if (!manager.checkRecruitmentSession(p.id)) return result(false, "not_logged_in", "请先登录并处理平台限制");
                store.resume(p, System.currentTimeMillis());
                return result(true, "resumed", "已恢复；UNKNOWN 岗位仍不会重发，任务需手动启动");
            });
        } catch (Exception e) { return result(false, "paused", "尚不能恢复，请检查登录状态与冷却时间"); }
    }

    private Session session(RecruitmentPlatform p, Run run) {
        checkCancelled(run);
        if (!manager.checkRecruitmentSession(p.id)) throw new PlatformDeliveryStore.Paused("请先在官方界面登录或完成验证");
        String scope = manager.recruitmentSessionScope(p.id);
        if (scope == null) throw new PlatformDeliveryStore.Paused("没有可绑定的登录凭证");
        return new Session(p, run, manager.recruitmentPage(p.id), scope);
    }

    private final class Session implements AutoCloseable {
        final RecruitmentPlatform p;
        final Run run;
        final Page page;
        final String scope;
        final Consumer<Response> listener;
        RuntimeException observerFailure;

        Session(RecruitmentPlatform p, Run run, Page page, String scope) {
            this.p = p; this.run = run; this.page = page; this.scope = scope;
            listener = response -> {
                try {
                    if (p.owns(response.url()) && List.of(401, 403, 405, 429).contains(response.status())) {
                        long retry = retryAt(response.headerValue("retry-after"));
                        store.hold(p, "平台返回 HTTP " + response.status() + "，请人工处理后恢复", retry);
                    } else if (p.owns(response.url()) && response.status() == 200
                            && String.valueOf(response.headerValue("content-type")).contains("json")) {
                        String length = response.headerValue("content-length");
                        if (length != null && Long.parseLong(length) > 131_072) return;
                        String body = response.text();
                        if (body != null && body.length() <= 131_072) {
                            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                            for (String field : List.of("message", "msg", "error", "errorMessage")) {
                                if (PlatformPageSafety.restrictionMessage(json.path(field).asText(""))) {
                                    store.hold(p, "平台接口提示访问限制，请人工处理后恢复", retryAt(response.headerValue("retry-after")));
                                }
                            }
                        }
                    }
                } catch (Exception e) { observerFailure = new IllegalStateException("Cannot inspect platform response", e); }
            };
            page.context().onResponse(listener);
        }

        void check() {
            checkCancelled(run);
            if (observerFailure != null) throw new IllegalStateException("Restriction observation failed", observerFailure);
            var hold = store.hold(p);
            if (hold.isPresent()) throw new PlatformDeliveryStore.Paused(hold.get().reason());
            if (!manager.checkRecruitmentSession(p.id) || !scope.equals(manager.recruitmentSessionScope(p.id))) {
                throw new PlatformDeliveryStore.Paused("登录会话已变化或需要验证，请人工核对");
            }
        }

        void waitFor(String action, String search) {
            check();
            long wait;
            while ((wait = store.reserve(p, action, search, System.currentTimeMillis())) > 0) {
                checkCancelled(run);
                page.waitForTimeout(Math.min(wait, 250));
                if (observerFailure != null || store.hold(p).isPresent()) check();
            }
            check();
        }

        @Override public void close() { page.context().offResponse(listener); }
    }

    static long retryAt(String value) {
        if (value == null || value.isBlank()) return 0;
        if (value.trim().matches("\\d+")) {
            try { return Math.addExact(System.currentTimeMillis(), Math.multiplyExact(Long.parseLong(value.trim()), 1000)); }
            catch (ArithmeticException | NumberFormatException e) { return Long.MAX_VALUE; }
        }
        try { return Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()); }
        catch (Exception e) { return 0; }
    }

    private void checkCancelled(Run run) {
        if (run.cancelled.get() || Thread.currentThread().isInterrupted()) throw new Cancelled();
        if (System.currentTimeMillis() - run.started > 30 * 60_000) throw new PlatformDeliveryStore.Paused("达到单次任务 30 分钟时长预算");
    }

    private <T> T onOwner(RecruitmentPlatform p, Run run, Callable<T> action) {
        AtomicBoolean entered = new AtomicBoolean();
        try {
            return manager.callOnPlaywright(() -> {
                entered.set(true);
                try {
                    manager.pauseRecruitmentMonitoring(p.id, true);
                    return action.call();
                }
                catch (Exception e) {
                    if (!(e instanceof Cancelled)) pause(p, e);
                    throw e;
                } finally {
                    try { manager.pauseRecruitmentMonitoring(p.id, false); }
                    finally { active.remove(p, run); }
                }
            });
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) run.cancelled.set(true);
            if (!entered.get() && !Thread.currentThread().isInterrupted()) active.remove(p, run);
            throw e;
        }
    }

    private void pause(RecruitmentPlatform p, Exception e) {
        String reason = e instanceof PlatformDeliveryStore.Paused ? e.getMessage() : "浏览器、网络或状态保存异常，请核对后恢复";
        store.hold(p, reason, 0);
        log.warn("Platform task paused | platform={} | error={}", p.id, e.getClass().getSimpleName());
    }

    private static void emit(Consumer<JobProgressMessage> callback, JobProgressMessage message) {
        if (callback != null) try { callback.accept(message); } catch (RuntimeException ignored) { }
    }

    public static Map<String, Object> result(boolean success, String status, String message) {
        return new HashMap<>(Map.of("success", success, "status", status, "message", message));
    }

    @PreDestroy public void shutdown() { active.values().forEach(run -> run.cancelled.set(true)); executor.shutdown(); }
}
