package com.armeria.grpc.registry.api;

/**
 * 全角色注册中心：具体实现（Nacos / Eureka / K8s）都实现它。
 * 上层按角色依赖 {@link ServiceRegistrar} / {@link ServiceDiscovery}，不直接依赖本接口实现类。
 */
public interface Registry extends ServiceRegistrar, ServiceDiscovery {

    /**
     * 两个父接口各自声明了 default close()，必须在此显式合并。
     * 具体实现若需释放资源，直接覆写本方法（或调用父接口实现）。
     */
    @Override
    default void close() {
        ServiceDiscovery.super.close();
    }
}
