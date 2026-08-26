package org.example.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigServiceTest {

    private static final String SOA_DATA_ID = "soa.json";
    private static final String APP_DATA_ID = "user-service.yaml";
    private static final String GROUP = "100004336";

    private static AppConfigService newAppConfigService(ConfigService configService,
                                                        List<String> dataIds,
                                                        List<Object> events) {
        return new AppConfigService(configService, new NacosConfigProperties(), dataIds, GROUP, events::add);
    }

    @Test
    void loadsAllConfiguredDataIds() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(SOA_DATA_ID, GROUP, 3000))
                .thenReturn("{\"global\":{\"rateLimit\":{\"qps\":10000}},\"100004336\":{\"blacklist\":[\"1.2.3.4\"]}}");
        when(configService.getConfig(APP_DATA_ID, GROUP, 3000))
                .thenReturn("server:\n  port: 8080\n  tags:\n    - a\n    - b\nname: user-service\n");

        AppConfigService service = newAppConfigService(configService, List.of(SOA_DATA_ID, APP_DATA_ID),
                new ArrayList<>());
        service.afterPropertiesSet();

        // 按 dataId 读取
        assertEquals("10000", service.getProperty(SOA_DATA_ID, "global.rateLimit.qps"));
        assertEquals("1.2.3.4", service.getProperty(SOA_DATA_ID, "100004336.blacklist[0]"));
        assertEquals("8080", service.getProperty(APP_DATA_ID, "server.port"));
        assertEquals("a", service.getProperty(APP_DATA_ID, "server.tags[0]"));
        assertInstanceOf(Map.class, service.getConfig(APP_DATA_ID).get("server"));
        assertTrue(service.isLoaded(SOA_DATA_ID));
        assertTrue(service.isLoaded(APP_DATA_ID));

        // 合并视图包含两个文件的 key
        Map<String, String> merged = service.getProperties();
        assertEquals("10000", merged.get("global.rateLimit.qps"));
        assertEquals("8080", merged.get("server.port"));
    }

    @Test
    void missingSoaJsonDoesNotBreakStartup() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        // 不 stub getConfig，Mockito 默认返回 null，模拟 soa.json 未配置

        AppConfigService service = newAppConfigService(configService, List.of(SOA_DATA_ID), new ArrayList<>());
        service.afterPropertiesSet();

        assertFalse(service.isLoaded(SOA_DATA_ID));
        assertNull(service.getRawConfig(SOA_DATA_ID));
        assertEquals(Map.of(), service.getConfig(SOA_DATA_ID));
        assertEquals(Map.of(), service.getProperties(SOA_DATA_ID));
    }

    @Test
    void updatesConfigAndPublishesEventWhenNacosNotifies() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(SOA_DATA_ID, GROUP, 3000))
                .thenReturn("{\"rateLimit\":{\"qps\":10000}}");

        List<Object> events = new ArrayList<>();
        AppConfigService service = newAppConfigService(configService, List.of(SOA_DATA_ID), events);
        service.afterPropertiesSet();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq(SOA_DATA_ID), eq(GROUP), listenerCaptor.capture());
        listenerCaptor.getValue().receiveConfigInfo("{\"rateLimit\":{\"qps\":20000}}");

        assertEquals("20000", service.getProperty(SOA_DATA_ID, "rateLimit.qps"));
        assertEquals(1, events.size());
        AppConfigChangedEvent event = assertInstanceOf(AppConfigChangedEvent.class, events.get(0));
        assertEquals(SOA_DATA_ID, event.getDataId());
        assertEquals(GROUP, event.getGroup());
        assertEquals("{\"rateLimit\":{\"qps\":10000}}", event.getOldContent());
        assertEquals("{\"rateLimit\":{\"qps\":20000}}", event.getNewContent());
    }

    @Test
    void keepsPreviousConfigWhenUpdateIsInvalid() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(SOA_DATA_ID, GROUP, 3000))
                .thenReturn("{\"rateLimit\":{\"qps\":10000}}");

        List<Object> events = new ArrayList<>();
        AppConfigService service = newAppConfigService(configService, List.of(SOA_DATA_ID), events);
        service.afterPropertiesSet();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq(SOA_DATA_ID), eq(GROUP), listenerCaptor.capture());
        // 非法 JSON 应保留旧配置
        listenerCaptor.getValue().receiveConfigInfo("{not-json");

        assertEquals("10000", service.getProperty(SOA_DATA_ID, "rateLimit.qps"));
        assertTrue(events.isEmpty());
    }

    @Test
    void retriesSubscriptionWhenInitialSubscribeFails() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(SOA_DATA_ID, GROUP, 3000)).thenReturn("a=1\n");
        doThrow(new NacosException(500, "boom"))
                .doNothing()
                .when(configService).addListener(eq(SOA_DATA_ID), eq(GROUP), any(Listener.class));

        NacosConfigProperties properties = new NacosConfigProperties();
        properties.setRetryIntervalMs(50);
        List<Object> events = new ArrayList<>();
        AppConfigService service = new AppConfigService(configService, properties,
                List.of(SOA_DATA_ID), GROUP, events::add);
        service.afterPropertiesSet();

        verify(configService, timeout(3000).atLeast(2))
                .addListener(eq(SOA_DATA_ID), eq(GROUP), any(Listener.class));
        service.destroy();
    }
}
