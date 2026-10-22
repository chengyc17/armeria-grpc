package com.armeria.grpc.gateway.dto;

import java.util.Map;

/**
 * Nacos 健康实例信息（已过滤不健康/未启用实例）。
 */
public record InstanceInfo(
        String ip,
        int port,
        double weight,
        boolean healthy,
        Map<String, String> metadata) {

    /**
     * 形如 ip:port 的地址，IPv6 自动加方括号。
     */
    public String address() {
        return ip.contains(":") ? "[" + ip + "]:" + port : ip + ":" + port;
    }
}
