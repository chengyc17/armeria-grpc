package com.armeria.grpc.registry.nacos;

import com.armeria.grpc.registry.api.Registry;
import com.armeria.grpc.registry.api.RegistryFactory;
import com.armeria.grpc.registry.api.RegistryProperties;

/**
 * Nacos 注册中心工厂。
 */
public class NacosRegistryFactory implements RegistryFactory {

    @Override
    public String type() {
        return "nacos";
    }

    @Override
    public Registry create(RegistryProperties.Item props) {
        return new NacosRegistry(props);
    }
}
