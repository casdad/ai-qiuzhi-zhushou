package com.getjobs.worker.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Keeps the existing analytics tables in sync; the delivery ledger remains authoritative. */
@Service
@RequiredArgsConstructor
public class PlatformHistory {
    private final JdbcTemplate jdbc;

    private String table(RecruitmentPlatform p) {
        return switch (p) { case LIEPIN -> "liepin_data"; case JOB51 -> "job51_data"; case ZHILIAN -> "zhilian_data"; };
    }

    private boolean exists(RecruitmentPlatform p) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table(p)) > 0;
    }

    public boolean sent(RecruitmentPlatform p, String jobKey) {
        if (!exists(p)) return false;
        String status = p == RecruitmentPlatform.ZHILIAN ? "delivery_status='已投递'" : "delivered=1";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table(p) + " WHERE job_id=? AND " + status, Integer.class, jobKey) > 0;
    }

    public void save(RecruitmentPlatform p, PlatformCandidate job) {
        if (!exists(p)) return;
        if (p == RecruitmentPlatform.ZHILIAN) {
            jdbc.update("""
                    INSERT INTO zhilian_data(job_id,job_title,job_link,company_name,salary,location,delivery_status,create_time,update_time)
                    SELECT ?,?,?,?,?,?,'未投递',datetime('now'),datetime('now') WHERE NOT EXISTS(SELECT 1 FROM zhilian_data WHERE job_id=?)
                    """, job.jobKey(), job.title(), job.url(), job.company(), job.salary(), job.location(), job.jobKey());
        } else {
            jdbc.update("INSERT OR IGNORE INTO " + table(p)
                            + "(job_id,job_title,job_link,comp_name,job_salary_text,job_area,delivered,create_time,update_time) VALUES (?,?,?,?,?,?,0,datetime('now'),datetime('now'))",
                    job.jobKey(), job.title(), job.url(), job.company(), job.salary(), job.location());
        }
    }

    public void markSent(RecruitmentPlatform p, String jobKey) {
        if (!exists(p)) return;
        String status = p == RecruitmentPlatform.ZHILIAN ? "delivery_status='已投递'" : "delivered=1";
        jdbc.update("UPDATE " + table(p) + " SET " + status + " WHERE job_id=?", jobKey);
    }
}
