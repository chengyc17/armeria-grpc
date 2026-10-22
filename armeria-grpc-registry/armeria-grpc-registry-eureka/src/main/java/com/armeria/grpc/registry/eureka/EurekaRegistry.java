package com.armeria.grpc.registry.eureka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.armeria.grpc.registry.api.Registry;
import com.armeria.grpc.registry.api.RegistryInstance;
import com.armeria.grpc.registry.api.RegistryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Eureka 注册中心适配器：基于 Eureka REST API v2（JDK HttpClient 直连，与官方 eureka-client 同协议）。
 *
 * <p>不引入官方 eureka-client 的原因：其依赖链（archaius / netflix-infix / jersey 等）很重；
 * Eureka 的注册/发现/心跳本就是 REST 协议，轻量直连即可，且更易测试。
 *
 * <p>关键行为：
 * <ul>
 *   <li>注册：POST /apps/{app} + 心跳调度（每 30s PUT /apps/{app}/{instanceId}，防止被剔除）；</li>
 *   <li>发现：GET /apps/{app} 解析 UP 实例（Eureka appId 大写约定）；</li>
 *   <li>订阅：Eureka 无推送，按 refreshIntervalMs（默认 30s）轮询全量，变化时广播。</li>
 * </ul>
 */
public class EurekaRegistry implements Registry {

