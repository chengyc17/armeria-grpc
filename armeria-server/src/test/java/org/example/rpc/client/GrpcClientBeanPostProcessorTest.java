package org.example.rpc.client;

import com.alibaba.nacos.api.naming.pojo.Instance;
import org.example.common.discovery.IdcPoolSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrpcClientBeanPostProcessorTest {

    private static Instance instance(String ip, String idc) {
        Instance instance = new Instance();
        instance.setIp(ip);
        if (idc != null) {
            instance.setMetadata(Map.of(IdcPoolSelector.IDC_METADATA_KEY, idc));
        }
        return instance;
    }

    @Test
    void prefersSameIdcInstances() {
        List<Instance> instances = List.of(
                instance("10.0.0.1", "bj"),
                instance("10.0.0.2", "sh"),
                instance("10.0.0.3", "bj"));

        List<Instance> pool = IdcPoolSelector.selectPool(instances, "bj", Instance::getMetadata);

        assertEquals(List.of("10.0.0.1", "10.0.0.3"),
                pool.stream().map(Instance::getIp).toList());
    }

    @Test
    void idcMatchIsCaseInsensitive() {
        List<Instance> instances = List.of(instance("10.0.0.1", "BJ"));

        List<Instance> pool = IdcPoolSelector.selectPool(instances, "bj", Instance::getMetadata);

        assertEquals(1, pool.size());
    }

    @Test
    void fallsBackToAllWhenNoSameIdc() {
        List<Instance> instances = List.of(instance("10.0.0.1", "sh"), instance("10.0.0.2", "gz"));

        List<Instance> pool = IdcPoolSelector.selectPool(instances, "bj", Instance::getMetadata);

        assertEquals(instances, pool);
    }

    @Test
    void fallsBackToAllWhenClientHasNoIdc() {
        List<Instance> instances = List.of(instance("10.0.0.1", "bj"), instance("10.0.0.2", "sh"));

        assertEquals(instances, IdcPoolSelector.selectPool(instances, "", Instance::getMetadata));
        assertEquals(instances, IdcPoolSelector.selectPool(instances, null, Instance::getMetadata));
    }

    @Test
    void ignoresInstancesWithoutIdcMetadataWhenClientHasIdc() {
        List<Instance> instances = List.of(instance("10.0.0.1", "bj"), instance("10.0.0.2", null));

        List<Instance> pool = IdcPoolSelector.selectPool(instances, "bj", Instance::getMetadata);

        assertEquals(List.of("10.0.0.1"), pool.stream().map(Instance::getIp).toList());
    }
}
