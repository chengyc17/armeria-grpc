package org.example.config;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按命名空间懒创建并缓存 {@link NamingService}。
 *
 * <p>命名空间原先在启动期固定为 env 变量对应的值，现在由页面动态选择，
 * 因此每个命名空间对应一个独立的 NamingService 实例，进程退出时统一关闭。
 */
@Component
@EnableConfigurationProperties(TestConsoleProperties.class)
public class NamingServiceManager {

    private static final Logger log = LoggerFactory.getLogger(NamingServiceManager.class);

    /** public 命名空间标识；Nacos 各版本对 public 的 id 表述不一（空串或 "public"），统一归一化为该值 */
    public static final String PUBLIC_NAMESPACE = "public";

    private final TestConsoleProperties properties;
    private final Environment environment;
    private final Map<String, NamingService> cache = new ConcurrentHashMap<>();

    public NamingServiceManager(TestConsoleProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * 页面初始选中的默认命名空间：
     * test-console.nacos.namespace > 本机 ops 目录 env.properties 的 env 值 > public。
     */
    public String defaultNamespace() {
        String configured = properties.getNacos().getNamespace();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String env = environment.getProperty("env");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return PUBLIC_NAMESPACE;
    }

    /**
     * 获取指定命名空间的 NamingService，同一命名空间复用同一实例。
     */
    public NamingService forNamespace(String namespace) {
        String ns = namespace == null || namespace.isBlank() ? PUBLIC_NAMESPACE : namespace.trim();
        return cache.computeIfAbsent(ns, this::create);
    }

    private NamingService create(String namespace) {
        Properties props = new Properties();
        props.setProperty("serverAddr", properties.getNacos().getServerAddr());
        props.setProperty("namespace", namespace);
        String username = properties.getNacos().getUsername();
        if (username != null && !username.isBlank()) {
            props.setProperty("username", username);
            props.setProperty("password", properties.getNacos().getPassword() == null
                    ? "" : properties.getNacos().getPassword());
        }
        try {
            log.info("Creating NamingService for namespace {}", namespace);
            return NamingFactory.createNamingService(props);
        } catch (NacosException e) {
            throw new IllegalStateException("创建 Nacos 客户端失败 (namespace=" + namespace + "): " + e.getErrMsg(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        cache.forEach((namespace, service) -> {
            try {
                service.shutDown();
            } catch (NacosException e) {
                log.warn("Failed to shut down NamingService for namespace {}", namespace, e);
            }
        });
        cache.clear();
    }
}
