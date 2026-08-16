package org.example.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import org.example.rpc.server.GrpcServices;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Properties;

/**
 * Nacos 配置中心自动配置：启动时按 dataId 拉取配置，并订阅变更实时更新。
 * 命名空间默认取本机 ops 目录 env.properties 的 env 值，group 默认取当前服务 appId。
 */
@AutoConfiguration
@EnableConfigurationProperties(NacosConfigProperties.class)
@ConditionalOnProperty(prefix = "rpc.config", name = "enabled", havingValue = "true", matchIfMissing = true)
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
        String namespace = properties.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            // 命名空间未配置时，取本机 ops 目录 env.properties 中的 env 值
            namespace = environment.getProperty("env");
        }
        if (namespace != null && !namespace.isBlank()) {
            props.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        }
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
                                             ApplicationEventPublisher eventPublisher) {
        String dataId = properties.getDataId();
        if (dataId == null || dataId.isBlank()) {
            throw new IllegalStateException(
                    "rpc.config.data-id must be configured (e.g. soa.json).");
        }
        String group = properties.getGroup();
        if (group == null || group.isBlank()) {
            // group 未配置时，默认取当前服务唯一的 appId
            group = GrpcServices.resolveAppId(context);
        }
        return new AppConfigService(configService, properties, dataId, group, eventPublisher);
    }
}
