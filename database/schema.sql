-- 仅表结构与索引，不含任何个人配置、Cookie 或投递记录。
CREATE TABLE "ai" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    introduce TEXT,
    prompt TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE boss_blacklist (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(20) NOT NULL,
    value VARCHAR(200) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE boss_browse_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at INTEGER NOT NULL,
    action TEXT NOT NULL,
    search_key TEXT
);

CREATE TABLE boss_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    debugger INTEGER DEFAULT 0,
    wait_time INTEGER DEFAULT 10,
    keywords VARCHAR(500),
    city_code VARCHAR(200),
    industry VARCHAR(200),
    job_type VARCHAR(50),
    experience VARCHAR(50),
    degree VARCHAR(200),
    salary VARCHAR(50),
    scale VARCHAR(200),
    stage VARCHAR(200),
    say_hi TEXT,
    expected_salary_min INTEGER,
    expected_salary_max INTEGER,
    enable_ai INTEGER DEFAULT 1,
    send_img_resume INTEGER DEFAULT 0,
    filter_dead_hr INTEGER DEFAULT 1,
    dead_status VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
, expected_salary_unit TEXT, enable_ai_relevance INTEGER, min_ai_score INTEGER, delivery_mode TEXT DEFAULT 'CONFIRM', job_intent TEXT, wait_time_max INTEGER);

CREATE TABLE "boss_data" (id INTEGER PRIMARY KEY AUTOINCREMENT, encrypt_id TEXT, encrypt_user_id TEXT, company_name TEXT, job_name TEXT, salary TEXT, location TEXT, experience TEXT, degree TEXT, hr_name TEXT, hr_position TEXT, hr_active_status TEXT, delivery_status TEXT, job_description TEXT, job_url TEXT, recruitment_status TEXT, company_address TEXT, industry TEXT, introduce TEXT, financing_stage TEXT, company_scale TEXT, created_at TEXT, updated_at TEXT, ai_score INTEGER, ai_decision TEXT, matched_skills TEXT, missing_skills TEXT, relevance_reason TEXT);

CREATE TABLE boss_delivery_draft (
    id TEXT PRIMARY KEY,
    job_key TEXT NOT NULL,
    session_scope TEXT NOT NULL,
    job_json TEXT NOT NULL,
    message TEXT NOT NULL,
    send_img_resume INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    reason TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(session_scope, job_key)
);

CREATE TABLE boss_detail_cache (
    encrypt_id TEXT PRIMARY KEY,
    detail_json TEXT NOT NULL,
    fetched_at INTEGER NOT NULL
);

CREATE TABLE boss_option (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT NOT NULL,  -- 选项类型：city, industry, experience, jobType, salary, degree, scale, stage
    name TEXT NOT NULL,  -- 选项名称
    code TEXT NOT NULL,  -- 选项代码
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
, sort_order INTEGER);

CREATE TABLE boss_safety_hold (session_scope TEXT PRIMARY KEY, reason TEXT NOT NULL, resume_after INTEGER NOT NULL);

