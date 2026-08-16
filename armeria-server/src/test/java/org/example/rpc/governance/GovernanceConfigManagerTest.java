package org.example.rpc.governance;

import org.example.config.AppConfigService;
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
        AppConfigService appConfig = mock(AppConfigService.class);
        when(appConfig.getRawConfig())
                .thenReturn("{\"rateLimit\":{\"qps\":3000},\"blacklist\":[\"1.2.3.4\"]}");
        ObjectProvider<AppConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(
                new RpcGovernanceProperties(), provider, "user-service");
        manager.reload();

        assertEquals(3000, manager.getConfig().rateLimitQps());
        assertFalse(manager.isIpAllowed("1.2.3.4"));
        assertTrue(manager.isIpAllowed("2.2.2.2"));
    }

    @Test
    void keepsBaselineWhenConfigMissing() {
        AppConfigService appConfig = mock(AppConfigService.class);
        when(appConfig.getRawConfig()).thenReturn(null);
        ObjectProvider<AppConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(
                new RpcGovernanceProperties(), provider, "user-service");
        manager.reload();

        assertEquals(10_000, manager.getConfig().rateLimitQps());
        assertTrue(manager.isIpAllowed("1.1.1.1"));
    }

    @Test
    void enforcesInterfaceRateLimit() {
        AppConfigService appConfig = mock(AppConfigService.class);
        when(appConfig.getRawConfig())
                .thenReturn("{\"rateLimit\":{\"qps\":100},\"interfaces\":{\"GetUser\":1}}");
        ObjectProvider<AppConfigService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(appConfig);

        GovernanceConfigManager manager = new GovernanceConfigManager(
                new RpcGovernanceProperties(), provider, "user-service");
        manager.reload();

        assertTrue(manager.tryAcquire("com.example.UserService/GetUser", "GetUser"));
        assertFalse(manager.tryAcquire("com.example.UserService/GetUser", "GetUser"));
    }
}
