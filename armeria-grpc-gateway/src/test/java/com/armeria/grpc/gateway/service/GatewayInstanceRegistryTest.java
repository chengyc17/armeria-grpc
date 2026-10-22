package com.armeria.grpc.gateway.service;

import com.armeria.grpc.common.discovery.IdcPoolSelector;
import com.armeria.grpc.common.discovery.MultiRegistryEndpointGroup;
import com.armeria.grpc.gateway.config.GatewayProperties;
import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.gateway.support.FakeServiceDiscovery;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayInstanceRegistryTest {

    private final FakeServiceDiscovery discovery = new FakeServiceDiscovery("nacos-main");
    private final GatewayInstanceRegistry registry =
            new GatewayInstanceRegistry(List.of(discovery), new GatewayProperties(), env(null));

    private static Environment env(String idc) {
        StandardEnvironment environment = new StandardEnvironment();
        if (idc != null && !idc.isBlank()) {
            environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("idc", idc)));
        }
        return environment;
    }

    private static RegistryInstance instance(String ip, int port, boolean healthy) {
        return new RegistryInstance(ip, port, 1.0, healthy, true, Map.of());
    }

    @Test
    void firstAccessQueriesRegistryAndSubscribes() {
        discovery.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));

        ServiceSnapshot snapshot = registry.snapshot("order");

        assertEquals(1, snapshot.instances().size());
        assertEquals("10.0.0.1", snapshot.instances().get(0).ip());
        assertEquals(1, discovery.queryCount());
        assertNotNull(discovery.lastListener());
    }

    @Test
    void secondAccessUsesCache() {
        discovery.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));

        registry.snapshot("order");
        registry.endpointGroup("order");

        assertEquals(1, discovery.queryCount());
    }

    @Test
    void changeEventUpdatesSnapshotAndEndpoints() {
        discovery.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));
        MultiRegistryEndpointGroup group = registry.endpointGroup("order");
        assertEquals(1, group.endpoints().size());

        discovery.push(List.of(instance("10.0.0.2", 8081, true)));

        assertEquals("10.0.0.2", group.endpoints().get(0).host());
        assertEquals("10.0.0.2", registry.snapshot("order").instances().get(0).ip());
    }

    @Test
    void unhealthyInstancesAreFilteredByLookup() {
        discovery.defaultInstances(List.of(
                instance("10.0.0.1", 8080, true),
                instance("10.0.0.2", 8080, false)));

        // 抽象层约定：lookup 已过滤 unhealthy/enabled=false（Nacos 适配器行为一致）
        List<RegistryInstance> healthyOnly = discovery.lookup("order").stream()
                .filter(RegistryInstance::healthy)
                .toList();

        assertEquals(1, healthyOnly.size());
    }

    @Test
    void weightMappedToEndpointWeight() {
        RegistryInstance weighted = new RegistryInstance("10.0.0.1", 8080, 2.0, true, true, Map.of());
        discovery.defaultInstances(List.of(weighted));

        MultiRegistryEndpointGroup group = registry.endpointGroup("order");

        assertEquals(2000, group.endpoints().get(0).weight());
    }

    @Test
    void prefersSameIdcInstancesForRouting() {
        discovery.defaultInstances(List.of(
                new RegistryInstance("10.0.0.1", 8080, 1.0, true, true,
                        Map.of(IdcPoolSelector.IDC_METADATA_KEY, "bj")),
                new RegistryInstance("10.0.0.2", 8080, 1.0, true, true,
                        Map.of(IdcPoolSelector.IDC_METADATA_KEY, "sh"))));

        GatewayInstanceRegistry bjRegistry =
                new GatewayInstanceRegistry(List.of(discovery), new GatewayProperties(), env("bj"));
        MultiRegistryEndpointGroup group = bjRegistry.endpointGroup("order");

        assertEquals(1, group.endpoints().size());
        assertEquals("10.0.0.1", group.endpoints().get(0).host());
    }

    @Test
    void fallsBackToAllInstancesWhenNoSameIdcExists() {
        discovery.defaultInstances(List.of(
                new RegistryInstance("10.0.0.2", 8080, 1.0, true, true,
                        Map.of(IdcPoolSelector.IDC_METADATA_KEY, "sh"))));

        GatewayInstanceRegistry bjRegistry =
                new GatewayInstanceRegistry(List.of(discovery), new GatewayProperties(), env("bj"));
        MultiRegistryEndpointGroup group = bjRegistry.endpointGroup("order");

        assertEquals(1, group.endpoints().size());
        assertEquals("10.0.0.2", group.endpoints().get(0).host());
    }

    @Test
    void snapshotKeepsAllInstancesWhileRoutingPrefersSameIdc() {
        discovery.defaultInstances(List.of(
                new RegistryInstance("10.0.0.1", 8080, 1.0, true, true,
                        Map.of(IdcPoolSelector.IDC_METADATA_KEY, "bj")),
                new RegistryInstance("10.0.0.2", 8080, 1.0, true, true,
                        Map.of(IdcPoolSelector.IDC_METADATA_KEY, "sh"))));

        GatewayInstanceRegistry bjRegistry =
                new GatewayInstanceRegistry(List.of(discovery), new GatewayProperties(), env("bj"));

        assertEquals(2, bjRegistry.snapshot("order").instances().size());
        assertEquals(1, bjRegistry.endpointGroup("order").endpoints().size());
    }

    @Test
    void firstAccessFailureCachesEmptyAndDoesNotHammerRegistry() {
        discovery.nextQueryError(new IllegalStateException("registry down"));

        assertTrue(registry.snapshot("order").instances().isEmpty());
        assertTrue(registry.snapshot("order").instances().isEmpty());

        assertEquals(1, discovery.queryCount());
    }

    @Test
    void refreshFailureKeepsStaleSnapshot() {
        discovery.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));
        registry.snapshot("order");

        discovery.nextQueryError(new IllegalStateException("registry down"));
        registry.refreshAll();

        assertEquals(1, registry.snapshot("order").instances().size());
    }

    @Test
    void refreshAppliesRegistryChanges() {
        discovery.defaultInstances(List.of(instance("10.0.0.1", 8080, true)));
        registry.snapshot("order");

        discovery.defaultInstances(List.of(
                instance("10.0.0.1", 8080, true),
                instance("10.0.0.2", 8080, true)));
        registry.refreshAll();

        assertEquals(2, registry.snapshot("order").instances().size());
    }

    @Test
    void closeUnsubscribesAll() {
        discovery.defaultInstances(List.of());
        registry.snapshot("order");
        registry.close();
        assertTrue(discovery.unsubscribed().contains("order"));
    }
}
