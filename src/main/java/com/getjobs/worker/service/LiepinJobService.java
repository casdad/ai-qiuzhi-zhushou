package com.getjobs.worker.service;

import com.getjobs.worker.platform.PlatformDeliveryEngine;
import com.getjobs.worker.platform.RecruitmentPlatform;
import org.springframework.stereotype.Service;

@Service
public class LiepinJobService extends ManagedPlatformJobService {
    public LiepinJobService(PlatformDeliveryEngine engine) {
        super(engine, RecruitmentPlatform.LIEPIN);
    }
}
