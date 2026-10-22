package com.armeria.grpc.server;

import com.armeria.grpc.registry.api.ServiceDiscovery;
import com.armeria.grpc.registry.api.ServiceRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多注册中心管理器：把本实例注册到全部启用注册的中心，任一中心失败容忍（或 fail-fast）。
 *
 * <p>替代旧 {@code NacosInstanceRegistrar}：注册侧遍历所有 {@link ServiceRegistrar}，
 * 单中心失败默认 WARN 容忍（实例随中心心跳自愈），failFast=true 时抛异常；
 * 注销失败始终只记日志，不阻塞进程退出。close() 统一关闭全部中心客户端（含只发现中心）。
 */
public class RegistryManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RegistryManager.class);

    /** 启用注册的中心（registerEnabled=true），每个中心带独立权重 */
    private final List<RegistrarEntry> entries;

    /** 全部中心（含只发现），close 时统一释放 */
    private final List<AutoCloseable> closeables;

    private final boolean failFast;

    private volatile String serviceName;
    private volatile String ip;
    private volatile int port;

    /** 注册侧条目：注册器 + 该中心配置的权重。 */
    public record RegistrarEntry(ServiceRegistrar registrar, double weight) {
    }

    public RegistryManager(List<RegistrarEntry> entries, List<AutoCloseable> closeables,
                           boolean failFast) {
        this.entries = List.copyOf(entries);
        this.closeables = List.copyOf(closeables);
        this.failFast = failFast;
    }

    /**
     * 注册本实例到全部启用注册的中心（各中心使用自身配置的权重）。
     *
     * @throws IllegalStateException failFast 且单中心注册失败时
     */
    public void register(String serviceName, String ip, int port, Map<String, String> metadata) {
        this.serviceName = serviceName;
        this.ip = ip;
        this.port = port;
        int failed = 0;
        for (RegistrarEntry entry : entries) {
            try {
                entry.registrar().register(serviceName, ip, port, entry.weight(), metadata);
            } catch (Exception e) {
                failed++;
                if (failFast) {
                    throw new IllegalStateException(
                            "Register to " + entry.registrar().name() + " failed", e);
                }
                log.warn("Register {} to {} failed, continue with others: {}",
                        serviceName, entry.registrar().name(), e.getMessage());
            }
        }
        if (failed > 0) {
            log.warn("Registered {} to {}/{} centers ({} failed)",
                    serviceName, entries.size() - failed, entries.size(), failed);
        }
    }

    /** 注销本实例；失败只记日志。 */
    public void deregister() {
        if (serviceName == null || ip == null) {
            return;
        }
        for (RegistrarEntry entry : entries) {
            try {
                entry.registrar().deregister(serviceName, ip, port);
            } catch (Exception e) {
                log.warn("Deregister {} from {} failed: {}", serviceName, entry.registrar().name(), e.getMessage());
            }
        }
    }

    /** 全部中心（含只发现中心）的发现侧视图，供客户端/网关合并使用。 */
    public List<ServiceDiscovery> discoveries() {
        List<ServiceDiscovery> discoveries = new ArrayList<>();
        for (AutoCloseable closeable : closeables) {
            if (closeable instanceof ServiceDiscovery discovery) {
                discoveries.add(discovery);
            }
        }
        return discoveries;
    }

    @Override
    public void close() {
        deregister();
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Failed to close registry center: {}", e.getMessage());
            }
        }
        log.info("RegistryManager closed, {} centers released", closeables.size());
    }
}
