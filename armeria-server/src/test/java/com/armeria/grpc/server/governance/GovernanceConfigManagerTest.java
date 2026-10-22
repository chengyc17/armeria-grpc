package com.armeria.grpc.server.governance;

import com.armeria.grpc.config.api.ConfigSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceConfigManagerTest {

    @Test
    void reloadAppliesSoaJson() {
        ConfigSource appConfig = mock(ConfigSource.class);
        when(appConfig.getRawConfig(ConfigSource.SOA_CONFIG_DATA_ID))
                .thenReturn("{\"rateLimit\":{\"qps\":3000},\"blacklist\":[\"order-service\"]}");
        ObjectProvider<ConfigSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(provider, "user-service");
        manager.reload();

        assertEquals(3000, manager.getConfig().rateLimitQps());
        assertFalse(manager.isAppIdAllowed("order-service"));
        assertTrue(manager.isAppIdAllowed("web-front"));
    }

    @Test
    void appIdMatchIsCaseInsensitiveAndTrimmed() {
        ConfigSource appConfig = mock(ConfigSource.class);
        when(appConfig.getRawConfig(ConfigSource.SOA_CONFIG_DATA_ID))
                .thenReturn("{\"blacklist\":[\" Order-Service \"]}");
        ObjectProvider<ConfigSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(provider, "user-service");
        manager.reload();

        assertFalse(manager.isAppIdAllowed("ORDER-SERVICE"));
        assertFalse(manager.isAppIdAllowed("order-service"));
        assertTrue(manager.isAppIdAllowed("other-app"));
    }

    @Test
    void whitelistDeniesUnknownAndMissingAppId() {
        ConfigSource appConfig = mock(ConfigSource.class);
        when(appConfig.getRawConfig(ConfigSource.SOA_CONFIG_DATA_ID))
                .thenReturn("{\"whitelist\":[\"web-front\",\"mobile-api\"]}");
        ObjectProvider<ConfigSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(provider, "user-service");
        manager.reload();

        assertTrue(manager.isAppIdAllowed("web-front"));
        assertFalse(manager.isAppIdAllowed("unknown-app"));
        // 未携带 appId 的调用方不在白名单内，同样拒绝
        assertFalse(manager.isAppIdAllowed(null));
    }

    @Test
    void noGovernanceWhenConfigMissing() {
        // soa.json 缺失：不限流、不熔断、名单为空，全部放行
        ConfigSource appConfig = mock(ConfigSource.class);
        when(appConfig.getRawConfig(ConfigSource.SOA_CONFIG_DATA_ID)).thenReturn(null);
        ObjectProvider<ConfigSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(provider, "user-service");
        manager.reload();

        assertFalse(manager.getConfig().rateLimitEnabled());
        assertEquals(-1, manager.getConfig().rateLimitQps());
        assertTrue(manager.isAppIdAllowed("any-app"));
        assertTrue(manager.isAppIdAllowed(null));
        assertTrue(manager.tryAcquire("com.example.UserService/GetUser", "GetUser"));
        assertTrue(manager.tryAcquireCircuitBreaker());
    }

    @Test
    void enforcesInterfaceRateLimit() {
        ConfigSource appConfig = mock(ConfigSource.class);
        when(appConfig.getRawConfig(ConfigSource.SOA_CONFIG_DATA_ID))
                .thenReturn("{\"rateLimit\":{\"qps\":100},\"interfaces\":{\"GetUser\":1}}");
        ObjectProvider<ConfigSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(provider, "user-service");
        manager.reload();

        assertTrue(manager.tryAcquire("com.example.UserService/GetUser", "GetUser"));
        assertFalse(manager.tryAcquire("com.example.UserService/GetUser", "GetUser"));
    }
}
