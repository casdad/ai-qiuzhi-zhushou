package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.regex.Pattern;

/** Shared by task execution and the explicit resume/login checks. */
public final class BossPageSafety {
    private static final Pattern RESTRICTION_TEXT = Pattern.compile(
            "操作过于频繁|访问过于频繁|请求过于频繁|访问受限|访问被拒绝|暂时无法访问此页面|"
                    + "今日.{0,12}(上限|用完)|沟通.{0,12}(达到上限|已达上限)|"
                    + "账号.{0,8}(异常|受限|冻结)|请.{0,8}完成.{0,8}验证");

    private BossPageSafety() {}

    public static boolean isRestrictionMessage(String message) {
        return message != null && RESTRICTION_TEXT.matcher(message).find();
    }

    public static boolean hasVisibleRestriction(Page page) {
        Locator notices = page.locator("[role='alert'],.toast,.toast-message,.toast-box,.error-content,"
                + ".dialog-message,.dialog-error,.verify-container,[role='dialog'],.error-page,.error-wrap");
        for (int i = 0; i < notices.count(); i++) {
            Locator notice = notices.nth(i);
            if (!notice.isVisible()) continue;
            String text = notice.innerText(new Locator.InnerTextOptions().setTimeout(1000));
            if (text != null && text.length() <= 800 && isRestrictionMessage(text)) return true;
        }
        // Some blocked documents are plain HTML without any known dialog class.
        Locator body = page.locator("body");
        if (body.count() == 0) return false;
        String text = body.innerText(new Locator.InnerTextOptions().setTimeout(1000));
        return text != null && text.length() <= 800
                && (text.strip().startsWith("访问受限") || text.strip().startsWith("访问被拒绝"))
                && text.contains("暂时无法访问此页面");
    }
}
