package com.armeria.grpc.gateway.service;

import jakarta.annotation.PreDestroy;
import com.armeria.grpc.common.discovery.IdcPoolSelector;
import com.armeria.grpc.common.discovery.MultiRegistryEndpointGroup;
import com.armeria.grpc.common.env.OpsEnvironmentPostProcessor;
import com.armeria.grpc.gateway.config.GatewayProperties;
import com.armeria.grpc.gateway.dto.InstanceInfo;
import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.registry.api.ServiceDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多注册中心实例注册表：按 appId 维护 {@link MultiRegistryEndpointGroup}（多中心合并 +
 * idc 就近 + 订阅推送），并缓存快照供状态查看接口使用。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>零 Nacos 依赖：只注入 {@link List}<code>ServiceDiscovery</code>，中心切换只改依赖与配置；</li>
 *   <li>缓存：每个 appId 只建一次端点组，实例变更走订阅推送，秒级生效；</li>
 *   <li>负缓存：首次查询失败也建空端点组（内部 3s/5s 重试），不逐请求打注册中心；</li>
 *   <li>兜底：定时全量刷新，防止推送偶发丢失后缓存与注册中心不一致；</li>
 *   <li>降级：刷新失败保留旧端点（stale-while-error），中心抖动不影响路由。</li>
 * </ul>
 */
@Service
public class GatewayInstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(GatewayInstanceRegistry.class);

    private final List<ServiceDiscovery> discoveries;
    private final GatewayProperties properties;
    private final String idc;
    private final Map<String, MultiRegistryEndpointGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, Instant> updatedAt = new ConcurrentHashMap<>();

    public GatewayInstanceRegistry(List<ServiceDiscovery> discoveries,
                                   GatewayProperties properties,
                                   Environment environment) {
        this.discoveries = discoveries;
        this.properties = properties;
        // 就近访问：优先取 ops/env.properties 的 idc，其次取 gateway.registry.idc（统一走 common 的共享方法）
        this.idc = OpsEnvironmentPostProcessor.idc(environment, properties.getRegistry().getIdc());
    }

    /**
     * 获取 appId 对应的 Armeria 端点组。首次访问同步查询注册中心并建立订阅，之后读缓存。
     */
    public MultiRegistryEndpointGroup endpointGroup(String appId) {
        return load(appId);
    }

    /**
     * 获取 appId 当前的实例快照（首次访问触发注册中心查询），供状态查看接口使用。
     */
    public ServiceSnapshot snapshot(String appId) {
        MultiRegistryEndpointGroup group = load(appId);
        return new ServiceSnapshot(toInstanceInfos(group.instances()),
                updatedAt.getOrDefault(appId, Instant.now()));
    }

    /**
     * 当前缓存的所有 appId 快照，供状态查看接口使用。
     */
    public Map<String, ServiceSnapshot> snapshotMap() {
        Map<String, ServiceSnapshot> result = new ConcurrentHashMap<>();
        groups.forEach((appId, group) -> result.put(appId, snapshot(appId)));
        return result;
    }

    /**
     * 定时兜底刷新：订阅推送偶发丢失、订阅建立失败等场景下，保证缓存最终与注册中心一致。
     */
    @Scheduled(fixedDelayString = "${gateway.registry.refresh-interval-ms:60000}")
    public void refreshAll() {
        groups.forEach((appId, group) -> {
            try {
                group.refreshAll();
                updatedAt.put(appId, Instant.now());
            } catch (Exception e) {
                // 保留旧端点，注册中心抖动期间继续用缓存路由
                log.warn("Refresh instances failed, keep stale endpoints. appId={}, error={}",
                        appId, e.getMessage());
            }
        });
    }

    @PreDestroy
    public void close() {
        groups.forEach((appId, group) -> group.closeGroup());
        groups.clear();
        updatedAt.clear();
    }

    private MultiRegistryEndpointGroup load(String appId) {
        return groups.computeIfAbsent(appId, id -> {
            // 多中心合并端点组：构造时即做首次查询 + 订阅，无健康实例时内部 3s/5s 重试
            MultiRegistryEndpointGroup group = new MultiRegistryEndpointGroup(discoveries, id, this.idc);
            updatedAt.put(id, Instant.now());
            return group;
        });
    }

    private static List<InstanceInfo> toInstanceInfos(List<RegistryInstance> instances) {
        return instances.stream()
                .map(instance -> new InstanceInfo(
                        instance.ip(),
                        instance.port(),
                        instance.weight(),
                        instance.healthy(),
                        instance.metadata()))
                .toList();
    }
}
