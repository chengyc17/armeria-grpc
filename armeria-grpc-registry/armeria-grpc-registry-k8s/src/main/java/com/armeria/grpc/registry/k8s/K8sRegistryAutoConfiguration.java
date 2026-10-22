package com.armeria.grpc.registry.k8s;

import com.armeria.grpc.registry.api.RegistryFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * K8s 注册中心自动装配：向容器注册一个 {@link RegistryFactory}（type=k8s）。
 * 极简 Service DNS 直连实现，零第三方运行时依赖、无后台线程。
 */
@AutoConfiguration
public class K8sRegistryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RegistryFactory k8sRegistryFactory(Environment environment) {
        return new K8sRegistryFactory(environment);
    }
}
