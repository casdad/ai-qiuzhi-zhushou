package com.getjobs.worker.job51;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.platform.PlatformAdapter;
import com.getjobs.worker.platform.PlatformCandidate;
import com.getjobs.worker.platform.PlatformDeliveryStore;
import com.getjobs.worker.platform.RecruitmentPlatform;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class Job51 extends PlatformAdapter {
    private final ConfigService configService;
    @Override public RecruitmentPlatform platform() { return RecruitmentPlatform.JOB51; }

    @Override public List<PlatformCandidate.Search> searches() {
        Job51Config config = configService.getJob51Config();
        String area = config.getJobArea() == null ? "" : String.join(",", config.getJobArea());
        String salary = config.getSalary() == null ? "" : String.join(",", config.getSalary());
        return keywords(config.getKeywords()).stream().map(keyword -> new PlatformCandidate.Search(
                "https://we.51job.com/pc/search?jobArea=" + encode(area) + "&salary=" + encode(salary)
                        + "&keyword=" + encode(keyword), keyword)).toList();
    }

    @Override protected List<Locator> cards(Page page) {
        List<Locator> cards = new ArrayList<>();
        for (Locator checkbox : nodes(page.locator("div.ick"))) {
            Locator card = checkbox.locator("xpath=ancestor::*[.//*[contains(@class,'jname')]][1]");
            if (card.count() == 1 && card.locator("[class*='jname']").count() == 1) cards.add(card);
        }
        return cards;
    }

    @Override protected PlatformCandidate candidate(Locator card, PlatformCandidate.Search search) {
        return snapshot(href(card, "a[href*='/pc/jobdetail'],a[href*='jobs.51job.com/']"),
                text(card, "[class*='jname']"), text(card, "[class*='cname']"),
                text(card, ".sal,.salary"), text(card, ".area,.city"), search);
    }

    @Override protected Locator action(Page page, Locator card) {
        // The site exposes a bulk control; submit exactly one verified selection, never a whole page.
        List<Locator> all = allCheckboxes(page);
        for (Locator checkbox : all) if (selected(checkbox)) checkbox.click(new Locator.ClickOptions().setTimeout(3_000));
        if (all.stream().anyMatch(this::selected)) throw new PlatformDeliveryStore.Paused("无法清除原有勾选，不执行批量投递");
        Locator target = card.locator("div.ick");
        if (target.count() != 1) throw new PlatformDeliveryStore.Paused("岗位选择框不唯一");
        target.click(new Locator.ClickOptions().setTimeout(3_000));
        if (!selected(target) || all.stream().filter(this::selected).count() != 1) {
            throw new PlatformDeliveryStore.Paused("无法确认只选中当前岗位，不执行投递");
        }
        return page.locator("div.tabs_in button.p_but")
                .filter(new Locator.FilterOptions().setHasText(Pattern.compile("^\\s*(申请职位|投递简历|申请|投递|批量申请|批量投递)\\s*$")));
    }

    private List<Locator> allCheckboxes(Page page) {
        Locator checkboxes = page.locator("div.ick");
        if (checkboxes.count() > 100) throw new PlatformDeliveryStore.Paused("岗位勾选框数量异常，无法核对全部选择，不执行批量投递");
        return nodes(checkboxes);
    }

    private boolean selected(Locator checkbox) {
        String checked = checkbox.getAttribute("aria-checked");
        if (checked != null) {
            if (!checked.equals("true") && !checked.equals("false")) throw new PlatformDeliveryStore.Paused("无法识别岗位勾选状态");
            return checked.equals("true");
        }
        String cls = checkbox.getAttribute("class");
        if (cls == null || !cls.matches("(?:ick|on|checked|active|is-checked|\\s)+")) {
            throw new PlatformDeliveryStore.Paused("无法验证全部岗位勾选状态，不执行批量投递");
        }
        return cls != null && Pattern.compile("(?:^|\\s)(?:on|checked|active|is-checked)(?:\\s|$)").matcher(cls).find();
    }

    @Override protected void beforeClick(Page page, Locator card) {
        if (!selected(card.locator("div.ick")) || allCheckboxes(page).stream().filter(this::selected).count() != 1) {
            throw new PlatformDeliveryStore.Paused("投递前勾选状态发生变化，已停止");
        }
    }
}
