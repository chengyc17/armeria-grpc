package com.armeria.grpc.registry.api;

import java.util.List;

/**
 * 发现侧角色接口（客户端/网关使用）：查询实例 + 订阅变更。
 *
 * <p>角色隔离：客户端/网关只注入 {@link List}<code>ServiceDiscovery</code>，
 * 拿不到注册侧能力。多中心时注入多个 discovery，由上层按 ip:port 合并去重。
 */
public interface ServiceDiscovery extends AutoCloseable {

    /** 注册中心标识，对应配置里的 name */
    String name();

    /** 注册中心类型，对应配置里的 type */
    String type();

    /**
     * 查询某服务的当前实例列表。实现必须已过滤 unhealthy/disabled，
     * 或标记 healthy=false 由上层过滤（与现状 Nacos 行为一致）。
     *
     * @param serviceName 服务名（即 appId）
     */
    List<RegistryInstance> lookup(String serviceName);

    /**
     * 订阅实例变更。幂等：同一 listener 重复订阅不产生双份推送。
     * 回调必须在异步线程执行，不得阻塞调用方。
     */
    void subscribe(String serviceName, Listener listener);

    /** 取消订阅。 */
    void unsubscribe(String serviceName, Listener listener);

    /** 释放底层连接；幂等。 */
    @Override
    default void close() {
    }

    @FunctionalInterface
    interface Listener {
        void onInstancesChanged(List<RegistryInstance> instances);
    }
}
