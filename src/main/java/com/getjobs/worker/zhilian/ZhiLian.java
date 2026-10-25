package com.getjobs.worker.zhilian;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.platform.PlatformAdapter;
import com.getjobs.worker.platform.PlatformCandidate;
import com.getjobs.worker.platform.PlatformDeliveryStore;
import com.getjobs.worker.platform.RecruitmentPlatform;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ZhiLian extends PlatformAdapter {
    private final ConfigService configService;
    @Override public RecruitmentPlatform platform() { return RecruitmentPlatform.ZHILIAN; }

    @Override public List<PlatformCandidate.Search> searches() {
        ZhilianConfig config = configService.getZhilianConfig();
        return keywords(config.getKeywords()).stream().map(keyword -> new PlatformCandidate.Search(
                "https://www.zhaopin.com/sou/jl" + encode(config.getCityCode()) + "/p1?sl=" + encode(config.getSalary()), keyword)).toList();
    }

    @Override public void open(Page page, PlatformCandidate.Search search, Consumer<String> beforeBrowse) {
        super.open(page, search, beforeBrowse);
        Locator input = page.locator("input[name='kw'],input[placeholder*='职位'],input[placeholder*='公司']");
        List<Locator> visible = nodes(input).stream().filter(Locator::isVisible).toList();
        if (visible.size() != 1) throw new PlatformDeliveryStore.Paused("智联搜索框无法唯一定位");
        beforeBrowse.accept(null);
        visible.getFirst().fill(search.keyword());
        visible.getFirst().press("Enter");
        page.waitForTimeout(1_000);
    }

    @Override protected List<Locator> cards(Page page) { return nodes(page.locator("div.joblist-box__item")); }

    @Override protected PlatformCandidate candidate(Locator card, PlatformCandidate.Search search) {
        return snapshot(href(card, "a.jobinfo__name"), text(card, "a.jobinfo__name"),
                text(card, "div.companyinfo__name"), text(card, "p.jobinfo__salary"),
                text(card, "div.jobinfo__other-info div.jobinfo__other-info-item > span"), search);
    }

    @Override protected Locator action(Page page, Locator card) {
        return card.locator("button.collect-and-apply__btn")
                .filter(new Locator.FilterOptions().setHasText(Pattern.compile("^\\s*(立即投递|投递|申请职位)\\s*$")));
    }
}
