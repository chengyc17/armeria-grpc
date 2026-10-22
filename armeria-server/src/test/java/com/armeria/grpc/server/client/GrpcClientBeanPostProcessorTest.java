package com.armeria.grpc.server.client;

import com.armeria.grpc.common.discovery.IdcPoolSelector;
import com.armeria.grpc.registry.api.RegistryInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrpcClientBeanPostProcessorTest {

    private static RegistryInstance instance(String ip, String idc) {
        return new RegistryInstance(ip, 8080, 1.0, true, true,
                idc == null ? Map.of() : Map.of(IdcPoolSelector.IDC_METADATA_KEY, idc));
    }

    @Test
    void prefersSameIdcInstances() {
        List<RegistryInstance> instances = List.of(
                instance("10.0.0.1", "bj"),
                instance("10.0.0.2", "sh"),
                instance("10.0.0.3", "bj"));

        List<RegistryInstance> pool = IdcPoolSelector.selectPool(instances, "bj", RegistryInstance::metadata);

        assertEquals(List.of("10.0.0.1", "10.0.0.3"),
                pool.stream().map(RegistryInstance::ip).toList());
    }

    @Test
    void idcMatchIsCaseInsensitive() {
        List<RegistryInstance> instances = List.of(instance("10.0.0.1", "BJ"));

        List<RegistryInstance> pool = IdcPoolSelector.selectPool(instances, "bj", RegistryInstance::metadata);

        assertEquals(1, pool.size());
    }

    @Test
    void fallsBackToAllWhenNoSameIdc() {
        List<RegistryInstance> instances = List.of(instance("10.0.0.1", "sh"), instance("10.0.0.2", "gz"));

        List<RegistryInstance> pool = IdcPoolSelector.selectPool(instances, "bj", RegistryInstance::metadata);

        assertEquals(instances, pool);
    }

    @Test
    void fallsBackToAllWhenClientHasNoIdc() {
        List<RegistryInstance> instances = List.of(instance("10.0.0.1", "bj"), instance("10.0.0.2", "sh"));

        assertEquals(instances, IdcPoolSelector.selectPool(instances, "", RegistryInstance::metadata));
        assertEquals(instances, IdcPoolSelector.selectPool(instances, null, RegistryInstance::metadata));
    }

    @Test
    void ignoresInstancesWithoutIdcMetadataWhenClientHasIdc() {
        List<RegistryInstance> instances = List.of(instance("10.0.0.1", "bj"), instance("10.0.0.2", null));

        List<RegistryInstance> pool = IdcPoolSelector.selectPool(instances, "bj", RegistryInstance::metadata);

        assertEquals(List.of("10.0.0.1"), pool.stream().map(RegistryInstance::ip).toList());
    }
}
