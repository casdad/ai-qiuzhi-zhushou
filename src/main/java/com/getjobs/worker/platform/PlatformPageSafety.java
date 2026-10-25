package com.getjobs.worker.platform;

import com.getjobs.worker.boss.BossPageSafety;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Cookie;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class PlatformPageSafety {
    private PlatformPageSafety() { }

    public static boolean visible(Page page, String selector) {
        Locator nodes = page.locator(selector);
        for (int i = 0; i < Math.min(nodes.count(), 30); i++) if (nodes.nth(i).isVisible()) return true;
        return false;
    }

    public static String problem(RecruitmentPlatform p, Page page) {
        if (page == null || page.isClosed()) return "浏览器页面已关闭";
        if (!p.owns(page.url())) return "页面已离开目标平台，请重新登录";
        String path = URI.create(page.url()).getPath().toLowerCase(Locale.ROOT);
        if (restricted(page)) {
            return "遇到验证码或访问限制，请在官方界面处理";
        }
        if (path.contains("login") || path.contains("/passport")
                || URI.create(page.url()).getHost().startsWith("passport.")) return "需要重新登录";
        return null;
    }

    public static boolean restricted(Page page) {
        String path = URI.create(page.url()).getPath().toLowerCase(Locale.ROOT);
        if (path.contains("captcha") || path.contains("verify") || path.contains("403")
                || BossPageSafety.hasVisibleRestriction(page)
                || visible(page, ".geetest_panel,.captcha-wrap,.verify-wrap,iframe[src*='captcha']")) return true;
        Locator notices = page.locator("[role='alert'],.el-message,.el-dialog__body,.ant-message-notice,.ant-notification-notice,.toast,.a-job-apply-workflow,div.deliver-dialog");
        for (int i = 0; i < Math.min(30, notices.count()); i++) {
            Locator notice = notices.nth(i);
            if (notice.isVisible() && restrictionMessage(notice.innerText(new Locator.InnerTextOptions().setTimeout(1_000)))) return true;
        }
        return false;
    }

    public static boolean restrictionMessage(String text) {
        return BossPageSafety.isRestrictionMessage(text) || (text != null && text.length() <= 800
                && text.matches("(?s).*(今日投递太多|休息一下明天再来|达到上限|已达上限|次数过多).*"));
    }

    public static boolean authenticated(RecruitmentPlatform p, Page page) {
        if (problem(p, page) != null) return false;
        String login = switch (p) {
            case LIEPIN -> "#header-quick-menu-login";
            case JOB51 -> "span.login.loginBtnClick";
            case ZHILIAN -> "a.home-header__c-no-login";
        };
        if (visible(page, login)) return false;
        String account = switch (p) {
            case LIEPIN -> "#header-quick-menu-user-info,.header-quick-menu-user-photo";
            case JOB51 -> "a.uname.e_icon.at,a[href*='/pc/my/myjob'],.login-info,.user-info,.username";
            case ZHILIAN -> ".home-header__c-user,.home-header__c-login,a[href*='i.zhaopin.com/resume'],.user-info,.user-name,.username-text";
        };
        return visible(page, account) || visible(page, "a:has-text('退出登录'),button:has-text('退出登录')");
    }

    /** Cookie values are hashed only in memory. They never enter draft snapshots or logs. */
    public static String scope(RecruitmentPlatform p, List<Cookie> cookies) {
        List<Cookie> scoped = cookies.stream().filter(c -> {
            String domain = c.domain == null ? "" : c.domain.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            return (domain.equals(p.domain) || domain.endsWith("." + p.domain)) && c.value != null && !c.value.isBlank()
                    && (c.expires == null || c.expires <= 0 || c.expires > System.currentTimeMillis() / 1000.0);
        }).toList();
        List<Cookie> binding = scoped.stream().filter(c -> Boolean.TRUE.equals(c.httpOnly)
                || c.name.toLowerCase(Locale.ROOT).matches(".*(token|auth|ticket|session|login|userid|user_id).*"))
                .toList();
        if (binding.isEmpty()) binding = scoped;
        if (binding.isEmpty()) return null;
        return PlatformDeliveryStore.hash(binding.stream().sorted(Comparator.comparing(c -> c.domain + c.path + c.name))
                .map(c -> c.domain + "\n" + c.path + "\n" + c.name + "\n" + c.value)
                .collect(Collectors.joining("\n\n")));
    }
}
