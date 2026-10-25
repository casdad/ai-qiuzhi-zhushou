package com.getjobs.worker.boss;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.JobRelevancePersistenceService;
import com.getjobs.application.service.JobRelevanceService;
import com.getjobs.worker.utils.Job;
import com.getjobs.worker.utils.JobUtils;
import com.getjobs.worker.utils.PlaywrightUtil;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import static com.getjobs.worker.boss.Locators.*;


/**
 * @author 项目贡献者
 * 上游来源与许可见根目录 README.md。
 * Boss直聘自动投递
 */
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class Boss {

    @Setter
    private Page page;
    @Setter
    private BossConfig config;
    private final BossService bossService;
    private final AiService aiService;
    private final JobRelevanceService jobRelevanceService;
    private final JobRelevancePersistenceService jobRelevancePersistenceService;
    private final BossDeliveryService deliveryService;
    private final BossOperationGate operationGate;
    private final PlaywrightManager playwrightManager;
    private final BossBrowseGuard browseGuard;
    @Setter
    private String sessionScope;
    private Set<String> blackCompanies;
    private Set<String> blackRecruiters;
    private Set<String> blackJobs;
    // 记录 encryptId -> encryptUserId 的映射，用于后续更新投递状态
    private final ConcurrentMap<String, String> encryptIdToUserId = new ConcurrentHashMap<>();
    @Setter
    private ProgressCallback progressCallback;
    @Setter
    private Supplier<Boolean> shouldStopCallback;

    private final List<Job> resultList = new ArrayList<>();
    private final Set<String> inspectedJobIds = new HashSet<>();
    private int inspectedJobs;
    private int unconfirmedCount;
    private int remainingSearches;
    private int totalJobBudget;
    private int duplicateCount;
    private int existingDeliveryCount;
    private int historicalUnknownCount;
    private int salaryFilteredCount;
    private int aiFilteredCount;
    private int otherFilteredCount;
    private int queuedCount;
    private int failedCount;
    private boolean automaticBatch;
    private String lastAction = "CHECK";
    private final long createdAtNanos = System.nanoTime();
    private static final int MAX_JOBS_PER_SEARCH = 100;
    private static final int MAX_LIST_LOAD_ROUNDS = 20;
    private static final int MAX_STALLED_LIST_LOAD_ROUNDS = 2;
    private static final long MAX_RUN_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(30);
    private static final long AI_SCORE_RETRY_MILLIS = 30_000;
    static final String EMPTY_JOB_LIST_SELECTOR = ":text-matches('^没有找到相关职位(?:[，,。\\\\s]|$)', '')";

    /** Boss 首页，UI 搜索的入口 */
    private static final String BOSS_HOME_URL = "https://www.zhipin.com";
    /**
     * true=先回首页、在搜索框里做一次真实 UI 搜索再进列表页；false=直接拼 URL 跳转。
     * <p>
     * 默认关掉。实测 Boss 首页在受控标签页里会一直转圈加载不完，navigate 要 60 秒才返回，
     * 期间 evaluate / locator 全部无限期阻塞（这两个 API 没有默认超时）—— 启动慢、投递卡死、
     * 管理页面按钮没反应都出在这。而岗位列表页在同一个浏览器里是秒开的。
     * 想重新试 UI 搜索路径时再打开。
     */
    private static final boolean USE_UI_SEARCH = false;
    /** 等待页面加载状态的超时（毫秒），绝不能不设 —— 见 waitForPageSettled 的说明 */
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    /**
     * 进度回调接口
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void accept(String message, Integer current, Integer total);
    }

    // 通过 Lombok @RequiredArgsConstructor 使用构造器注入 bossService 与 aiService

    public void prepare() {
        // 调整 boss_data 表结构：将 encrypt_id、encrypt_user_id 前置
        try { bossService.ensureBossDataColumnOrder(); } catch (Throwable ignore) {}
        // 上述遗留列顺序迁移可能重建表，确保评分字段在重建后仍然存在。
        try { jobRelevancePersistenceService.refreshColumns(); } catch (Throwable ignore) {}
        // 从数据库加载黑名单
        this.blackCompanies = bossService.getBlackCompanies();
        this.blackRecruiters = bossService.getBlackRecruiters();
        this.blackJobs = bossService.getBlackJobs();

        log.info("黑名单加载完成: 公司({}) 招聘者({}) 职位({})",
                blackCompanies != null ? blackCompanies.size() : 0,
                blackRecruiters != null ? blackRecruiters.size() : 0,
                blackJobs != null ? blackJobs.size() : 0);
        // 不在页面初始化阶段入库，仅用于后续点击卡片时按需入库
    }

    /**
     * 执行投递
     */
    public int execute() {
        automaticBatch = "AUTO".equalsIgnoreCase(config.getDeliveryMode());
        try {
            return withSafetyMonitoring(this::executeBatch);
        } finally {
            automaticBatch = false;
        }
    }

    private int executeBatch() {
        assertSafe(page);
        if (config.getCityCode() == null || config.getKeywords() == null) return 0;
        remainingSearches = config.getCityCode().size() * config.getKeywords().size();
        totalJobBudget = remainingSearches * MAX_JOBS_PER_SEARCH;
        for (String cityCode : config.getCityCode()) {
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
            postJobByCity(cityCode);
            if (inspectedJobs >= totalJobBudget) break;
            if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                progressCallback.accept("用户取消投递", 0, 0);
                break;
            }
        }
        return resultList.size();
    }

    /**
     * 获取结果列表
     */
    public List<Job> getResultList() {
        return new ArrayList<>(resultList);
    }

    public int getUnconfirmedCount() {
        return unconfirmedCount;
    }

    public String getRunSummary() {
        return String.format("新检查 %d/%d 个；本轮重复 %d，已有投递记录 %d（历史待核对 %d）；薪资排除 %d，AI未通过 %d，其他排除 %d；待确认 %d，失败 %d",
                inspectedJobs, totalJobBudget, duplicateCount, existingDeliveryCount,
                historicalUnknownCount, salaryFilteredCount, aiFilteredCount, otherFilteredCount,
                queuedCount, failedCount);
    }

    /**
     * 单个岗位的投递结果。
     *
     * <p>区分 SENT / SKIPPED / FAILED 是必须的：只有真正发出招呼语才允许记为”已投递”，
     * 而”页面结构变了没找到按钮”这种从未尝试过的情况不能算投递失败，否则会污染
     * 投递统计，也会让 AI 门禁的去重逻辑误判。</p>
     */
    public enum SubmitResult {
        /** 招呼语已确认发出 */
        SENT,
        /** 从未尝试投递（调试模式、收到停止指令、找不到按钮等），不改写数据库状态 */
        SKIPPED,
        /** 尝试过但没发出去 */
        FAILED,
        QUEUED,
        UNKNOWN,
        RESTRICTED
    }

    /**
     * 投递一个已经从数据库选中的岗位。
     *
     * <p>语义推荐接口使用这个入口，避免重新按配置关键词扫描整页岗位。
     * 传入的岗位链接会优先使用；旧的批量流程没有链接时仍回退到当前列表页
     * 的”查看更多信息”按钮。</p>
     */
    public SubmitResult submitSingle(String keyword, Job job) {
        if (job == null) {
            return SubmitResult.SKIPPED;
        }
        // 单岗投递是用户在页面上显式点击的一次性操作，即使开了调试模式也应该真的发出去，
        // 否则用户会看到”投递失败”，而岗位其实从未被尝试。
        SubmitResult result = withSafetyMonitoring(() -> resumeSubmission(keyword, job, true));
        if (result == SubmitResult.SENT) {
            resultList.add(job);
        }
        return result;
    }

    public SubmitResult submitConfirmed(Job job, String message, boolean sendImgResume) {
        return withSafetyMonitoring(() -> sendPrepared(job, message, sendImgResume));
    }

    /** 为单岗投递预先注册岗位与招聘者的 Boss 身份映射。 */
    public void registerJobIdentity(String encryptId, String encryptUserId) {
        if (encryptId != null && !encryptId.isBlank()
                && encryptUserId != null && !encryptUserId.isBlank()) {
            encryptIdToUserId.put(encryptId, encryptUserId);
        }
    }

    /**
     * 更新黑名单（从聊天记录中）
     */
    public Map<String, Set<String>> updateBlacklistFromChats() {
        page.navigate("https://www.zhipin.com/web/geek/chat");
        PlaywrightUtil.sleep(3);

        int newBlacklistCount = 0;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                Locator bottomLocator = page.locator(FINISHED_TEXT);
                if (bottomLocator.count() > 0 && "没有更多了".equals(bottomLocator.textContent())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }

            Locator items = page.locator(CHAT_LIST_ITEM);
            int itemCount = items.count();

            for (int i = 0; i < itemCount; i++) {
                try {
                    Locator companyElements = page.locator(COMPANY_NAME_IN_CHAT);
                    Locator messageElements = page.locator(LAST_MESSAGE);

                    if (i >= companyElements.count() || i >= messageElements.count()) {
                        break;
                    }

                    String companyName = null;
                    String message = null;
                    int retryCount = 0;

                    while (true) {
                        try {
                            companyName = companyElements.nth(i).textContent();
                            message = messageElements.nth(i).textContent();
                            break;
                        } catch (Exception e) {
                            retryCount++;
                            if (retryCount >= 2) {
                                log.info("尝试获取元素文本2次失败，放弃本次获取");
                                break;
                            }
                            log.info("页面元素已变更，正在重试第{}次获取元素文本...", retryCount);
                            PlaywrightUtil.sleep(1);
                        }
                    }

                    if (companyName != null && message != null) {
                        boolean match = message.contains("不") || message.contains("感谢") || message.contains("但")
                                || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                        boolean nomatch = message.contains("不是") || message.contains("不生");
                        if (match && !nomatch) {
                            if (blackCompanies.stream().anyMatch(companyName::contains)) {
                                continue;
                            }
                            companyName = companyName.replaceAll("\\.{3}", "");
                            if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                                blackCompanies.add(companyName);
                                // 保存到数据库
                                bossService.addBlacklist("company", companyName);
                                newBlacklistCount++;
                                log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...", e);
                }
            }

            try {
                Locator scrollElement = page.locator(SCROLL_LOAD_MORE);
                if (scrollElement.count() > 0) {
                    scrollElement.scrollIntoViewIfNeeded();
                } else {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight);");
                }
            } catch (Exception e) {
                log.error("滚动元素出错", e);
                break;
            }
        }
        log.info("黑名单公司数量：{}，本次新增：{}", (blackCompanies != null ? blackCompanies.size() : 0), newBlacklistCount);

        Map<String, Set<String>> result = new HashMap<>();
        result.put("blackCompanies", new HashSet<>(blackCompanies != null ? blackCompanies : Collections.emptySet()));
        result.put("blackRecruiters", new HashSet<>(blackRecruiters != null ? blackRecruiters : Collections.emptySet()));
        result.put("blackJobs", new HashSet<>(blackJobs != null ? blackJobs : Collections.emptySet()));
        return result;
    }

    private void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        for (String keyword : config.getKeywords()) {
            assertSafe(page);
            if (inspectedJobs >= totalJobBudget) return;
            int searchBudget = MAX_JOBS_PER_SEARCH;
            remainingSearches--;
            int inspectedAtStart = inspectedJobs;
            // 检查是否需要停止
            if (isCancelled()) {
                progressCallback.accept("用户取消投递", 0, 0);
                return;
            }

            int postCount = 0;
            // 使用 URLEncoder 对关键词进行编码
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            String url = searchUrl + (searchUrl.contains("?") ? "&" : "?") + "query=" + encodedKeyword;
            try {
            // Enter once; any restriction or uncertain result stops this run.
            if (!openJobListWithRetry(keyword, url, cityCode)) {
                String message = "【" + keyword + "】没有找到相关职位，跳过当前关键词";
                if (progressCallback != null) progressCallback.accept(message, null, null);
                else log.info(message);
                continue;
            }

            // Load only a bounded number of list batches; this is not a full-site scan.
            int stalledLoadRounds = 0;
            for (int i = 0; i < MAX_LIST_LOAD_ROUNDS; i++) {
                // 停止检查：滚动加载过程中也要及时响应
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", 0, 0);
                    return;
                }
                try {
                    assertSafe(page);
                    int previousCount = countJobCards();
                    if (previousCount >= searchBudget) break;
                    // Browsing and delivery have independent rate limits.
                    beforeBrowse(page, BossBrowseGuard.Action.SCROLL, null);
                    page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * 1.5))");
                    waitSafely(page, 1000);

                    int currentCount = countJobCards();
                    log.info("【{}】滚动加载第 {}/{} 轮，已加载 {} 个岗位", keyword, i + 1,
                            MAX_LIST_LOAD_ROUNDS, currentCount);
                    stalledLoadRounds = currentCount > previousCount ? 0 : stalledLoadRounds + 1;
                    if (stalledLoadRounds >= MAX_STALLED_LIST_LOAD_ROUNDS) {
                        log.info("【{}】连续 {} 轮未加载到更多岗位，使用当前列表", keyword, stalledLoadRounds);
                        break;
                    }
                } catch (Exception e) {
                    if (!isStaleObjectError(e)) {
                        throw e;
                    }
                    assertSafe(page);
                    log.warn("列表结构发生变化，结束预加载，不刷新或追加滚动");
                    break;
                }
            }
            log.info("【{}】列表预加载结束，使用当前已加载岗位", keyword);
            // 统计最终岗位数量
            int loadedCount = countJobCards();
            log.info("【{}】当前已加载岗位数:{}，本关键词新岗位检查额度:{}，重复/已有记录不占额度",
                    keyword, loadedCount, searchBudget);
            progressCallback.accept("岗位加载完成：" + keyword + "，检查至多 " + searchBudget + " 个新岗位", 0, loadedCount);

            // 2. 回到页面顶部
            beforeBrowse(page, BossBrowseGuard.Action.SCROLL, null);
            page.evaluate("window.scrollTo(0, 0);");
            PlaywrightUtil.sleep(1);

            // 3. 逐个遍历所有岗位
            Locator cards = page.locator(JOB_LIST_SELECTOR);
            int count = cards.count();
            for (int i = 0; i < count && inspectedJobs - inspectedAtStart < searchBudget; i++) {
                // 检查是否需要停止
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    progressCallback.accept("用户取消投递", i, count);
                    return;
                }

                // 重新获取卡片，避免元素过期
                cards = page.locator(JOB_LIST_SELECTOR);
                assertSafe(page);
                Locator links = cards.nth(i).locator("a[href*='/job_detail/']");
                if (links.count() == 0) continue;
                String expectedHref = toAbsoluteDetailUrl(links.first().getAttribute("href"));
                String expectedId = extractEncryptId(expectedHref);
                if (expectedId == null) continue;
                if (!inspectedJobIds.add(expectedId)) {
                    duplicateCount++;
                    log.debug("跳过本轮重复岗位，不占新岗位检查额度");
                    continue;
                }
                String existingStatus = deliveryService.statusForJob(sessionScope, expectedHref);
                if (existingStatus != null || bossService.hasConfirmedDelivery(expectedId)) {
                    existingDeliveryCount++;
                    if ("UNKNOWN".equals(existingStatus)) historicalUnknownCount++;
                    log.info("跳过已有投递记录，不重发、不占新岗位检查额度 | status={}",
                            existingStatus != null ? existingStatus : "已投递或待核对");
                    continue;
                }
                inspectedJobs++;
                String detailBody = loadJobDetail(cards, i, count, expectedId);
                assertSafe(page);

                // 统一从请求返回的 JSON 中获取数据并做过滤
                String jobName = null;
                String jobSalary = null;
                java.util.List<String> tags = new java.util.ArrayList<>();
                String jobDesc = null;
                String bossName = null;
                String bossActive = null;
                String bossCompany = null;
                String bossJobTitle = null;

                if (detailBody != null) {
                    try {
                        // 从 JSON 构建用于投递与过滤的字段
                        org.json.JSONObject root = new org.json.JSONObject(detailBody);
                        assertSafe(page);
                        if (root.optInt("code", -1) != 0) throw new IllegalStateException("岗位详情返回非成功业务码");
                        org.json.JSONObject zpData = root.optJSONObject("zpData");
                        org.json.JSONObject jobInfo = zpData != null ? zpData.optJSONObject("jobInfo") : null;
                        org.json.JSONObject brand = zpData != null ? zpData.optJSONObject("brandComInfo") : null;
                        org.json.JSONObject boss = zpData != null ? zpData.optJSONObject("bossInfo") : null;
                        if (jobInfo == null || !expectedId.equals(jobInfo.optString("encryptId"))) {
                            throw new IllegalStateException("详情与当前岗位身份不一致，停止任务");
                        }
                        processJobDetailJsonAndInsert(detailBody);

                        if (jobInfo != null) {
                            jobName = jobInfo.optString("jobName", "");
                            jobSalary = jobInfo.optString("salaryDesc", "");
                            String city = jobInfo.optString("locationName", "");
                            String exp = jobInfo.optString("experienceName", "");
                            String deg = jobInfo.optString("degreeName", "");
                            if (!city.isEmpty()) tags.add(city);
                            if (!exp.isEmpty()) tags.add(exp);
                            if (!deg.isEmpty()) tags.add(deg);
                            jobDesc = jobInfo.optString("postDescription", "");
                        }

                        if (boss != null) {
                            bossName = boss.optString("name", "");
                            bossActive = boss.optString("activeTimeDesc", "");
                            bossJobTitle = boss.optString("title", "");
                        }

                        if (brand != null) {
                            bossCompany = brand.optString("brandName", "");
                        }
                    } catch (BossOperationGate.DeliveryPausedException | CancellationException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException("岗位详情解析失败，未发起沟通", e);
                    }
                }
                if (isBlank(jobName) || isBlank(bossCompany)) {
                    throw new IllegalStateException("岗位身份信息不完整，未发起沟通");
                }

                // 过滤（全部基于 JSON 字段），并输出过滤原因
                if (jobName != null && blackJobs != null && blackJobs.stream().anyMatch(jobName::contains)) {
                    otherFilteredCount++;
                    String term = findMatchedTerm(blackJobs, jobName);
                    log.info("被过滤：职位黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName, term != null ? term : "");
                    continue;
                }
                // HR活跃状态过滤：当开启过滤开关且活跃描述包含“年”时，视为不活跃
                boolean hrInactiveByYear = bossActive != null && bossActive.contains("年");
                if (Boolean.TRUE.equals(config.getFilterDeadHR()) && hrInactiveByYear) {
                    otherFilteredCount++;
                    log.info("被过滤：HR活跃状态包含‘年’ | 公司：{} | 岗位：{} | 活跃：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossActive);
                    continue;
                }
                if (bossCompany != null && blackCompanies != null && blackCompanies.stream().anyMatch(bossCompany::contains)) {
                    otherFilteredCount++;
                    String term = findMatchedTerm(blackCompanies, bossCompany);
                    log.info("被过滤：公司黑名单命中 | 公司：{} | 岗位：{} | 关键词：{}", bossCompany, jobName != null ? jobName : "", term != null ? term : "");
                    continue;
                }
                if (bossJobTitle != null && blackRecruiters != null && blackRecruiters.stream().anyMatch(bossJobTitle::contains)) {
                    otherFilteredCount++;
                    String term = findMatchedTerm(blackRecruiters, bossJobTitle);
                    log.info("被过滤：招聘者黑名单命中 | 公司：{} | 岗位：{} | 招聘者：{} | 关键词：{}", bossCompany != null ? bossCompany : "", jobName != null ? jobName : "", bossJobTitle, term != null ? term : "");
                    continue;
                }

                // 薪资过滤必须发生在发起聊天之前。岗位仍会在 processJobDetailJsonAndInsert 中入库，
                // 但不满足活动画像/旧配置范围的岗位不会进入自动投递。
                if (isSalaryNotExpected(jobSalary)) {
                    salaryFilteredCount++;
                    log.info("被过滤：薪资不符合期望 | 公司：{} | 岗位：{} | 薪资：{} | 目标：{} {}",
                            bossCompany != null ? bossCompany : "", jobName != null ? jobName : "",
                            jobSalary != null ? jobSalary : "", config.getExpectedSalary(), config.getExpectedSalaryUnit());
                    continue;
                }

                // 创建Job对象（全部基于 JSON 字段）
                Job job = new Job();
                job.setHref(expectedHref);
                job.setJobName(jobName != null ? jobName : "");
                job.setSalary(jobSalary != null ? jobSalary : "");
                job.setJobArea(String.join(", ", tags));
                job.setCompanyName(bossCompany != null ? bossCompany : "");
                job.setRecruiter(bossName != null ? bossName : "");
                job.setJobInfo(jobDesc != null ? jobDesc : "");

                // 可选的 AI 相关性门禁：只有 apply 才进入自动投递，review/skip 均不会
                // 触发聊天，避免模型不确定或岗位低匹配时产生不可逆的外部操作。
                if (Boolean.TRUE.equals(config.getEnableAiRelevance())) {
                    if (progressCallback != null) {
                        progressCallback.accept("正在等待AI评分（此时官网列表不需要变化）：" + jobName, i + 1, count);
                    }
                    JobRelevanceService.Result relevance = waitForAiScore(job, i + 1, count);
                    jobRelevancePersistenceService.save(job, relevance);
                    String decision = relevance.decision();
                    if (!"apply".equalsIgnoreCase(decision)) {
                        aiFilteredCount++;
                        String message = String.format("AI匹配%s（%d分）：%s，跳过自动投递：%s",
                                decision, relevance.score(), relevance.reason(), jobName);
                        if (progressCallback != null) {
                            progressCallback.accept(message, i + 1, count);
                        } else {
                            log.info(message);
                        }
                        continue;
                    }
                    log.info("AI匹配通过（{}分），继续投递：{}", relevance.score(), jobName);
                }

                // 输出
                progressCallback.accept("正在投递：" + jobName, i + 1, count);
                // 只有真正发出招呼语才计入已投递数量；被跳过（没找到按钮/输入框等）
                // 的岗位不能算投递成功，否则统计会严重虚高。
                // resultList 是 execute() 的返回值来源，批量路径必须一并登记。
                SubmitResult submitResult = resumeSubmission(keyword, job);
                if (submitResult == SubmitResult.SENT) {
                    postCount++;
                    resultList.add(job);
                } else if (submitResult == SubmitResult.QUEUED) {
                    queuedCount++;
                } else if (submitResult == SubmitResult.FAILED) {
                    failedCount++;
                } else if (submitResult == SubmitResult.SKIPPED) {
                    otherFilteredCount++;
                }
                // A missing receipt belongs to this job, not the whole account.
                // Real restrictions raised during sending still take precedence.
                assertSafe(page);
                if (submitResult == SubmitResult.RESTRICTED) {
                    throw new BossOperationGate.DeliveryPausedException("平台要求登录、验证或限制操作，请在官方页面处理");
                }
                if (submitResult == SubmitResult.UNKNOWN) {
                    unconfirmedCount++;
                    String message = "本岗位发送结果待核对，不重复发送；继续处理下一岗位：" + jobName;
                    log.warn(message);
                    if (progressCallback != null) progressCallback.accept(message, i + 1, count);
                }

                // 为避免点击下面的卡片触发页面刷新：在点击5个卡片之后，每次点击后适度下滑
                try {
                    if (i >= 5) {
                        beforeBrowse(page, BossBrowseGuard.Action.SCROLL, null);
                        page.evaluate("window.scrollBy(0, 140);");
                        PlaywrightUtil.sleep(1);
                    }
                } catch (BossOperationGate.DeliveryPausedException | CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("岗位列表滚动失败，停止检查页面", e);
                }

                // 投递间隔在对话框填好文字后统一等待，此处只检查页面状态。
                assertSafe(page);

                // 停顿期间可能已经被弹到安全验证页，发现了就停下来等人工过验证，
                // 而不是继续闷头点下去（继续点只会让后面的关键词全部失败）
                if (isSecurityVerifyUrl(safeUrl())) {
                    log.warn("【{}】遍历过程中被跳转到安全验证页：{}", keyword, safeUrl());
                    if (progressCallback != null) {
                        progressCallback.accept("触发Boss安全校验，请在浏览器中手动完成验证", i + 1, count);
                    }
                    waitForSliderVerify(page);
                }
            }
            String summary = String.format("【%s】本轮检查结束：新检查 %d 个，确认发送 %d 条；%s",
                    keyword, inspectedJobs - inspectedAtStart, postCount,
                    remainingSearches > 0 ? "继续下一个搜索" : "本轮搜索已处理完");
            if (progressCallback != null) progressCallback.accept(summary, null, null);
            else log.info(summary);
            } catch (BossOperationGate.DeliveryPausedException | CancellationException e) {
                throw e;
            } catch (Exception e) {
                operationGate.hold(sessionScope, "列表或详情异常，请检查后人工恢复");
                log.warn("Boss任务停止 | type={}", e.getClass().getSimpleName());
                if (progressCallback != null) {
                    progressCallback.accept("任务已暂停，请检查页面和日志，不自动重试", 0, 0);
                }
                throw e;
            }
        }
    }

    private JobRelevanceService.Result waitForAiScore(Job job, int current, int total) {
        long failures = 0;
        while (true) {
            beforeOperation(page);
            try {
                JobRelevanceService.Result result = jobRelevanceService.evaluate(job, config.getAiIntroduce(),
                        config.getAiPrompt(), config.getJobIntent(), config.getMinAiScore());
                beforeOperation(page);
                if (result == null || !result.aiUsed() || result.fallback()) {
                    throw new JobRelevanceService.AiScoringUnavailableException("未取得有效AI评分");
                }
                if (failures > 0 && progressCallback != null) {
                    progressCallback.accept("AI评分已恢复，继续处理当前岗位：" + job.getJobName(), current, total);
                }
                return result;
            } catch (JobRelevanceService.AiScoringUnavailableException e) {
                beforeOperation(page);
                failures++;
                String message = String.format("AI评分失败（第 %d 次），等待 %d 秒后重试当前岗位：%s；不使用本地匹配、不跳过，可点击停止",
                        failures, AI_SCORE_RETRY_MILLIS / 1000, job.getJobName());
                log.warn("{} | reason={}", message, e.getMessage());
                if (progressCallback != null) progressCallback.accept(message, current, total);
                waitForAiRetry(AI_SCORE_RETRY_MILLIS);
            }
        }
    }

    void waitForAiRetry(long milliseconds) {
        waitSafely(page, milliseconds);
        beforeOperation(page);
    }

    /** Reuse verified metadata before initiating another remote detail request. */
    private String loadJobDetail(Locator cards, int index, int count, String expectedId) {
        String cached = browseGuard.cachedDetail(expectedId);
        if (cached != null) {
            log.info("复用有效期内的本地岗位详情，不发起详情请求");
            return cached;
        }
        try {
            if (index == 0 && count > 1) {
                // A default-selected first card may not issue another request on click.
                beforeBrowse(page, BossBrowseGuard.Action.DETAIL, null);
                cards.nth(1).click();
                waitSafely(page, 250);
            }
            beforeBrowse(page, BossBrowseGuard.Action.DETAIL, null);
            Response response = page.waitForResponse(r -> {
                try {
                    return r.url() != null && r.url().contains("/wapi/zpgeek/job/detail.json")
                            && "GET".equalsIgnoreCase(r.request().method());
                } catch (RuntimeException ignored) { return false; }
            }, new Page.WaitForResponseOptions().setTimeout(10_000), cards.nth(index)::click);
            inspectResponse(response);
            assertSafe(page);
            JSONObject body = new JSONObject(response.text());
            browseGuard.cacheDetail(expectedId, body);
            return body.toString();
        } catch (BossOperationGate.DeliveryPausedException | CancellationException e) {
            throw e;
        } catch (Exception e) {
            assertSafe(page);
            throw new IllegalStateException("岗位详情未能可靠获取，停止任务检查页面", e);
        }
    }

    private void processJobDetailJsonAndInsert(String body) {
        if (body == null || body.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) return;

            JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            JSONObject brand = zpData.optJSONObject("brandComInfo");
            JSONObject bossInfo = zpData.optJSONObject("bossInfo");
            if (jobInfo == null) return;

            String encryptId = jobInfo.optString("encryptId", null);
            String encryptUserId = jobInfo.optString("encryptUserId", null);
            if (encryptUserId == null && bossInfo != null) {
                // 兼容部分页面字段落在 bossInfo 内
                encryptUserId = bossInfo.optString("encryptUserId", null);
                if (encryptUserId == null) {
                    // 进一步兼容可能的字段命名
                    encryptUserId = bossInfo.optString("encryptBossId", null);
                }
            }
            if (encryptId != null && encryptUserId != null) {
                encryptIdToUserId.put(encryptId, encryptUserId);
            }

            com.getjobs.application.entity.BossJobDataEntity entity = new com.getjobs.application.entity.BossJobDataEntity();
            entity.setJobName(jobInfo.optString("jobName", null));
            entity.setSalary(jobInfo.optString("salaryDesc", null));
            entity.setLocation(jobInfo.optString("locationName", null));
            entity.setExperience(jobInfo.optString("experienceName", null));
            entity.setDegree(jobInfo.optString("degreeName", null));
            entity.setJobDescription(jobInfo.optString("postDescription", null));
            entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
            entity.setCompanyAddress(jobInfo.optString("address", null));
            entity.setEncryptId(encryptId);
            entity.setEncryptUserId(encryptUserId);

            entity.setCompanyName(brand != null ? brand.optString("brandName", null) : null);
            entity.setIndustry(brand != null ? brand.optString("industryName", null) : null);
            entity.setIntroduce(brand != null ? brand.optString("introduce", null) : null);
            entity.setFinancingStage(brand != null ? brand.optString("stageName", null) : null);
            entity.setCompanyScale(brand != null ? brand.optString("scaleName", null) : null);

            entity.setHrName(bossInfo != null ? bossInfo.optString("name", null) : null);
            entity.setHrPosition(bossInfo != null ? bossInfo.optString("title", null) : null);
            entity.setHrActiveStatus(bossInfo != null ? bossInfo.optString("activeTimeDesc", null) : null);

            if (encryptId != null && !encryptId.isEmpty()) {
                entity.setJobUrl("https://www.zhipin.com/job_detail/" + encryptId + ".html");
            }

            // 黑名单处理
            boolean filtered = false;
            String companyName = entity.getCompanyName() != null ? entity.getCompanyName() : "";
            String positionName = entity.getJobName() != null ? entity.getJobName() : "";
            String hrPosition = entity.getHrPosition() != null ? entity.getHrPosition() : "";
            try {
                if (blackCompanies != null && blackCompanies.stream().anyMatch(companyName::contains)) filtered = true;
                if (!filtered && blackJobs != null && blackJobs.stream().anyMatch(positionName::contains)) filtered = true;
                if (!filtered && blackRecruiters != null && blackRecruiters.stream().anyMatch(hrPosition::contains)) filtered = true;
            } catch (Throwable ignore) {}

            // HR活跃状态过滤：开启过滤且活跃描述包含“年”，则标记为已过滤，但仍入库
            if (!filtered && Boolean.TRUE.equals(config.getFilterDeadHR())) {
                String hrActive = entity.getHrActiveStatus();
                if (hrActive != null && hrActive.contains("年")) {
                    filtered = true;
                }
            }

            if (!filtered && isSalaryNotExpected(entity.getSalary())) {
                filtered = true;
            }

            entity.setDeliveryStatus(filtered ? "已过滤" : "未投递");

            // 入库（若不存在），优先以 encrypt_id + encrypt_user_id 去重；若 userId 缺失，则以 encrypt_id 去重
            if (encryptId != null) {
                try {
                    boolean exists = false;
                    if (encryptUserId != null) {
        exists = bossService.existsBossJob(encryptId, encryptUserId);
                    } else {
        exists = bossService.existsBossJobByEncryptId(encryptId);
                    }
                    if (!exists) {
        bossService.insertBossJob(entity);
                        log.debug("岗位入库：{} | 公司：{} | HR：{} | 状态：{}", entity.getJobName(), entity.getCompanyName(), entity.getHrName(), entity.getDeliveryStatus());
                    }
                } catch (Exception e) {
                    log.warn("岗位入库失败：{}", e.getMessage());
                }
            }
        } catch (Throwable e) {
            log.debug("解析岗位详情 JSON 失败：{}", e.getMessage());
        }
    }

    public String decodeSalary(String text) {
        Map<Character, Character> fontMap = new HashMap<>();
        fontMap.put('\uE8F0', '0');
        fontMap.put('\uE8F1', '1');
        fontMap.put('\uE8F2', '2');
        fontMap.put('\uE8F3', '3');
        fontMap.put('\uE8F4', '4');
        fontMap.put('\uE8F5', '5');
        fontMap.put('\uE8F6', '6');
        fontMap.put('\uE8F7', '7');
        fontMap.put('\uE8F8', '8');
        fontMap.put('\uE8F9', '9');
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            result.append(fontMap.getOrDefault(c, c));
        }
        return result.toString();
    }

    // 安全获取单个文本内容
    public String safeText(Locator root, String selector) {
        Locator node = root.locator(selector);
        try {
            if (node.count() > 0 && node.innerText() != null) {
                return node.innerText().trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    // 安全获取多个文本内容
    public List<String> safeAllText(Locator root, String selector) {
        try {
            return root.locator(selector).allInnerTexts();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // Boss姓名+活跃状态拆分
    public String[] splitBossName(String raw) {
        String[] bossParts = raw.trim().split("\\s+");
        String bossName = bossParts[0];
        String bossActive = bossParts.length > 1 ? String.join(" ", Arrays.copyOfRange(bossParts, 1, bossParts.length)) : "";
        return new String[]{bossName, bossActive};
    }

    // Boss公司+职位拆分
    public String[] splitBossTitle(String raw) {
        String[] parts = raw.trim().split(" · ");
        String company = parts[0];
        String job = parts.length > 1 ? parts[1] : "";
        return new String[]{company, job};
    }

    // 匹配命中词条（用于日志输出过滤原因）
    private String findMatchedTerm(java.util.Collection<String> patterns, String text) {
        if (patterns == null || text == null) return null;
        try {
            for (String p : patterns) {
                if (p != null && !p.isEmpty() && text.contains(p)) {
                    return p;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    public static String buildSearchUrl(BossConfig config, String cityCode) {
        String baseUrl = "https://www.zhipin.com/web/geek/jobs";
        if (config == null) {
            return baseUrl;
        }
        List<String> params = new ArrayList<>();
        addParam(params, JobUtils.appendParam("city", cityCode));
        addParam(params, JobUtils.appendParam("jobType", config.getJobType()));
        addParam(params, JobUtils.appendListParam("salary", config.getSalary()));
        addParam(params, JobUtils.appendListParam("experience", config.getExperience()));
        addParam(params, JobUtils.appendListParam("degree", config.getDegree()));
        addParam(params, JobUtils.appendListParam("scale", config.getScale()));
        addParam(params, JobUtils.appendListParam("industry", config.getIndustry()));
        addParam(params, JobUtils.appendListParam("stage", config.getStage()));
        if (params.isEmpty()) {
            return baseUrl;
        }
        return baseUrl + "?" + String.join("&", params);
    }

    private static void addParam(List<String> params, String param) {
        if (param == null || param.isEmpty()) {
            return;
        }
        params.add(param.startsWith("&") ? param.substring(1) : param);
    }

    private String getSearchUrl(String cityCode) {
        return buildSearchUrl(config, cityCode);
    }

    /**
     * 进入岗位列表页。
     * <p>
     * 优先模拟真人路径：回首页 -> 在搜索框里逐字输入关键词 -> 点搜索按钮，
     * 而不是冷启动直接把拼好的列表页 URL 丢给浏览器。
     * <p>
     * UI 搜索只能带上关键词和首页当前城市，config 里的城市/薪资/经验等筛选项仍然只能靠 URL，
     * 所以落地之后如果条件对不上，会再做一次同标签页跳转 —— 此时已经是热会话 + 同源 referer，
     * 和冷启动直闯不是一回事。UI 搜索任何一步失败都会退回原来的直接跳转，不影响主流程。
     */
    private void openJobList(String keyword, String targetUrl, String cityCode) {
        boolean uiSearchDone = USE_UI_SEARCH && searchFromHomePage(keyword);
        if (!uiSearchDone) {
            navigateToJobList(targetUrl);
        } else if (needsUrlFilters(targetUrl, cityCode)) {
            log.info("【{}】UI搜索已落地，补充筛选条件跳转：{}", keyword, targetUrl);
            navigateToJobList(targetUrl);
        }
        warnIfSecurityCheck(keyword);
    }

    /**
     * 在 Boss 首页搜索框里做一次真实的 UI 搜索。
     *
     * @return 是否成功落到岗位列表页
     */
    private boolean searchFromHomePage(String keyword) {
        try {
            String current = page.url();
            if (current == null || !current.startsWith(BOSS_HOME_URL) || current.contains("/web/geek/")) {
                navigateTo(BOSS_HOME_URL);
            }
            // 首页落地后还会自己跳一次（/shanghai/?seoRefer=index 之类），
            // 不等它跳完就找搜索框，会卡在 "waiting for navigation to finish" 直到超时
            waitForPageSettled();

            Locator input = page.locator(HOME_SEARCH_INPUT).first();
            input.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
            input.click();
            input.fill("");
            // 逐字输入，模拟真人打字节奏；一次性 fill 在输入行为层面太干净了
            input.pressSequentially(keyword, new Locator.PressSequentiallyOptions().setDelay(140));
            PlaywrightUtil.sleep(1);

            Locator searchBtn = page.locator(HOME_SEARCH_BUTTON).first();
            if (searchBtn.count() > 0) {
                searchBtn.click();
            } else {
                input.press("Enter");
            }
            // 表单提交是当前标签页跳转；若 Boss 改成新开标签页，这里会超时并退回直接跳转
            page.waitForURL("**/web/geek/jobs**", new Page.WaitForURLOptions().setTimeout(15_000));
            log.info("【{}】已通过首页搜索框进入岗位列表：{}", keyword, page.url());
            return true;
        } catch (Exception e) {
            log.warn("【{}】首页UI搜索失败，退回直接跳转：{}", keyword, e.getMessage());
            return false;
        }
    }

    /**
     * UI 搜索落地后，判断还需不需要用 URL 把筛选条件补上。
     */
    private boolean needsUrlFilters(String targetUrl, String cityCode) {
        String landed = page.url();
        if (landed == null) {
            return true;
        }
        // 首页搜索用的是首页当前城市，不一定等于配置里的城市
        if (cityCode != null && !cityCode.isEmpty() && !landed.contains("city=" + cityCode)) {
            return true;
        }
        // 除 city/query 外还有别的筛选项（薪资、经验、学历……），UI 搜索带不上
        int queryStart = targetUrl.indexOf('?');
        if (queryStart < 0) {
            return false;
        }
        for (String param : targetUrl.substring(queryStart + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (value.isEmpty() || "city".equals(name) || "query".equals(name)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void navigateToJobList(String url) {
        navigateTo(url);
    }

    /** Failed navigation must not turn into an automatic reload of a blocked page. */
    private void navigateTo(String url) {
        // Boss 的 SPA 在网络不佳或被限流时，15 秒常常不够，之前实测连着两次都超时
        Page.NavigateOptions options = new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(45_000);
        beforeBrowse(page, BossBrowseGuard.Action.SEARCH, url);
        try {
            inspectResponse(page.navigate(url, options));
            assertSafe(page);
        } catch (BossOperationGate.DeliveryPausedException | CancellationException e) {
            throw e;
        } catch (Exception first) {
            assertSafe(page);
            // 导航期间页面自己又跳了一次时，Playwright 会抛 "Object doesn't exist: request@/frame@"，
            // 但页面其实已经到位了。先看落地 URL，别白白重试一遍。
            if (isStaleObjectError(first) && landedOn(url)) {
                log.debug("导航报了失效对象异常但页面已到位，忽略：{}", first.getMessage());
                settleAfterNavigation();
                return;
            }
            throw first;
        }
        settleAfterNavigation();
    }

    /**
     * 判断页面是否已经落在目标地址上（只比较路径，查询参数会被 Boss 改写）。
     */
    private boolean landedOn(String targetUrl) {
        try {
            String current = page.url();
            if (current == null) {
                return false;
            }
            String targetPath = targetUrl.split("\\?")[0];
            return current.startsWith(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 等页面真正稳定下来再继续。
     * <p>
     * DOMCONTENTLOADED 返回之后 Boss 还会自己做客户端跳转，此时 frame 被替换，
     * 后续任何 locator 调用都会报 "Object doesn't exist: frame@..."。
     */
    private void settleAfterNavigation() {
        waitForPageSettled();
    }

    /**
     * 等页面进入 domcontentloaded，最多等 {@value #LOAD_STATE_TIMEOUT} 毫秒。
     * <p>
     * 两个坑都踩过了：
     * 一是不能等 LOAD/NETWORKIDLE —— Boss 是 SPA + WebSocket 长连接，这两个状态可能永远不到；
     * 二是必须显式给超时 —— 不给超时就是无限期挂起，而"卡住"不是异常，
     * 外面包 try/catch 完全没用（实测把线程挂了两分钟以上还在等）。
     */
    private void waitForPageSettled() {
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception ignore) {
            // 等不到就算了，后面的 locator 调用自己有超时
        }
        waitSafely(page, 250);
    }

    /**
     * 判断是不是 Playwright 的"对象已失效"异常。
     * <p>
     * 页面频繁增删 frame 时，Playwright Java 在派发事件时会引用到已经销毁的对象，
     * 抛 "Object doesn't exist: frame@..."。这个异常和当时在飞的那个调用没有因果关系，
     * 连接本身仍然可用，重试即可。
     */
    private static boolean isStaleObjectError(Throwable e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        // 两类都是"页面在动"导致的瞬时异常，跟调用本身没有因果关系，重试就好：
        // - Object doesn't exist: frame@/request@  事件派发时引用到已销毁的对象
        // - Execution context was destroyed         求值期间页面发生了导航
        return message.contains("Object doesn't exist")
                || message.contains("Execution context was destroyed");
    }

    /**
     * 进入岗位列表并等列表渲染出来，整段带重试。
     * Boss 首页/列表页在登录态下会连着跳好几次，一次失败很正常。
     */
    private boolean openJobListWithRetry(String keyword, String targetUrl, String cityCode) {
        openJobList(keyword, targetUrl, cityCode);
        assertSafe(page);
        return waitForJobList();
    }

    /**
     * 统计当前列表里的岗位卡片数量，对失效对象异常做重试。
     */
    private int countJobCards() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return page.locator(JOB_LIST_SELECTOR).count();
            } catch (Exception e) {
                assertSafe(page);
                if (!isStaleObjectError(e) || attempt == 3) {
                    if (isStaleObjectError(e)) {
                        log.warn("统计岗位数量始终失败，按 0 处理：{}", e.getMessage());
                        return 0;
                    }
                    throw e;
                }
                PlaywrightUtil.sleep(1);
            }
        }
        return 0;
    }

    /**
     * 等待岗位列表容器出现，带重试。
     * 页面在这期间可能还在跳转，一次失败不代表真的没有列表。
     */
    private boolean waitForJobList() {
        // Boss 改版频繁，推荐页和搜索结果页的容器类名不一样。
        // 用逗号把候选选择器拼成一个，让 Playwright 一次性等"任意一个先出现"，
        // 不要逐个 8 秒串行试 —— 那样一轮就要 48 秒，页面正常时也慢得像卡死。
        String containers = String.join(", ",
                "ul.rec-job-list",
                "ul.job-list-box",
                ".job-list-box",
                ".search-job-result",
                "li.job-card-box",
                "li.job-card-wrapper",
                EMPTY_JOB_LIST_SELECTOR);

        for (int attempt = 1; attempt <= 1; attempt++) {
            try {
                page.waitForSelector(containers,
                        new Page.WaitForSelectorOptions().setTimeout(15_000));
                assertSafe(page);
                if (hasNoJobResults(page)) return false;
                log.info("岗位列表容器已出现（第{}次尝试）", attempt);
                return true;
            } catch (Exception e) {
                assertSafe(page);
                if (hasNoJobResults(page)) return false;
                log.warn("等待岗位列表失败，当前页面: {}", safeUrl());
                settleAfterNavigation();
            }
        }
        dumpListPageStructure();
        throw new IllegalStateException("岗位列表始终未出现，当前页面: " + safeUrl());
    }

    boolean hasNoJobResults(Page target) {
        if (target.locator(JOB_LIST_SELECTOR).count() > 0) return false;
        Locator empty = target.locator(EMPTY_JOB_LIST_SELECTOR);
        for (int i = 0; i < empty.count(); i++) {
            if (empty.nth(i).isVisible()) return true;
        }
        return false;
    }

    private String safeUrl() {
        try {
            return page.url();
        } catch (Exception e) {
            return "(取不到URL)";
        }
    }

    /**
     * 列表容器一个都没命中时，把页面上的候选列表结构打出来，方便修选择器。
     */
    private void dumpListPageStructure() {
        try {
            Object info = page.evaluate("""
                    () => {
                      const uls = Array.from(document.querySelectorAll('ul,div'))
                        .filter(el => el.className && typeof el.className === 'string'
                                   && /job|list|rec/i.test(el.className))
                        .slice(0, 15)
                        .map(el => el.tagName.toLowerCase() + '.' + el.className.trim().replace(/\\s+/g, '.')
                                 + ' (children=' + el.childElementCount + ')');
                      return { title: document.title, url: location.href, candidates: uls };
                    }""");
            log.warn("列表页结构快照: {}", info);
        } catch (Exception e) {
            log.warn("抓取列表页结构失败: {}", e.getMessage());
        }
    }

    /**
     * 落地后检查是否被弹到风控/验证页，日志里能直接看出是哪一步触发的。
     */
    private void warnIfSecurityCheck(String keyword) {
        String url = page.url();
        if (url == null) {
            return;
        }
        if (isSecurityVerifyUrl(url)) {
            log.warn("【{}】进入岗位列表时被跳转到验证页：{}", keyword, url);
            if (progressCallback != null) {
                progressCallback.accept("触发Boss安全校验，请手动完成验证", 0, 0);
            }
            waitForSliderVerify(page);
        }
    }

    private <T> T withSafetyMonitoring(Supplier<T> action) {
        Consumer<Response> listener = this::inspectResponse;
        var context = page.context();
        context.onResponse(listener);
        try {
            return action.get();
        } finally {
            context.offResponse(listener);
        }
    }

    private boolean isCancelled() {
        return Thread.currentThread().isInterrupted()
                || (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get()));
    }

    private void beforeOperation(Page target) {
        assertSafe(target);
        if (sessionScope == null || sessionScope.isBlank()) {
            throw new IllegalStateException("No authenticated Boss session");
        }
        if (!Objects.equals(sessionScope, playwrightManager.getBossSessionScope())) {
            operationGate.hold(sessionScope, "登录会话已经变化，请重新生成待确认消息");
            throw new BossOperationGate.DeliveryPausedException("登录会话已变化");
        }
    }

    private void beforeBrowse(Page target, BossBrowseGuard.Action action, String searchUrl) {
        beforeOperation(target);
        lastAction = action.name();
        try {
            browseGuard.await(action, searchUrl, this::isCancelled, millis -> waitSafely(target, millis));
        } catch (BossBrowseGuard.BrowsePausedException e) {
            operationGate.hold(sessionScope, e.getMessage(), e.retryAfterMillis());
            throw e;
        }
        beforeOperation(target);
    }

    private void beforeDelivery(Page target) {
        beforeOperation(target);
        lastAction = "DELIVERY";
        int intervalSeconds = resolveWaitTimeSeconds(ThreadLocalRandom.current());
        String message = "招呼语已填入对话框，完整等待 " + intervalSeconds + " 秒后点击发送";
        if (progressCallback != null) progressCallback.accept(message, null, null);
        else log.info(message);
        operationGate.awaitAfterPreparation(sessionScope, intervalSeconds, this::isCancelled,
                millis -> waitSafely(target, millis));
        assertSafe(target);
        if (!Objects.equals(sessionScope, playwrightManager.getBossSessionScope())) {
            operationGate.hold(sessionScope, "等待期间登录会话发生变化，请重新生成草稿");
            throw new BossOperationGate.DeliveryPausedException("登录会话已变化");
        }
    }

    private void waitSafely(Page target, long milliseconds) {
        long deadline = System.nanoTime() + milliseconds * 1_000_000L;
        do {
            assertSafe(target);
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) return;
            target.waitForTimeout(Math.min(remaining, 250));
        } while (true);
    }

    private void assertSafe(Page target) {
        if (isCancelled()) throw new CancellationException("Boss task cancelled");
        // Continuous automatic batches have no wall-clock cutoff; individual operations stay bounded.
        if (!automaticBatch && System.nanoTime() - createdAtNanos >= MAX_RUN_NANOS) {
            operationGate.hold(sessionScope, "达到单次任务30分钟预算，请人工检查后恢复");
            throw new BossOperationGate.DeliveryPausedException("任务时长预算已用尽");
        }
        String held = operationGate.holdReason(sessionScope);
        if (held != null) throw new BossOperationGate.DeliveryPausedException(held);
        if (target == null || target.isClosed()) throw new IllegalStateException("Boss page unavailable");
        String url = target.url();
        if (hasVisibleRestriction(target)) {
            pauseForRestriction("页面提示访问受限、操作限制或额度已用尽，请在官方页面处理",
                    url, null, null, 0);
            throw new BossOperationGate.DeliveryPausedException("平台访问或操作受限，不是普通登录失效");
        }
        if (isSecurityVerifyUrl(url) || url.contains("/web/user/") || url.contains("/web/passport/")) {
            pauseForRestriction("需要在官方页面完成登录或安全验证", url, null, null, 0);
            throw new BossOperationGate.DeliveryPausedException("需要人工登录或验证");
        }
    }

    boolean hasVisibleRestriction(Page target) {
        return BossPageSafety.hasVisibleRestriction(target);
    }

    private void inspectResponse(Response response) {
        if (response == null) return;
        try {
            URI uri = URI.create(response.url());
            if (!"www.zhipin.com".equalsIgnoreCase(uri.getHost())) return;
            if (!uri.getPath().startsWith("/wapi/")
                    && !"document".equals(response.request().resourceType())) return;
            int status = response.status();
            if (status == 429 || status == 401 || status == 403 || status == 405) {
                long retryAfter = retryAfterMillis(response.headerValue("Retry-After"));
                pauseForRestriction("平台返回认证、访问或频率限制（HTTP " + status + "）",
                        response.url(), status, null, retryAfter);
                return;
            }
            if (!uri.getPath().startsWith("/wapi/")) return;
            String type = response.headerValue("Content-Type");
            if (type == null || !type.contains("json")) return;
            JSONObject body = new JSONObject(response.text());
            String message = body.optString("message", body.optString("msg", ""));
            int code = body.optInt("code", -1);
            if (code != 0 && (BossPageSafety.isRestrictionMessage(message)
                    || message.contains("登录") || message.contains("验证"))) {
                pauseForRestriction("平台业务响应要求登录、验证或限制操作", response.url(), status, code,
                        retryAfterMillis(response.headerValue("Retry-After")));
            } else if (code == 0 && uri.getPath().equals("/wapi/zpgeek/job/detail.json")) {
                JSONObject data = body.optJSONObject("zpData");
                JSONObject job = data == null ? null : data.optJSONObject("jobInfo");
                if (job != null) browseGuard.cacheDetail(job.optString("encryptId"), body);
            }
        } catch (Exception e) {
            // Do not log response bodies or let event callbacks retry an operation.
            log.debug("无法分类平台响应 | type={}", e.getClass().getSimpleName());
        }
    }

    private void pauseForRestriction(String reason, String url, Integer status, Integer code, long retryAfter) {
        log.warn("Boss安全暂停 | action={} | path={} | http={} | code={} | retryAfterMs={}",
                lastAction, diagnosticPath(url), status, code, retryAfter);
        operationGate.hold(sessionScope, reason, retryAfter);
    }

    static String diagnosticPath(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "(unknown)" : path.replaceAll("/job_detail/[^/]+", "/job_detail/{id}");
        } catch (RuntimeException e) {
            return "(unknown)";
        }
    }

    static long retryAfterMillis(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.min(Long.MAX_VALUE / 1000, Math.max(0, Long.parseLong(value.trim()))) * 1000;
        } catch (NumberFormatException ignored) {
            try {
                long timestamp = java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return Math.max(0, timestamp - System.currentTimeMillis());
            } catch (Exception invalid) { return 0; }
        }
    }

    /** 单岗投递（批量流程入口），保留调试模式开关。 */
    private SubmitResult resumeSubmission(String keyword, Job job) {
        return resumeSubmission(keyword, job, false);
    }

    /**
     * 备注：目前Boss无法通过新标签页打开立即沟通按钮，所以只能点击更多详情，然后从更多详情里打开聊天按钮
     *
     * <p>整个流程包在 try/catch/finally 里：单个岗位的任何异常都不能掀翻整批投递，
     * 且新开的详情页必须在 finally 中关闭，否则异常路径会持续泄漏浏览器标签页。
     * 返回值区分 SENT / SKIPPED / FAILED，供调用方决定是否计数与回写状态。</p>
     *
     * @param ignoreDebug 单岗手动投递时忽略调试开关（调试模式只用于批量试跑）
     */
    private SubmitResult resumeSubmission(String keyword, Job job, boolean ignoreDebug) {
        if (job == null) {
            return SubmitResult.SKIPPED;
        }
        // 若收到停止指令，直接短路返回
        if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
            log.info("停止指令已触发，跳过投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return SubmitResult.SKIPPED;
        }
        // 调试模式：仅遍历不投递
        if (!ignoreDebug && Boolean.TRUE.equals(config.getDebugger())) {
            log.info("调试模式：仅遍历岗位，不投递 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
            return SubmitResult.SKIPPED;
        }

        // 1. 语义推荐的单岗流程直接使用数据库中的岗位链接；批量流程仍从
        // 当前列表页读取“查看更多信息”按钮，保持原有行为。
        String href = job.getHref();
        if (href == null || href.isBlank()) {
            Locator moreInfoBtn = page.locator("a.more-job-btn");
            if (moreInfoBtn.count() == 0) {
                log.warn("未找到\"查看更多信息\"按钮，跳过...");
                return SubmitResult.SKIPPED;
            }
            href = moreInfoBtn.first().getAttribute("href");
        }
        String detailUrl = toAbsoluteDetailUrl(href);
        if (detailUrl == null) {
            log.warn("未获取到合法的岗位详情链接，跳过 | 岗位：{} | href：{}", job.getJobName(), href);
            return SubmitResult.SKIPPED;
        }

        job.setHref(detailUrl);
        if (bossService.hasConfirmedDelivery(extractEncryptId(detailUrl))) return SubmitResult.SKIPPED;
        boolean automatic = "AUTO".equalsIgnoreCase(config.getDeliveryMode());
        BossDeliveryService.DeliveryDraft draft = automatic
                ? deliveryService.prepareAndClaimAutomatic(sessionScope, job, buildGreetingMessage(keyword, job),
                    Boolean.TRUE.equals(config.getSendImgResume()))
                : deliveryService.prepare(sessionScope, job, buildGreetingMessage(keyword, job),
                    Boolean.TRUE.equals(config.getSendImgResume()));
        if (draft == null) return SubmitResult.SKIPPED;
        if (!automatic && !"PENDING".equals(draft.status())) {
            // A processed draft must not recreate an account-wide hold after review.
            return SubmitResult.SKIPPED;
        }
        if (!automatic) {
            persistDeliveryStatus(detailUrl, SubmitResult.QUEUED);
            if (progressCallback != null) progressCallback.accept("已加入待确认：" + job.getJobName(), null, null);
            return SubmitResult.QUEUED;
        }
        SubmitResult result;
        try {
            result = sendPrepared(job, draft.message(), draft.sendImgResume());
        } catch (CancellationException e) {
            deliveryService.finish(draft.id(), "FAILED", "发送前用户取消，未自动重试");
            throw e;
        } catch (RuntimeException e) {
            deliveryService.finish(draft.id(), "UNKNOWN", "执行异常，请在官方界面核对，不自动重发");
            operationGate.hold(sessionScope, "发送结果不确定，请人工核对");
            throw e;
        }
        String status = switch (result) {
            case SENT -> "SENT";
            case UNKNOWN -> "UNKNOWN";
            case RESTRICTED -> "RESTRICTED";
            default -> "FAILED";
        };
        deliveryService.finish(draft.id(), status, switch (result) {
            case UNKNOWN -> "本岗位发送结果待核对，不自动重发；不因此暂停其他岗位";
            case RESTRICTED -> "平台要求人工处理，任务已暂停";
            case SENT -> "招呼语已确认；如配置图片简历，请另行核对附件状态";
            default -> "未完成发送，不自动重试";
        });
        return result;
    }

    private SubmitResult sendPrepared(Job job, String message, boolean sendImgResume) {
        if (job == null || isBlank(message) || message.length() > 2000) return SubmitResult.FAILED;
        String detailUrl = toAbsoluteDetailUrl(job.getHref());
        if (detailUrl == null) return SubmitResult.FAILED;
        if (bossService.hasConfirmedDelivery(extractEncryptId(detailUrl))) return SubmitResult.SKIPPED;
        if ((blackCompanies != null && blackCompanies.stream().anyMatch(
                term -> Objects.toString(job.getCompanyName(), "").contains(term)))
                || (blackJobs != null && blackJobs.stream().anyMatch(
                term -> Objects.toString(job.getJobName(), "").contains(term)))) return SubmitResult.SKIPPED;

        Page detailPage = null;
        SubmitResult result = SubmitResult.FAILED;
        boolean writeStarted = false;
        try {
            beforeBrowse(page, BossBrowseGuard.Action.DETAIL_PAGE, null);
            // 2. 在新窗口打开详情页
            detailPage = page.context().newPage();
            detailPage.setDefaultTimeout(10_000);
            Response navigation = detailPage.navigate(detailUrl, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000));
            inspectResponse(navigation);
            assertSafe(detailPage);
            if (!Objects.equals(extractEncryptId(detailUrl), extractEncryptId(detailPage.url()))) {
                throw new IllegalStateException("岗位详情发生意外跳转，已停止发送");
            }

            // 3. 查找"立即沟通"按钮
            Locator chatBtn = detailPage.locator("a.btn-startchat, a.op-btn-chat");
            boolean foundChatBtn = false;
            for (int i = 0; i < 5; i++) {
                assertSafe(detailPage);
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    log.info("停止指令已触发，结束查找聊天按钮 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                    result = SubmitResult.SKIPPED;
                    return result;
                }
                // textContent() 在元素被替换/销毁时可能返回 null，直接 .contains 会 NPE
                if (chatBtn.count() > 0
                        && Objects.toString(chatBtn.first().textContent(), "").contains("立即沟通")) {
                    foundChatBtn = true;
                    break;
                }
                waitSafely(detailPage, 250);
            }
            if (!foundChatBtn) {
                log.warn("未找到立即沟通按钮，跳过岗位: {}", job.getJobName());
                throw new IllegalStateException("立即沟通按钮不可用，停止任务并检查页面");
            }
            beforeOperation(detailPage);
            // Starting a chat can itself be an irreversible platform operation.
            writeStarted = true;
            chatBtn.first().click();
            waitSafely(detailPage, 250);

            // 4. 等待聊天输入框
            Locator inputLocator = detailPage.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area");
            boolean inputReady = false;
            for (int i = 0; i < 10; i++) {
                assertSafe(detailPage);
                if (shouldStopCallback != null && Boolean.TRUE.equals(shouldStopCallback.get())) {
                    log.info("停止指令已触发，结束等待聊天输入框 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                    result = SubmitResult.UNKNOWN;
                    return result;
                }
                if (inputLocator.count() > 0 && inputLocator.first().isVisible()) {
                    inputReady = true;
                    break;
                }
                waitSafely(detailPage, 250);
            }
            if (!inputReady) {
                log.warn("聊天输入框未出现，跳过: {}", job.getJobName());
                result = SubmitResult.UNKNOWN;
                return result;
            }

            // 6. 输入打招呼语
            Locator input = inputLocator.first();
            input.click();
            String tag = Objects.toString(input.evaluate("el => el.tagName.toLowerCase()"), "");
            input.fill(message);

            // 6.1 自检：确认文本真的写进了输入框。写不进去就绝不能点发送，
            // 否则会发出空消息，而且旧逻辑会把它记成"投递成功"。
            if (!message.equals(readInputText(input, tag))) {
                log.warn("招呼语未能写入输入框，放弃发送 | 公司：{} | 岗位：{}", job.getCompanyName(), job.getJobName());
                result = SubmitResult.UNKNOWN;
                return result;
            }

            // 7. 点击发送按钮（div.send-message 或 button.btn-send）
            Locator sendText = detailPage.locator("div.send-message, button[type='send'].btn-send, button.btn-send");
            if (sendText.count() == 0) {
                log.warn("未找到发送按钮，自动跳过！岗位：{}", job.getJobName());
                result = SubmitResult.UNKNOWN;
                return result;
            }
            beforeOperation(detailPage);
            if (hasAmbiguousOutgoingMessage(detailPage, message)) {
                result = SubmitResult.UNKNOWN;
                return result;
            }
            beforeDelivery(detailPage);
            if (!message.equals(readInputText(input, tag)) || hasAmbiguousOutgoingMessage(detailPage, message)) {
                log.warn("等待期间输入框或发送状态发生变化，不点击发送 | 岗位：{}", job.getJobName());
                result = SubmitResult.UNKNOWN;
                return result;
            }
            Set<String> messagesBefore = snapshotMessageIds(detailPage);
            sendText.first().click();

            // Never infer acceptance from an empty or detached composer.
            boolean sendSuccess = waitForSendConfirmed(detailPage, message, messagesBefore);
            if (!sendSuccess) {
                log.warn("未取得明确发送确认；仅本岗位标为待核对，不重发，不因此暂停其他岗位");
            }

            // 8. 发送图片简历（可选，仅在招呼语确实发出后才尝试）
            boolean imgResume = false;
            result = sendSuccess ? SubmitResult.SENT : SubmitResult.UNKNOWN;
            if (sendSuccess && sendImgResume) {
                beforeOperation(detailPage);
                imgResume = sendImageResume(detailPage);
            }

            log.info("投递结果 | result={} | imageUploadAttempted={}", sendSuccess ? "SENT" : "UNKNOWN", imgResume);

            result = sendSuccess ? SubmitResult.SENT : SubmitResult.UNKNOWN;
            return result;
        } catch (BossOperationGate.DeliveryPausedException e) {
            if (operationGate.holdReason(sessionScope) == null) {
                operationGate.hold(sessionScope, e.getMessage());
            }
            if (result != SubmitResult.SENT) result = writeStarted ? SubmitResult.UNKNOWN : SubmitResult.RESTRICTED;
            return result;
        } catch (CancellationException e) {
            if (result != SubmitResult.SENT) result = writeStarted ? SubmitResult.UNKNOWN : SubmitResult.SKIPPED;
            return result;
        } catch (Exception e) {
            log.warn("投递流程异常 | type={}", e.getClass().getSimpleName());
            if (result != SubmitResult.SENT) result = writeStarted ? SubmitResult.UNKNOWN : SubmitResult.FAILED;
            operationGate.hold(sessionScope, "页面或网络异常，请人工检查后恢复任务");
            return result;
        } finally {
            if (result == SubmitResult.RESTRICTED && operationGate.holdReason(sessionScope) == null) {
                operationGate.hold(sessionScope, "平台要求登录、验证或限制操作，请在官方页面处理");
            }
            // Keep a challenge/error page for manual handling, but do not leak a tab per unknown receipt.
            if (operationGate.holdReason(sessionScope) == null) {
                closeQuietly(detailPage);
            }
            // 状态回写只针对真正尝试过的投递；SKIPPED 表示从未尝试，保持原状态不动
            persistDeliveryStatus(detailUrl, result);
        }
    }

    /** 生成打招呼语：AI 生成优先，其次配置的 sayHi，最后内置兜底文案。 */
    private String buildGreetingMessage(String keyword, Job job) {
        String aiMessage = null;
        if (Boolean.TRUE.equals(config.getEnableAI())) {
            String jd = job.getJobInfo();
            if (!isBlank(jd)) {
                aiMessage = generateAiMessage(keyword, job.getJobName(), jd);
            }
        }
        String message = isValidString(aiMessage) ? aiMessage : config.getSayHi();
        // 允许只使用单岗投递而未配置默认招呼语；不要把 null/空白传给 Playwright 的
        // fill/evaluate，避免因 NPE 或空白消息导致整次投递失败。
        return isValidString(message) ? message : DEFAULT_GREETING;
    }

    /**
     * Require a new identified outgoing message with an explicit accepted UI state.
     */
    boolean waitForSendConfirmed(Page target, String message, Set<String> messagesBefore) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            assertSafe(target);
            try {
                if (hasNewConfirmedMessage(target, message, messagesBefore)) return true;
            } catch (Exception e) {
                log.debug("读取发送确认时页面发生变化，将在时限内继续检查 | type={}", e.getClass().getSimpleName());
            }
            waitSafely(target, 250);
        }
        try {
            log.warn("发送确认诊断（仅计数，不含聊天内容）：{}",
                    sendConfirmationEvidence(target, message, messagesBefore));
        } catch (Exception e) {
            log.warn("无法读取发送确认诊断 | type={}", e.getClass().getSimpleName());
        }
        return false;
    }

    Set<String> snapshotMessageIds(Page target) {
        Object result = target.evaluate("""
                () => Array.from(document.querySelectorAll('.message-item,.chat-message,[data-message-id]'))
                  .map(el => el.getAttribute('data-message-id') || el.getAttribute('data-mid')
                    || el.getAttribute('data-id') || el.id).filter(Boolean)
                """);
        if (!(result instanceof List<?> ids)) throw new IllegalStateException("Cannot snapshot message identities");
        Set<String> snapshot = new HashSet<>();
        for (Object id : ids) if (id instanceof String text) snapshot.add(text);
        return snapshot;
    }

    boolean hasAmbiguousOutgoingMessage(Page target, String message) {
        Object result = target.evaluate("""
                text => Array.from(document.querySelectorAll('.message-item,.chat-message,[data-message-id]'))
                  .some(item => {
                    const outgoing = item.matches('.item-myself,.is-self,.myself,.message-right,.from-self')
                      || item.getAttribute('data-direction') === 'outgoing'
                      || item.getAttribute('data-self') === 'true';
                    if (!outgoing || !item.textContent.includes(text)) return false;
                    const id = item.getAttribute('data-message-id') || item.getAttribute('data-mid')
                      || item.getAttribute('data-id') || item.id;
                    return !id || !!item.querySelector('.sending,.send-failed,.message-error');
                  })
                """, message);
        return !Boolean.FALSE.equals(result);
    }

    boolean hasNewConfirmedMessage(Page target, String message, Set<String> messagesBefore) {
        return sendConfirmationEvidence(target, message, messagesBefore).getOrDefault("confirmed", 0) > 0;
    }

    Map<String, Integer> sendConfirmationEvidence(Page target, String message, Set<String> messagesBefore) {
        Map<String, Integer> evidence = new LinkedHashMap<>();
        Locator copies = target.getByText(message, new Page.GetByTextOptions().setExact(true));
        int count = copies.count();
        evidence.put("textMatches", count);
        for (int i = 0; i < count; i++) {
            Locator copy = copies.nth(i);
            if (!copy.isVisible()) {
                evidence.merge("hidden", 1, Integer::sum);
                continue;
            }
            Object reason = copy.evaluate("""
                    (el, previousIds) => {
                      if (el.closest('textarea,input,[contenteditable="true"]')) return 'composer';
                      const item = el.closest('.message-item,.chat-message,[data-message-id]');
                      if (!item) return 'missingContainer';
                      if (item.querySelector('.sending,.send-failed,.message-error')) return 'pendingOrFailed';
                      const outgoing = item.matches('.item-myself,.is-self,.myself,.message-right,.from-self')
                        || item.getAttribute('data-direction') === 'outgoing'
                        || item.getAttribute('data-self') === 'true';
                      if (!outgoing) return 'notOutgoing';
                      const status = item.querySelector('.message-status,.read-status,.message-state,[data-status]');
                      if (!status) return 'missingReceipt';
                      const accepted = /^(sent|delivered|read)$/.test(status.getAttribute('data-status') || '')
                        || /^(已发送|发送成功|已送达|送达|已读)$/.test((status.textContent || '').trim());
                      if (!accepted) return 'unrecognizedReceipt';
                      const id = item.getAttribute('data-message-id') || item.getAttribute('data-mid')
                        || item.getAttribute('data-id') || item.id;
                      if (!id || !id.trim()) return 'missingIdentity';
                      return previousIds.includes(id) ? 'existingMessage' : 'confirmed';
                    }
                    """, new ArrayList<>(messagesBefore));
            if (reason instanceof String key) evidence.merge(key, 1, Integer::sum);
        }
        return evidence;
    }

    /** 读取输入框当前文本；元素不可用时返回 null。 */
    private String readInputText(Locator input, String tag) {
        try {
            return "textarea".equals(tag) ? input.inputValue() : input.textContent();
        } catch (Exception e) {
            return null;
        }
    }

    /** 关闭页面并吞掉关闭失败（页面可能已被用户手动关掉）。 */
    private void closeQuietly(Page target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 回写投递状态。
     *
     * <p>SKIPPED 不写库：岗位从未被尝试投递，保持"未投递"以便下次任务重试，
     * 而不是错误地标记成"投递失败"污染统计。</p>
     */
    private void persistDeliveryStatus(String detailUrl, SubmitResult result) {
        if (result == null || result == SubmitResult.SKIPPED) {
            return;
        }
        String encryptId = extractEncryptId(detailUrl);
        if (encryptId == null) {
            log.warn("未能从详情链接提取 encryptId，无法回写投递状态 | detailUrl：{}", detailUrl);
            return;
        }
        String encryptUserId = encryptIdToUserId.get(encryptId);
        String status = switch (result) {
            case SENT -> "已投递";
            case QUEUED -> "待确认";
            case UNKNOWN -> "待核对";
            case RESTRICTED -> "平台受限";
            default -> "投递失败";
        };
        try {
            bossService.updateDeliveryStatus(encryptId, encryptUserId, status);
            log.info("投递状态回写 | encryptId：{} | status：{}", encryptId, status);
        } catch (Exception e) {
            log.warn("回写投递状态失败 | encryptId：{} | status：{} | error：{}", encryptId, status, e.getMessage());
        }
    }

    /** 允许导航的岗位详情页域名（Boss 官方域名及其子域）。 */
    private static final String BOSS_HOST = "zhipin.com";

    /** 未配置任何招呼语时使用的兜底文案。 */
    private static final String DEFAULT_GREETING = "您好，我对这个岗位很感兴趣，期待进一步沟通。";

    /**
     * 把列表页/数据库里的 href 规范化成绝对 URL，并校验域名。
     *
     * <p>岗位链接来自数据库字段或页面属性，属于外部输入。不加校验就交给
     * {@code Page.navigate}，等于允许任意站点被自动打开。</p>
     *
     * @return 合法的绝对 URL；非法或非 Boss 域名时返回 null
     */
    private String toAbsoluteDetailUrl(String href) {
        if (isBlank(href)) {
            return null;
        }
        String url;
        if (href.startsWith("//")) {
            url = "https:" + href;
        } else if (href.startsWith("/")) {
            url = "https://www.zhipin.com" + href;
        } else if (href.startsWith("http://") || href.startsWith("https://")) {
            url = href;
        } else {
            url = "https://www.zhipin.com/" + href;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String lowerHost = host.toLowerCase();
            if ((!lowerHost.equals("www." + BOSS_HOST) && !lowerHost.equals(BOSS_HOST))
                    || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !uri.getPath().matches("/job_detail/[A-Za-z0-9_-]+\\.html")) {
                log.warn("岗位链接域名非法，拒绝导航 | href：{} | host：{}", href, host);
                return null;
            }
            return "https://www.zhipin.com" + uri.getPath();
        } catch (Exception e) {
            log.warn("岗位链接无法解析，拒绝导航 | href：{}", href);
            return null;
        }
    }



    /**
     * 从详情页 URL 中提取 encrypt_id
     */
    private String extractEncryptId(String detailUrl) {
        try {
            if (detailUrl == null) return null;
            String key = "/job_detail/";
            int idx = detailUrl.indexOf(key);
            if (idx < 0) return null;
            int start = idx + key.length();
            int end = detailUrl.indexOf(".html", start);
            if (end < 0) end = detailUrl.length();
            return detailUrl.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValidString(String str) {
        return str != null && !str.isBlank();
    }

    private static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    private boolean sendImageResume(Page page) {
        try {
            // 0) 资源存在性校验，避免后续无效操作
            URL resourceUrlCheck = Boss.class.getResource("/resume.jpg");
            if (resourceUrlCheck == null) {
                log.error("资源文件 resume.jpg 不存在，跳过发送图片简历");
                return false;
            }

            // 进入聊天页
            if (!page.url().contains("/web/geek/chat")) {
                Locator chatBtn = page.locator("a.btn-startchat, a.op-btn-chat");
                if (chatBtn.count() == 0) {
                    log.warn("未找到【继续沟通/立即沟通】按钮，跳过发送图片简历");
                    return false;
                }
                beforeOperation(page);
                chatBtn.first().click();
                page.waitForURL("**/web/geek/chat**", new Page.WaitForURLOptions().setTimeout(15_000));
            }

            // 1) 解析图片路径（在可能触发文件选择器前就准备好）
            java.nio.file.Path imagePath = resolveResumeImage();

            // 精准定位聊天工具栏内的图片输入，避免匹配到页面其他上传控件
            Locator imgContainer = page.locator("div.btn-sendimg[aria-label='发送图片'], div[aria-label='发送图片'].btn-sendimg");
            Locator imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
            boolean chooserHandled = false;
            if (imageInput.count() == 0) {
                // 若未渲染，尝试拦截系统文件选择器；若未出现则普通点击促使 input 出现
                if (imgContainer.count() > 0) {
                    try {
                        beforeOperation(page);
                        com.microsoft.playwright.FileChooser chooser = page.waitForFileChooser(() -> {
                            assertSafe(page);
                            imgContainer.first().click();
                        });
                        beforeOperation(page);
                        chooser.setFiles(imagePath);
                        chooserHandled = true;
                        log.info("通过 FileChooser 直接提交图片文件，避免系统窗口阻塞");
                    } catch (com.microsoft.playwright.PlaywrightException e) {
                        operationGate.hold(sessionScope, "图片上传结果不确定，请人工核对附件，不自动重试");
                        return false;
                    }
                    if (!chooserHandled) {
                        PlaywrightUtil.sleep(1);
                        imageInput = imgContainer.locator("input[type='file'][accept*='image']").first();
                    }
                }
            }
            // FileChooser 已经把文件交给页面了，必须直接返回成功。
            // 继续往下走 imageInput.waitFor()，而 imageInput 是上面那个零匹配的
            // locator，必然 10 秒超时并被 catch 吞掉 —— 表现为"简历其实发出去了，
            // 日志却记未发送"，同时每个岗位白白浪费 10 秒。
            if (chooserHandled) {
                log.info("图片文件已提交给页面；请在官方界面核对附件状态");
                return true;
            }
            imageInput.waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            // 上传图片
            beforeOperation(page);
            imageInput.setInputFiles(imagePath);
            waitSafely(page, 250);
            return true;
        } catch (BossOperationGate.DeliveryPausedException | CancellationException e) {
            throw e;
        } catch (Exception e) {
            operationGate.hold(sessionScope, "图片上传异常，请人工核对附件，不自动重试");
            log.warn("图片上传异常 | type={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private java.nio.file.Path resolveResumeImage() throws Exception {
        URL resourceUrl = Boss.class.getResource("/resume.jpg");
        if (resourceUrl == null) {
            throw new IllegalStateException("资源文件 /resume.jpg 未找到，请将图片放置到 src/main/resources 目录下");
        }
        if ("file".equalsIgnoreCase(resourceUrl.getProtocol())) {
            return java.nio.file.Paths.get(resourceUrl.toURI());
        }
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("resume-", ".jpg");
        try (java.io.InputStream in = Boss.class.getResourceAsStream("/resume.jpg")) {
            if (in == null) {
                throw new IllegalStateException("无法从类路径读取 /resume.jpg 资源");
            }
            java.nio.file.Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    /**
     * 检查岗位薪资是否符合预期
     *
     * @return boolean
     * true 不符合预期
     * false 符合预期
     * 期望的最低薪资如果比岗位最高薪资还小，则不符合（薪资给的太少）
     * 期望的最高薪资如果比岗位最低薪资还小，则不符合(要求太高满足不了)
     */
    private boolean isSalaryNotExpected(String salary) {
        List<Integer> expected = config == null ? null : config.getExpectedSalary();
        if (expected == null || expected.isEmpty()
                || (expected.get(0) == null && (expected.size() < 2 || expected.get(1) == null))) {
            return false;
        }
        try {
            SalaryRange job = parseSalaryRange(salary);
            if (job == null) return true; // 已配置范围时，面议/无法解析默认过滤

            double expectedMin = expectedValue(expected, 0);
            double expectedMax = expectedValue(expected, 1);
            if (Double.isNaN(expectedMin) && Double.isNaN(expectedMax)) return false;
            if (Double.isNaN(expectedMin)) expectedMin = expectedMax;
            if (Double.isNaN(expectedMax)) expectedMax = expectedMin;
            if (expectedMin > expectedMax) {
                double swap = expectedMin; expectedMin = expectedMax; expectedMax = swap;
            }

            // 使用月薪人民币作为内部标准，避免日薪/月份薪资单位不一致导致误过滤。
            double jobMin = toMonthlySalary(job.min, job.unit);
            double jobMax = toMonthlySalary(job.max, job.unit);
            String expectedUnit = normalizeSalaryUnit(config.getExpectedSalaryUnit());
            if (expectedUnit == null) {
                // 旧版 boss_config 将 18-30 存为 18K-30K；保留该语义。
                expectedMin *= 1000D;
                expectedMax *= 1000D;
            } else {
                expectedMin = toMonthlySalary(expectedMin, expectedUnit);
                expectedMax = toMonthlySalary(expectedMax, expectedUnit);
            }
            // 两个区间无交集时过滤；只要岗位薪资区间与用户范围有交集即可保留。
            return jobMax < expectedMin || jobMin > expectedMax;
        } catch (Exception e) {
            log.warn("岗位薪资解析失败，按不符合范围处理: {} ({})", salary, e.getMessage());
            return true;
        }
    }

    private double expectedValue(List<Integer> values, int index) {
        if (values == null || index >= values.size() || values.get(index) == null) return Double.NaN;
        return values.get(index).doubleValue();
    }

    private String normalizeSalaryUnit(String unit) {
        if (unit == null || unit.isBlank()) return null;
        String value = unit.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.contains("天") || value.contains("day") || value.contains("daily")) return "day";
        if ((value.contains("k") || value.contains("千"))
                && (value.contains("月") || value.contains("month"))) return "month_k";
        if (value.contains("月") || value.contains("month") || value.contains("monthly")) return "month";
        return value;
    }

    private double toMonthlySalary(double value, String unit) {
        String normalized = normalizeSalaryUnit(unit);
        if ("day".equals(normalized)) return value * 21.75D;
        if ("month_k".equals(normalized)) return value * 1000D;
        return value;
    }

    /** 解析 Boss/猎聘常见的 15-30K、200-500元/天、18,000-30,000 元/月等文本。 */
    private SalaryRange parseSalaryRange(String salaryText) {
        if (salaryText == null || salaryText.isBlank()) return null;
        String text = salaryText.trim().replace(",", "").replace("，", "")
                .replace('－', '-').replace('—', '-').replace('~', '-');
        // 年终奖不是月薪区间的一部分（例如 15-30K·13薪）。
        text = text.replaceAll("[·.]?\\s*\\d+\\s*薪.*$", "");
        String unit = (text.contains("元/天") || text.contains("/天") || text.contains("日薪")) ? "day" : "month";
        boolean thousand = text.matches(".*(?i)(k|千).*\\d.*");
        boolean tenThousand = text.contains("万");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*(?:[-至到]|-)\\s*(\\d+(?:\\.\\d+)?)")
                .matcher(text);
        double min;
        double max;
        if (matcher.find()) {
            min = Double.parseDouble(matcher.group(1));
            max = Double.parseDouble(matcher.group(2));
        } else {
            matcher = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?").matcher(text);
            if (!matcher.find()) return null;
            min = max = Double.parseDouble(matcher.group());
        }
        // 部分平台省略 K（例如“15-30”），小于 1000 时按 K 兼容；明确写“元/月”的值按人民币处理。
        if ("month".equals(unit) && tenThousand) {
            min *= 10000D;
            max *= 10000D;
        } else if ("month".equals(unit) && (thousand || (!text.contains("元") && max < 1000D))) {
            min *= 1000D;
            max *= 1000D;
        }
        return new SalaryRange(min, max, unit);
    }

    private static final class SalaryRange {
        private final double min;
        private final double max;
        private final String unit;

        private SalaryRange(double min, double max, String unit) {
            this.min = min;
            this.max = max;
            this.unit = unit;
        }
    }

    public boolean containsDeadStatus(String activeTimeText, List<String> deadStatus) {
        for (String status : deadStatus) {
            if (activeTimeText.contains(status)) {
                return true;// 一旦找到包含的值，立即返回 true
            }
        }
        return false;// 如果没有找到，返回 false
    }

    private String generateAiMessage(String keyword, String jobName, String jd) {
        AiEntity aiConfig = aiService.getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";
        String prompt = (aiConfig != null) ? aiConfig.getPrompt() : null;

        // String.format 必须包在 try 里：用户自定义 prompt 里出现一个孤立的 %，
        // 或 %s 数量与参数不一致，都会抛格式异常。放在 try 外会让异常冒泡出
        // resumeSubmission，把整个投递任务在第一个岗位就打挂。
        String requestMessage;
        try {
            requestMessage = (prompt != null)
                    ? String.format(prompt, introduce, keyword, jobName, jd, config.getSayHi())
                    : buildDefaultPrompt(introduce, keyword, jobName, jd);
        } catch (Exception e) {
            log.warn("AI提示词格式不合法，已退回默认提示词: {}", e.getMessage());
            requestMessage = buildDefaultPrompt(introduce, keyword, jobName, jd);
        }

        try {
            String result = aiService.sendRequest(requestMessage);
            if (result == null || result.isBlank()) {
                return config.getSayHi();
            }
            // 兜底护栏：返回值看起来是响应体/结构化数据（例如误把原始 JSON 当回复返回），
            // 一律退回配置的打招呼语，绝不能让 JSON 被投递给 HR。
            if (looksLikeRawPayload(result)) {
                log.warn("AI返回内容疑似原始响应体，已退回配置的打招呼语: {}",
                        result.length() > 200 ? result.substring(0, 200) + "..." : result);
                return config.getSayHi();
            }
            return result.toLowerCase().contains("false") ? config.getSayHi() : result;
        } catch (Exception e) {
            log.warn("AI请求失败，使用原有打招呼语: {}", e.getMessage());
            return config.getSayHi();
        }
    }

    /**
     * 判断返回值是否明显不是一句打招呼语，而是原始响应体/结构化数据。
     */
    private boolean looksLikeRawPayload(String text) {
        String trimmed = text.trim();
        if (trimmed.length() > 500) {
            return true;
        }
        if (!trimmed.startsWith("{")) {
            return false;
        }
        // 单个 JSON 对象且带这些响应体专有字段，说明解析链路把原始响应返回了
        return trimmed.contains("\"object\"")
                || trimmed.contains("\"output\"")
                || trimmed.contains("\"choices\"")
                || trimmed.contains("\"status\"");
    }

    private String buildDefaultPrompt(String introduce, String keyword, String jobName, String jd) {
        return "请基于以下信息生成简洁友好的中文打招呼语，不超过60字：\n" +
                "个人介绍：" + introduce + "\n" +
                "关键词：" + keyword + "\n" +
                "职位名称：" + jobName + "\n" +
                "职位描述：" + jd + "\n" +
                "参考语：" + config.getSayHi();
    }

    // Sample once per delivery, never inside the cancellable wait loop.
    int resolveWaitTimeSeconds(RandomGenerator random) {
        int min = parseWaitTimeSeconds(config == null ? null : config.getWaitTime(),
                BossConfig.DEFAULT_WAIT_TIME_SECONDS);
        int max = parseWaitTimeSeconds(config == null ? null : config.getWaitTimeMax(),
                Math.max(min, BossConfig.DEFAULT_WAIT_TIME_MAX_SECONDS));
        if (max < min) throw new IllegalArgumentException("waitTimeMax must be greater than or equal to waitTime");
        return random.nextInt(min, max + 1);
    }

    private static int parseWaitTimeSeconds(String raw, int fallback) {
        try {
            if (raw != null && !raw.isBlank()) {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed > 0 && parsed <= 3600) {
                    return parsed;
                }
                throw new IllegalArgumentException("waitTime must be between 1 and 3600 seconds");
            }
        } catch (NumberFormatException ignored) {
            // 配置里塞了非数字，用默认值
        }
        return fallback;
    }

    /**
     * 判断是不是 Boss 的安全验证页。
     * <p>
     * 实测密集遍历后会被弹到 /web/passport/zp/verify.html（极验滑块，页面上是
     * div.geetest_success_correct 那一套），而原来的判断只认 verify-slider，
     * 导致真正遇到验证时程序把关键词当失败跳过，人也不知道要去过验证。
     */
    private static boolean isSecurityVerifyUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.contains("/web/user/safe/verify-slider")
                || url.contains("/web/passport/zp/verify")
                || url.contains("/web/passport/zp/security")
                || url.contains("security-check");
    }

    private void waitForSliderVerify(Page page) {
        operationGate.hold(sessionScope, "请在官方页面完成安全验证后，再明确恢复任务");
        if (progressCallback != null) progressCallback.accept("任务已暂停，请手动完成安全验证", 0, 0);
        throw new BossOperationGate.DeliveryPausedException("需要人工安全验证");
    }


    private boolean isLoginRequired() {
        try {
            Locator buttonLocator = page.locator(LOGIN_BTNS);
            if (buttonLocator.count() > 0 && buttonLocator.textContent().contains("登录")) {
                return true;
            }
        } catch (Exception e) {
            try {
                page.locator(PAGE_HEADER).waitFor();
                Locator errorLoginLocator = page.locator(ERROR_PAGE_LOGIN);
                if (errorLoginLocator.count() > 0) {
                    errorLoginLocator.click();
                }
                return true;
            } catch (Exception ex) {
                log.info("没有出现403访问异常");
            }
            log.info("cookie有效，已登录...");
            return false;
        }
        return false;
    }

}
