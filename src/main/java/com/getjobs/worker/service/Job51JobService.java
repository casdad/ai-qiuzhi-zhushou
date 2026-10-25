package com.getjobs.worker.service;

import com.getjobs.worker.platform.PlatformDeliveryEngine;
import com.getjobs.worker.platform.RecruitmentPlatform;
import org.springframework.stereotype.Service;

@Service
public class Job51JobService extends ManagedPlatformJobService {
    public Job51JobService(PlatformDeliveryEngine engine) {
        super(engine, RecruitmentPlatform.JOB51);
    }
}
