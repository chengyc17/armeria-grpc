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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigServiceTest {

    private static final String DATA_ID = "user-service";
    private static final String GROUP = "DEFAULT_GROUP";

    private static AppConfigService newAppConfigService(ConfigService configService,
                                                        String dataId,
                                                        List<Object> events) {
        return new AppConfigService(configService, new NacosConfigProperties(), dataId, GROUP, events::add);
    }

    @Test
    void loadsPropertiesConfigAtStartup() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(DATA_ID, GROUP, 3000))
                .thenReturn("app.name=user-service\napp.port=8080\n");

        AppConfigService service = newAppConfigService(configService, DATA_ID, new ArrayList<>());
        service.afterPropertiesSet();

        assertTrue(service.isLoaded());
        assertEquals("user-service", service.getProperty("app.name"));
        assertEquals("8080", service.getProperty("app.port"));
        assertEquals("default", service.getProperty("missing", "default"));
        assertEquals(2, service.getProperties().size());
    }

    @Test
    void loadsYamlConfigAndFlattens() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig("user-service.yaml", GROUP, 3000))
                .thenReturn("server:\n  port: 8080\n  tags:\n    - a\n    - b\nname: demo\n");

        AppConfigService service = newAppConfigService(configService, "user-service.yaml", new ArrayList<>());
        service.afterPropertiesSet();

        assertEquals("8080", service.getProperty("server.port"));
        assertEquals("a", service.getProperty("server.tags[0]"));
        assertEquals("b", service.getProperty("server.tags[1]"));
        assertEquals("demo", service.getProperty("name"));
        assertInstanceOf(Map.class, service.getConfig().get("server"));
    }

    @Test
    void loadsJsonConfigAndFlattens() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig("soa.json", GROUP, 3000))
                .thenReturn("{\"global\":{\"rateLimit\":{\"qps\":10000}},\"user-service\":{\"blacklist\":[\"1.2.3.4\"]}}\n");

        AppConfigService service = newAppConfigService(configService, "soa.json", new ArrayList<>());
        service.afterPropertiesSet();

        assertEquals("10000", service.getProperty("global.rateLimit.qps"));
        assertEquals("1.2.3.4", service.getProperty("user-service.blacklist[0]"));
        assertInstanceOf(Map.class, service.getConfig().get("global"));
    }

    @Test
    void updatesConfigAndPublishesEventWhenNacosNotifies() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(DATA_ID, GROUP, 3000)).thenReturn("limit=10\n");

        List<Object> events = new ArrayList<>();
        AppConfigService service = newAppConfigService(configService, DATA_ID, events);
        service.afterPropertiesSet();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq(DATA_ID), eq(GROUP), listenerCaptor.capture());
        listenerCaptor.getValue().receiveConfigInfo("limit=20\nfeature.on=true\n");

        assertEquals("20", service.getProperty("limit"));
        assertEquals("true", service.getProperty("feature.on"));
        assertEquals(1, events.size());
        AppConfigChangedEvent event = assertInstanceOf(AppConfigChangedEvent.class, events.get(0));
        assertEquals(DATA_ID, event.getDataId());
        assertEquals(GROUP, event.getGroup());
        assertEquals("limit=10\n", event.getOldContent());
        assertEquals("limit=20\nfeature.on=true\n", event.getNewContent());
    }

    @Test
    void keepsPreviousConfigWhenUpdateIsInvalid() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(DATA_ID, GROUP, 3000)).thenReturn("limit=10\n");

        List<Object> events = new ArrayList<>();
        AppConfigService service = newAppConfigService(configService, DATA_ID, events);
        service.afterPropertiesSet();

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.forClass(Listener.class);
        verify(configService).addListener(eq(DATA_ID), eq(GROUP), listenerCaptor.capture());
        // 非法转义会让 Properties.load 抛异常，应保留旧配置
        listenerCaptor.getValue().receiveConfigInfo("limit=10\nbad=\\uZZZZ\n");

        assertEquals("10", service.getProperty("limit"));
        assertTrue(events.isEmpty());
    }

    @Test
    void retriesSubscriptionWhenInitialSubscribeFails() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getConfig(DATA_ID, GROUP, 3000)).thenReturn("a=1\n");
        doThrow(new NacosException(500, "boom"))
                .doNothing()
                .when(configService).addListener(eq(DATA_ID), eq(GROUP), any(Listener.class));

        NacosConfigProperties properties = new NacosConfigProperties();
        properties.setRetryIntervalMs(50);
        List<Object> events = new ArrayList<>();
        AppConfigService service = new AppConfigService(configService, properties, DATA_ID, GROUP, events::add);
        service.afterPropertiesSet();

        verify(configService, timeout(3000).atLeast(2))
                .addListener(eq(DATA_ID), eq(GROUP), any(Listener.class));
        service.destroy();
    }
}
