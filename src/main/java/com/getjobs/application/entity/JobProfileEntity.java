package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可复用的岗位投递画像。画像和历史 boss_config 并存，启用画像优先用于新的投递任务。
 */
@Data
@TableName("job_profile")
public class JobProfileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户可读名称，例如“计算机视觉实习”或“秋招算法岗”。 */
    @JsonAlias({"profileName"})
    private String name;
    /** 画像类型（internship/recruitment/custom），仅作展示和筛选，不限制配置值。 */
    private String type;
    private String keywords;
    /** 用户期望的岗位方向，仅用于匹配偏好，不作为技能证明。 */
    private String jobIntent;
    @JsonAlias({"cities", "city"})
    private String cityCode;
    private String industry;
    private String jobType;
    private String experience;
    private String degree;
    private String salary;
    private String scale;
    private String stage;
    private String sayHi;
    private Integer expectedSalaryMin;
    private Integer expectedSalaryMax;
    /** day（元/天）或 month（元/月）。 */
    @JsonAlias({"expectedSalaryUnit", "unit"})
    private String salaryUnit;
    private Integer enableAi;
    private Integer sendImgResume;
    private Integer filterDeadHr;
    private String deadStatus;
    /** AI 画像字段，当前用于保存个人介绍和提示词，供 AI 服务使用。 */
    private String aiIntroduce;
    private String aiPrompt;
    /** 是否启用投递前 AI 岗位匹配门禁（1=启用，0=关闭）。 */
    private Integer enableAiRelevance;
    /** AI 匹配最低分（0-100），低于阈值进入 skip/review。 */
    private Integer minAiScore;
    /** 1 为当前候选画像；多个启用时取 updated_at 最新的一条。 */
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
