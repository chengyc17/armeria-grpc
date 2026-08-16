package org.example.service;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import jakarta.annotation.PreDestroy;
import org.example.config.GatewayProperties;
import org.example.dto.InstanceInfo;
import org.example.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 appId 维护 Nacos 实例缓存，并订阅实例变更事件。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>缓存：避免每个请求都查询 Nacos，Nacos 只作为低频数据源；</li>
 *   <li>订阅：{@link NamingService#subscribe} 推送实例变更，秒级生效，无需等待 TTL；</li>
 *   <li>负缓存：首次查询失败也建空缓存，避免 Nacos 抖动时每个请求都打 Nacos；</li>
 *   <li>兜底：定时全量刷新，防止推送偶发丢失后缓存与 Nacos 不一致；</li>
 *   <li>降级：刷新失败保留旧快照（stale-while-error），Nacos 抖动不影响路由。</li>
 * </ul>
 */
@Service
public class NacosInstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosInstanceRegistry.class);

    private final NamingService namingService;
    private final GatewayProperties properties;
    private final Map<String, ServiceCache> caches = new ConcurrentHashMap<>();

    public NacosInstanceRegistry(NamingService namingService, GatewayProperties properties) {
        this.namingService = namingService;
        this.properties = properties;
    }

    /**
     * 获取 appId 对应的 Armeria 端点组。首次访问同步查询 Nacos 并建立订阅，之后读缓存。
     */
    public NacosEndpointGroup endpointGroup(String appId) {
        return loadAndSubscribe(appId).group();
    }

    /**
     * 获取 appId 当前的实例快照（首次访问触发 Nacos 查询），供状态查看接口使用。
     */
    public ServiceSnapshot snapshot(String appId) {
        return loadAndSubscribe(appId).snapshot();
    }

    /**
     * 当前缓存的所有 appId 快照，供状态查看接口使用。
     */
    public Map<String, ServiceSnapshot> snapshotMap() {
        Map<String, ServiceSnapshot> result = new ConcurrentHashMap<>();
        caches.forEach((appId, cache) -> result.put(appId, cache.snapshot()));
        return result;
    }

    /**
     * 定时兜底刷新：订阅推送偶发丢失、订阅建立失败等场景下，保证缓存最终与 Nacos 一致。
     */
    @Scheduled(fixedDelayString = "${gateway.nacos.refresh-interval-ms:60000}")
    public void refreshAll() {
        caches.forEach((appId, cache) -> {
            try {
                cache.update(toInstanceInfos(queryNacos(appId)));
                subscribeIfNeeded(cache);
            } catch (Exception e) {
                // 保留旧快照，Nacos 抖动期间继续用缓存路由
                log.warn("Refresh Nacos instances failed, keep stale cache. appId={}, error={}",
                        appId, e.getMessage());
            }
        });
    }

    @PreDestroy
    public void close() {
        caches.forEach((appId, cache) -> {
            try {
                namingService.unsubscribe(appId, group(), cache.listener());
            } catch (NacosException e) {
                log.warn("Failed to unsubscribe appId={}", appId, e);
            }
        });
        caches.clear();
    }

    private synchronized ServiceCache loadAndSubscribe(String appId) {
        ServiceCache existing = caches.get(appId);
        if (existing != null) {
            return existing;
        }
        ServiceCache cache;
        try {
            cache = new ServiceCache(appId, toInstanceInfos(queryNacos(appId)));
        } catch (ServiceUnavailableException e) {
            // 负缓存：Nacos 不可用时不再逐请求重试，由定时刷新自动恢复
            log.warn("Load Nacos instances failed, cache empty for now. appId={}, error={}",
                    appId, e.getMessage());
            cache = new ServiceCache(appId, List.of());
        }
        caches.put(appId, cache);
        subscribeIfNeeded(cache);
        return cache;
    }

    private void subscribeIfNeeded(ServiceCache cache) {
        if (cache.subscribed()) {
            return;
        }
        try {
            namingService.subscribe(cache.appId(), group(), cache.listener());
            cache.markSubscribed();
            log.info("Subscribed Nacos instance changes. appId={}, group={}", cache.appId(), group());
        } catch (NacosException e) {
            // 订阅失败不阻塞请求，由定时刷新兜底并重试订阅
            log.warn("Subscribe Nacos failed, fallback to periodic refresh. appId={}, error={}",
                    cache.appId(), e.getErrMsg());
        }
    }

    private List<Instance> queryNacos(String appId) {
        try {
            // subscribe=false：订阅由本类统一管理，避免重复订阅
            return namingService.getAllInstances(appId, group(), false);
        } catch (NacosException e) {
            throw new ServiceUnavailableException("从 Nacos 查询实例失败: " + e.getErrMsg(), e);
        }
    }

    private static List<InstanceInfo> toInstanceInfos(List<Instance> instances) {
        return instances.stream()
                .filter(Instance::isHealthy)
                .filter(Instance::isEnabled)
                .map(instance -> new InstanceInfo(
                        instance.getIp(),
                        instance.getPort(),
                        instance.getWeight(),
                        instance.isHealthy(),
                        instance.getMetadata() == null ? Map.of() : Map.copyOf(instance.getMetadata())))
                .toList();
    }

    private String group() {
        String group = properties.getNacos().getGroup();
        return (group == null || group.isBlank()) ? "DEFAULT_GROUP" : group;
    }

    /**
     * 单个 appId 的缓存条目：动态端点组 + 实例快照 + 变更监听器。
     */
    private static final class ServiceCache {

        private final String appId;
        private final NacosEndpointGroup group = new NacosEndpointGroup();
        private final EventListener listener;
        private volatile boolean subscribed;
        private volatile Instant updatedAt = Instant.now();
        private volatile List<InstanceInfo> instances = List.of();

        ServiceCache(String appId, List<InstanceInfo> initial) {
            this.appId = appId;
            update(initial);
            this.listener = this::handle;
        }

        private void handle(Event event) {
            if (event instanceof NamingEvent namingEvent) {
                log.info("Nacos instance changed. appId={}, instances={}",
                        appId, namingEvent.getInstances().size());
                update(toInstanceInfos(namingEvent.getInstances()));
            }
        }

        void update(List<InstanceInfo> newInstances) {
            List<InstanceInfo> copy = List.copyOf(newInstances);
            this.instances = copy;
            this.updatedAt = Instant.now();
            this.group.update(copy);
        }

        ServiceSnapshot snapshot() {
            return new ServiceSnapshot(instances, updatedAt);
        }

        NacosEndpointGroup group() {
            return group;
        }

        String appId() {
            return appId;
        }

        EventListener listener() {
            return listener;
        }

        boolean subscribed() {
            return subscribed;
        }

        void markSubscribed() {
            this.subscribed = true;
        }
    }
}