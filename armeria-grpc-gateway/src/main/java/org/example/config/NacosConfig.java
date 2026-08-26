package org.example.config;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Properties;

/**
 * Nacos NamingService 配置，与 armeria-grpc-canary 保持一致。
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class NacosConfig {

    @Bean(destroyMethod = "shutDown")
    public NamingService namingService(GatewayProperties properties, Environment environment) throws Exception {
        Properties props = new Properties();
        props.setProperty("serverAddr", properties.getNacos().getServerAddr());
        props.setProperty("namespace", environment.getProperty("env"));
        return NamingFactory.createNamingService(props);
    }
}
