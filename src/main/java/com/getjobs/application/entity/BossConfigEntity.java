package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("boss_config")
public class BossConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;
    /** 调试模式（1=开启，0=关闭） */
    private Integer debugger;
    /** 相邻岗位开始投递的随机间隔下限（秒，含端点） */
    private Integer waitTime;
    /** 随机投递间隔上限（秒，含端点） */
    private Integer waitTimeMax;
    /** AUTO or CONFIRM. Missing values default to CONFIRM. */
    private String deliveryMode;
    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;
    /** 无启用画像时使用的意向岗位描述。 */
    private String jobIntent;
    /** 城市（名称或代码，支持列表） */
    private String cityCode;
    /** 行业（名称或代码，支持列表） */
    private String industry;
    /** 职位类型（名称或代码，单值或列表，优先取第一项） */
    private String jobType;
    /** 工作经验（名称或代码，支持列表） */
    private String experience;
    /** 学历要求（名称或代码，支持列表） */
    private String degree;
    /** 薪资区间（名称或代码，支持列表） */
    private String salary;
    /** 公司规模（名称或代码，支持列表） */
    private String scale;
    /** 融资阶段（名称或代码，支持列表） */
    private String stage;
    /** 默认打招呼语（当AI返回为空时使用） */
    private String sayHi;
    /** 期望薪资下限（单位：元/日或元/月，按平台规则解析） */
    private Integer expectedSalaryMin;
    /** 期望薪资上限（单位：元/日或元/月，按平台规则解析） */
    private Integer expectedSalaryMax;
    /** 期望薪资单位：day（元/天）或 month（元/月）；为空时兼容旧版月薪K配置 */
    @JsonAlias({"salaryUnit", "unit"})
    private String expectedSalaryUnit;
    /** 是否启用AI生成打招呼（1=启用，0=关闭） */
    private Integer enableAi;
    /** 是否发送图片简历（1=启用，0=关闭） */
    private Integer sendImgResume;
    /** 是否过滤不在线HR（1=启用，0=关闭） */
    private Integer filterDeadHr;
    /** 是否启用投递前 AI 岗位相关性门禁（1=启用，0=关闭） */
    private Integer enableAiRelevance;
    /** AI 岗位匹配最低分（0-100） */
    private Integer minAiScore;
    /** HR不在线状态列表（逗号或括号列表） */
    private String deadStatus;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
