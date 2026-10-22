package com.armeria.grpc.registry.eureka;

import com.armeria.grpc.registry.api.Registry;
import com.armeria.grpc.registry.api.RegistryFactory;
import com.armeria.grpc.registry.api.RegistryProperties;

/**
 * Eureka 注册中心工厂。
 */
public class EurekaRegistryFactory implements RegistryFactory {

    @Override
    public String type() {
        return "eureka";
    }

    @Override
    public Registry create(RegistryProperties.Item props) {
        return new EurekaRegistry(props);
    }
}
