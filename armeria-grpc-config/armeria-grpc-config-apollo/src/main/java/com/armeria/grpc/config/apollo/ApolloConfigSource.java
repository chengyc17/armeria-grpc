package com.armeria.grpc.config.apollo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.armeria.grpc.config.api.AppConfigChangedEvent;
import com.armeria.grpc.config.api.ConfigParser;
import com.armeria.grpc.config.api.ConfigProperties;
import com.armeria.grpc.config.api.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apollo 配置中心客户端：基于 Apollo Config Service REST API（JDK HttpClient 直连 + 长轮询订阅）。
 *
 * <p>与官方 apollo-client 同协议；不引入官方客户端的原因：其依赖链（guava / okhttp 等）较重，
 * 而 Apollo 的拉取/订阅本就是 HTTP 协议。
 *
 * <p>dataId 统一标识映射：dataId = Apollo namespace 名（如 application / soa.json）。
 *
 * <p>拉取：GET {configService}/configs/{appId}/{cluster}/{namespace} → configurations（扁平 k/v）。
 * 订阅：GET {configService}/notifications/v2（长轮询，服务端 hold 连接直到变更），变更后重拉 + 发事件。
 *
 * <p>注意：Apollo namespace 默认 properties 格式。对 .json/.yaml 后缀的 dataId，
 * 若 configurations 只有一个 key 则视为完整原始内容（raw）；否则按 properties 序列化。
 * 治理配置（soa.json）建议以 properties 格式或单 key 形式存放。
 */
public class ApolloConfigSource implements ConfigSource {

