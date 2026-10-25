package com.getjobs.worker.platform;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class PlatformAdapter {
    public abstract RecruitmentPlatform platform();
    public abstract List<PlatformCandidate.Search> searches();
    protected abstract List<Locator> cards(Page page);
    protected abstract PlatformCandidate candidate(Locator card, PlatformCandidate.Search search);
    protected abstract Locator action(Page page, Locator card);

    public void open(Page page, PlatformCandidate.Search search, Consumer<String> beforeBrowse) {
        if (!platform().owns(search.url())) throw new IllegalArgumentException("Invalid search URL");
        beforeBrowse.accept(search.url() + "\n" + search.keyword());
        page.navigate(search.url(), new Page.NavigateOptions().setTimeout(15_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        page.waitForTimeout(1_000);
    }

    public List<PlatformCandidate> collect(Page page, PlatformCandidate.Search search) {
        var jobs = new LinkedHashMap<String, PlatformCandidate>();
        for (Locator card : cards(page)) {
            if (jobs.size() >= 20) break;
            try {
                PlatformCandidate job = candidate(card, search);
                job.validate(platform());
                jobs.putIfAbsent(job.jobKey(), job);
            } catch (IllegalArgumentException ignored) {
                // Missing stable identity is not permission to click an arbitrary card.
            }
        }
        return new ArrayList<>(jobs.values());
    }

    public Prepared prepare(Page page, PlatformCandidate expected) {
        expected.validate(platform());
        List<Locator> matches = new ArrayList<>();
        for (Locator card : cards(page)) {
            try {
                PlatformCandidate actual = candidate(card, expected.search());
                if (expected.jobKey().equals(actual.jobKey()) && expected.title().equals(actual.title())
                        && expected.company().equals(actual.company())) matches.add(card);
            } catch (IllegalArgumentException ignored) { }
        }
        if (matches.size() != 1) throw new PlatformDeliveryStore.Paused("岗位已变化或无法唯一定位，请重新核对，不自动投递");
        Locator button = action(page, matches.getFirst());
        if (button.count() != 1 || !button.isVisible() || !button.isEnabled()) {
            throw new PlatformDeliveryStore.Paused("未找到唯一且可用的投递按钮，请核对页面");
        }
        Set<String> baseline = notices(page);
        return new Prepared(() -> {
            PlatformCandidate actual = candidate(matches.getFirst(), expected.search());
            if (!expected.jobKey().equals(actual.jobKey()) || !expected.title().equals(actual.title())
                    || !expected.company().equals(actual.company())) throw new PlatformDeliveryStore.Paused("投递前岗位发生变化");
            beforeClick(page, matches.getFirst());
            button.click(new Locator.ClickOptions().setTimeout(5_000));
        }, baseline, Set.copyOf(page.context().pages()));
    }

    public record Prepared(Runnable click, Set<String> baseline, Set<Page> pagesBefore) { }

    protected void beforeClick(Page page, Locator card) { }

    public void closeReceipt(Page page, Prepared prepared, String outcome) {
        if ("UNKNOWN".equals(outcome)) return;
        for (Locator close : nodes(page.locator("button.el-dialog__headerbtn,.ant-modal-close,div.__im_basic__contacts-title svg"))) {
            if (close.isVisible()) close.click(new Locator.ClickOptions().setTimeout(2_000));
        }
        for (Page popup : receiptPages(page, prepared)) popup.close();
    }

    public String receipt(Page page, Prepared prepared) {
        Set<String> after = notices(page);
        after.removeAll(prepared.baseline());
        for (Page popup : receiptPages(page, prepared)) {
            String problem = PlatformPageSafety.problem(platform(), popup);
            if (problem != null) throw new PlatformDeliveryStore.Paused(problem);
            after.addAll(notices(popup));
        }
        for (String text : after) {
            if (PlatformPageSafety.restrictionMessage(text)) throw new PlatformDeliveryStore.Paused("平台提示验证或额度限制，请人工处理后恢复");
        }
        for (String text : after) {
            if (text.matches("(?s).*(投递失败|申请失败|发送失败|沟通失败).*")) return "FAILED";
        }
        return after.stream().anyMatch(PlatformAdapter::confirmedNotice) ? "SENT" : null;
    }

    private List<Page> receiptPages(Page page, Prepared prepared) {
        List<Page> pages = new ArrayList<>();
        for (Page candidate : page.context().pages()) {
            if (candidate == page || candidate.isClosed() || prepared.pagesBefore().contains(candidate) || candidate.opener() != page) continue;
            if ("about:blank".equals(candidate.url())) continue;
            if (!platform().owns(candidate.url())) throw new PlatformDeliveryStore.Paused("投递弹窗离开目标平台，请人工核对");
            pages.add(candidate);
        }
        if (pages.size() > 1) throw new PlatformDeliveryStore.Paused("出现多个投递弹窗，无法唯一确认结果");
        return pages;
    }

    static boolean confirmedNotice(String text) {
        if (text == null || text.length() > 500 || text.matches("(?s).*(失败|未成功|不成功|未(?:投递|申请|发送|沟通)成功|未(?:投递|申请)\\s*[1-9]|成功\\s*0|成功\\s*[2-9]\\d*|成功\\s*1\\d+).*")) return false;
        return Pattern.compile("(?:投递|申请|发送|沟通)成功(?:[，。！!\\s]|1(?:\\s*个)?|$)").matcher(text).find();
    }

    private Set<String> notices(Page page) {
        Locator notices = page.locator("[role='alert'],.el-message,.el-dialog__body,.ant-message-notice,.ant-notification-notice,.toast,.a-job-apply-workflow,div.deliver-dialog");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < Math.min(30, notices.count()); i++) {
            Locator node = notices.nth(i);
            if (node.isVisible()) texts.add(node.innerText(new Locator.InnerTextOptions().setTimeout(1_000)).trim());
        }
        return texts.stream().filter(t -> !t.isBlank()).collect(Collectors.toSet());
    }

    protected PlatformCandidate snapshot(String href, String title, String company, String salary,
                                         String location, PlatformCandidate.Search search) {
        if (href == null) throw new IllegalArgumentException("No job link");
        String url = URI.create(search.url()).resolve(href).toString();
        PlatformCandidate candidate = new PlatformCandidate(platform().jobKey(url), url, title, company, salary, location, search);
        candidate.validate(platform());
        return candidate;
    }

    protected static List<Locator> nodes(Locator locator) {
        List<Locator> result = new ArrayList<>();
        for (int i = 0; i < Math.min(locator.count(), 100); i++) result.add(locator.nth(i));
        return result;
    }

    protected static String text(Locator card, String selector) {
        Locator target = card.locator(selector);
        return target.count() == 0 ? "" : target.first().innerText(new Locator.InnerTextOptions().setTimeout(1_000)).trim();
    }

    protected static String href(Locator card, String selector) {
        Locator anchors = card.locator(selector);
        if (anchors.count() == 0) throw new IllegalArgumentException("No job anchor");
        return anchors.first().getAttribute("href");
    }

    protected static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    protected static List<String> keywords(List<String> values) {
        return values == null ? List.of() : values.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().limit(5).toList();
    }
}
