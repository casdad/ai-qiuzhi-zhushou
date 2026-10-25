package com.getjobs.worker.service;

import com.getjobs.worker.platform.PlatformDeliveryEngine;
import com.getjobs.worker.platform.RecruitmentPlatform;
import org.springframework.stereotype.Service;

@Service
public class ZhilianJobService extends ManagedPlatformJobService {
    public ZhilianJobService(PlatformDeliveryEngine engine) {
        super(engine, RecruitmentPlatform.ZHILIAN);
    }
}
