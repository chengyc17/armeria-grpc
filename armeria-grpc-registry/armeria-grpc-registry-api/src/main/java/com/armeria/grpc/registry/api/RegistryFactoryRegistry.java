package com.armeria.grpc.registry.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工厂类型注册表：收集容器内所有 {@link RegistryFactory} bean，按 type 建索引。
 *
 * <p>核心模块（server/gateway）只依赖本类 + {@link RegistryProperties}，
 * 不 import 任何具体实现类。配置里的 type 写错 / 依赖缺失时启动即报错，
 * 错误信息列出可用类型，不会等到运行时才发现。
 */
public class RegistryFactoryRegistry {

    private static final Logger log = LoggerFactory.getLogger(RegistryFactoryRegistry.class);

    private final Map<String, RegistryFactory> factories = new ConcurrentHashMap<>();

    public RegistryFactoryRegistry(List<RegistryFactory> factoryBeans) {
        if (factoryBeans != null) {
            for (RegistryFactory factory : factoryBeans) {
                RegistryFactory prev = factories.putIfAbsent(factory.type(), factory);
                if (prev != null) {
                    throw new IllegalStateException("重复的注册中心类型: " + factory.type()
                            + "（" + prev.getClass().getName() + " 与 " + factory.getClass().getName() + "）");
                }
                log.info("Registry factory registered: type={}, impl={}",
                        factory.type(), factory.getClass().getName());
            }
        }
    }

    /**
     * 按配置创建注册中心实例。
     *
     * @throws IllegalStateException type 不存在时，错误信息列出可用类型
     */
    public Registry create(RegistryProperties.Item props) throws Exception {
        RegistryFactory factory = factories.get(props.getType());
        if (factory == null) {
            throw new IllegalStateException("不支持的注册中心类型: " + props.getType()
                    + "，可用类型: " + factories.keySet()
                    + "。请检查是否引入了对应的 registry-* 依赖");
        }
        return factory.create(props);
    }

    /** 当前已注册的注册中心类型集合。 */
    public Set<String> types() {
        return factories.keySet();
    }
}
