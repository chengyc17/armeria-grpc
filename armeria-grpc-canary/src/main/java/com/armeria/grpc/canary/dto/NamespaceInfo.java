package com.armeria.grpc.canary.dto;

/**
 * Nacos 命名空间：id 为实际访问命名空间时使用的标识，name 为控制台展示名。
 */
public record NamespaceInfo(String id, String name) {
}
