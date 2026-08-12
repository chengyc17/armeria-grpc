package org.example.config;

import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
@EnableConfigurationProperties(TestConsoleProperties.class)
public class NacosConfig {

    @Bean(destroyMethod = "shutDown")
    public NamingService namingService(TestConsoleProperties properties) throws Exception {
        Properties props = new Properties();
        props.setProperty("serverAddr", properties.getNacos().getServerAddr());
        String namespace = properties.getNacos().getNamespace();
        if (namespace != null && !namespace.isBlank()) {
            props.setProperty("namespace", namespace);
        }
        return NamingFactory.createNamingService(props);
    }
}
