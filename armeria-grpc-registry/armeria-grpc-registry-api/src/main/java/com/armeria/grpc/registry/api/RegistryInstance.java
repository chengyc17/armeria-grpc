package com.armeria.grpc.registry.api;

import java.util.Map;

/**
 * 统一实例模型：Nacos Instance / Eureka InstanceInfo / K8s Endpoint 都映射成它。
 *
 * @param ip       实例 IP
 * @param port     实例端口
 * @param weight   调度权重（Nacos 原生语义，1.0 为默认）
 * @param healthy  是否健康
 * @param enabled  是否启用
 * @param metadata 元数据（如 idc 就近路由键）
 */
public record RegistryInstance(
        String ip,
        int port,
        double weight,
        boolean healthy,
        boolean enabled,
        Map<String, String> metadata) {

    public RegistryInstance {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String address() {
        return ip + ":" + port;
    }
}
