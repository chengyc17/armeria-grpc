package com.armeria.grpc.registry.nacos;

import com.alibaba.nacos.api.naming.NamingService;
import com.armeria.grpc.registry.api.RegistryFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Nacos 注册中心自动装配：classpath 有 nacos-client 才生效，
 * 向容器注册一个 {@link RegistryFactory}（type=nacos）。
 */
@AutoConfiguration
@ConditionalOnClass(NamingService.class)
public class NacosRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RegistryFactory nacosRegistryFactory() {
        return new NacosRegistryFactory();
    }
}
