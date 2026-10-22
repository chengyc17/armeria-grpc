package com.armeria.grpc.registry.api;

import java.util.Map;

/**
 * 注册侧角色接口（服务端使用）：只负责把本实例注册/注销到注册中心。
 *
 * <p>角色隔离：服务端只注入 {@link List}<code>ServiceRegistrar</code>，
 * 拿不到发现侧能力（lookup/subscribe）。具体实现（Nacos/Eureka）同时实现
 * {@link Registry}（注册+发现），K8s 是纯发现源，注册方法为 no-op。
 */
public interface ServiceRegistrar extends AutoCloseable {

    /** 注册中心标识，对应配置里的 name，如 nacos-main */
    String name();

    /** 注册中心类型，对应配置里的 type，如 nacos / eureka / k8s */
    String type();

    /**
     * 注册本实例。
     *
     * @param serviceName 服务名（即 appId）
     * @param ip          本机 IP
     * @param port        服务端口
     * @param weight      调度权重
     * @param metadata    附加元数据（如 idc）
     * @throws Exception 注册失败（由上层决定容忍或 fail-fast）
     */
    void register(String serviceName, String ip, int port, double weight,
                  Map<String, String> metadata) throws Exception;

    /**
     * 注销本实例；失败只记录日志，不阻塞进程退出。
     */
    void deregister(String serviceName, String ip, int port);

    /** 释放底层客户端连接；幂等。 */
    @Override
    default void close() {
    }
}
