package com.armeria.grpc.config.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.armeria.grpc.config.api.ConfigSourceFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Nacos 配置中心自动装配：classpath 有 nacos-client 才生效，
 * 向容器注册一个 {@link ConfigSourceFactory}（type=nacos）。
 */
@AutoConfiguration
@ConditionalOnClass(ConfigService.class)
public class NacosConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConfigSourceFactory nacosConfigSourceFactory(ApplicationEventPublisher eventPublisher,
                                                        ApplicationContext context,
                                                        Environment environment) {
        return new NacosConfigSourceFactory(eventPublisher, context, environment);
    }
}
