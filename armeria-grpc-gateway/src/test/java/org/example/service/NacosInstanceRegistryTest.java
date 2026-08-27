package org.example.service;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.example.config.GatewayProperties;
import org.example.support.FakeNamingService;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosInstanceRegistryTest {

    private final FakeNamingService naming = new FakeNamingService();
    private final NacosInstanceRegistry registry =
            new NacosInstanceRegistry(naming.proxy(), new GatewayProperties(), env(null));

    private static Environment env(String idc) {
        StandardEnvironment environment = new StandardEnvironment();
        if (idc != null && !idc.isBlank()) {
            environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("idc", idc)));
        }
        return environment;
    }

    private Instance instance(String ip, int port, boolean healthy) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setHealthy(healthy);
        instance.setEnabled(true);
        instance.setWeight(1.0);
        return instance;
    }

    @Test
    void firstAccessQueriesNacosAndSubscribes() {
        naming.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));

        ServiceSnapshot snapshot = registry.snapshot("order");

        assertEquals(1, snapshot.instances().size());
        assertEquals("10.0.0.1", snapshot.instances().get(0).ip());
        assertEquals(1, naming.queryCount());
        assertNotNull(naming.lastListener());
    }

    @Test
    void secondAccessUsesCache() {
        naming.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));

        registry.snapshot("order");
        registry.endpointGroup("order");

        assertEquals(1, naming.queryCount());
    }

    @Test
    void nacosEventUpdatesSnapshotAndEndpoints() {
        naming.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));
        NacosEndpointGroup group = registry.endpointGroup("order");
        assertEquals(1, group.endpoints().size());

        naming.lastListener().onEvent(new NamingEvent(
                "order", "DEFAULT_GROUP", "", List.of(instance("10.0.0.2", 8081, true))));

        assertEquals("10.0.0.2", group.endpoints().get(0).host());
        assertEquals("10.0.0.2", registry.snapshot("order").instances().get(0).ip());
    }

    @Test
    void unhealthyInstancesAreFiltered() {
        naming.defaultInstances(List.of(
                instance("10.0.0.1", 8080, true),
                instance("10.0.0.2", 8080, false)));

        ServiceSnapshot snapshot = registry.snapshot("order");

        assertEquals(1, snapshot.instances().size());
    }

    @Test
    void nacosWeightMappedToEndpointWeight() {
        Instance weighted = instance("10.0.0.1", 8080, true);
        weighted.setWeight(2.0);
        naming.defaultInstances(List.of(weighted));

        NacosEndpointGroup group = registry.endpointGroup("order");

        assertEquals(2000, group.endpoints().get(0).weight());
    }

    @Test
    void prefersSameIdcInstancesForRouting() {
        Instance bj = instance("10.0.0.1", 8080, true);
        bj.setMetadata(Map.of("idc", "bj"));
        Instance sh = instance("10.0.0.2", 8080, true);
        sh.setMetadata(Map.of("idc", "sh"));
        naming.defaultInstances(List.of(bj, sh));

        NacosInstanceRegistry bjRegistry =
                new NacosInstanceRegistry(naming.proxy(), new GatewayProperties(), env("bj"));
        NacosEndpointGroup group = bjRegistry.endpointGroup("order");

        assertEquals(1, group.endpoints().size());
        assertEquals("10.0.0.1", group.endpoints().get(0).host());
    }

    @Test
    void fallsBackToAllInstancesWhenNoSameIdcExists() {
        Instance sh = instance("10.0.0.2", 8080, true);
        sh.setMetadata(Map.of("idc", "sh"));
        naming.defaultInstances(List.of(sh));

        NacosInstanceRegistry bjRegistry =
                new NacosInstanceRegistry(naming.proxy(), new GatewayProperties(), env("bj"));
        NacosEndpointGroup group = bjRegistry.endpointGroup("order");

        assertEquals(1, group.endpoints().size());
        assertEquals("10.0.0.2", group.endpoints().get(0).host());
    }

    @Test
    void snapshotKeepsAllInstancesWhileRoutingPrefersSameIdc() {
        Instance bj = instance("10.0.0.1", 8080, true);
        bj.setMetadata(Map.of("idc", "bj"));
        Instance sh = instance("10.0.0.2", 8080, true);
        sh.setMetadata(Map.of("idc", "sh"));
        naming.defaultInstances(List.of(bj, sh));

        NacosInstanceRegistry bjRegistry =
                new NacosInstanceRegistry(naming.proxy(), new GatewayProperties(), env("bj"));

        assertEquals(2, bjRegistry.snapshot("order").instances().size());
        assertEquals(1, bjRegistry.endpointGroup("order").endpoints().size());
    }

    @Test
    void firstAccessFailureCachesEmptyAndDoesNotHammerNacos() {
        naming.nextQueryError(new NacosException(NacosException.SERVER_ERROR, "nacos down"));

        assertTrue(registry.snapshot("order").instances().isEmpty());
        assertTrue(registry.snapshot("order").instances().isEmpty());

        assertEquals(1, naming.queryCount());
    }

    @Test
    void refreshFailureKeepsStaleSnapshot() {
        naming.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));
        registry.snapshot("order");

        naming.nextQueryError(new NacosException(NacosException.SERVER_ERROR, "nacos down"));
        registry.refreshAll();

        assertEquals(1, registry.snapshot("order").instances().size());
    }

    @Test
    void refreshAppliesNacosChanges() {
        naming.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));
        registry.snapshot("order");

        naming.defaultInstances(List.of(
                instance("10.0.0.1", 8080, true),
                instance("10.0.0.2", 8080, true)));
        registry.refreshAll();

        assertEquals(2, registry.snapshot("order").instances().size());
    }

    @Test
    void closeUnsubscribesAll() {
        naming.defaultInstances(List.of());
        registry.snapshot("order");
        registry.close();
        assertTrue(naming.unsubscribed().contains("order"));
    }
}
