package com.getjobs.worker.utils;

import lombok.Getter;

/**
 * @author 项目贡献者
 * 上游来源与许可见根目录 README.md。
 */
@Getter
public enum Platform {
    ZHILIAN("智联招聘"),
    BOSS("Boss直聘"),
    LIEPIN("猎聘"),
    JOB51("前程无忧"),
    LAGOU("拉勾网"),
    UNKNOWN("未知平台");

    // 获取枚举值的描述
    private final String platformName;

    // 构造函数
    Platform(String platformName) {
        this.platformName = platformName;
    }

}
