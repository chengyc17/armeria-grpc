package com.armeria.grpc.config.apollo;

import com.armeria.grpc.common.env.AppIdEnvironmentPostProcessor;
import com.armeria.grpc.common.env.AppIdResolver;
import com.armeria.grpc.config.api.ConfigProperties;
import com.armeria.grpc.config.api.ConfigSource;
import com.armeria.grpc.config.api.ConfigSourceFactory;
import com.armeria.grpc.common.server.GrpcServices;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;

/**
 * Apollo 配置源工厂：type=apollo。
 * 作为 Spring bean 装配，create 时解析 appId（缺省读 META-INF/app.config 的 appid）。
 */
public class ApolloConfigSourceFactory implements ConfigSourceFactory {

    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext context;
    private final Environment environment;

    public ApolloConfigSourceFactory(ApplicationEventPublisher eventPublisher,
                                     ApplicationContext context,
                                     Environment environment) {
        this.eventPublisher = eventPublisher;
        this.context = context;
        this.environment = environment;
    }

    @Override
    public String type() {
        return "apollo";
    }

    @Override
    public ConfigSource create(ConfigProperties props) {
        String appId = props.getAppId() != null && !props.getAppId().isBlank()
                ? props.getAppId()
                : AppIdResolver.resolve(
                        environment.getProperty(AppIdEnvironmentPostProcessor.APP_ID_PROPERTY),
                        GrpcServices.findAppId(context));
        ApolloConfigSource source = new ApolloConfigSource(props, appId, eventPublisher);
        source.start();
        return source;
    }
}