    private static final Logger log = LoggerFactory.getLogger(ApolloConfigSource.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_CLUSTER = "default";
    private static final long NOTIFY_TIMEOUT_MS = 90_000;

    private final String type;
    private final String configServiceUrl;   // e.g. http://127.0.0.1:8080
    private final String appId;
    private final String cluster;
    private final List<String> dataIds;
    private final ConfigParser.Format format;
    private final ApplicationEventPublisher eventPublisher;
    private final HttpClient httpClient;

    private final Map<String, ConfigParser.ConfigSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Long> notificationIds = new ConcurrentHashMap<>();

    private final List<Thread> notifyThreads = new ArrayList<>();

    public ApolloConfigSource(ConfigProperties props, String appId,
                              ApplicationEventPublisher eventPublisher) {
        this.type = props.getType();
        this.appId = appId == null || appId.isBlank() ? "unknown" : appId;
        this.cluster = DEFAULT_CLUSTER;
        this.dataIds = List.copyOf(props.getDataIds());
        this.format = props.getFormat();
        this.eventPublisher = eventPublisher;
        this.configServiceUrl = resolveConfigServiceUrl(props);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** meta-server 先拉 /services/config 拿 config service 地址；server-addr 直接使用。 */
    private static String resolveConfigServiceUrl(ConfigProperties props) {
        String serverAddr = props.getServerAddr();
        if (serverAddr != null && !serverAddr.isBlank()) {
            return trimSlash(serverAddr);
        }
        String metaServer = props.getMetaServer();
        if (metaServer == null || metaServer.isBlank()) {
            throw new IllegalStateException(
                    "rpc.config.meta-server (or server-addr) must be configured for apollo config.");
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(trimSlash(metaServer) + "/services/config"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(resp.body());
            if (root.isArray() && !root.isEmpty()) {
                return trimSlash(root.get(0).path("homepage").asText());
            }
            throw new IllegalStateException("No config service returned by meta server: " + metaServer);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve Apollo config service from " + metaServer
                    + ": " + e.getMessage(), e);
        }
    }

    /** 启动：拉取全部 dataId 并启动长轮询订阅线程。 */
    public void start() {
        for (String dataId : dataIds) {
            snapshots.put(dataId, ConfigParser.ConfigSnapshot.empty());
            notificationIds.put(dataId, 0L);
            loadInitial(dataId);
        }
        for (String dataId : dataIds) {
            Thread thread = new Thread(() -> notifyLoop(dataId), "apollo-notify-" + dataId);
            thread.setDaemon(true);
            notifyThreads.add(thread);
            thread.start();
        }
        log.info("Apollo config source started, appId={}, dataIds={}, url={}",
                appId, dataIds, configServiceUrl);
    }

    private void loadInitial(String dataId) {
        try {
            Map<String, String> configurations = fetchConfigurations(dataId);
            if (configurations.isEmpty()) {
                log.info("Apollo namespace {} not configured, keep defaults", dataId);
                return;
            }
            snapshots.put(dataId, toSnapshot(dataId, configurations));
            log.info("Loaded Apollo namespace {} ({} keys)", dataId, configurations.size());
        } catch (Exception e) {
            log.warn("Failed to load Apollo namespace {}: {}", dataId, e.getMessage());
        }
    }

    /** 长轮询：服务端 hold 连接直到变更或超时；变更后重拉并广播。 */
    private void notifyLoop(String dataId) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                long notificationId = notificationIds.getOrDefault(dataId, 0L);
                List<Map<String, Object>> notifications = List.of(Map.of(
                        "namespaceName", dataId,
                        "notificationId", notificationId));
                String query = "?appId=" + appId + "&cluster=" + cluster
                        + "&notifications=" + MAPPER.writeValueAsString(notifications);
                HttpResponse<String> resp = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(configServiceUrl + "/notifications/v2" + query))
                                .timeout(Duration.ofSeconds(NOTIFY_TIMEOUT_MS + 10))
                                .header("Accept", "application/json")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode root = MAPPER.readTree(resp.body());
                if (root.isArray() && !root.isEmpty()) {
                    long newId = root.get(0).path("notificationId").asLong();
                    notificationIds.put(dataId, newId);
                    reload(dataId);
                }
            } catch (Exception e) {
                log.warn("Apollo notify loop for {} interrupted/error, retry in 10s: {}",
                        dataId, e.getMessage());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void reload(String dataId) {
        try {
            Map<String, String> configurations = fetchConfigurations(dataId);
            ConfigParser.ConfigSnapshot next = toSnapshot(dataId, configurations);
            ConfigParser.ConfigSnapshot previous = snapshots.getOrDefault(dataId, ConfigParser.ConfigSnapshot.empty());
            if (Objects.equals(previous.rawContent(), next.rawContent())) {
                return;
            }
            snapshots.put(dataId, next);
            log.info("Apollo namespace {} updated", dataId);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new AppConfigChangedEvent(
                        this, dataId, previous.rawContent(), next.rawContent()));
            }
        } catch (Exception e) {
            log.error("Failed to reload Apollo namespace {}, keep previous config: {}", dataId, e.getMessage());
        }
    }

    /** GET /configs/{appId}/{cluster}/{namespace} → configurations 扁平 k/v。 */
    private Map<String, String> fetchConfigurations(String dataId) throws Exception {
        URI uri = URI.create(configServiceUrl + "/configs/" + appId + "/" + cluster + "/" + dataId);
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            return Map.of();
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Apollo fetch failed, status=" + resp.statusCode()
                    + ", body=" + truncate(resp.body(), 200));
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode configurations = root.get("configurations");
        Map<String, String> result = new LinkedHashMap<>();
        if (configurations != null && configurations.isObject()) {
            configurations.fields().forEachRemaining(entry ->
                    result.put(entry.getKey(), entry.getValue().isNull() ? "" : entry.getValue().asText()));
        }
        return result;
    }

    private ConfigParser.ConfigSnapshot toSnapshot(String dataId, Map<String, String> configurations) {
        // 非 properties 格式（.json/.yaml 后缀）且只有一个 key：视为完整原始内容
        String raw;
        String lower = dataId.toLowerCase();
        boolean structuredFormat = lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml");
        if (structuredFormat && configurations.size() == 1) {
            raw = configurations.values().iterator().next();
        } else {
            StringBuilder sb = new StringBuilder();
            configurations.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            raw = sb.toString();
        }
        return ConfigParser.parse(format, dataId, raw);
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String getRawConfig(String dataId) {
        ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? null : snapshot.rawContent();
    }

    @Override
    public boolean isLoaded(String dataId) {
        ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot != null && snapshot.rawContent() != null;
    }

    @Override
    public boolean isLoaded() {
        return dataIds.stream().anyMatch(this::isLoaded);
    }

    @Override
    public Map<String, Object> getConfig(String dataId) {
        ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? Map.of() : snapshot.structured();
    }

    @Override
    public Map<String, String> getProperties(String dataId) {
        ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? Map.of() : snapshot.flat();
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String dataId : dataIds) {
            ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
            if (snapshot != null) {
                merged.putAll(snapshot.flat());
            }
        }
        return merged;
    }

    @Override
    public String getProperty(String dataId, String key, String defaultValue) {
        ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? defaultValue : snapshot.flat().getOrDefault(key, defaultValue);
    }

    @Override
    public void close() {
        notifyThreads.forEach(Thread::interrupt);
        log.info("Apollo config source closed, appId={}", appId);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}
