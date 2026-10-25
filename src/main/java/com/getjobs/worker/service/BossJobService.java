package com.getjobs.worker.service;

import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.BossDeliveryService.DeliveryDraft;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.boss.BossOperationGate;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.utils.Job;
import com.microsoft.playwright.Page;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final BossService bossService;
    private final ObjectProvider<Boss> bossProvider;
    private final BossDeliveryService deliveryService;
    private final BossOperationGate operationGate;

    private final AtomicReference<RunContext> activeRun = new AtomicReference<>();
    private volatile String lastSessionScope;
    private final ThreadPoolExecutor taskExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "boss-task");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private static final class RunContext {
        private final String id = UUID.randomUUID().toString();
        private final long startedAt = System.currentTimeMillis();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;
    }

    private RunContext reserve() {
        RunContext run = new RunContext();
        return activeRun.compareAndSet(null, run) ? run : null;
    }

    private void emit(Consumer<JobProgressMessage> callback, JobProgressMessage message) {
        if (callback == null || message == null) {
            return;
        }
        try {
            callback.accept(message);
        } catch (Exception e) {
            log.debug("进度推送失败: {}", e.getClass().getSimpleName());
        }
    }

    private void release(RunContext run, boolean monitoringPaused) {
        if (activeRun.get() != run) {
            return;
        }
        if (monitoringPaused) {
            try {
                playwrightManager.resumeBossMonitoring();
            } catch (Exception e) {
                log.warn("恢复 Boss 登录监控失败: {}", e.getClass().getSimpleName());
            }
        }
        activeRun.compareAndSet(run, null);
    }

    private <T> T onPlaywright(RunContext run, Callable<T> action) {
        AtomicBoolean entered = new AtomicBoolean();
        try {
            return playwrightManager.callOnPlaywright(() -> {
                entered.set(true);
                boolean paused = false;
                try {
                    playwrightManager.pauseBossMonitoring();
                    paused = true;
                    return action.call();
                } finally {
                    // Release on the browser thread, not on an interrupted waiting HTTP thread.
                    release(run, paused);
                }
            });
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                run.cancelled.set(true);
            }
            if (!entered.get() && !Thread.currentThread().isInterrupted()) {
                release(run, false);
            }
            throw e;
        }
    }

    /** Reserve synchronously before dispatch so simultaneous HTTP requests cannot both start. */
    public Map<String, Object> startDelivery(Consumer<JobProgressMessage> callback) {
        RunContext run = reserve();
        if (run == null) {
            return result(false, "running", "Boss 任务仍在运行或停止中，请等待当前任务实际退出");
        }
        try {
            run.future = taskExecutor.submit(() -> runDelivery(run, callback));
            Map<String, Object> response = result(true, "started", "Boss 任务已启动");
            response.put("runId", run.id);
            return response;
        } catch (RejectedExecutionException e) {
            release(run, false);
            return result(false, "unavailable", "Boss 任务执行器不可用，请稍后重试");
        }
    }

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> callback) {
        RunContext run = reserve();
        if (run == null) {
            emit(callback, JobProgressMessage.warning(PLATFORM, "任务已在运行或停止中"));
            return;
        }
        runDelivery(run, callback);
    }

    private void runDelivery(RunContext run, Consumer<JobProgressMessage> callback) {
        try {
            onPlaywright(run, () -> {
                if (run.cancelled.get()) {
                    emit(callback, JobProgressMessage.info(PLATFORM, "任务已取消"));
                    return null;
                }
                emit(callback, JobProgressMessage.info(PLATFORM, "检查浏览器与登录状态..."));
                playwrightManager.ensureReady();
                String scope = null;
                long round = 0;
                // Keep the same run slot and cancellation token until all rounds actually exit.
                while (!run.cancelled.get()) {
                    if (!playwrightManager.checkBossSession()) {
                        emit(callback, JobProgressMessage.error(PLATFORM, "登录已失效或需要验证，请在官方界面处理"));
                        return null;
                    }
                    String currentScope = playwrightManager.getBossSessionScope();
                    if (currentScope == null || currentScope.isBlank()) {
                        emit(callback, JobProgressMessage.error(PLATFORM, "没有可用的 Boss 登录会话"));
                        return null;
                    }
                    if (scope == null) {
                        scope = currentScope;
                        lastSessionScope = scope;
                    } else if (!scope.equals(currentScope)) {
                        String reason = "登录会话已变化，自动循环已停止，请人工核对后重新启动";
                        operationGate.hold(scope, reason);
                        throw new BossOperationGate.DeliveryPausedException(reason);
                    }
                    String hold = operationGate.holdReason(scope);
                    if (hold != null) {
                        emit(callback, JobProgressMessage.warning(PLATFORM,
                                "任务已暂停，请核对结果或处理平台限制后显式恢复：" + hold));
                        return null;
                    }
                    BossConfig config = configService.getBossConfig();
                    boolean automatic = "AUTO".equalsIgnoreCase(config.getDeliveryMode());
                    if (round > 0 && !automatic) {
                        emit(callback, JobProgressMessage.info(PLATFORM,
                                "已关闭自动投递，循环已停止；可重新开始任务生成待确认草稿"));
                        return null;
                    }
                    if (config.getCityCode() == null || config.getCityCode().isEmpty()
                            || config.getCityCode().stream().anyMatch(city -> city == null || city.isBlank())
                            || config.getKeywords() == null || config.getKeywords().isEmpty()
                            || config.getKeywords().stream().anyMatch(keyword -> keyword == null || keyword.isBlank())) {
                        emit(callback, JobProgressMessage.error(PLATFORM, "请先保存有效的城市和搜索关键词，任务未继续"));
                        return null;
                    }
                    round++;
                    emit(callback, JobProgressMessage.info(PLATFORM,
                            String.format("第 %d 轮开始：按已保存的城市和关键词依次处理", round)));
                    // Boss is prototype-scoped: reset per-round counters, not persisted delivery history.
                    Boss boss = createBoss(run, config, scope, (message, current, total) -> {
                        if (current != null && total != null) {
                            emit(callback, JobProgressMessage.progress(PLATFORM, message, current, total));
                        } else {
                            emit(callback, JobProgressMessage.info(PLATFORM, message));
                        }
                    });
                    if (run.cancelled.get()) break;
                    int delivered = boss.execute();
                    hold = operationGate.holdReason(scope);
                    if (hold != null) {
                        emit(callback, JobProgressMessage.warning(PLATFORM, "已暂停，需要人工处理：" + hold));
                        return null;
                    }
                    if (run.cancelled.get()) break;
                    if (!automatic) {
                        emit(callback, JobProgressMessage.success(PLATFORM, "岗位准备已完成，请在确认列表逐条核对并发送"));
                        return null;
                    }
                    emit(callback, JobProgressMessage.info(PLATFORM,
                            String.format("第 %d 轮完成：已确认发送 %d 条，另有 %d 条已尝试但结果待核对（不自动重发）；%s；自动继续下一轮，可点击停止结束",
                                    round, delivered, boss.getUnconfirmedCount(), boss.getRunSummary())));
                }
                emit(callback, JobProgressMessage.info(PLATFORM, "任务已停止，不再开始下一轮"));
                return null;
            });
        } catch (CancellationException e) {
            log.info("Boss 任务已停止 | runId={}", run.id);
            emit(callback, JobProgressMessage.info(PLATFORM, "任务已停止，不再开始下一轮"));
        } catch (BossOperationGate.DeliveryPausedException e) {
            log.warn("Boss 任务已暂停 | runId={} | reason={}", run.id, e.getMessage());
            emit(callback, JobProgressMessage.warning(PLATFORM,
                    "任务已暂停：" + e.getMessage() + "；请按暂停原因核对后显式恢复，不自动重试"));
        } catch (Exception e) {
            log.error("Boss 任务执行失败 | runId={}", run.id, e);
            emit(callback, JobProgressMessage.error(PLATFORM, "任务已停止；请检查登录、验证状态及诊断日志，不自动重试"));
        }
    }

    private String sessionScope() {
        String scope = playwrightManager.getBossSessionScope();
        lastSessionScope = scope;
        return scope;
    }

    private Boss createBoss(RunContext run, BossConfig config, String scope, Boss.ProgressCallback callback) {
        Page page = playwrightManager.getBossPage();
        if (page == null) {
            throw new IllegalStateException("Boss 页面未初始化");
        }
        Boss boss = bossProvider.getObject();
        boss.setPage(page);
        boss.setConfig(config);
        boss.setSessionScope(scope);
        boss.setProgressCallback(callback);
        boss.setShouldStopCallback(run.cancelled::get);
        boss.prepare();
        return boss;
    }

    @Override
    public void stopDelivery() {
        RunContext run = activeRun.get();
        if (run != null) {
            run.cancelled.set(true);
            log.info("Boss 任务进入 STOPPING，等待实际退出 | runId={}", run.id);
        }
    }

    public long runningForMillis() {
        RunContext run = activeRun.get();
        return run == null ? 0L : System.currentTimeMillis() - run.startedAt;
    }

    @Override
    public Map<String, Object> getStatus() {
        RunContext run = activeRun.get();
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", run != null);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        status.put("state", run == null ? "STOPPED" : run.cancelled.get() ? "STOPPING" : "RUNNING");
        status.put("taskState", run == null ? "IDLE" : run.cancelled.get() ? "STOPPING" : "RUNNING");
        status.put("runId", run == null ? null : run.id);
        status.put("runningSeconds", run == null ? 0 : (System.currentTimeMillis() - run.startedAt) / 1000);
        status.put("safetyHoldReason", lastSessionScope == null ? null : operationGate.holdReason(lastSessionScope));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return activeRun.get() != null;
    }

    public boolean shouldStop() {
        RunContext run = activeRun.get();
        return run != null && run.cancelled.get();
    }

    public Map<String, Object> logout(Runnable logoutAction) {
        RunContext run = reserve();
        if (run == null) {
            return result(false, "busy", "任务仍在运行或停止中，请等待实际退出后再退出登录");
        }
        return onPlaywright(run, () -> {
            logoutAction.run();
            lastSessionScope = null;
            return result(true, "logged_out", "Boss 已退出登录，本地保存的会话已清理");
        });
    }

    public Map<String, Object> resumeAfterReview(boolean acknowledged) {
        if (!acknowledged) {
            return result(false, "acknowledgement_required", "请先确认已在官方界面核对未知结果并处理平台限制");
        }
        RunContext run = reserve();
        if (run == null) {
            return result(false, "busy", "请等待当前任务实际退出后恢复");
        }
        try {
            return onPlaywright(run, () -> {
                if (run.cancelled.get()) {
                    return result(false, "cancelled", "恢复操作已取消");
                }
                playwrightManager.ensureReady();
                if (!playwrightManager.checkBossSession()) {
                    return result(false, "not_logged_in", "请先在官方界面完成登录或验证");
                }
                String scope = sessionScope();
                if (scope == null || scope.isBlank()) {
                    return result(false, "not_logged_in", "没有可用的 Boss 登录会话");
                }
                operationGate.resume(scope);
                return result(true, "resumed", "已恢复其他未发送岗位；结果未知的草稿不会重新发送");
            });
        } catch (Exception e) {
            log.warn("Boss 恢复操作被拒绝 | runId={} | error={}", run.id, e.getClass().getSimpleName());
            return result(false, "restricted", "暂不能恢复，请确认限制已解除并等待要求的冷却时间");
        }
    }

    public Map<String, Object> applySingle(BossJobDataEntity entity) {
        if (entity == null || entity.getId() == null) {
            return result(false, "invalid_job", "岗位信息无效");
        }
        RunContext run = reserve();
        if (run == null) {
            return result(false, "busy", "Boss 任务仍在运行或停止中，请等待当前任务退出");
        }
        try {
            return onPlaywright(run, () -> {
                if (run.cancelled.get()) {
                    return result(false, "SKIPPED", "任务已取消");
                }
                playwrightManager.ensureReady();
                if (!playwrightManager.checkBossSession()) {
                    return result(false, "not_logged_in", "请先在官方界面完成登录或验证");
                }
                String scope = sessionScope();
                if (scope == null || scope.isBlank()) {
                    return result(false, "not_logged_in", "没有可用的 Boss 登录会话");
                }
                if (operationGate.holdReason(scope) != null) {
                    return result(false, "RESTRICTED", "已暂停，请人工核对并显式恢复");
                }
                Boss boss = createBoss(run, configService.getBossConfig(), scope,
                        (message, current, total) -> log.info("[boss-single] {}", message));
                Job job = toJob(entity);
                boss.registerJobIdentity(entity.getEncryptId(), entity.getEncryptUserId());
                Boss.SubmitResult outcome = boss.submitSingle(job.getJobName(), job);
                holdIfNeeded(scope, outcome);
                try {
                    if (outcome == Boss.SubmitResult.SENT) {
                        bossService.updateDeliveryStatusById(entity.getId(), "已投递");
                    } else if (outcome == Boss.SubmitResult.FAILED) {
                        bossService.updateDeliveryStatusById(entity.getId(), "投递失败");
                    }
                } catch (Exception e) {
                    log.warn("单岗投递状态回写失败 | id={}", entity.getId(), e);
                }
                return outcomeResult(outcome);
            });
        } catch (Exception e) {
            log.error("Boss 单岗操作失败 | id={} | runId={}", entity.getId(), run.id, e);
            return result(false, "error", "操作已停止，请检查诊断日志；不会自动重试");
        }
    }

    public Map<String, Object> confirmDelivery(String id, String message) {
        BossDeliveryService.validateMessage(message);
        DeliveryDraft draft = deliveryService.find(id).orElse(null);
        if (draft == null) {
            return result(false, "not_found", "未找到投递草稿");
        }
        if (!"PENDING".equals(draft.status())) {
            return result(false, draft.status(), "此草稿已处理或结果待核对，不能再次发送");
        }
        RunContext run = reserve();
        if (run == null) {
            return result(false, "busy", "Boss 任务仍在运行或停止中；草稿未发送，请等待任务退出");
        }
        AtomicBoolean claimed = new AtomicBoolean();
        try {
            return onPlaywright(run, () -> {
                if (run.cancelled.get()) {
                    return result(false, "SKIPPED", "任务已取消，草稿仍待确认");
                }
                playwrightManager.ensureReady();
                if (!playwrightManager.checkBossSession()) {
                    return result(false, "not_logged_in", "请先在官方界面完成登录或验证，草稿未发送");
                }
                String scope = sessionScope();
                if (scope == null || !scope.equals(draft.sessionScope())) {
                    return result(false, "session_changed", "登录会话已变化；为避免跨账号发送，请重新准备岗位草稿");
                }
                if (operationGate.holdReason(scope) != null) {
                    return result(false, "RESTRICTED", "已暂停，请人工核对并显式恢复；草稿未发送");
                }
                Boss boss = createBoss(run, configService.getBossConfig(), scope,
                        (event, current, total) -> log.info("[boss-confirm] {}", event));
                if (run.cancelled.get()) {
                    return result(false, "SKIPPED", "任务已取消，草稿仍待确认");
                }
                if (!deliveryService.claim(id, message)) {
                    return result(false, "already_processed", "此草稿已被处理，不会重复发送");
                }
                claimed.set(true);
                try {
                    Boss.SubmitResult outcome = boss.submitConfirmed(draft.job(), message, draft.sendImgResume());
                    if (outcome == Boss.SubmitResult.QUEUED) {
                        outcome = Boss.SubmitResult.UNKNOWN;
                    }
                    holdIfNeeded(scope, outcome);
                    Map<String, Object> response = outcomeResult(outcome);
                    deliveryService.finish(id, outcome.name(), (String) response.get("message"));
                    return response;
                } catch (Exception e) {
                    log.error("确认发送结果未确认 | draftId={} | runId={}", id, run.id, e);
                    operationGate.hold(scope, "发送结果未确认，请在官方界面核对");
                    deliveryService.finish(id, "UNKNOWN", "发送结果未确认，请在官方界面核对，不自动重发");
                    return outcomeResult(Boss.SubmitResult.UNKNOWN);
                }
            });
        } catch (Exception e) {
            log.error("Boss 确认操作失败 | draftId={} | runId={}", id, run.id, e);
            if (claimed.get()) {
                try {
                    operationGate.hold(draft.sessionScope(), "发送结果或状态保存未确认，请在官方界面核对");
                    deliveryService.finish(id, "UNKNOWN", "状态保存未确认，请在官方界面核对，不自动重发");
                } catch (Exception persistenceError) {
                    log.error("保存投递未知状态失败 | draftId={}", id, persistenceError);
                }
                return outcomeResult(Boss.SubmitResult.UNKNOWN);
            }
            return result(false, "error", "操作已停止，请刷新草稿状态并检查日志，不要重复发送");
        }
    }

    private void holdIfNeeded(String scope, Boss.SubmitResult outcome) {
        if (outcome == Boss.SubmitResult.RESTRICTED && operationGate.holdReason(scope) == null) {
            operationGate.hold(scope, "遇到验证或平台限制，请在官方界面处理");
        }
    }

    private Map<String, Object> outcomeResult(Boss.SubmitResult outcome) {
        String message = switch (outcome) {
            case SENT -> "招呼语已确认发送";
            case QUEUED -> "草稿已准备，请到确认列表逐条核对并发送";
            case SKIPPED -> "未执行发送；请核对岗位状态";
            case FAILED -> "发送失败，不自动重试";
            case UNKNOWN -> "本岗位发送结果待核对，不自动重发；不因此暂停其他岗位";
            case RESTRICTED -> "遇到验证或平台限制，已停止，请在官方界面处理";
        };
        Map<String, Object> response = result(outcome == Boss.SubmitResult.SENT
                || outcome == Boss.SubmitResult.QUEUED, outcome.name(), message);
        response.put("needsHuman", outcome == Boss.SubmitResult.UNKNOWN || outcome == Boss.SubmitResult.RESTRICTED);
        response.put("queued", outcome == Boss.SubmitResult.QUEUED);
        response.put("applied", outcome == Boss.SubmitResult.SENT);
        response.put("attempted", outcome != Boss.SubmitResult.QUEUED);
        return response;
    }

    private static Map<String, Object> result(boolean success, String status, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("status", status);
        result.put("message", message);
        result.put("attempted", false);
        return result;
    }

    @PreDestroy
    public void shutdown() {
        stopDelivery();
        taskExecutor.shutdown();
    }

    private Job toJob(BossJobDataEntity entity) {
        Job job = new Job();
        String href = entity.getJobUrl();
        if ((href == null || href.isBlank()) && entity.getEncryptId() != null
                && !entity.getEncryptId().isBlank()) {
            href = "https://www.zhipin.com/job_detail/" + entity.getEncryptId() + ".html";
        }
        job.setHref(href);
        job.setJobName(entity.getJobName() == null ? "" : entity.getJobName());
        job.setJobArea(entity.getLocation() == null ? "" : entity.getLocation());
        job.setJobInfo(entity.getJobDescription() == null ? "" : entity.getJobDescription());
        job.setSalary(entity.getSalary() == null ? "" : entity.getSalary());
        job.setCompanyName(entity.getCompanyName() == null ? "" : entity.getCompanyName());
        job.setRecruiter(entity.getHrName() == null ? "" : entity.getHrName());
        return job;
    }
}
