package com.armeria.grpc.config.api;

/**
 * 配置源 SPI：统一按 dataId 拉取 / 解析 / 订阅（订阅内部完成，变更发 Spring 事件）。
 *
 * <p>dataId 是统一配置标识：Nacos 里就是 dataId，Apollo 里映射为 namespace 名。
 * 具体实现（nacos / apollo）在内部订阅变更，解析后通过
 * {@link AppConfigChangedEvent} 通知上层，消费方无需感知具体配置中心。
 */
public interface ConfigSource extends AutoCloseable {

    /** 默认拉取的服务治理配置文件；缺失时治理使用空配置（全部放行） */
    String SOA_CONFIG_DATA_ID = "soa.json";

    /** 配置中心类型：nacos / apollo */
    String type();

    /** 最近一次加载/更新的指定 dataId 原始配置内容；未配置或拉取不到时为 null */
    String getRawConfig(String dataId);

    /** 指定 dataId 是否已成功加载 */
    boolean isLoaded(String dataId);

    /** 是否存在任意一个已加载的配置文件 */
    boolean isLoaded();

    /** 指定 dataId 的结构化配置：YAML 为嵌套 Map，properties 为扁平 Map */
    java.util.Map<String, Object> getConfig(String dataId);

    /** 指定 dataId 的扁平化 key=value 配置 */
    java.util.Map<String, String> getProperties(String dataId);

    /** 所有已配置文件的合并扁平视图（后配置的 dataId 覆盖先配置的同名 key） */
    java.util.Map<String, String> getProperties();

    /** 指定 dataId + key 取值，缺省返回 defaultValue */
    String getProperty(String dataId, String key, String defaultValue);

    /** 指定 dataId + key 取值，缺省返回 null */
    default String getProperty(String dataId, String key) {
        return getProperty(dataId, key, null);
    }

    /** 释放底层客户端连接；幂等。 */
    @Override
    default void close() {
    }
}
