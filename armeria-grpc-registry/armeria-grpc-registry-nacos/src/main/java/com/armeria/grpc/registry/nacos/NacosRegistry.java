package com.armeria.grpc.registry.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.armeria.grpc.registry.api.Registry;
import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.registry.api.RegistryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nacos 注册中心适配器：一个独立 {@link NamingService}，同时提供注册侧与发现侧能力。
 *
 * <p>订阅模型：按 serviceName 维护一个 Nacos 订阅 + 本地监听器集合；任一本地监听器
 * 加入时若该服务尚未订阅 Nacos 则建立订阅，收到 {@link NamingEvent} 后统一转换为
 * {@link RegistryInstance} 广播给全部本地监听器。全部取消后移除 Nacos 订阅。
 */
public class NacosRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistry.class);

    private final String name;
    private final String type;
    private final String namespace;
    private final String group;
    private final NamingService namingService;

    /** serviceName -> 本地监听器集合 */
    private final Map<String, List<Listener>> localListeners = new ConcurrentHashMap<>();

    /** serviceName -> Nacos 事件监听器（每服务一个） */
    private final Map<String, EventListener> nacosListeners = new ConcurrentHashMap<>();

    public NacosRegistry(RegistryProperties.Item props) {
        this.name = props.getName();
        this.type = props.getType();
        this.namespace = props.getNamespace();
        this.group = blankToDefault(props.getGroup());
        this.namingService = createNamingService(props);
    }

    private static NamingService createNamingService(RegistryProperties.Item props) {
        String serverAddr = props.getServerAddr();
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalStateException("nacos registry [" + props.getName()
                    + "] server-addr must be configured, e.g. 127.0.0.1:8848");
        }
        Properties nacosProps = new Properties();
        nacosProps.setProperty("serverAddr", serverAddr);
        if (props.getNamespace() != null && !props.getNamespace().isBlank()) {
            nacosProps.setProperty("namespace", props.getNamespace());
        }
        try {
            return NamingFactory.createNamingService(nacosProps);
        } catch (NacosException e) {
            throw new IllegalStateException("Failed to create Nacos NamingService for ["
                    + props.getName() + "]: " + e.getErrMsg(), e);
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

    // ---------- 注册侧 ----------

    @Override
    public void register(String serviceName, String ip, int port, double weight,
                         Map<String, String> metadata) throws Exception {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(weight);
        if (metadata != null && !metadata.isEmpty()) {
            instance.setMetadata(metadata);
        }
        namingService.registerInstance(serviceName, group, instance);
        log.info("Registered service {} -> {}:{} (weight={}, metadata={}) to Nacos [{}]",
                serviceName, ip, port, weight, metadata, name);
    }

    @Override
    public void deregister(String serviceName, String ip, int port) {
        try {
            namingService.deregisterInstance(serviceName, group, ip, port);
            log.info("Deregistered service {} -> {}:{} from Nacos [{}]", serviceName, ip, port, name);
        } catch (Exception e) {
            log.warn("Failed to deregister service {} from Nacos [{}]: {}", serviceName, name, e.getMessage());
        }
    }

    // ---------- 发现侧 ----------

    @Override
    public List<RegistryInstance> lookup(String serviceName) {
        try {
            List<Instance> instances = namingService.selectInstances(serviceName, group, true);
            return instances == null ? List.of() : instances.stream()
                    .filter(Instance::isHealthy)
                    .filter(Instance::isEnabled)
                    .map(NacosRegistry::toRegistryInstance)
                    .toList();
        } catch (NacosException e) {
            throw new IllegalStateException("Failed to lookup service " + serviceName
                    + " from Nacos [" + name + "]: " + e.getErrMsg(), e);
        }
    }

    @Override
    public void subscribe(String serviceName, Listener listener) {
        List<Listener> listeners = localListeners.computeIfAbsent(serviceName, key -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        if (nacosListeners.containsKey(serviceName)) {
            return; // 已订阅 Nacos，无需重复订阅
        }
        EventListener nacosListener = event -> {
            if (event instanceof NamingEvent namingEvent) {
                broadcast(serviceName, namingEvent.getInstances());
            }
        };
        nacosListeners.put(serviceName, nacosListener);
        try {
            namingService.subscribe(serviceName, group, nacosListener);
            log.info("Subscribed Nacos instance changes for {} [{}]", serviceName, name);
        } catch (NacosException e) {
            nacosListeners.remove(serviceName);
            // 订阅失败由上层定时刷新兜底重试
            log.warn("Failed to subscribe Nacos instances for {} [{}]: {}", serviceName, name, e.getErrMsg());
        }
    }

    @Override
    public void unsubscribe(String serviceName, Listener listener) {
        List<Listener> listeners = localListeners.get(serviceName);
        if (listeners != null) {
            listeners.remove(listener);
            if (!listeners.isEmpty()) {
                return;
            }
            localListeners.remove(serviceName);
        }
        EventListener nacosListener = nacosListeners.remove(serviceName);
        if (nacosListener != null) {
            try {
                namingService.unsubscribe(serviceName, group, nacosListener);
            } catch (NacosException e) {
                log.warn("Failed to unsubscribe {} from Nacos [{}]: {}", serviceName, name, e.getErrMsg());
            }
        }
    }

    private void broadcast(String serviceName, List<Instance> instances) {
        List<RegistryInstance> converted = (instances == null ? List.<Instance>of() : instances).stream()
                .filter(Instance::isHealthy)
                .filter(Instance::isEnabled)
                .map(NacosRegistry::toRegistryInstance)
                .toList();
        log.info("Nacos instances changed for {}, count={} [{}]", serviceName, converted.size(), name);
        List<Listener> listeners = localListeners.getOrDefault(serviceName, List.of());
        for (Listener listener : listeners) {
            try {
                listener.onInstancesChanged(converted);
            } catch (Exception e) {
                log.warn("Listener for {} failed: {}", serviceName, e.getMessage());
            }
        }
    }

    private static RegistryInstance toRegistryInstance(Instance instance) {
        Map<String, String> metadata = instance.getMetadata();
        return new RegistryInstance(
                instance.getIp(),
                instance.getPort(),
                instance.getWeight(),
                instance.isHealthy(),
                instance.isEnabled(),
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    private static String blankToDefault(String group) {
        return (group == null || group.isBlank()) ? "DEFAULT_GROUP" : group;
    }

    @Override
    public void close() {
        List<String> services = new ArrayList<>(nacosListeners.keySet());
        for (String serviceName : services) {
            unsubscribe(serviceName, null); // 触发移除 Nacos 订阅
        }
        try {
            namingService.shutDown();
            log.info("Nacos NamingService shut down [{}]", name);
        } catch (Exception e) {
            log.warn("Failed to shut down Nacos NamingService [{}]: {}", name, e.getMessage());
        }
    }
}
