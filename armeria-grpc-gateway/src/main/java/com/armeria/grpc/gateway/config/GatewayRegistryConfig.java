package com.armeria.grpc.gateway.config;

import com.armeria.grpc.registry.api.Registry;
import com.armeria.grpc.registry.api.RegistryFactory;
import com.armeria.grpc.registry.api.RegistryFactoryRegistry;
import com.armeria.grpc.registry.api.RegistryProperties;
import com.armeria.grpc.registry.api.RegistryResolvers;
import com.armeria.grpc.registry.api.ServiceDiscovery;
import com.armeria.grpc.gateway.service.DiscoveryManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关注册中心抽象层装配：收集 {@link RegistryFactory} bean → 按
 * {@code rpc.registry.registries[]} 创建各中心实例 → 暴露发现侧视图。
 *
 * <p>网关只发现不注册（无 @GrpcService，不需要注册本实例）。</p>
 */
@Configuration
@EnableConfigurationProperties({GatewayProperties.class, RegistryProperties.class})
public class GatewayRegistryConfig {

    @Bean
    public RegistryFactoryRegistry registryFactoryRegistry(List<RegistryFactory> factories) {
        return new RegistryFactoryRegistry(factories);
    }

    @Bean(destroyMethod = "close")
    public DiscoveryManager discoveryManager(RegistryFactoryRegistry factoryRegistry,
                                             RegistryProperties registryProperties,
                                             Environment environment) throws Exception {
        List<RegistryProperties.Item> items = RegistryResolvers.resolve(registryProperties, environment);
        List<ServiceDiscovery> discoveries = new ArrayList<>();
        List<AutoCloseable> closeables = new ArrayList<>();
        for (RegistryProperties.Item item : items) {
            Registry registry = factoryRegistry.create(item);
            closeables.add(registry);
            discoveries.add(registry);
        }
        return new DiscoveryManager(discoveries, closeables);
    }

    @Bean
    public List<ServiceDiscovery> serviceDiscoveries(DiscoveryManager discoveryManager) {
        return discoveryManager.discoveries();
    }
}