CREATE TABLE "config" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT,
    config_type VARCHAR(50) NOT NULL DEFAULT 'string',
    category VARCHAR(50) NOT NULL DEFAULT 'general',
    description TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE "cookie" (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    platform VARCHAR(50) NOT NULL,
    cookie_value TEXT NOT NULL,
    remark TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE job51_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    keywords VARCHAR(500),
    job_area VARCHAR(200),
    salary VARCHAR(200),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE job51_data ( job_id            BIGINT PRIMARY KEY, job_title         VARCHAR(200), job_link          VARCHAR(300), job_salary_text   VARCHAR(100), job_area          VARCHAR(100), job_edu_req       VARCHAR(50), job_exp_req       VARCHAR(50), job_publish_time  VARCHAR(50), comp_id           BIGINT, comp_name         VARCHAR(200), comp_industry     VARCHAR(100), comp_scale        VARCHAR(50), hr_id             VARCHAR(64), hr_name           VARCHAR(50), hr_title          VARCHAR(100), delivered         INTEGER DEFAULT 0, create_time       DATETIME, update_time       DATETIME);

CREATE TABLE job51_option ( id INTEGER PRIMARY KEY AUTOINCREMENT, type VARCHAR(50), name VARCHAR(100), code VARCHAR(100), sort_order INTEGER, created_at DATETIME, updated_at DATETIME);

CREATE TABLE job_profile (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type TEXT, keywords TEXT, city_code TEXT, industry TEXT, job_type TEXT, experience TEXT, degree TEXT, salary TEXT, scale TEXT, stage TEXT, say_hi TEXT, expected_salary_min INTEGER, expected_salary_max INTEGER, salary_unit TEXT, enable_ai INTEGER, send_img_resume INTEGER, filter_dead_hr INTEGER, dead_status TEXT, ai_introduce TEXT, ai_prompt TEXT, enable_ai_relevance INTEGER, min_ai_score INTEGER, enabled INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP, updated_at TIMESTAMP, job_intent TEXT);

CREATE TABLE "liepin_config" (
  "id" INTEGER PRIMARY KEY AUTOINCREMENT,
  "keywords" VARCHAR(500),
  "city" VARCHAR(200),
  "salary_code" VARCHAR(200),
  "created_at" DATETIME DEFAULT CURRENT_TIMESTAMP,
  "updated_at" DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE liepin_data ( job_id            BIGINT PRIMARY KEY, job_title         VARCHAR(200), job_link          VARCHAR(300), job_salary_text   VARCHAR(100), job_area          VARCHAR(100), job_edu_req       VARCHAR(50), job_exp_req       VARCHAR(50), job_publish_time  VARCHAR(50), comp_id           BIGINT, comp_name         VARCHAR(200), comp_industry     VARCHAR(100), comp_scale        VARCHAR(50), hr_id             VARCHAR(64), hr_name           VARCHAR(50), hr_title          VARCHAR(100), hr_im_id          VARCHAR(64), create_time       DATETIME, update_time       DATETIME, delivered INTEGER DEFAULT 0);

CREATE TABLE liepin_option (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, name TEXT NOT NULL, code TEXT NOT NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP, sort_order INTEGER);

CREATE TABLE platform_delivery_draft (
    id TEXT PRIMARY KEY, platform TEXT NOT NULL, scope TEXT NOT NULL, job_key TEXT NOT NULL,
    job_json TEXT NOT NULL, status TEXT NOT NULL, reason TEXT,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
    UNIQUE(platform, scope, job_key));

CREATE TABLE platform_delivery_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL,
    action TEXT NOT NULL, search_key TEXT, occurred_at INTEGER NOT NULL);

CREATE TABLE platform_delivery_hold (platform TEXT PRIMARY KEY, reason TEXT NOT NULL, retry_at INTEGER NOT NULL);

CREATE TABLE platform_delivery_settings (platform TEXT PRIMARY KEY, mode TEXT NOT NULL);

CREATE TABLE zhilian_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    keywords VARCHAR(500),
    city_code VARCHAR(200),
    salary VARCHAR(50),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE zhilian_data ( id INTEGER PRIMARY KEY AUTOINCREMENT, job_id VARCHAR(64), job_title VARCHAR(200), job_link VARCHAR(300), salary VARCHAR(100), location VARCHAR(100), experience VARCHAR(100), degree VARCHAR(100), company_name VARCHAR(200), delivery_status VARCHAR(20) DEFAULT '未投递', create_time DATETIME, update_time DATETIME);

CREATE TABLE zhilian_option ( id INTEGER PRIMARY KEY AUTOINCREMENT, type VARCHAR(50), name VARCHAR(100), code VARCHAR(100), sort_order INTEGER, created_at DATETIME, updated_at DATETIME);

CREATE UNIQUE INDEX idx_blacklist_type_value ON boss_blacklist(type, value);

CREATE INDEX idx_boss_browse_time ON boss_browse_event(occurred_at);

CREATE INDEX idx_boss_config_created ON boss_config(created_at);

CREATE INDEX idx_boss_config_updated ON boss_config(updated_at);

CREATE INDEX idx_boss_option_type ON boss_option(type);

CREATE UNIQUE INDEX idx_cookie_platform ON cookie(platform);

CREATE INDEX idx_job51_config_created ON job51_config(created_at);

CREATE INDEX idx_job51_config_updated ON job51_config(updated_at);

CREATE INDEX idx_job_profile_enabled ON job_profile(enabled, updated_at);

CREATE INDEX idx_liepin_option_type ON liepin_option(type);

CREATE INDEX idx_platform_delivery_event ON platform_delivery_event(platform, occurred_at);

CREATE INDEX idx_zhilian_config_created ON zhilian_config(created_at);

CREATE INDEX idx_zhilian_config_updated ON zhilian_config(updated_at);
