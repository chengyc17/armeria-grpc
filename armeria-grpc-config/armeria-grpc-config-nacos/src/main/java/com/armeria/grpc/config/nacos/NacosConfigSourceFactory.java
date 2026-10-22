package com.armeria.grpc.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.armeria.grpc.common.env.AppIdEnvironmentPostProcessor;
import com.armeria.grpc.common.env.AppIdResolver;
import com.armeria.grpc.config.api.ConfigProperties;
import com.armeria.grpc.config.api.ConfigSource;
import com.armeria.grpc.config.api.ConfigSourceFactory;
import com.armeria.grpc.common.server.GrpcServices;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Nacos 配置源工厂：type=nacos，创建 {@link AppConfigService}。
 *
 * <p>作为 Spring bean 装配（注入事件发布器/应用上下文/环境），
 * create 时解析 appId 作为 group（Nacos 配置分组），并保证默认 soa.json 始终在 data-ids 中。
 */
public class NacosConfigSourceFactory implements ConfigSourceFactory {

    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationContext context;
    private final Environment environment;

    public NacosConfigSourceFactory(ApplicationEventPublisher eventPublisher,
                                    ApplicationContext context,
                                    Environment environment) {
        this.eventPublisher = eventPublisher;
        this.context = context;
        this.environment = environment;
    }

    @Override
    public String type() {
        return "nacos";
    }

    @Override
    public ConfigSource create(ConfigProperties props) throws Exception {
        ConfigService configService = createConfigService(props);
        String appId = AppIdResolver.resolve(
                environment.getProperty(AppIdEnvironmentPostProcessor.APP_ID_PROPERTY),
                GrpcServices.findAppId(context));
        AppConfigService service = new AppConfigService(
                configService, props, resolveDataIds(props.getDataIds()), appId, eventPublisher);
        service.afterPropertiesSet();
        return service;
    }

    private static ConfigService createConfigService(ConfigProperties props) {
        String serverAddr = props.getServerAddr();
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalStateException(
                    "rpc.config.server-addr must be configured (e.g. 127.0.0.1:8848) for nacos config.");
        }
        Properties nacosProps = new Properties();
        nacosProps.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        try {
            return NacosFactory.createConfigService(nacosProps);
        } catch (NacosException e) {
            throw new IllegalStateException("Failed to create Nacos ConfigService: " + e.getErrMsg(), e);
        }
    }

    /**
     * 规整 data-ids：过滤空值，并确保默认的 {@link ConfigSource#SOA_CONFIG_DATA_ID} 始终存在。
     * 已配置时不会重复添加；未配置或列表为空时自动补上。
     */
    static List<String> resolveDataIds(List<String> configured) {
        List<String> dataIds = new ArrayList<>();
        if (configured != null) {
            for (String dataId : configured) {
                if (dataId != null && !dataId.isBlank()) {
                    dataIds.add(dataId.trim());
                }
            }
        }
        if (dataIds.stream().noneMatch(ConfigSource.SOA_CONFIG_DATA_ID::equalsIgnoreCase)) {
            dataIds.add(0, ConfigSource.SOA_CONFIG_DATA_ID);
        }
        return List.copyOf(dataIds);
    }
}
