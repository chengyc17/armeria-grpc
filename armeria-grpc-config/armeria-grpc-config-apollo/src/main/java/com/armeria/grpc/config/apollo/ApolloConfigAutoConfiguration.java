package com.armeria.grpc.config.apollo;

import com.armeria.grpc.config.api.ConfigSourceFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Apollo 配置中心自动装配：向容器注册一个 {@link ConfigSourceFactory}（type=apollo）。
 * 纯 JDK HttpClient 实现，无需 @ConditionalOnClass。
 */
@AutoConfiguration
public class ApolloConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConfigSourceFactory apolloConfigSourceFactory(ApplicationEventPublisher eventPublisher,
                                                         ApplicationContext context,
                                                         Environment environment) {
        return new ApolloConfigSourceFactory(eventPublisher, context, environment);
    }
}
