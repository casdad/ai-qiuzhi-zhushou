package com.getjobs.application.service;

import com.getjobs.application.entity.ConfigEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ConfigServiceEnvironmentTest {
    private ConfigService service() {
        return spy(new ConfigService(null, null, null, null, null));
    }

    @Test
    void environmentCredentialsOverrideDatabaseWithoutReadingIt() {
        ConfigService service = service();
        doReturn("placeholder-api-key").when(service).readEnvironmentValue("API_KEY");
        assertEquals("placeholder-api-key", service.getConfigValue("API_KEY"));
        verify(service, never()).getConfigByKey(anyString());
    }

    @Test
    void blankEnvironmentFallsBackToWebConfiguration() {
        ConfigService service = service();
        ConfigEntity saved = new ConfigEntity();
        saved.setConfigValue("configured-model");
        doReturn(" ").when(service).readEnvironmentValue("MODEL");
        doReturn(saved).when(service).getConfigByKey("MODEL");
        assertEquals("configured-model", service.getConfigValue("MODEL"));
    }

    @Test
    void falseNotificationFlagIsAValidOverride() {
        ConfigService service = service();
        doReturn("false").when(service).readEnvironmentValue("BOT_IS_SEND");
        assertEquals("false", service.getConfigValue("BOT_IS_SEND"));
    }

    @Test
    void otherConfigurationKeysContinueUsingDatabase() {
        ConfigService service = service();
        ConfigEntity saved = new ConfigEntity();
        saved.setConfigValue("local-setting");
        doReturn(saved).when(service).getConfigByKey("CUSTOM_SETTING");
        assertEquals("local-setting", service.getConfigValue("CUSTOM_SETTING"));
        verify(service, never()).readEnvironmentValue(anyString());
    }
}
