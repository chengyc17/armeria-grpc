package com.armeria.grpc.registry.api;

/**
 * 注册中心工厂：type -> 创建实例。
 *
 * <p>每个 registry-* 实现模块提供一个工厂 bean，Spring 容器收集所有工厂后交给
 * {@link RegistryFactoryRegistry} 按 type 建索引。切换注册中心 = 换依赖 + 改 type + 重启。
 */
public interface RegistryFactory {

    /** 工厂支持的注册中心类型，如 nacos / eureka / k8s */
    String type();

    /**
     * 按配置创建注册中心实例。
     *
     * @param props 单中心配置（name/type/serverAddr/namespace/group/weight/options）
     */
    Registry create(RegistryProperties.Item props) throws Exception;
}
