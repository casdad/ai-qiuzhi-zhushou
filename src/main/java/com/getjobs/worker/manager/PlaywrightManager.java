package com.getjobs.worker.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.platform.PlatformPageSafety;
import com.getjobs.worker.platform.RecruitmentPlatform;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Playwright管理器
 * Spring管理的单例Bean，在应用启动时自动初始化Playwright实例
 * 支持4个求职平台的共享BrowserContext和登录状态监控
 * 所有平台在同一个浏览器窗口的不同标签页中运行
 */
@Slf4j
@Getter
@Component
@Lazy
public class PlaywrightManager {

    // Playwright实例
    private Playwright playwright;

    // Authentication belongs only to the lifetime of this browser process.
    private Browser browser;

    // 浏览器是否已经关闭（用户手动关窗口、Chrome 崩溃等）。
    // 不检测的话：字段还都非空，isInitialized() 依旧返回 true，
    // 但任何页面操作都会抛 TargetClosedError，投递任务随即卡死、按钮再点毫无反应。
    private volatile boolean browserClosed = false;

    // 浏览器是通过 CDP 接管来的，进程归用户所有：关掉它等于替用户关浏览器
    private volatile boolean attachedOverCdp = false;

    // 浏览器上下文（所有平台共享，在同一个窗口中打开多个标签页）
    private BrowserContext context;

    // Boss直聘页面
    private Page bossPage;

    // 猎聘页面
    private Page liepinPage;

    // 51job页面（预留）
    private Page job51Page;

    // 智联招聘页面（预留）
    private Page zhilianPage;

    // 登录状态追踪（平台 -> 是否已登录）
    private final Map<String, Boolean> loginStatus = new ConcurrentHashMap<>();

    // 登录状态监听器
    private final List<Consumer<LoginStatusChange>> loginStatusListeners = new CopyOnWriteArrayList<>();

    // 控制是否暂停对bossPage的后台监控，避免与任务执行并发访问同一页面
    private volatile boolean bossMonitoringPaused = false;

    // 已经就"页面跑偏"告警过的平台，避免定时检测每 3 秒重复刷同一条日志
    private final Set<String> offSiteWarned = ConcurrentHashMap.newKeySet();
    // 控制是否暂停对liepinPage的后台监控
    private volatile boolean liepinMonitoringPaused = false;

    // 控制是否暂停对51jobPage的后台监控
  private volatile boolean job51MonitoringPaused = false;

    // 控制是否暂停对zhilianPage的后台监控
    private volatile boolean zhilianMonitoringPaused = false;

    // 记录智联招聘是否已处理过未登录引导（仅初始化时执行一次）
    private volatile boolean zhilianLoginGuided = false;

    // 默认超时时间（毫秒）
  private static final int DEFAULT_TIMEOUT = 30000;

    // Playwright调试端口
    private static final int CDP_PORT = 7866;

    // waitForLoadState 的超时（毫秒）。
    // Boss / 智联都是 SPA + WebSocket 长连接（cookie 里就有 ws.zhipin.com），
    // NETWORKIDLE 可能永远等不到。不给超时会直接把线程挂死 —— 而且"卡住"不是异常，
    // 外面包多少层 try/catch 都没用，实测把初始化卡了两分钟以上还在等。
    private static final double LOAD_STATE_TIMEOUT = 10_000;

    // Local session binding only; these cookies do not prove server-side authentication.
    // bst rotates on ordinary list navigation and cannot identify a login session.
    private static final Set<String> BOSS_LOGIN_COOKIES = Set.of("wt2", "zp_at", "geek_zp_token");
    private final AtomicBoolean destroyRequested = new AtomicBoolean();

    /** Platform identities used by the login status stream. */
    private static final List<String> SESSION_PLATFORMS = List.of("boss", "liepin", "51job", "zhilian");

    @Value("${playwright.startup-platforms:boss}")
    private String startupPlatforms = "boss";

    // 平台URL常量
    private static final String BOSS_URL = "https://www.zhipin.com";
    // 初始化时打开的落地页。
    // 不要用首页：实测 navigate("https://www.zhipin.com") 要 60 秒才回来，页面标签一直转圈，
    // 期间任何 evaluate / locator 调用都会无限期阻塞（这两个 API 都没有默认超时），
    // 表现就是启动奇慢、投递卡死、管理页面点按钮没反应。
    // 岗位列表页在同一个浏览器里秒开，用它作为落地页。
    private static final String BOSS_ENTRY_URL = "https://www.zhipin.com/web/geek/jobs";
    private static final String BOSS_LOGIN_URL = "https://www.zhipin.com/web/user/";
    private static final String LIEPIN_URL = "https://www.liepin.com";
  private static final String JOB51_URL = "https://www.51job.com";
    private static final String ZHILIAN_URL = "https://www.zhaopin.com";
    private static final String BOSS_DOMAIN = "zhipin.com";
    private static final String LIEPIN_DOMAIN = "liepin.com";
    private static final String JOB51_DOMAIN = "51job.com";
    private static final String ZHILIAN_DOMAIN = "zhaopin.com";

    @Autowired
    private CookieService cookieService;

    // ------------------------------------------------------------------
    // Playwright 单线程调度
    //
    // Playwright Java 不是线程安全的：Playwright 对象以及由它创建的所有对象，
    // 都必须在创建它的那个线程上调用。这个项目原本同时从 main、ForkJoinPool、
    // scheduling-1、http-nio 多个线程操作同一个 BrowserContext，会随机爆出
    // "Object doesn't exist: request@/response@"，以及导航被打断的 net::ERR_ABORTED
    // （投递任务因此整个挂掉）。
    //
    // 这里把所有 Playwright 调用收敛到一个专用线程上串行执行。
    // ------------------------------------------------------------------
    private static final String PW_THREAD_NAME = "playwright-thread";
    private volatile Thread playwrightOwnerThread;

