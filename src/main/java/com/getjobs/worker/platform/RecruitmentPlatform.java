package com.getjobs.worker.platform;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

public enum RecruitmentPlatform {
    LIEPIN("liepin", "liepin.com", "https://www.liepin.com/login", "/(?:job|a)/(\\d+)\\.s?html"),
    JOB51("51job", "51job.com", "https://we.51job.com/pc/my/myjob", "(?:/[^/]+)*/(\\d+)\\.html"),
    ZHILIAN("zhilian", "zhaopin.com", "https://passport.zhaopin.com/login", "/jobdetail/([A-Za-z0-9_-]+)\\.html?");

    public final String id;
    public final String domain;
    public final String loginUrl;
    private final Pattern jobPath;

    RecruitmentPlatform(String id, String domain, String loginUrl, String jobPath) {
        this.id = id;
        this.domain = domain;
        this.loginUrl = loginUrl;
        this.jobPath = Pattern.compile(jobPath);
    }

    public static RecruitmentPlatform from(String id) {
        return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported recruitment platform"));
    }

    public boolean owns(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && (host.equals(domain) || host.endsWith("." + domain));
        } catch (RuntimeException e) { return false; }
    }

    public String jobKey(String url) {
        if (!owns(url)) throw new IllegalArgumentException("Invalid platform job URL");
        URI uri = URI.create(url);
        if (this == JOB51 && "/pc/jobdetail".equals(uri.getPath())) {
            var matcher = Pattern.compile("(?:^|&)jobId=(\\d+)(?:&|$)").matcher(String.valueOf(uri.getRawQuery()));
            if (matcher.find()) return matcher.group(1);
        }
        var matcher = jobPath.matcher(uri.getPath());
        if (matcher.matches()) return matcher.group(1);
        throw new IllegalArgumentException("Unrecognized platform job identity");
    }
}
