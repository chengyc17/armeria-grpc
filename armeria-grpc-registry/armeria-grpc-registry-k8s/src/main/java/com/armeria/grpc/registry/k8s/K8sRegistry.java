package com.armeria.grpc.registry.k8s;

import com.armeria.grpc.registry.api.Registry;
import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.registry.api.RegistryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * K8s Service 注册中心（极简）：只发现、不注册，无任何后台线程。
 *
 * <p>不做 DNS-SRV 查询、不调 K8s REST API——直接把目标地址当作 K8s Service 的集群内域名：
 * {@code <service>.<namespace>.<cluster-domain>}（默认 {@code svc.cluster.local}）。
 * 其中 <b>service 名就是 appId</b>（由 stub 反推 / 调用方指定），
 * <b>namespace 默认取本机 ops env.properties 的 {@code env} 值</b>（也可在 registries[] 显式配置）。
 * 多副本由 Service ClusterIP 在 K8s 内部负载均衡，调用方无需感知具体 Pod IP/Endpoints。
 *
 * <p>注册侧 {@link #register} / {@link #deregister} 为 no-op + WARN
 * （Pod 是否就绪由 Deployment/Service 自动维护，无需本框架注册）。
 * 发现侧域名恒定不变，因此不轮询不推送：{@link #subscribe} 只登记 listener 并立即推送一次当前快照。
 *
 * <p>options：
 * <ul>
 *   <li>{@code port}：必填，K8s Service 端口（非 Pod targetPort），如 18081；</li>
 *   <li>{@code cluster-domain}：可选，集群 DNS 域名，默认 {@code svc.cluster.local}。</li>
 * </ul>
 */
public class K8sRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(K8sRegistry.class);

    private static final String DEFAULT_CLUSTER_DOMAIN = "svc.cluster.local";

    private final String name;
    private final String type;
    private final String namespace;
    private final String clusterDomain;
    private final int port;

    private final Map<String, List<Listener>> localListeners = new ConcurrentHashMap<>();

    public K8sRegistry(RegistryProperties.Item props, String namespace) {
        this.name = props.getName();
        this.type = props.getType();
        this.namespace = namespace;
        this.clusterDomain = props.getOptions()
                .getOrDefault("cluster-domain", DEFAULT_CLUSTER_DOMAIN);
        String port = props.getOptions().get("port");
        if (port == null || port.isBlank()) {
            throw new IllegalStateException("k8s registry [" + name
                    + "] requires options.port (K8s Service port, e.g. 18081)");
        }
        try {
            this.port = Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("k8s registry [" + name
                    + "] options.port must be a number, got: " + port);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return type;
    }

    // ---------- 注册侧：K8s 无需注册 ----------

    @Override
    public void register(String serviceName, String ip, int port, double weight,
                         Map<String, String> metadata) {
        log.warn("K8s registry [{}] does not support manual registration"
                + " (Pod endpoints are maintained by Deployment/Service), ignored {}", name, serviceName);
    }

    @Override
    public void deregister(String serviceName, String ip, int port) {
        // no-op
    }

    // ---------- 发现侧：Service DNS 直连 ----------

    /** Service 集群内域名：{@code <service>.<namespace>.<cluster-domain>}（service 名 = appId）。 */
    String serviceHost(String serviceName) {
        return serviceName + "." + namespace + "." + clusterDomain;
    }

    @Override
    public List<RegistryInstance> lookup(String serviceName) {
        // 单实例语义：Service 域名恒定可达，多副本由 ClusterIP 负载均衡，无需返回 Pod 列表
        RegistryInstance instance = new RegistryInstance(
                serviceHost(serviceName), port, 1.0, true, true, Map.of());
        return List.of(instance);
    }

    @Override
    public void subscribe(String serviceName, Listener listener) {
        List<Listener> listeners = localListeners.computeIfAbsent(serviceName,
                key -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        // 域名恒定、无后续变化事件；登记后立即推送一次当前快照，兼容“先订阅后查询”的消费方
        listener.onInstancesChanged(lookup(serviceName));
    }

    @Override
    public void unsubscribe(String serviceName, Listener listener) {
        List<Listener> listeners = localListeners.get(serviceName);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                localListeners.remove(serviceName);
            }
        }
    }

    @Override
    public void close() {
        localListeners.clear();
        log.info("K8s registry closed [{}]", name);
    }
}