    private static final Logger log = LoggerFactory.getLogger(EurekaRegistry.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_HEARTBEAT_MS = 30_000;
    private static final long DEFAULT_REFRESH_MS = 30_000;

    private final String name;
    private final String type;
    private final String baseUrl;      // e.g. http://127.0.0.1:8761/eureka/
    private final HttpClient httpClient;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "eureka-registry-" + (r.hashCode() & 0xffff));
        t.setDaemon(true);
        return t;
    });

    // 注册侧状态（本实例）
    private volatile String registeredService;
    private volatile String registeredInstanceId;
    private volatile ScheduledFuture<?> heartbeatFuture;

    // 发现侧：serviceName -> 本地监听器集合 + 轮询任务
    private final Map<String, List<Listener>> localListeners = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pollFutures = new ConcurrentHashMap<>();
    private final Map<String, List<RegistryInstance>> lastSnapshot = new ConcurrentHashMap<>();

    public EurekaRegistry(RegistryProperties.Item props) {
        this.name = props.getName();
        this.type = props.getType();
        String serverAddr = props.getServerAddr();
        if (serverAddr == null || serverAddr.isBlank()) {
            throw new IllegalStateException("eureka registry [" + props.getName()
                    + "] server-addr must be configured, e.g. http://127.0.0.1:8761/eureka");
        }
        this.baseUrl = serverAddr.endsWith("/") ? serverAddr : serverAddr + "/";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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
    public synchronized void register(String serviceName, String ip, int port, double weight,
                                      Map<String, String> metadata) throws Exception {
        String app = appName(serviceName);
        String instanceId = ip + ":" + serviceName + ":" + port;

        ObjectNode instance = MAPPER.createObjectNode();
        instance.put("instanceId", instanceId);
        instance.put("hostName", ip);
        instance.put("app", app);
        instance.put("ipAddr", ip);
        instance.put("status", "UP");
        ObjectNode portNode = instance.putObject("port");
        portNode.put("$", port);
        portNode.put("@enabled", "true");
        instance.put("vipAddress", serviceName);
        instance.put("secureVipAddress", serviceName);
        if (metadata != null && !metadata.isEmpty()) {
            ObjectNode meta = instance.putObject("metadata");
            metadata.forEach(meta::put);
        }
        ObjectNode dc = instance.putObject("dataCenterInfo");
        dc.put("@class", "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo");
        dc.put("name", "MyOwn");
        ObjectNode body = MAPPER.createObjectNode();
        body.set("instance", instance);

        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "apps/" + app))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        int status = resp.statusCode();
        if (status != 204 && status != 200) {
            throw new IllegalStateException("Eureka register failed, status=" + status + ", body="
                    + truncate(resp.body(), 200));
        }
        this.registeredService = serviceName;
        this.registeredInstanceId = instanceId;
        startHeartbeat(serviceName, instanceId);
        log.info("Registered service {} -> {}:{} to Eureka [{}]", serviceName, ip, port, name);
    }

    private void startHeartbeat(String serviceName, String instanceId) {
        ScheduledFuture<?> existing = heartbeatFuture;
        if (existing != null) {
            existing.cancel(false);
        }
        String app = appName(serviceName);
        heartbeatFuture = scheduler.scheduleWithFixedDelay(() -> {
            try {
                HttpResponse<String> resp = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "apps/" + app + "/" + instanceId + "?status=UP"))
                                .timeout(Duration.ofSeconds(5))
                                .PUT(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("Eureka heartbeat failed, status={} [{}]", resp.statusCode(), name);
                }
            } catch (Exception e) {
                log.warn("Eureka heartbeat error [{}]: {}", name, e.getMessage());
            }
        }, DEFAULT_HEARTBEAT_MS, DEFAULT_HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void deregister(String serviceName, String ip, int port) {
        ScheduledFuture<?> future = heartbeatFuture;
        if (future != null) {
            future.cancel(false);
            heartbeatFuture = null;
        }
        if (registeredInstanceId == null) {
            return;
        }
        try {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "apps/" + appName(serviceName) + "/" + registeredInstanceId))
                            .timeout(Duration.ofSeconds(5))
                            .DELETE()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            log.info("Deregistered service {} from Eureka [{}], status={}", serviceName, name, resp.statusCode());
        } catch (Exception e) {
            log.warn("Failed to deregister service {} from Eureka [{}]: {}", serviceName, name, e.getMessage());
        } finally {
            registeredService = null;
            registeredInstanceId = null;
        }
    }

    // ---------- 发现侧 ----------

    @Override
    public List<RegistryInstance> lookup(String serviceName) {
        return fetch(serviceName);
    }

    @Override
    public synchronized void subscribe(String serviceName, Listener listener) {
        List<Listener> listeners = localListeners.computeIfAbsent(serviceName, key -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        if (pollFutures.containsKey(serviceName)) {
            return; // 已有轮询任务
        }
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> pollOnce(serviceName),
                DEFAULT_REFRESH_MS, DEFAULT_REFRESH_MS, TimeUnit.MILLISECONDS);
        pollFutures.put(serviceName, future);
        log.info("Started Eureka polling for {} [{}]", serviceName, name);
    }

    @Override
    public synchronized void unsubscribe(String serviceName, Listener listener) {
        List<Listener> listeners = localListeners.get(serviceName);
        if (listeners != null) {
            listeners.remove(listener);
            if (!listeners.isEmpty()) {
                return;
            }
            localListeners.remove(serviceName);
        }
        ScheduledFuture<?> future = pollFutures.remove(serviceName);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void pollOnce(String serviceName) {
        try {
            List<RegistryInstance> instances = fetch(serviceName);
            List<RegistryInstance> previous = lastSnapshot.getOrDefault(serviceName, List.of());
            if (!instances.equals(previous)) {
                lastSnapshot.put(serviceName, instances);
                broadcast(serviceName, instances);
            }
        } catch (Exception e) {
            // 轮询失败保留旧快照，Eureka 抖动不影响路由
            log.warn("Eureka poll failed for {} [{}]: {}", serviceName, name, e.getMessage());
        }
    }

    private void broadcast(String serviceName, List<RegistryInstance> instances) {
        List<Listener> listeners = localListeners.getOrDefault(serviceName, List.of());
        for (Listener listener : listeners) {
            try {
                listener.onInstancesChanged(instances);
            } catch (Exception e) {
                log.warn("Listener for {} failed: {}", serviceName, e.getMessage());
            }
        }
    }

    // ---------- Eureka REST 调用 ----------

    private List<RegistryInstance> fetch(String serviceName) {
        try {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "apps/" + appName(serviceName)))
                            .timeout(Duration.ofSeconds(5))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return List.of(); // 应用不存在
            }
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Eureka lookup failed, status=" + resp.statusCode());
            }
            return parseApplication(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to lookup " + serviceName + " from Eureka [" + name + "]: "
                    + e.getMessage(), e);
        }
    }

    /** 解析 GET /apps/{app} 响应：{"application": {"name": "...", "instance": [...]}}，单实例时 instance 是对象。 */
    private List<RegistryInstance> parseApplication(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode application = root.path("application");
        if (application.isMissingNode() || application.isNull()) {
            return List.of();
        }
        JsonNode instanceNode = application.get("instance");
        List<JsonNode> nodes = new ArrayList<>();
        if (instanceNode == null || instanceNode.isNull()) {
            return List.of();
        }
        if (instanceNode.isArray()) {
            instanceNode.forEach(nodes::add);
        } else {
            nodes.add(instanceNode);
        }
        List<RegistryInstance> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            String status = node.path("status").asText();
            if (!"UP".equalsIgnoreCase(status)) {
                continue;
            }
            String ip = node.path("ipAddr").asText();
            int port = node.path("port").path("$").asInt(0);
            if (ip.isBlank() || port <= 0) {
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            JsonNode meta = node.get("metadata");
            if (meta != null && meta.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = meta.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    metadata.put(entry.getKey(), entry.getValue().asText());
                }
            }
            result.add(new RegistryInstance(ip, port, 1.0, true, true, metadata));
        }
        return List.copyOf(result);
    }

    /** Eureka appId 大写约定：统一转大写做匹配。 */
    private static String appName(String serviceName) {
        return serviceName.toUpperCase(Locale.ROOT);
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }

    @Override
    public synchronized void close() {
        pollFutures.values().forEach(future -> future.cancel(false));
        pollFutures.clear();
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
        scheduler.shutdownNow();
        log.info("Eureka registry closed [{}]", name);
    }
}
