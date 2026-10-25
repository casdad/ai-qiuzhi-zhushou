package com.getjobs.worker.boss;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author 项目贡献者
 * 上游来源与许可见根目录 README.md。
 *
 * Boss配置数据类
 * 配置加载由 BossConfigLoaderService 负责
 */
@Data
public class BossConfig {
    public static final int DEFAULT_WAIT_TIME_SECONDS = 60;
    public static final int DEFAULT_WAIT_TIME_MAX_SECONDS = 120;

    /**
     * 用于打招呼的语句
     */
    private String sayHi;

    /**
     * 开发者模式
     */
    private Boolean debugger;

    /**
     * 搜索关键词列表
     */
    private List<String> keywords;
    /** 意向岗位描述，与候选人真实技术栈分别传入评分。 */
    private String jobIntent;

    /**
     * 城市编码
     */
    private List<String> cityCode;

    /**
     * 自定义城市编码映射
     */
    private Map<String, String> customCityCode;

    /**
     * 行业列表
     */
    private List<String> industry;

    /**
     * 工作经验要求
     */
    private List<String> experience;

    /**
     * 工作类型
     */
    private String jobType;

    /**
     * 薪资范围（多选）
     */
    private java.util.List<String> salary;

    /**
     * 学历要求列表
     */
    private List<String> degree;

    /**
     * 公司规模列表
     */
    private List<String> scale;

    /**
     * 公司融资阶段列表
     */
    private List<String> stage;

    /**
     * 是否开放AI检测
     */
    private Boolean enableAI;

    /**
     * 是否过滤不活跃hr
     */
    private Boolean filterDeadHR;

    /**
     * 是否发送图片简历
     */
    private Boolean sendImgResume;

    /**
     * 目标薪资
     */
    private List<Integer> expectedSalary;

    /**
     * 目标薪资单位：day（元/天）或 month（元/月）。为空时按旧版月薪 K 兼容处理。
     */
    private String expectedSalaryUnit;

    /**
     * 对话框填好文字后完整等待的随机间隔下限（秒，含端点）
     */
    private String waitTime = String.valueOf(DEFAULT_WAIT_TIME_SECONDS);

    /** 随机投递间隔上限（秒，含端点）。 */
    private String waitTimeMax = String.valueOf(DEFAULT_WAIT_TIME_MAX_SECONDS);

    /** AUTO sends after preparation and waiting; CONFIRM persists a draft for explicit approval. */
    private String deliveryMode = "CONFIRM";

    /**
     * HR未上线状态
     */
    private List<String> deadStatus;

    /**
     * 是否在投递前使用 AI 对岗位与个人画像进行匹配评分。
     * 未配置时保持旧行为（不做额外的 AI 调用）。
     */
    private Boolean enableAiRelevance;

    /**
     * AI 匹配最低分（0-100）。评分低于该值的岗位会进入 skip，
     * AI 调用失败时在当前岗位等待重试，不使用本地匹配，不跳过或投递。
     */
    private Integer minAiScore;

    /** 活动岗位画像专用的候选人介绍；为空时回退到全局 AI 配置。 */
    private String aiIntroduce;

    /** 活动岗位画像专用的匹配提示词；为空时使用服务默认提示词。 */
    private String aiPrompt;
}
