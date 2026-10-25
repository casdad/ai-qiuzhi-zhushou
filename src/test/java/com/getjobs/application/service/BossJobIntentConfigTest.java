package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.JobProfileEntity;
import com.getjobs.application.mapper.BlacklistMapper;
import com.getjobs.application.mapper.BossConfigMapper;
import com.getjobs.application.mapper.BossIndustryMapper;
import com.getjobs.application.mapper.BossJobDataMapper;
import com.getjobs.application.mapper.BossOptionMapper;
import com.getjobs.application.mapper.JobProfileMapper;
import com.getjobs.worker.boss.BossConfig;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BossJobIntentConfigTest {
    @TempDir Path directory;
    private SqlSession session;
    private JdbcTemplate jdbc;
    private JobProfileService profiles;
    private BossService boss;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("config.db"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE boss_config (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, debugger INTEGER, wait_time INTEGER,
                  keywords TEXT, city_code TEXT, industry TEXT, job_type TEXT, experience TEXT,
                  degree TEXT, salary TEXT, scale TEXT, stage TEXT, say_hi TEXT,
                  expected_salary_min INTEGER, expected_salary_max INTEGER,
                  enable_ai INTEGER, send_img_resume INTEGER, filter_dead_hr INTEGER,
                  dead_status TEXT, created_at TIMESTAMP, updated_at TIMESTAMP)
                """);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(JobProfileMapper.class);
        config.addMapper(BossConfigMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true);
        profiles = new JobProfileService(session.getMapper(JobProfileMapper.class), source);
        boss = new BossService(mock(BossOptionMapper.class), mock(BossIndustryMapper.class),
                session.getMapper(BossConfigMapper.class), mock(BlacklistMapper.class),
                mock(BossJobDataMapper.class), source, profiles);
        profiles.ensureTable();
        boss.ensureBossConfigSalaryUnitColumn();
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
    }

    @Test
    void migrationsUpgradeLegacyTablesIdempotentlyWithoutReplacingData() {
        jdbc.execute("ALTER TABLE job_profile DROP COLUMN job_intent");
        jdbc.execute("ALTER TABLE boss_config DROP COLUMN job_intent");
        jdbc.execute("ALTER TABLE boss_config DROP COLUMN wait_time_max");
        jdbc.update("INSERT INTO job_profile (name, ai_introduce) VALUES (?, ?)", "Existing", "Python PyTorch");
        jdbc.update("INSERT INTO boss_config (wait_time) VALUES (120)");
        profiles.ensureTable();
        boss.ensureBossConfigSalaryUnitColumn();
        profiles.ensureTable();
        boss.ensureBossConfigSalaryUnitColumn();

        assertEquals("Python PyTorch", profiles.list().getFirst().getAiIntroduce());
        assertNull(profiles.list().getFirst().getJobIntent());
        assertNull(boss.getFirstConfig().getJobIntent());
        assertEquals(120, boss.getFirstConfig().getWaitTime());
        assertNull(boss.getFirstConfig().getWaitTimeMax());
        assertEquals("120", boss.loadBossConfig().getWaitTimeMax());
    }

    @Test
    void savedProfileIntentAndTechnicalStackLoadTogetherWithGlobalInterval() {
        BossConfigEntity fallback = new BossConfigEntity();
        fallback.setWaitTime(120);
        fallback.setWaitTimeMax(180);
        fallback.setJobIntent("Fallback direction");
        boss.saveOrUpdateFirstSelective(fallback);
        JobProfileEntity profile = new JobProfileEntity();
        profile.setName("Vision profile");
        profile.setJobIntent("  Computer vision, deep learning and object detection  ");
        profile.setAiIntroduce("Python PyTorch; trained object detectors");
        profile.setEnableAiRelevance(1);
        profile.setMinAiScore(75);
        profiles.save(profile);

        BossConfig loaded = boss.loadBossConfig();
        assertEquals("Computer vision, deep learning and object detection", loaded.getJobIntent());
        assertEquals(profile.getAiIntroduce(), loaded.getAiIntroduce());
        assertEquals("120", loaded.getWaitTime());
        assertEquals("180", loaded.getWaitTimeMax());
        assertTrue(loaded.getEnableAiRelevance());
        assertEquals(75, loaded.getMinAiScore());
    }

    @Test
    void partialAndFullUpdatesRoundTripAndClearIntentWithoutLosingTheTechnicalStack() {
        JobProfileEntity profile = new JobProfileEntity();
        profile.setName("Vision profile");
        profile.setAiIntroduce("Python PyTorch");
        profile.setJobIntent("Initial direction");
        profiles.save(profile);
        JobProfileEntity patch = new JobProfileEntity();
        patch.setId(profile.getId());
        patch.setJobIntent("Updated direction");
        profiles.update(patch);
        JobProfileEntity saved = profiles.get(profile.getId());
        assertEquals("Updated direction", saved.getJobIntent());
        assertEquals("Python PyTorch", saved.getAiIntroduce());

        saved.setJobIntent("");
        profiles.update(saved, true);
        assertEquals("", boss.loadBossConfig().getJobIntent());
        assertEquals("Python PyTorch", boss.loadBossConfig().getAiIntroduce());
    }

    @Test
    void noActiveProfileUsesMirroredIntentAndDefaultsTo60To120Seconds() {
        assertEquals("60", boss.loadBossConfig().getWaitTime());
        assertEquals("120", boss.loadBossConfig().getWaitTimeMax());
        BossConfigEntity config = new BossConfigEntity();
        config.setJobIntent("Computer vision");
        config.setEnableAiRelevance(1);
        boss.saveOrUpdateFirstSelective(config);
        assertEquals(60, boss.getFirstConfig().getWaitTime());
        assertEquals(120, boss.getFirstConfig().getWaitTimeMax());
        assertEquals("Computer vision", boss.loadBossConfig().getJobIntent());
        assertTrue(boss.loadBossConfig().getEnableAiRelevance());

        BossConfigEntity patch = new BossConfigEntity();
        patch.setJobIntent("");
        boss.saveOrUpdateFirstSelective(patch);
        assertEquals("", boss.loadBossConfig().getJobIntent());
        assertEquals("60", boss.loadBossConfig().getWaitTime());
        assertEquals("120", boss.loadBossConfig().getWaitTimeMax());
    }

    @Test
    void intervalBoundsSurviveSelectiveUpdatesAndLoadWithoutAProfile() {
        BossConfigEntity config = new BossConfigEntity();
        config.setWaitTime(120);
        config.setWaitTimeMax(200);
        boss.saveConfig(config);

        BossConfigEntity patch = new BossConfigEntity();
        patch.setWaitTimeMax(180);
        boss.saveOrUpdateFirstSelective(patch);
        assertEquals("120", boss.loadBossConfig().getWaitTime());
        assertEquals("180", boss.loadBossConfig().getWaitTimeMax());

        BossConfigEntity byId = new BossConfigEntity();
        byId.setId(config.getId());
        byId.setWaitTimeMax(200);
        boss.updateConfig(byId);
        assertEquals("120", boss.loadBossConfig().getWaitTime());
        assertEquals("200", boss.loadBossConfig().getWaitTimeMax());
    }

    @Test
    void invalidBoundsAreRejectedBeforeUpdatingTheDatabase() {
        boss.saveOrUpdateFirstSelective(new BossConfigEntity());
        for (int max : new int[] {0, -1, 59, 3601}) {
            BossConfigEntity patch = new BossConfigEntity();
            patch.setWaitTimeMax(max);
            assertThrows(IllegalArgumentException.class, () -> boss.saveOrUpdateFirstSelective(patch));
            patch.setId(boss.getFirstConfig().getId());
            assertThrows(IllegalArgumentException.class, () -> boss.updateConfig(patch));
            assertThrows(IllegalArgumentException.class, () -> boss.saveConfig(patch));
        }
        assertEquals(60, boss.getFirstConfig().getWaitTime());
        assertEquals(120, boss.getFirstConfig().getWaitTimeMax());
        BossConfigEntity invalidMin = new BossConfigEntity();
        invalidMin.setWaitTime(201);
        assertThrows(IllegalArgumentException.class, () -> boss.saveOrUpdateFirstSelective(invalidMin));
        assertEquals(60, boss.getFirstConfig().getWaitTime());
    }

    @Test
    void legacyIntervalsLongerThanTheDefaultMaximumAreNotShortened() {
        jdbc.update("INSERT INTO boss_config (wait_time) VALUES (300)");
        assertEquals("300", boss.loadBossConfig().getWaitTime());
        assertEquals("300", boss.loadBossConfig().getWaitTimeMax());
    }
}