    private final ExecutorService playwrightExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, PW_THREAD_NAME);
        thread.setDaemon(true);
        playwrightOwnerThread = thread;
        return thread;
    });

    // 排队中 + 执行中的任务数，给定时监控做"忙就跳过"判断，避免任务堆积
    private final AtomicInteger playwrightQueueDepth = new AtomicInteger();

    /** 当前是否已经在 Playwright 线程上（嵌套调用要就地执行，否则自己等自己会死锁） */
    private boolean onPlaywrightThread() {
        return Thread.currentThread() == playwrightOwnerThread;
    }

    /**
     * 在 Playwright 专用线程上执行并等待结果。已经在该线程上时就地执行。
     */
    public <T> T callOnPlaywright(Callable<T> task) {
        if (onPlaywrightThread()) {
            try {
                return task.call();
            } catch (Exception e) {
                throw toRuntime(e);
            }
        }
        playwrightQueueDepth.incrementAndGet();
        try {
            return playwrightExecutor.submit(() -> {
                try {
                    return task.call();
                } finally {
                    playwrightQueueDepth.decrementAndGet();
                }
            }).get();
        } catch (ExecutionException e) {
            throw toRuntime(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待Playwright任务被中断", e);
        } catch (RejectedExecutionException e) {
            playwrightQueueDepth.decrementAndGet();
            throw new IllegalStateException("Playwright manager is closed", e);
        }
    }

    /**
     * 在 Playwright 专用线程上执行并等待完成。
     */
    public void runOnPlaywright(Runnable task) {
        callOnPlaywright(() -> {
            task.run();
            return null;
        });
    }

    /**
     * 给定时监控用：Playwright 线程正忙（比如正在跑投递）时直接跳过这一轮，
     * 不排队、不阻塞调度线程。
     *
     * @return 是否真的提交了任务
     */
    public boolean tryRunOnPlaywright(Runnable task) {
        if (playwrightQueueDepth.get() > 0) {
            return false;
        }
        playwrightQueueDepth.incrementAndGet();
        try {
            playwrightExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.debug("Playwright后台任务异常: {}", e.getMessage());
                } finally {
                    playwrightQueueDepth.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            playwrightQueueDepth.decrementAndGet();
            return false;
        }
        return true;
    }

    /** Playwright 线程上是否有前台任务在跑（投递、初始化等） */
    public boolean isPlaywrightBusy() {
        return playwrightQueueDepth.get() > 0;
    }

    private static RuntimeException toRuntime(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(throwable);
    }

    /**
     * 初始化Playwright实例（延迟初始化）。
     * Playwright 对象必须在专用线程上创建，否则后续所有调用都得跨线程。
     */
    public void init() {
        if (isInitialized()) {
            return;
        }
        runOnPlaywright(this::initInternal);
    }

    private void initInternal() {
        if (destroyRequested.get()) {
            throw new IllegalStateException("Playwright manager is closed");
        }
        if (isInitialized()) {
            return;
        }
        log.info("========================================");
        log.info("  初始化浏览器自动化引擎");
        log.info("========================================");

        try {
            List<String> platforms = parseStartupPlatforms(startupPlatforms);
            // bootRun selects the compatible driver via playwright.cli.dir.
            playwright = createPlaywright();
            log.info("✓ Playwright引擎已启动 (driver: {})", describeDriver());

            context = createFreshBrowserContext(playwright);
            browserClosed = false;
            // 浏览器一旦关闭（手动关窗口 / Chrome 崩溃）立刻置位，
            // 让 isInitialized() 变 false，后续操作走重新初始化而不是一路 TargetClosedError
            context.onClose(closed -> {
                browserClosed = true;
                loginStatus.keySet().forEach(platform -> setLoginStatus(platform, false));
                log.warn("Browser was closed; stop active tasks before requesting a restart");
            });
            log.info("✓ Chrome已启动（临时会话，必须重新登录）");
            log.info("✓ BrowserContext已创建（所有平台共享）");

            List<Page> startupPages = new ArrayList<>(context.pages());
            initializePlatformPages(platforms);

            // Close any unused startup tabs.
            for (Page startup : startupPages) {
                try {
                    startup.close();
                } catch (Exception e) {
                    log.debug("关闭启动空白页失败（忽略）: {}", e.getMessage());
                }
            }
            if (!startupPages.isEmpty()) {
                log.info("✓ 已关闭 {} 个启动空白页", startupPages.size());
            }

            log.info("✓ 浏览器自动化引擎初始化完成");
            log.info("平台页面导航结果: boss={} | liepin={} | 51job={} | zhilian={}",
                    currentUrl(bossPage), currentUrl(liepinPage), currentUrl(job51Page), currentUrl(zhilianPage));
            log.info("========================================");
        } catch (Exception e) {
            log.error("✗ 浏览器自动化引擎初始化失败", e);
            closeInternal();
            throw new RuntimeException("Playwright初始化失败", e);
        }
    }

    static List<String> parseStartupPlatforms(String configured) {
        Set<String> platforms = new LinkedHashSet<>();
        for (String value : (configured == null ? "" : configured).split(",")) {
            String platform = value.trim().toLowerCase(Locale.ROOT);
            if (platform.isEmpty()) continue;
            if (!SESSION_PLATFORMS.contains(platform)) {
                throw new IllegalArgumentException("Unsupported startup platform: " + platform);
            }
            platforms.add(platform);
        }
        if (platforms.isEmpty()) {
            throw new IllegalArgumentException("playwright.startup-platforms must contain at least one platform");
        }
        return List.copyOf(platforms);
    }

    void initializePlatformPages(List<String> platforms) {
        log.info("本次仅初始化平台：{}；其他平台不打开页面、不等待登录", platforms);
        // Shared Playwright connection: initialize selected platforms serially.
        for (String platform : platforms) {
            Page platformPage = context.newPage();
            platformPage.setDefaultTimeout(DEFAULT_TIMEOUT);
            switch (platform) {
                case "boss" -> { bossPage = platformPage; setupBossPlatform(); }
                case "liepin" -> { liepinPage = platformPage; setupLiepinPlatform(); }
                case "51job" -> { job51Page = platformPage; setup51jobPlatform(); }
                case "zhilian" -> { zhilianPage = platformPage; setupZhilianPlatform(); }
                default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
            }
        }
    }

    BrowserContext createFreshBrowserContext(Playwright engine) {
        SESSION_PLATFORMS.forEach(platform -> setLoginStatus(platform, false));
        BrowserContext attached = attachToRunningChrome(engine);
        if (attached != null) {
            return attached;
        }
        browser = engine.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel("chrome").setHeadless(false).setSlowMo(50).setChromiumSandbox(true)
                .setArgs(List.of("--start-maximized", "--no-first-run", "--no-default-browser-check",
                        "--disable-blink-features=AutomationControlled")));
        return browser.newContext(new Browser.NewContextOptions().setViewportSize(null));
    }

    /**
     * 优先接管一个已经在运行的普通 Chrome（run.py 会用 --remote-debugging-port 把它拉起来）。
     *
     * CDP attachment does not remove driver instrumentation. The driver selected by
     * bootRun also matters for site compatibility. Fall back to launching Chrome if unavailable.
     */
    private BrowserContext attachToRunningChrome(Playwright engine) {
        String endpoint = "http://127.0.0.1:" + CDP_PORT;
        try {
            Browser running = engine.chromium().connectOverCDP(endpoint);
            List<BrowserContext> contexts = running.contexts();
            if (contexts.isEmpty()) {
                log.warn("{} 上没有可用的浏览器上下文，改为由程序自行启动", endpoint);
                return null;
            }
            browser = running;
            attachedOverCdp = true;
            log.info("✓ 已接管本机 Chrome (CDP {})，登录态沿用该浏览器配置", endpoint);
            return contexts.get(0);
        } catch (Exception e) {
            log.info("未检测到 {} 上的 Chrome（{}），改为由程序自行启动浏览器", endpoint, e.getMessage());
            return null;
        }
    }

    /** Creates the driver selected by the deployment configuration. */
    private Playwright createPlaywright() {
        // 驱动会读进程里的 HTTP(S)_PROXY。不把回环地址排除掉的话，连本机 Chrome 的 CDP
        // 探测请求也会被发给代理，拿回 502，于是被误判成"本机没有 Chrome 可接管"。
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("NO_PROXY", withLoopback(env.get("NO_PROXY")));
        env.put("no_proxy", withLoopback(env.get("no_proxy")));

        // An explicitly supplied external driver may need a system Node executable.
        if (System.getProperty("playwright.cli.dir") != null && System.getenv("PLAYWRIGHT_NODEJS_PATH") == null) {
            String nodePath = locateNodeExecutable();
            if (nodePath != null) {
                env.put("PLAYWRIGHT_NODEJS_PATH", nodePath);
                log.info("使用本机 node: {}", nodePath);
            } else {
                log.warn("Configured external Playwright driver has no Node executable on PATH");
            }
        }

        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    /** 在已有的 NO_PROXY 基础上补上回环地址，保留用户原本排除的主机 */
    private static String withLoopback(String noProxy) {
        String loopback = "127.0.0.1,localhost,::1";
        if (noProxy == null || noProxy.isBlank()) {
            return loopback;
        }
        return noProxy.contains("127.0.0.1") ? noProxy : noProxy + "," + loopback;
    }

    /**
     * 从 PATH 里找 node 可执行文件。
     */
    private String locateNodeExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
        String exe = windows ? "node.exe" : "node";
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(entry.trim()).resolve(exe);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (Exception ignore) {
                // PATH 里可能有非法路径，跳过
            }
        }
        return null;
    }

    /** Driver source for startup diagnostics. */
    private String describeDriver() {
        String cliDir = System.getProperty("playwright.cli.dir");
        if (cliDir == null || cliDir.isBlank()) {
            return "official driver-bundle";
        }
        return "configured external driver: " + cliDir;
    }

    /**
     * 设置Boss直聘平台（新会话导航与监控）
     */
    private void setupBossPlatform() {
        log.info("开始初始化Boss直聘平台...");
        // Never replay an old database snapshot over the persistent browser profile.
        // Legacy snapshots are only imported through an explicit, one-shot operation.

        if (!navigateTo(bossPage, BOSS_ENTRY_URL, BOSS_DOMAIN, "Boss直聘")) {
            log.warn("Boss直聘页面导航失败");
        }

        // The SPA keeps background connections open. DOM checks below must not wait for network idle.
        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        setLoginStatus("boss", checkIfLoggedIn());
        // 设置登录状态监控
        setupLoginMonitoring(bossPage);
    }

    /**
     * 检查Boss是否已登录
     */
    private boolean checkIfLoggedIn() {
        try {
            if (!isInitialized() || bossPage.isClosed()) {
                return false;
            }
            // A challenge in a detail tab also invalidates permission to keep sending.
            for (Page page : context.pages()) {
                String url = page.url();
                if (isBossUrl(url) && (hasBossChallenge(page, url)
                        || com.getjobs.worker.boss.BossPageSafety.hasVisibleRestriction(page))) {
                    return false;
                }
            }
            String url = bossPage.url();
            if (!isBossUrl(url) || url.contains("/web/user/") || hasBossChallenge(bossPage, url)) {
                return false;
            }
            Locator loginAnchor = bossPage.locator("li.nav-sign a, .btn-sign, .sign-form").first();
            if (isVisibleQuick(loginAnchor)) {
                return false;
            }
            return isVisibleQuick(bossPage.locator("li.nav-figure span.label-text, li.nav-figure").first())
                    && context.cookies().stream().anyMatch(PlaywrightManager::isUsableBossLoginCookie);
        } catch (Exception ignored) {
            // Unknown/closed pages are not evidence of a valid authenticated session.
        }
        return false;
    }

    private boolean hasBossChallenge(Page page, String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("/verify") || lower.contains("/captcha")
                || isVisibleQuick(page.locator(".geetest_panel, .geetest_panel_box, "
                + "iframe[src*='captcha'], .verify-wrap, .captcha-wrap").first());
    }

    private boolean isBossUrl(String url) {
        return isPlatformUrl(url, BOSS_DOMAIN);
    }

    /** 页面已关闭时读取 URL 会抛异常，统一按空串处理，不让异常打断导航流程 */
    private static String currentUrl(Page page) {
        try {
            String url = page.url();
            return url == null ? "" : url;
        } catch (Exception e) {
            return "";
        }
    }

    /** URL 的主机名是否属于该平台域名（含子域名） */
    private static boolean isPlatformUrl(String url, String domain) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return host != null && (host.equals(domain) || host.endsWith("." + domain));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 导航到目标地址，并以最终 URL 为准确认页面真的落地了。
     * navigate() 正常返回不代表加载成功：请求可能在提交前就被中断，页签会一直停在初始的
     * about:blank，而 Playwright 不会为此抛异常。只信 navigate() 的返回值，
     * 启动后就会看到平台页签是空白的，日志里却一条失败线索都没有。
     *
     * 校验要分两次：站点可能在 DOMContentLoaded 之后才将页面重定向到 about:blank，
     * 只在 navigate() 返回的瞬间看一眼会把这种页签误判成加载成功。
     */
    private boolean navigateTo(Page page, String url, String domain, String label) {
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (page.isClosed()) {
                log.warn("{}页面已关闭，停止导航", label);
                return false;
            }
            try {
                page.navigate(url, new Page.NavigateOptions()
                        .setTimeout(60000)
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } catch (Exception e) {
                // Playwright在并发导航时可能抛出 "Object doesn't exist" 异常，但页面实际已加载
                log.debug("{}第{}次导航异常: {}", label, attempt, e.getMessage());
            }
            if (isPlatformUrl(currentUrl(page), domain)) {
                try {
                    page.waitForTimeout(3000);
                } catch (RuntimeException e) {
                    log.warn("{}页面在导航后不可用: {}", label, e.getMessage());
                    return false;
                }
                if (isPlatformUrl(currentUrl(page), domain)) {
                    return true;
                }
                log.warn("{}第{}次导航后被页面重置为{}，准备重试", label, attempt, currentUrl(page));
            } else {
                log.warn("{}第{}次导航后仍停留在{}，准备重试", label, attempt, currentUrl(page));
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /** Refreshes cached state without issuing a new network probe or navigation. */
    public boolean checkBossSession() {
        return callOnPlaywright(() -> {
            boolean valid = checkIfLoggedIn();
            setLoginStatus("boss", valid);
            return valid;
        });
    }

    /** Bind to login credentials, excluding navigation cookies; not a permanent account ID. */
    public String getBossSessionScope() {
        return callOnPlaywright(() -> {
            if (!isInitialized()) {
                return null;
            }
            try {
                return bossSessionScope(context.cookies());
            } catch (RuntimeException e) {
                setLoginStatus("boss", false);
                return null;
            }
        });
    }

    static String bossSessionScope(List<Cookie> cookies) {
        List<Cookie> authCookies = cookies == null ? List.of() : cookies.stream()
                .filter(PlaywrightManager::isUsableBossLoginCookie)
                .sorted(Comparator.comparing((Cookie cookie) -> cookie.name).thenComparing(cookie -> cookie.value))
                .toList();
        if (authCookies.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Cookie cookie : authCookies) {
                updateDigest(digest, cookie.name);
                updateDigest(digest, cookie.value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static boolean isUsableBossLoginCookie(Cookie cookie) {
        if (cookie == null || !BOSS_LOGIN_COOKIES.contains(cookie.name)
                || cookie.value == null || cookie.value.isBlank() || cookie.domain == null) {
            return false;
        }
        String domain = cookie.domain.toLowerCase(Locale.ROOT);
        return (domain.equals(BOSS_DOMAIN) || domain.endsWith("." + BOSS_DOMAIN))
                && (cookie.expires == null || cookie.expires < 0
                || cookie.expires > System.currentTimeMillis() / 1000.0);
    }

    /** Explicit migration only; existing profile credentials are never overwritten. */
    public boolean importLegacyBossCookies() {
        throw new IllegalStateException("每次必须重新登录，不支持导入历史登录凭证");
    }

    /** Immediate DOM observation; unknown elements are not login evidence. */
    private boolean isVisibleQuick(Locator locator) {
        try {
            return locator.isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设置登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                // 事件回调是在别的调用"进行中"被派发的，此时再去调 Playwright 属于重入，
                // 会打断正在进行的导航。所以除了暂停标志，还要避开前台任务运行期。
                if (!bossMonitoringPaused && !isPlaywrightBusy()) {
                    checkLoginStatus(page, "boss");
                }
            }
        });

        log.info("{}平台登录状态监控已启用", "boss");
    }

    /**
     * 设置猎聘平台（新会话导航与监控）
     */
    private void setupLiepinPlatform() {
        log.info("开始初始化猎聘平台...");

        if (!navigateTo(liepinPage, LIEPIN_URL, LIEPIN_DOMAIN, "猎聘")) {
            log.warn("猎聘页面导航失败");
        }

        // 等待页面网络空闲，确保头部导航渲染完成
        try {
            liepinPage.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception e) {
            log.debug("等待猎聘页面网络空闲失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        checkRecruitmentSession("liepin");
        // 设置登录状态监控
        setupLiepinLoginMonitoring(liepinPage);
    }

    /**
     * 设置猎聘登录状态监控
     *
     * @param page 页面实例
     */
    private void setupLiepinLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                // 事件回调里再调 Playwright 属于重入，会打断前台任务正在进行的导航
                if (!liepinMonitoringPaused && !isPlaywrightBusy()) {
                    checkLiepinLoginStatus(page);
                }
            }
        });

        log.info("猎聘平台登录状态监控已启用");
    }

    /**
     * 设置51job平台（新会话导航与监控）
     */
    private void setup51jobPlatform() {
        log.info("开始初始化51job平台...");

        if (!navigateTo(job51Page, JOB51_URL, JOB51_DOMAIN, "51job")) {
            log.warn("51job页面导航失败");
        }

        try {
            // 检查是否需要登录
            if (!checkRecruitmentSession("51job")) {
                log.info("检测到未登录51job，尝试自动点击登录入口并等待用户登录");

                try {
                    // 优先使用用户提供的选择器：span.login.loginBtnClick
                    Locator loginEntry = job51Page.locator("span.login.loginBtnClick").first();
                    if (loginEntry != null && isVisibleQuick(loginEntry)) {
                        loginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                        log.info("已点击 51job 首页的 ‘登录/注册’ 入口，等待用户登录...");
                        asyncWaitFor51jobLogin();
                    } else {
                        // 备用选择器：文本匹配
                        Locator altLoginEntry = job51Page.locator("text=/登录\\/注册|登录|注册/").first();
                        if (altLoginEntry != null && isVisibleQuick(altLoginEntry)) {
                            altLoginEntry.click(new Locator.ClickOptions().setTimeout(30000));
                            log.info("已点击 51job 首页的登录入口（文本匹配），等待用户登录...");
                            asyncWaitFor51jobLogin();
                        } else {
                            log.info("未找到 51job 登录入口元素，保持在首页等待用户自行登录");
                            // 启动后台轮询，确保无导航也能检测到登录成功
                            asyncWaitFor51jobLogin();
                        }
                    }
                } catch (Exception clickEx) {
                    log.warn("尝试点击 51job 登录入口时发生异常: {}，保持在首页等待用户登录", clickEx.getMessage());
                    // 启动后台轮询，避免异常导致无法检测登录成功
                    asyncWaitFor51jobLogin();
                }
            } else {
                log.info("51job已登录");
            }
        } catch (Exception e) {
            log.warn("51job页面初始化检查失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        checkRecruitmentSession("51job");
        // 设置登录状态监控
        setup51jobLoginMonitoring(job51Page);
    }

    /**
     * 设置51job登录状态监控
     *
     * @param page 页面实例
     */
    private void setup51jobLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!job51MonitoringPaused && !isPlaywrightBusy()) {
                    check51jobLoginStatus(page);
                }
            }
        });

        log.info("51job平台登录状态监控已启用");
    }

    /**
     * 检查51job登录状态
     *
     * @param page 页面实例
     */
    private void check51jobLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkRecruitmentSession("51job");
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("51job");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                on51jobLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查51job平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 51job登录成功回调
     */
    private void on51jobLoginSuccess() {
        log.info("51job平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("51job", true);

    }

    /**
     * 在后台异步等待 51job 登录成功。
     * 说明：不阻塞初始化主流程，独立线程每秒轮询一次登录状态，最长等待5分钟。
     */
    private void asyncWaitFor51jobLogin() {
        Thread waitThread = new Thread(() -> {
            try {
                int maxSeconds = 300; // 最长等待 5 分钟
                final java.util.concurrent.atomic.AtomicBoolean loggedIn = new java.util.concurrent.atomic.AtomicBoolean(false);
                for (int i = 0; i < maxSeconds; i++) {
                    // 这个线程不能直接调 Playwright：它和 playwright-thread 同时读同一条连接，
                    // 会抢走对方的事件，导致投递时报 "Object doesn't exist: response@..."。
                    // 所以检测动作交给专用线程；专用线程正忙（比如在投递）就跳过这一秒。
                    // tryRunOnPlaywright 是异步提交的，结果在下一轮循环开头再看
                    if (loggedIn.get()) {
                        return;
                    }
                    final int elapsed = i;
                    tryRunOnPlaywright(() -> {
                        if (job51MonitoringPaused) {
                            return;
                        }
                        try {
                            if (checkRecruitmentSession("51job")) {
                                // Update only the in-memory login status.
                                on51jobLoginSuccess();
                                log.info("后台等待检测到 51job 登录成功，用时约 {} 秒", elapsed);
                                loggedIn.set(true);
                            }
                        } catch (Exception ignored) {
                        }
                    });

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.debug("等待 51job 登录线程被中断");
                        return;
                    }
                }
                log.warn("后台等待 51job 登录超时（约5分钟），仍未检测到登录成功");
            } catch (Exception e) {
                log.warn("后台等待 51job 登录过程中发生异常: {}", e.getMessage());
            }
        }, "wait-51job-login-thread");

        waitThread.setDaemon(true);
        waitThread.start();
    }

    /**
     * 保存51job Cookie到数据库
     *
     * @param remark 备注信息
     */

    /**
     * 主动保存51job Cookie到数据库（用于调试/验证）
     */
    public void save51jobCookiesToDb(String remark) {
        throw sessionPersistenceDisabled();
    }

    /**
     * 清理51job上下文中的Cookie
     */
    public void clear51jobCookies() {
        clearPlatformCookies("51job", JOB51_DOMAIN);
    }

    /**
     * 暂停51job页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pause51jobMonitoring() {
        job51MonitoringPaused = true;
        log.debug("51job登录监控已暂停");
    }

    /**
     * 恢复51job页面的后台登录监控
     */
    public void resume51jobMonitoring() {
        job51MonitoringPaused = false;
        log.debug("51job登录监控已恢复");
    }

    /**
     * 触发 51job 登录流程：打开登录页并点击“微信扫码登录”按钮
     */
    public void trigger51jobLogin() {
        runOnPlaywright(this::trigger51jobLoginInternal);
    }

    private void trigger51jobLoginInternal() {
        try {
            if (job51Page == null) {
                if (context == null) {
                    throw new IllegalStateException("浏览器上下文尚未初始化");
                }
                job51Page = context.newPage();
            }

            resetPlatformSession("51job", JOB51_DOMAIN, false);

            // 先尝试在首页点击“登录/注册”入口
            try {
                job51Page.navigate(JOB51_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                Locator loginEntry = job51Page.locator("span.login.loginBtnClick, text=/登录\\/注册|登录|注册/").first();
                if (isVisibleQuick(loginEntry)) {
                    loginEntry.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                }
            } catch (Exception e) {
                log.debug("在首页尝试点击登录入口失败: {}", e.getMessage());
            }

            // 跳转到官方登录页
            String loginUrl = "https://login.51job.com/login.php";
            job51Page.navigate(loginUrl, new Page.NavigateOptions()
                .setTimeout(60000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 尝试点击“微信扫码登录”按钮
            Locator wechatScanBtn = job51Page.locator(
                "i.passIcon.custom-cursor-on-hover[data-sensor-id='sensor_login_wechatScan'], " +
                "i.passIcon[data-sensor-id='sensor_login_wechatScan'], " +
                "[data-sensor-id='sensor_login_wechatScan']"
            ).first();

            if (isVisibleQuick(wechatScanBtn)) {
                wechatScanBtn.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                log.info("已点击51job登录页的微信扫码按钮，等待用户扫码登录...");
            } else {
                log.warn("未找到微信扫码登录按钮，用户可在登录页自行选择扫码方式");
            }

            // 不阻塞等待：监控会自动检测到登录成功并保存Cookie
        } catch (Exception e) {
            log.error("触发51job登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发51job登录流程失败", e);
        }
    }

    /**
     * 设置智联招聘平台（新会话导航与监控）
     */
    private void setupZhilianPlatform() {
        log.info("开始初始化智联招聘平台...");

        if (!navigateTo(zhilianPage, ZHILIAN_URL, ZHILIAN_DOMAIN, "智联招聘")) {
            log.warn("智联招聘页面导航失败");
        }

        // 等待页面加载完成
        try {
            zhilianPage.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(LOAD_STATE_TIMEOUT));
        } catch (Exception e) {
            log.debug("等待智联页面网络空闲失败: {}", e.getMessage());
        }

        // 初始化登录状态并通知（如果有SSE连接会立即推送）
        checkRecruitmentSession("zhilian");
        // 设置登录状态监控
        setupZhilianLoginMonitoring(zhilianPage);
    }

    /**
     * 设置智联招聘登录状态监控
     *
     * @param page 页面实例
     */
    private void setupZhilianLoginMonitoring(Page page) {
        // 监听页面导航事件，检测URL变化
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                if (!zhilianMonitoringPaused && !isPlaywrightBusy()) {
                    checkZhilianLoginStatus(page);
                }
            }
        });

        log.info("智联招聘平台登录状态监控已启用");
    }

    /**
     * 检查智联招聘登录状态
     *
     * @param page 页面实例
     */
    private void checkZhilianLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkRecruitmentSession("zhilian");
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("zhilian");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onZhilianLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查智联招聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 主动触发智联招聘登录：点击二维码入口并等待登录成功跳转
     */
    public void triggerZhilianLogin() {
        runOnPlaywright(this::triggerZhilianLoginInternal);
    }

    private void triggerZhilianLoginInternal() {
        try {
            resetPlatformSession("zhilian", ZHILIAN_DOMAIN, false);
            if (zhilianPage == null) {
                throw new IllegalStateException("智联招聘页面未初始化");
            }

            // 导航到智联首页，确保DOM就绪
            zhilianPage.navigate(ZHILIAN_URL, new Page.NavigateOptions()
                    .setTimeout(60000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

            // 如果看到未登录入口，尝试打开二维码登录面板
            Locator noLoginAnchor = zhilianPage.locator("a.home-header__c-no-login").first();
            if (isVisibleQuick(noLoginAnchor)) {
                Locator qrToggle = zhilianPage.locator("div.zppp-panel-normal-bar__img").first();
                if (isVisibleQuick(qrToggle)) {
                    qrToggle.click(new Locator.ClickOptions().setTimeout(DEFAULT_TIMEOUT));
                    log.info("已点击智联二维码登录入口，等待用户扫码...");
                } else {
                    log.warn("未找到二维码登录入口元素：div.zppp-panel-normal-bar__img");
                }
            } else {
                log.info("未检测到未登录入口，可能已登录或在其他页面");
            }

            // 监听登录成功：等待URL跳转到 i.zhaopin.com 或用户信息元素出现
            try {
                zhilianPage.waitForURL("**i.zhaopin.com**", new Page.WaitForURLOptions().setTimeout(120_000));
                onZhilianLoginSuccess();
                return;
            } catch (Exception ignored) {
            }
            try {
                zhilianPage.waitForSelector(".user-info, .user-name, .username-text", new Page.WaitForSelectorOptions().setTimeout(120_000));
                onZhilianLoginSuccess();
            } catch (Exception e) {
                log.warn("等待智联登录成功超时或失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("触发智联登录流程失败: {}", e.getMessage(), e);
            throw new RuntimeException("触发智联登录流程失败", e);
        }
    }

    /**
     * 智联招聘登录成功回调
     */
    private void onZhilianLoginSuccess() {
        log.info("智联招聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("zhilian", true);

    }

    /**
     * 保存智联招聘Cookie到数据库
     *
     * @param remark 备注信息
     */

    /**
     * 主动保存智联招聘Cookie到数据库（用于调试/验证）
     */
    public void saveZhilianCookiesToDb(String remark) {
        throw sessionPersistenceDisabled();
    }

    /**
     * 统一按平台保存 Cookie 到数据库
     *
     * @param platform 平台标识（boss/liepin/51job/zhilian）
     * @param remark   备注
     */
    public void saveCookiesToDb(String platform, String remark) {
        throw sessionPersistenceDisabled();
    }

    /**
     * 清理智联招聘上下文中的Cookie
     */
    public void clearZhilianCookies() {
        clearPlatformCookies("zhilian", ZHILIAN_DOMAIN);
    }

    /**
     * 暂停智联招聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseZhilianMonitoring() {
        zhilianMonitoringPaused = true;
        log.debug("智联招聘登录监控已暂停");
    }

    /**
     * 恢复智联招聘页面的后台登录监控
     */
    public void resumeZhilianMonitoring() {
        zhilianMonitoringPaused = false;
        log.debug("智联招聘登录监控已恢复");
    }

    /**
     * 检查猎聘登录状态
     *
     * @param page 页面实例
     */
    private void checkLiepinLoginStatus(Page page) {
        try {
            boolean isLoggedIn = checkRecruitmentSession("liepin");
            // 如果登录状态发生变化（从未登录变为已登录）
            Boolean previousStatus = loginStatus.get("liepin");
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLiepinLoginSuccess();
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查猎聘平台登录状态时发生异常: {}", e.getMessage());
        }
    }

    /**
     * 猎聘登录成功回调
     */
    private void onLiepinLoginSuccess() {
        log.info("猎聘平台登录成功");

        // 更新登录状态并通知
        setLoginStatus("liepin", true);

    }

    /**
     * 保存猎聘Cookie到数据库
     *
     * @param remark 备注信息
     */

    /**
     * 主动保存猎聘Cookie到数据库（用于调试/验证）
     */
    public void saveLiepinCookiesToDb(String remark) {
        throw sessionPersistenceDisabled();
    }

    /**
     * 清理猎聘上下文中的Cookie
     */
    public void clearLiepinCookies() {
        clearPlatformCookies("liepin", LIEPIN_DOMAIN);
    }

    /**
     * 暂停猎聘页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseLiepinMonitoring() {
        liepinMonitoringPaused = true;
        log.debug("猎聘登录监控已暂停");
    }

    /**
     * 恢复猎聘页面的后台登录监控
     */
    public void resumeLiepinMonitoring() {
        liepinMonitoringPaused = false;
        log.debug("猎聘登录监控已恢复");
    }

    /**
     * 检查登录状态
     *
     * @param page     页面实例
     * @param platform 平台名称
     */
    private void checkLoginStatus(Page page, String platform) {
        try {
            boolean isLoggedIn = false;
            if (platform.equals("boss")) {
                // 统一复用更稳健的Boss登录判断逻辑
                isLoggedIn = checkIfLoggedIn();
            }
            Boolean previousStatus = loginStatus.get(platform);
            if (isLoggedIn && (previousStatus == null || !previousStatus)) {
                onLoginSuccess(platform);
            } else {
                setLoginStatus(platform, isLoggedIn);
            }
        } catch (Exception e) {
            // 忽略检查过程中的异常，避免影响正常流程
            log.debug("检查{}平台登录状态时发生异常: {}", platform, e.getMessage());
        }
    }

    /**
     * 登录成功回调
     *
     * @param platform 平台名称
     */
    private void onLoginSuccess(String platform) {
        log.info("{}平台登录成功", platform);

        // 更新登录状态并通知（统一使用setLoginStatus方法）
        setLoginStatus(platform, true);

    }

    /**
     * 统一的Boss Cookie保存方法（使用JSON序列化）
     *
     * @param remark 备注信息
     */

    /**
     * 主动保存 Boss Cookie 到数据库（用于调试/验证）
     */
    public void saveBossCookiesToDb(String remark) {
        throw sessionPersistenceDisabled();
    }

    private static IllegalStateException sessionPersistenceDisabled() {
        return new IllegalStateException("登录态仅在本次运行有效，不保存或复用登录凭证");
    }

    /**
     * 清理Boss上下文中的Cookie
     * Other platforms in the shared context must remain signed in.
     */
    public void clearBossCookies() {
        clearPlatformCookies("boss", BOSS_DOMAIN);
    }

    private void clearPlatformCookies(String platform, String domainSuffix) {
        resetPlatformSession(platform, domainSuffix, true);
    }

    private void resetPlatformSession(String platform, String domainSuffix, boolean openLandingPage) {
        runOnPlaywright(() -> {
            setLoginStatus(platform, false);
            if (context == null) return;
            List<String> origins = switch (platform) {
                case "boss" -> List.of(BOSS_URL, "https://zhipin.com");
                case "liepin" -> List.of(LIEPIN_URL, "https://passport.liepin.com");
                case "51job" -> List.of(JOB51_URL, "https://login.51job.com", "https://we.51job.com", "https://i.51job.com");
                case "zhilian" -> List.of(ZHILIAN_URL, "https://i.zhaopin.com", "https://passport.zhaopin.com");
                default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
            };
            Page freshPage = PlatformSessionReset.clear(context, domainSuffix, origins);
            freshPage.setDefaultTimeout(DEFAULT_TIMEOUT);
            String landing = switch (platform) {
                case "boss" -> { bossPage = freshPage; setupLoginMonitoring(freshPage); yield BOSS_LOGIN_URL; }
                case "liepin" -> { liepinPage = freshPage; setupLiepinLoginMonitoring(freshPage); yield LIEPIN_URL; }
                case "51job" -> { job51Page = freshPage; setup51jobLoginMonitoring(freshPage); yield JOB51_URL; }
                case "zhilian" -> { zhilianPage = freshPage; zhilianLoginGuided = false;
                    setupZhilianLoginMonitoring(freshPage); yield ZHILIAN_URL; }
                default -> throw new IllegalArgumentException("Unsupported platform: " + platform);
            };
            if (openLandingPage) {
                try {
                    freshPage.navigate(landing, new Page.NavigateOptions()
                            .setTimeout(5_000).setWaitUntil(WaitUntilState.COMMIT));
                } catch (RuntimeException e) {
                    // Session clearing already succeeded; remote rendering is not part of logout.
                    log.warn("{} 已退出登录；登录页面未及时打开，可在浏览器手动刷新", platform);
                }
            }
        });
    }

    /**
     * 定时检查登录状态（每3秒）
     * 用于捕获通过DOM元素判断登录状态的场景（无导航也可触发）
     */
    @Scheduled(fixedDelay = 3000)
    public void scheduledLoginCheck() {
        if (destroyRequested.get() || playwright == null || context == null) {
            return;
        }
        // 必须跑在 Playwright 专用线程上；线程正忙（例如正在投递）就直接跳过这一轮，
        // 否则会打断投递流程里正在进行的导航
        tryRunOnPlaywright(() -> {
            try {
                if (liepinPage != null && !liepinMonitoringPaused) {
                    warnIfOffSite("liepin", liepinPage, LIEPIN_DOMAIN);
                    checkRecruitmentSession("liepin");
                }
                // 其他平台如需也可启用（保留，但不强制）
                if (bossPage != null && !bossMonitoringPaused) {
                    warnIfOffSite("boss", bossPage, BOSS_DOMAIN);
                    checkLoginStatus(bossPage, "boss");
                }
                if (job51Page != null && !job51MonitoringPaused) {
                    warnIfOffSite("51job", job51Page, JOB51_DOMAIN);
                    checkRecruitmentSession("51job");
                }
                if (zhilianPage != null && !zhilianMonitoringPaused) {
                    warnIfOffSite("zhilian", zhilianPage, ZHILIAN_DOMAIN);
                    checkRecruitmentSession("zhilian");
                }
            } catch (Exception e) {
                log.debug("定时登录检测异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 页面如果不在平台站点上（比如被重置成 about:blank），该平台的登录检测已经失去意义，
     * 而登录状态会一路保持旧值。这里把这种跑偏暴露出来，只在状态翻转时告警一次，
     * 免得每 3 秒刷屏。
     */
    private void warnIfOffSite(String platform, Page page, String domain) {
        String url = currentUrl(page);
        if (isPlatformUrl(url, domain)) {
            offSiteWarned.remove(platform);
            return;
        }
        if (offSiteWarned.add(platform)) {
            log.warn("{}页面已不在{}站点上，当前地址为{}，该平台暂时不可用", platform, domain, url);
            setLoginStatus(platform, false);
        }
    }

    /**
     * 暂停Boss页面的后台登录监控（避免与业务流程并发操作页面）
     */
    public void pauseBossMonitoring() {
        bossMonitoringPaused = true;
        log.debug("Boss登录监控已暂停");
    }

    public Page recruitmentPage(String platform) {
        return switch (RecruitmentPlatform.from(platform)) {
            case LIEPIN -> liepinPage;
            case JOB51 -> job51Page;
            case ZHILIAN -> zhilianPage;
        };
    }

    public void pauseRecruitmentMonitoring(String platform, boolean paused) {
        switch (RecruitmentPlatform.from(platform)) {
            case LIEPIN -> liepinMonitoringPaused = paused;
            case JOB51 -> job51MonitoringPaused = paused;
            case ZHILIAN -> zhilianMonitoringPaused = paused;
        }
    }

    public boolean checkRecruitmentSession(String platform) {
        return callOnPlaywright(() -> {
            RecruitmentPlatform p = RecruitmentPlatform.from(platform);
            Page page = recruitmentPage(platform);
            boolean loggedIn = false;
            try {
                loggedIn = context != null && !browserClosed && PlatformPageSafety.authenticated(p, page)
                        && PlatformPageSafety.scope(p, context.cookies()) != null;
                if (loggedIn) {
                    for (Page other : context.pages()) {
                        if (other != page && !other.isClosed() && p.owns(other.url())
                                && PlatformPageSafety.restricted(other)) loggedIn = false;
                    }
                }
            } catch (RuntimeException ignored) { loggedIn = false; }
            setLoginStatus(platform, loggedIn);
            return loggedIn;
        });
    }

    public String recruitmentSessionScope(String platform) {
        return callOnPlaywright(() -> context == null ? null
                : PlatformPageSafety.scope(RecruitmentPlatform.from(platform), context.cookies()));
    }

    public void openRecruitmentPlatform(String platform) {
        runOnPlaywright(() -> {
            ensureReady();
            RecruitmentPlatform p = RecruitmentPlatform.from(platform);
            Page page = recruitmentPage(platform);
            if (page == null || page.isClosed()) {
                page = context.newPage();
                page.setDefaultTimeout(DEFAULT_TIMEOUT);
                switch (p) {
                    case LIEPIN -> { liepinPage = page; setupLiepinLoginMonitoring(page); }
                    case JOB51 -> { job51Page = page; setup51jobLoginMonitoring(page); }
                    case ZHILIAN -> { zhilianPage = page; setupZhilianLoginMonitoring(page); }
                }
            }
            if (!p.owns(page.url())) page.navigate(p.loginUrl,
                    new Page.NavigateOptions().setTimeout(5_000).setWaitUntil(WaitUntilState.COMMIT));
            page.bringToFront();
            checkRecruitmentSession(platform);
        });
    }

    /**
     * 恢复Boss页面的后台登录监控
     */
    public void resumeBossMonitoring() {
        bossMonitoringPaused = false;
        log.debug("Boss登录监控已恢复");
    }

    /**
     * 关闭Playwright实例
     * 在Spring容器销毁前自动执行
     */
    @PreDestroy
    public void destroy() {
        if (!destroyRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnPlaywright(this::closeInternal);
        } finally {
            playwrightExecutor.shutdown();
        }
    }

    private void closeInternal() {
        // CDP 接管的 Chrome 是用户自己的进程：关掉上下文等于替用户把所有页签关光，
        // 这里只断开连接，浏览器留给它自己
        boolean ownedByUs = !attachedOverCdp;
        try {
            if (context != null && ownedByUs) {
                context.close();
            }
        } catch (Exception e) {
            log.warn("Closing browser context failed: {}", e.getClass().getSimpleName());
        }
        try {
            if (browser != null && ownedByUs) browser.close();
        } catch (Exception e) {
            log.warn("Closing browser failed: {}", e.getClass().getSimpleName());
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception e) {
            log.warn("Closing Playwright driver failed: {}", e.getClass().getSimpleName());
        } finally {
            playwright = null;
            browser = null;
            context = null;
            bossPage = null;
            liepinPage = null;
            job51Page = null;
            zhilianPage = null;
            browserClosed = true;
            attachedOverCdp = false;
            loginStatus.keySet().forEach(platform -> setLoginStatus(platform, false));
        }
    }

    /**
     * 检查Playwright是否已初始化
     */
    public boolean isInitialized() {
        return playwright != null && context != null && bossPage != null && !browserClosed
                && !destroyRequested.get();
    }

    /**
     * 确保浏览器可用：已经关闭就重新拉起来。
     * <p>
     * 浏览器被手动关掉或崩溃之后，各个字段依然非空，界面上看什么都正常，
     * 但每个页面操作都会抛 TargetClosedError —— 投递任务卡死、按钮点了没反应。
     * 发起任务前先过一遍这里。
     */
    public void ensureReady() {
        runOnPlaywright(() -> {
            if (destroyRequested.get()) {
                throw new IllegalStateException("Playwright manager is closed");
            }
            if (isInitialized()) {
                return;
            }
            closeInternal();
            initInternal();
        });
    }

    /**
     * 获取CDP端口号
     */
    public int getCdpPort() {
        return CDP_PORT;
    }

    /**
     * 注册登录状态监听器
     *
     * @param listener 监听器
     */
    public void addLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.add(listener);
    }

    /**
     * 移除登录状态监听器
     *
     * @param listener 监听器
     */
    public void removeLoginStatusListener(Consumer<LoginStatusChange> listener) {
        loginStatusListeners.remove(listener);
    }

    /**
     * 获取平台登录状态
     *
     * @param platform 平台名称
     * @return 是否已登录
     */
    public boolean isLoggedIn(String platform) {
        return loginStatus.getOrDefault(platform, false);
    }

    /**
     * 手动设置平台登录状态（会触发SSE通知）
     *
     * @param platform   平台名称
     * @param isLoggedIn 是否已登录
     */
    public void setLoginStatus(String platform, boolean isLoggedIn) {
        Boolean previousStatus = loginStatus.get(platform);

        // 只有状态真正发生变化时才更新和通知
        if (previousStatus == null || previousStatus != isLoggedIn) {
            loginStatus.put(platform, isLoggedIn);

            // 通知所有监听器（触发SSE推送）
            LoginStatusChange change = new LoginStatusChange(platform, isLoggedIn, System.currentTimeMillis());
            loginStatusListeners.forEach(listener -> {
                try {
                    listener.accept(change);
                } catch (Exception e) {
                    log.error("通知登录状态监听器失败: platform={}, isLoggedIn={}", platform, isLoggedIn, e);
                }
            });

//            log.info("登录状态已更新: platform={}, isLoggedIn={}", platform, isLoggedIn);
        }
    }

    /**
     * 从JSON字符串解析Cookie列表
     *
     * @param cookieJson Cookie的JSON字符串
     * @return Cookie列表
     */
    private List<Cookie> parseCookiesFromString(String cookieJson) {
        List<Cookie> cookies = new ArrayList<>();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode jsonArray = objectMapper.readTree(cookieJson);

            for (com.fasterxml.jackson.databind.JsonNode node : jsonArray) {
                // 创建Cookie对象（name和value是必需的）
                Cookie cookie = new Cookie(
                        node.get("name").asText(),
                        node.get("value").asText()
                );

                // 设置可选字段
                if (node.has("domain") && !node.get("domain").isNull()) {
                    cookie.domain = node.get("domain").asText();
                }
                if (node.has("path") && !node.get("path").isNull()) {
                    cookie.path = node.get("path").asText();
                }
                if (node.has("expires") && !node.get("expires").isNull()) {
                    cookie.expires = node.get("expires").asDouble();
                }
                if (node.has("httpOnly") && !node.get("httpOnly").isNull()) {
                    cookie.httpOnly = node.get("httpOnly").asBoolean();
                }
                if (node.has("secure") && !node.get("secure").isNull()) {
                    cookie.secure = node.get("secure").asBoolean();
                }
                if (node.has("sameSite") && !node.get("sameSite").isNull()) {
                    String sameSite = node.get("sameSite").asText();
                    if (sameSite != null && !sameSite.isEmpty()) {
                        cookie.sameSite = com.microsoft.playwright.options.SameSiteAttribute.valueOf(
                                sameSite.toUpperCase()
                        );
                    }
                }

                cookies.add(cookie);
            }

            log.debug("成功解析Cookie，共 {} 条", cookies.size());
        } catch (Exception e) {
            log.warn("Cookie JSON parsing failed: {}", e.getClass().getSimpleName());
            return new ArrayList<>();
        }

        return cookies;
    }

    private List<Cookie> filterCookiesByDomain(List<Cookie> cookies, String domainSuffix) {
        if (cookies == null || cookies.isEmpty()) {
            return new ArrayList<>();
        }

        String suffix = domainSuffix == null ? "" : domainSuffix.toLowerCase(Locale.ROOT);
        List<Cookie> filtered = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookie == null || cookie.domain == null || cookie.domain.isBlank()) {
                continue;
            }
            String domain = cookie.domain.toLowerCase(Locale.ROOT);
            if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                filtered.add(cookie);
            }
        }

        return filtered;
    }

    /**
     * LoginStatusChange - 登录状态变化DTO
     */
    public record LoginStatusChange(String platform, boolean isLoggedIn, long timestamp) {
    }
}
