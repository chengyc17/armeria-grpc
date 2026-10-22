package com.armeria.grpc.config.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置源工厂注册表：收集容器内所有 {@link ConfigSourceFactory} bean，按 type 建索引。
 * 核心模块只依赖本类 + {@link ConfigProperties}，不 import 任何具体实现类。
 */
public class ConfigSourceFactoryRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceFactoryRegistry.class);

    private final Map<String, ConfigSourceFactory> factories = new ConcurrentHashMap<>();

    public ConfigSourceFactoryRegistry(List<ConfigSourceFactory> factoryBeans) {
        if (factoryBeans != null) {
            for (ConfigSourceFactory factory : factoryBeans) {
                ConfigSourceFactory prev = factories.putIfAbsent(factory.type(), factory);
                if (prev != null) {
                    throw new IllegalStateException("重复的配置中心类型: " + factory.type());
                }
                log.info("Config source factory registered: type={}, impl={}",
                        factory.type(), factory.getClass().getName());
            }
        }
    }

    /**
     * 按配置创建配置源。
     *
     * @throws IllegalStateException type 不存在时，错误信息列出可用类型
     */
    public ConfigSource create(ConfigProperties props) throws Exception {
        ConfigSourceFactory factory = factories.get(props.getType());
        if (factory == null) {
            throw new IllegalStateException("不支持的配置中心类型: " + props.getType()
                    + "，可用类型: " + factories.keySet()
                    + "。请检查是否引入了对应的 config-* 依赖");
        }
        return factory.create(props);
    }

    /** 当前已注册的配置中心类型集合。 */
    public Set<String> types() {
        return factories.keySet();
    }
}
