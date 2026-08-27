package org.example.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import org.example.common.env.AppIdEnvironmentPostProcessor;
import org.example.common.env.AppIdResolver;
import org.example.rpc.server.GrpcServices;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Nacos 配置中心自动配置：启动时按 dataId 拉取配置，并订阅变更实时更新。
 *
 * <p>经典数组配置方式：{@code rpc.config.data-ids} 可配置多个配置文件，全部拉取并订阅；
 * 命名空间默认取本机 ops 目录 env.properties 的 env 值，group 默认取当前服务 appId。
 */
@AutoConfiguration
@EnableConfigurationProperties(NacosConfigProperties.class)
@ConditionalOnProperty(prefix = "rpc.config", name = "server-addr")
public class NacosConfigAutoConfiguration {

    @Bean(destroyMethod = "shutDown")
    @ConditionalOnMissingBean(ConfigService.class)
    public ConfigService nacosConfigService(NacosConfigProperties properties, Environment environment) {
        String serverAddr = properties.getServerAddr();
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalStateException(
                    "rpc.config.server-addr must be configured (e.g. 127.0.0.1:8848).");
        }
        Properties props = new Properties();
        props.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        props.setProperty(PropertyKeyConst.NAMESPACE, environment.getProperty("env"));
        try {
            return NacosFactory.createConfigService(props);
        } catch (NacosException e) {
            throw new IllegalStateException("Failed to create Nacos ConfigService: " + e.getErrMsg(), e);
        }
    }

    @Bean
    @ConditionalOnMissingBean(AppConfigService.class)
    public AppConfigService appConfigService(ConfigService configService,
                                             NacosConfigProperties properties,
                                             ApplicationContext context,
                                             ApplicationEventPublisher eventPublisher,
                                             Environment environment) {
        List<String> dataIds = resolveDataIds(properties.getDataIds());
        String appId = AppIdResolver.resolve(
                environment.getProperty(AppIdEnvironmentPostProcessor.APP_ID_PROPERTY),
                GrpcServices.findAppId(context));
        return new AppConfigService(configService, properties, dataIds, appId, eventPublisher);
    }

    /**
     * 规整 data-ids：过滤空值，并确保默认的 {@link AppConfigService#SOA_CONFIG_DATA_ID} 始终存在。
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
        if (dataIds.stream().noneMatch(AppConfigService.SOA_CONFIG_DATA_ID::equalsIgnoreCase)) {
            dataIds.add(0, AppConfigService.SOA_CONFIG_DATA_ID);
        }
        return List.copyOf(dataIds);
    }
}
