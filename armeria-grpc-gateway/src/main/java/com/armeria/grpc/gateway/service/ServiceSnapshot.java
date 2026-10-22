package com.armeria.grpc.gateway.service;

import com.armeria.grpc.gateway.dto.InstanceInfo;

import java.time.Instant;
import java.util.List;

/**
 * 某个 appId 的实例快照，不可变，读时不需要加锁。
 */
public record ServiceSnapshot(List<InstanceInfo> instances, Instant updatedAt) {
}
