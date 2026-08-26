package org.example.config;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.yaml.snakeyaml.Yaml;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nacos 配置中心客户端：按数组方式（data-ids）拉取所有配置的配置文件，并订阅变更实时更新。
 *
 * <p>每个 dataId 独立加载、解析和订阅；读取方式：
 * <ul>
 *   <li>{@link #getRawConfig(String)} / {@link #getConfig(String)} / {@link #getProperties(String)}
 *       —— 按 dataId 读取单个文件；</li>
 *   <li>{@link #getProperties()} —— 所有配置文件合并后的扁平视图
 *       （后配置的 dataId 覆盖先配置的同名 key）。</li>
 * </ul>
 *
 * <p>默认拉取 {@value #SOA_CONFIG_DATA_ID}（服务治理配置：限流/熔断/黑白名单）；
 * 文件未配置或拉取不到时仅记录日志，不影响启动。
 */
public class AppConfigService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AppConfigService.class);

    /** 默认拉取的服务治理配置文件；缺失时治理使用本地兜底配置。 */
    public static final String SOA_CONFIG_DATA_ID = "soa.json";

    private final ConfigService configService;
    private final NacosConfigProperties properties;
    private final List<String> dataIds;
    private final String group;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService retryExecutor;

    private final Map<String, ConfigSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> retryFutures = new ConcurrentHashMap<>();

    public AppConfigService(ConfigService configService, NacosConfigProperties properties,
                            List<String> dataIds, String group,
                            ApplicationEventPublisher eventPublisher) {
        this.configService = configService;
        this.properties = properties;
        this.dataIds = List.copyOf(dataIds);
        this.group = group;
        this.eventPublisher = eventPublisher;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "nacos-config-subscribe");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void afterPropertiesSet() {
        for (String dataId : dataIds) {
            snapshots.put(dataId, ConfigSnapshot.empty());
            loadInitialConfig(dataId);
            subscribe(dataId);
        }
    }

    private void loadInitialConfig(String dataId) {
        try {
            String content = configService.getConfig(dataId, group, properties.getTimeoutMs());
            if (content == null || content.isBlank()) {
                log.info("Nacos config dataId={}, group={} not configured, keep defaults", dataId, group);
                return;
            }
            snapshots.put(dataId, parse(dataId, content));
            log.info("Loaded Nacos config dataId={}, group={}", dataId, group);
        } catch (Exception e) {
            log.warn("Failed to load Nacos config dataId={}, group={}: {}", dataId, group, e.getMessage());
        }
    }

    private void subscribe(String dataId) {
        Listener listener = new Listener() {
            @Override
            public java.util.concurrent.Executor getExecutor() {
                // null 表示使用 Nacos 客户端的默认执行器
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                onConfigChanged(dataId, configInfo);
            }
        };
        listeners.put(dataId, listener);

        try {
            configService.addListener(dataId, group, listener);
            log.info("Subscribed Nacos config dataId={}, group={}", dataId, group);
        } catch (Exception e) {
            log.warn("Failed to subscribe Nacos config dataId={}, group={}, will retry in {}ms: {}",
                    dataId, group, properties.getRetryIntervalMs(), e.getMessage());
            scheduleSubscribeRetry(dataId);
        }
    }

    private void scheduleSubscribeRetry(String dataId) {
        ScheduledFuture<?> future = retryExecutor.scheduleWithFixedDelay(() -> {
            try {
                configService.addListener(dataId, group, listeners.get(dataId));
                log.info("Subscribed Nacos config dataId={}, group={} after retry", dataId, group);
                ScheduledFuture<?> retryFuture = retryFutures.remove(dataId);
                if (retryFuture != null) {
                    retryFuture.cancel(false);
                }
            } catch (Exception e) {
                log.warn("Retry to subscribe Nacos config dataId={}, group={} failed: {}",
                        dataId, group, e.getMessage());
            }
        }, properties.getRetryIntervalMs(), properties.getRetryIntervalMs(), TimeUnit.MILLISECONDS);
        retryFutures.put(dataId, future);
    }

    private void onConfigChanged(String dataId, String configInfo) {
        ConfigSnapshot next;
        try {
            next = parse(dataId, configInfo);
        } catch (Exception e) {
            log.error("Failed to parse updated Nacos config dataId={}, group={}, keep previous config: {}",
                    dataId, group, e.getMessage());
            return;
        }
        ConfigSnapshot previous = snapshots.getOrDefault(dataId, ConfigSnapshot.empty());
        if (Objects.equals(previous.rawContent(), next.rawContent())) {
            return;
        }
        snapshots.put(dataId, next);
        log.info("Nacos config updated dataId={}, group={}", dataId, group);
        eventPublisher.publishEvent(new AppConfigChangedEvent(
                this, dataId, group, previous.rawContent(), next.rawContent()));
    }

    private ConfigSnapshot parse(String dataId, String content) {
        if (content == null || content.isBlank()) {
            return ConfigSnapshot.empty();
        }
        return switch (properties.getFormat()) {
            case YAML -> parseYaml(content);
            case JSON -> parseJson(content);
            case PROPERTIES -> parseProperties(content);
            case AUTO -> parseAuto(dataId, content);
        };
    }

    private ConfigSnapshot parseAuto(String dataId, String content) {
        if (isYamlDataId(dataId)) {
            return parseYaml(content);
        }
        if (isJsonDataId(dataId)) {
            return parseJson(content);
        }
        if (isYamlLike(content)) {
            return parseYaml(content);
        }
        return parseProperties(content);
    }

    private static boolean isYamlDataId(String dataId) {
        String lower = dataId.toLowerCase();
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static boolean isJsonDataId(String dataId) {
        return dataId.toLowerCase().endsWith(".json");
    }

    private static boolean isYamlLike(String content) {
        // 无后缀时：形如 "key: value" 的行按 YAML 处理
        return content.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .findFirst()
                .map(line -> line.matches("^[A-Za-z0-9_.\\-]+:\\s.*$"))
                .orElse(false);
    }

    private ConfigSnapshot parseYaml(String content) {
        Object loaded = new Yaml().load(content);
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "YAML config must be a mapping, but got: "
                            + (loaded == null ? "null" : loaded.getClass().getName()));
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            structured.put(key, value);
            flatten(key, value, flat);
        }
        return new ConfigSnapshot(content, structured, flat);
    }

    private ConfigSnapshot parseProperties(String content) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse properties config: " + e.getMessage(), e);
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        Map<String, String> flat = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            String value = props.getProperty(name);
            structured.put(name, value);
            flat.put(name, value);
        }
        return new ConfigSnapshot(content, structured, flat);
    }

    private ConfigSnapshot parseJson(String content) {
        try {
            JsonNode root = new ObjectMapper().readTree(content);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("JSON config must be a JSON object");
            }
            Map<String, Object> structured = new LinkedHashMap<>();
            Map<String, String> flat = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                Object value = toJava(entry.getValue());
                structured.put(entry.getKey(), value);
                flatten(entry.getKey(), value, flat);
            });
            return new ConfigSnapshot(content, structured, flat);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON config: " + e.getMessage(), e);
        }
    }

    private static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), toJava(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(toJava(item)));
            return list;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.toString();
    }

    private static void flatten(String prefix, Object value, Map<String, String> flat) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                flatten(prefix + "." + entry.getKey(), entry.getValue(), flat);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), flat);
            }
        } else {
            flat.put(prefix, value == null ? "" : value.toString());
        }
    }

    public List<String> getDataIds() {
        return dataIds;
    }

    public String getGroup() {
        return group;
    }

    /** 最近一次加载/更新的指定 dataId 原始配置内容；未配置或拉取不到时为 null。 */
    public String getRawConfig(String dataId) {
        ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? null : snapshot.rawContent();
    }

    public boolean isLoaded(String dataId) {
        ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot != null && snapshot.rawContent() != null;
    }

    /** 是否存在任意一个已加载的配置文件。 */
    public boolean isLoaded() {
        return dataIds.stream().anyMatch(this::isLoaded);
    }

    /** 指定 dataId 的结构化配置：YAML 为嵌套 Map，properties 为扁平 Map。 */
    public Map<String, Object> getConfig(String dataId) {
        ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? Map.of() : snapshot.structured();
    }

    /** 指定 dataId 的扁平化 key=value 配置。 */
    public Map<String, String> getProperties(String dataId) {
        ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? Map.of() : snapshot.flat();
    }

    /** 所有已配置文件的合并扁平视图（后配置的 dataId 覆盖先配置的同名 key）。 */
    public Map<String, String> getProperties() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (String dataId : dataIds) {
            ConfigSnapshot snapshot = snapshots.get(dataId);
            if (snapshot != null) {
                merged.putAll(snapshot.flat());
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    public String getProperty(String dataId, String key) {
        return getProperty(dataId, key, null);
    }

    public String getProperty(String dataId, String key, String defaultValue) {
        ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? defaultValue : snapshot.flat().getOrDefault(key, defaultValue);
    }

    @Override
    public void destroy() {
        retryFutures.values().forEach(future -> future.cancel(false));
        retryFutures.clear();
        retryExecutor.shutdownNow();

        for (Map.Entry<String, Listener> entry : listeners.entrySet()) {
            try {
                configService.removeListener(entry.getKey(), group, entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to remove Nacos config listener dataId={}, group={}: {}",
                        entry.getKey(), group, e.getMessage());
            }
        }
        listeners.clear();
    }

    private record ConfigSnapshot(String rawContent, Map<String, Object> structured, Map<String, String> flat) {

        static ConfigSnapshot empty() {
            return new ConfigSnapshot(null, Map.of(), Map.of());
        }

        ConfigSnapshot {
            structured = Collections.unmodifiableMap(new LinkedHashMap<>(structured));
            flat = Collections.unmodifiableMap(new LinkedHashMap<>(flat));
        }
    }
}
