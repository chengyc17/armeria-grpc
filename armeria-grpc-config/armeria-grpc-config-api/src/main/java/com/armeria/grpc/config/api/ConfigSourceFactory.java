package com.armeria.grpc.config.api;

/**
 * 配置源工厂：type -> 创建 ConfigSource。
 *
 * <p>每个 config-* 实现模块提供一个工厂 bean，容器收集后交给
 * {@link ConfigSourceFactoryRegistry} 按 type 建索引。切换配置中心 = 换依赖 + 改 type + 重启。
 */
public interface ConfigSourceFactory {

    /** 工厂支持的配置中心类型，如 nacos / apollo */
    String type();

    /** 按配置创建配置源。 */
    ConfigSource create(ConfigProperties props) throws Exception;
}
