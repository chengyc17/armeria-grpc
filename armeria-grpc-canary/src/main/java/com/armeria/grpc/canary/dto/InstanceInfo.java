package com.armeria.grpc.canary.dto;

import java.util.Map;

/**
 * Nacos 实例信息。
 */
public record InstanceInfo(
        String ip,
        int port,
        String address,
        String cluster,
        boolean healthy,
        Map<String, String> metadata) {
}
