package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.platform.PlatformDeliveryEngine;
import com.getjobs.worker.platform.RecruitmentPlatform;

import java.util.Map;
import java.util.function.Consumer;

public abstract class ManagedPlatformJobService implements JobPlatformService {
    protected final PlatformDeliveryEngine engine;
    private final RecruitmentPlatform platform;

    protected ManagedPlatformJobService(PlatformDeliveryEngine engine, RecruitmentPlatform platform) {
        this.engine = engine;
        this.platform = platform;
    }

    public Map<String, Object> startDelivery(Consumer<JobProgressMessage> callback) { return engine.start(platform, callback); }
    @Override public void executeDelivery(Consumer<JobProgressMessage> callback) { engine.execute(platform, callback); }
    @Override public void stopDelivery() { engine.stop(platform); }
    @Override public Map<String, Object> getStatus() { return engine.status(platform); }
    @Override public String getPlatformName() { return platform.id; }
    @Override public boolean isRunning() { return Boolean.TRUE.equals(getStatus().get("isRunning")); }
    public boolean shouldStop() { return "STOPPING".equals(getStatus().get("taskState")); }
    public Map<String, Object> logout(Runnable action) { return engine.logout(platform, action); }
}
