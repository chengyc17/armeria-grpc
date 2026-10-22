package com.armeria.grpc.common.discovery;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.armeria.grpc.registry.api.InstanceMerger;
import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.registry.api.ServiceDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 多注册中心动态端点组：把多个注册中心的发现结果合并（ip:port 去重）后，
 * 再按 idc 就近筛选，统一刷新到 Armeria 端点组。任一中心失败不影响其它中心。
 *
 * <p>⚠️ 去重在 {@link RegistryInstance} 列表层做（{@link InstanceMerger#merge}），
 * 不要在端点层去重——同 ip:port 出现两次会被当成两个端点参与加权，等于权重翻倍。
 *
 * <p>注意：{@code DynamicEndpointGroup#close()} 是 final 无法覆盖，
 * 退订/释放请调用 {@link #closeGroup()}。
 */
public class MultiRegistryEndpointGroup extends DynamicEndpointGroup {

    private static final Logger log = LoggerFactory.getLogger(MultiRegistryEndpointGroup.class);

    private final List<ServiceDiscovery> discoveries;
    private final String serviceName;
    private final String idc;
    private final List<ServiceDiscovery.Listener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService retryExecutor;

    /** 全中心合并去重后的实例（快照用，未做 idc 就近筛选） */
    private volatile List<RegistryInstance> lastMerged = List.of();

    private volatile boolean subscribed;
    private volatile ScheduledFuture<?> retryFuture;

    public MultiRegistryEndpointGroup(List<ServiceDiscovery> discoveries, String serviceName, String idc) {
        this.discoveries = List.copyOf(discoveries);
        this.serviceName = serviceName;
        this.idc = idc;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "registry-refresh-" + serviceName);
            t.setDaemon(true);
            return t;
        });

        boolean needsRetry = false;
        try {
            refreshAll();
        } catch (Exception e) {
            log.warn("No healthy instance for {} at startup, waiting for instances: {}",
                    serviceName, e.getMessage());
            needsRetry = true;
        }
        try {
            subscribeAll();
        } catch (Exception e) {
            log.warn("Failed to subscribe registry changes for {}: {}", serviceName, e.getMessage());
            needsRetry = true;
        }
        if (needsRetry) {
            scheduleRetry();
        }
    }

    /** 全量刷新：合并所有中心实例 → idc 就近 → 更新端点。 */
    public synchronized void refreshAll() {
        List<List<RegistryInstance>> perCenter = new ArrayList<>();
        List<String> failedCenters = new ArrayList<>();
        for (ServiceDiscovery discovery : discoveries) {
            try {
                perCenter.add(discovery.lookup(serviceName));
            } catch (Exception e) {
                failedCenters.add(discovery.name());
                log.warn("Lookup {} from {} failed, use other centers: {}",
                        serviceName, discovery.name(), e.getMessage());
            }
        }
        List<RegistryInstance> merged = InstanceMerger.merge(perCenter);
        if (merged.isEmpty() && !failedCenters.isEmpty()) {
            throw new IllegalStateException("No instances for " + serviceName
                    + " from any center (failed: " + failedCenters + ")");
        }
        this.lastMerged = List.copyOf(merged);
        List<RegistryInstance> pool = IdcPoolSelector.selectPool(merged, idc, RegistryInstance::metadata);
        update(pool);
        cancelRetry();
        log.info("Endpoints refreshed for {} ({} instances merged, {} same-idc)",
                serviceName, merged.size(), pool.size());
    }

    /**
     * 全中心合并去重后的实例列表（未做 idc 就近筛选），供状态快照使用。
     */
    public List<RegistryInstance> instances() {
        return lastMerged;
    }

    private void update(List<RegistryInstance> pool) {
        List<Endpoint> endpoints = pool.stream()
                .map(instance -> Endpoint.of(instance.ip(), instance.port())
                        .withWeight(EndpointWeights.toArmeriaWeight(instance.weight())))
                .toList();
        setEndpoints(endpoints);
    }

    /** 订阅所有中心：任一变更加载重合并（refreshAll）。 */
    private synchronized void subscribeAll() {
        if (subscribed) {
            return;
        }
        for (ServiceDiscovery discovery : discoveries) {
            ServiceDiscovery.Listener listener = instances -> {
                log.info("Instances changed for {} at [{}], refreshing", serviceName, discovery.name());
                try {
                    refreshAll();
                } catch (Exception e) {
                    log.warn("Failed to refresh endpoints for {}: {}", serviceName, e.getMessage());
                    scheduleRetry();
                }
            };
            listeners.add(listener);
            try {
                discovery.subscribe(serviceName, listener);
            } catch (Exception e) {
                // 单中心订阅失败不阻塞，由定时重试补订阅
                log.warn("Failed to subscribe {} at {}: {}", serviceName, discovery.name(), e.getMessage());
            }
        }
        subscribed = true;
    }

    private synchronized void scheduleRetry() {
        ScheduledFuture<?> existing = retryFuture;
        if (existing != null && !existing.isDone()) {
            return;
        }
        retryFuture = retryExecutor.scheduleWithFixedDelay(() -> {
            try {
                refreshAll();
                subscribeAll();
                log.info("Endpoints available for {} after retry", serviceName);
            } catch (Exception e) {
                log.warn("Endpoint refresh retry for {} failed: {}", serviceName, e.getMessage());
            }
        }, 3, 5, TimeUnit.SECONDS);
    }

    private void cancelRetry() {
        ScheduledFuture<?> future = retryFuture;
        if (future != null) {
            future.cancel(false);
            retryFuture = null;
        }
    }

    /** 退订所有中心并释放资源（DynamicEndpointGroup#close 是 final，不能覆盖）。 */
    public synchronized void closeGroup() {
        cancelRetry();
        retryExecutor.shutdownNow();
        for (int i = 0; i < discoveries.size(); i++) {
            ServiceDiscovery.Listener listener = i < listeners.size() ? listeners.get(i) : null;
            if (listener != null) {
                try {
                    discoveries.get(i).unsubscribe(serviceName, listener);
                } catch (Exception e) {
                    log.warn("Failed to unsubscribe {} from {}: {}", serviceName,
                            discoveries.get(i).name(), e.getMessage());
                }
            }
        }
        listeners.clear();
        subscribed = false;
    }
}
