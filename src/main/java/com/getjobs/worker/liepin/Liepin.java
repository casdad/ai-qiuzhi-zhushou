package com.getjobs.worker.liepin;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.platform.PlatformAdapter;
import com.getjobs.worker.platform.PlatformCandidate;
import com.getjobs.worker.platform.RecruitmentPlatform;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class Liepin extends PlatformAdapter {
    private final ConfigService configService;
    @Override public RecruitmentPlatform platform() { return RecruitmentPlatform.LIEPIN; }

    @Override public List<PlatformCandidate.Search> searches() {
        LiepinConfig config = configService.getLiepinConfig();
        return keywords(config.getKeywords()).stream().map(keyword -> new PlatformCandidate.Search(
                "https://www.liepin.com/zhaopin/?currentPage=0&city=" + encode(config.getCityCode())
                        + "&dq=" + encode(config.getCityCode()) + "&salary=" + encode(config.getSalary())
                        + "&key=" + encode(keyword), keyword)).toList();
    }

    @Override protected List<Locator> cards(Page page) { return nodes(page.locator(Locators.JOB_CARDS)); }

    @Override protected PlatformCandidate candidate(Locator card, PlatformCandidate.Search search) {
        return snapshot(href(card, "a[href*='/job/'],a[href*='/a/']"),
                text(card, ".job-title,.job-name,[class*='job-title'],[class*='jobTitle'],h3"),
                text(card, ".company-name,[class*='company-name'],[class*='companyName']"),
                text(card, ".job-salary,[class*='salary']"), text(card, ".job-dq,[class*='job-dq']"), search);
    }

    @Override protected Locator action(Page page, Locator card) {
        Locator recruiter = card.locator(".recruiter-info-box");
        if (recruiter.count() == 1 && recruiter.isVisible()) recruiter.hover(new Locator.HoverOptions().setTimeout(3_000));
        return card.locator("button").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^\\s*聊一聊\\s*$")));
    }
}
