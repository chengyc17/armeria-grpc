package com.armeria.grpc.gateway.service;

import com.armeria.grpc.registry.api.ServiceDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关发现管理器：持有全部注册中心的发现侧视图，close 时统一释放。
 *
 * <p>与 armeria-server 的 RegistryManager 角色一致，但网关只发现不注册，
 * 因此只保留 {@link List}<code>ServiceDiscovery</code> 与关闭逻辑。</p>
 */
public class DiscoveryManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryManager.class);

    private final List<ServiceDiscovery> discoveries;
    private final List<AutoCloseable> closeables;

    public DiscoveryManager(List<ServiceDiscovery> discoveries, List<AutoCloseable> closeables) {
        this.discoveries = List.copyOf(discoveries);
        this.closeables = List.copyOf(closeables);
    }

    public List<ServiceDiscovery> discoveries() {
        return discoveries;
    }

    @Override
    public void close() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Failed to close registry center: {}", e.getMessage());
            }
        }
        log.info("DiscoveryManager closed, {} centers released", closeables.size());
    }
}
