package com.armeria.grpc.registry.eureka;

import com.armeria.grpc.registry.api.RegistryFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Eureka 注册中心自动装配：向容器注册一个 {@link RegistryFactory}（type=eureka）。
 * 纯 JDK HttpClient 实现，无需 @ConditionalOnClass。
 */
@AutoConfiguration
public class EurekaRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RegistryFactory eurekaRegistryFactory() {
        return new EurekaRegistryFactory();
    }
}
