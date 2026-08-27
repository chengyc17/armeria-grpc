package org.example.rpc.server;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NacosInstanceRegistrarTest {

    @Test
    void registerPassesWeightAndIdcMetadata() throws Exception {
        NamingService namingService = mock(NamingService.class);
        NacosInstanceRegistrar registrar = new NacosInstanceRegistrar(namingService);

        registrar.register("app", "10.0.0.1", 8080, 2.5, Map.of("idc", "bj"));

        verify(namingService).registerInstance(eq("app"), eq("DEFAULT_GROUP"), argThat(argument -> {
            assertTrue(argument instanceof Instance);
            Instance instance = (Instance) argument;
            assertEquals("10.0.0.1", instance.getIp());
            assertEquals(8080, instance.getPort());
            assertEquals(2.5, instance.getWeight());
            assertEquals("bj", instance.getMetadata().get("idc"));
            return true;
        }));
    }

    @Test
    void registerWithoutMetadataKeepsEmptyMetadata() throws Exception {
        NamingService namingService = mock(NamingService.class);
        NacosInstanceRegistrar registrar = new NacosInstanceRegistrar(namingService);

        registrar.register("app", "10.0.0.1", 8080, 1.0, Map.of());

        verify(namingService).registerInstance(eq("app"), eq("DEFAULT_GROUP"), argThat(argument -> {
            Instance instance = (Instance) argument;
            assertEquals(1.0, instance.getWeight());
            return instance.getMetadata() == null || instance.getMetadata().isEmpty();
        }));
    }
}
