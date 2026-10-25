package com.getjobs.worker.platform;

public record PlatformCandidate(String jobKey, String url, String title, String company,
                                String salary, String location, Search search) {
    public record Search(String url, String keyword) { }

    public void validate(RecruitmentPlatform platform) {
        if (!platform.jobKey(url).equals(jobKey) || title == null || title.isBlank()
                || company == null || company.isBlank() || search == null || !platform.owns(search.url())) {
            throw new IllegalArgumentException("Incomplete or inconsistent job identity");
        }
    }
}
