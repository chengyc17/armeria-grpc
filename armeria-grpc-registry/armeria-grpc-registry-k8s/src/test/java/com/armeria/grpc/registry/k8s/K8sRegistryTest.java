package com.armeria.grpc.registry.k8s;

import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.registry.api.RegistryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * K8s 极简注册中心（Service DNS 直连）单测：域名拼接、namespace 默认取 env、port 校验、订阅快照。
 */
class K8sRegistryTest {

    private static RegistryProperties.Item item(String namespace, Map<String, String> options) {
        RegistryProperties.Item item = new RegistryProperties.Item();
        item.setName("k8s-test");
        item.setType("k8s");
        item.setNamespace(namespace);
        item.setOptions(options);
        return item;
    }

    @Test
    void lookupReturnsServiceDnsSingleInstance() {
        K8sRegistry registry = new K8sRegistry(
                item("my-ns", Map.of("port", "18081")), "my-ns");

        List<RegistryInstance> instances = registry.lookup("100004337");

        assertEquals(1, instances.size());
        RegistryInstance instance = instances.get(0);
        // service 名 = appId，域名 = {appId}.{namespace}.svc.cluster.local
        assertEquals("100004337.my-ns.svc.cluster.local", instance.ip());
        assertEquals(18081, instance.port());
        assertEquals(1.0, instance.weight());
        assertTrue(instance.healthy());
        assertTrue(instance.enabled());
    }

    @Test
    void namespaceDefaultsToOpsEnv() {
        Environment env = mock(Environment.class);
        when(env.getProperty("env")).thenReturn("fat");
        K8sRegistryFactory factory = new K8sRegistryFactory(env);

        K8sRegistry registry = (K8sRegistry) factory.create(
                item(null, Map.of("port", "18081")));

        assertEquals("100004337.fat.svc.cluster.local",
                registry.lookup("100004337").get(0).ip());
    }

    @Test
    void explicitNamespaceOverridesOpsEnv() {
        Environment env = mock(Environment.class);
        when(env.getProperty("env")).thenReturn("fat");
        K8sRegistryFactory factory = new K8sRegistryFactory(env);

        K8sRegistry registry = (K8sRegistry) factory.create(
                item("prod", Map.of("port", "18081")));

        assertEquals("100004337.prod.svc.cluster.local",
                registry.lookup("100004337").get(0).ip());
    }

    @Test
    void missingNamespaceAndEnvFailsFast() {
        Environment env = mock(Environment.class);
        when(env.getProperty("env")).thenReturn(null);
        K8sRegistryFactory factory = new K8sRegistryFactory(env);

        assertThrows(IllegalStateException.class,
                () -> factory.create(item(null, Map.of("port", "18081"))));
    }

    @Test
    void portIsRequired() {
        assertThrows(IllegalStateException.class,
                () -> new K8sRegistry(item("ns", Map.of()), "ns"));
    }

    @Test
    void customClusterDomainIsSupported() {
        K8sRegistry registry = new K8sRegistry(
                item("ns", Map.of("port", "18081", "cluster-domain", "svc.cluster.local")), "ns");
        assertEquals("app1.ns.svc.cluster.local", registry.lookup("app1").get(0).ip());

        K8sRegistry custom = new K8sRegistry(
                item("ns", Map.of("port", "18081", "cluster-domain", "svc.example.com")), "ns");
        assertEquals("app1.ns.svc.example.com", custom.lookup("app1").get(0).ip());
    }

    @Test
    void registerIsNoOp() {
        K8sRegistry registry = new K8sRegistry(item("ns", Map.of("port", "18081")), "ns");
        // 不抛异常即视为通过（K8s 由 Deployment/Service 维护 endpoints）
        registry.register("100004337", "10.0.0.1", 18081, 1.0, Map.of());
        registry.deregister("100004337", "10.0.0.1", 18081);
    }

    @Test
    void subscribePushesSnapshotImmediately() {
        K8sRegistry registry = new K8sRegistry(item("ns", Map.of("port", "18081")), "ns");
        AtomicReference<List<RegistryInstance>> snapshot = new AtomicReference<>();

        registry.subscribe("100004337", snapshot::set);

        assertEquals(1, snapshot.get().size());
        assertEquals("100004337.ns.svc.cluster.local", snapshot.get().get(0).ip());
        // unsubscribe 后不再有任何行为（无后台线程，仅验证可重复退订不抛错）
        registry.unsubscribe("100004337", snapshot::set);
        registry.close();
        assertFalse(registry.lookup("x").isEmpty());
    }
}
