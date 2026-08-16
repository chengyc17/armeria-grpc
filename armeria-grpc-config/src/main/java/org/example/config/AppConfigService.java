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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 从 Nacos 读取指定 dataId 的配置：启动时加载，配置修改后实时更新。
 * <p>
 * 读取方式：{@link #getProperty(String)} / {@link #getProperties()} 获取扁平化配置，
 * {@link #getConfig()} 获取结构化配置（YAML 为嵌套 Map，properties 为扁平 Map）；
 * 配置变更时会发布 {@link AppConfigChangedEvent}。
 */
public class AppConfigService implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AppConfigService.class);

    private final ConfigService configService;
    private final NacosConfigProperties properties;
    private final String dataId;
    private final String group;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService retryExecutor;
    private final Listener listener;

    private volatile ConfigSnapshot snapshot = ConfigSnapshot.empty();
    private volatile ScheduledFuture<?> subscribeRetryFuture;

    public AppConfigService(ConfigService configService, NacosConfigProperties properties,
                            String dataId, String group, ApplicationEventPublisher eventPublisher) {
        this.configService = configService;
        this.properties = properties;
        this.dataId = dataId;
        this.group = group;
        this.eventPublisher = eventPublisher;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "nacos-config-subscribe-" + dataId);
            thread.setDaemon(true);
            return thread;
        });
        this.listener = new Listener() {
            @Override
            public java.util.concurrent.Executor getExecutor() {
                // null 表示使用 Nacos 客户端的默认执行器
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                onConfigChanged(configInfo);
            }
        };
    }

    @Override
    public void afterPropertiesSet() {
        loadInitialConfig();
        subscribe();
    }

    private void loadInitialConfig() {
        try {
            String content = configService.getConfig(dataId, group, properties.getTimeoutMs());
            snapshot = parse(content);
            log.info("Loaded Nacos config dataId={}, group={}", dataId, group);
        } catch (Exception e) {
            log.error("Failed to load Nacos config dataId={}, group={}: {}", dataId, group, e.getMessage());
        }
    }

    private void subscribe() {
        try {
            configService.addListener(dataId, group, listener);
            log.info("Subscribed Nacos config dataId={}, group={}", dataId, group);
        } catch (Exception e) {
            log.warn("Failed to subscribe Nacos config dataId={}, group={}, will retry in {}ms: {}",
                    dataId, group, properties.getRetryIntervalMs(), e.getMessage());
            scheduleSubscribeRetry();
        }
    }

    private void scheduleSubscribeRetry() {
        subscribeRetryFuture = retryExecutor.scheduleWithFixedDelay(() -> {
            try {
                configService.addListener(dataId, group, listener);
                log.info("Subscribed Nacos config dataId={}, group={} after retry", dataId, group);
                ScheduledFuture<?> future = subscribeRetryFuture;
                if (future != null) {
                    future.cancel(false);
                    subscribeRetryFuture = null;
                }
            } catch (Exception e) {
                log.warn("Retry to subscribe Nacos config dataId={}, group={} failed: {}",
                        dataId, group, e.getMessage());
            }
        }, properties.getRetryIntervalMs(), properties.getRetryIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void onConfigChanged(String configInfo) {
        ConfigSnapshot next;
        try {
            next = parse(configInfo);
        } catch (Exception e) {
            log.error("Failed to parse updated Nacos config dataId={}, group={}, keep previous config: {}",
                    dataId, group, e.getMessage());
            return;
        }
        ConfigSnapshot previous = snapshot;
        if (Objects.equals(previous.rawContent(), next.rawContent())) {
            return;
        }
        snapshot = next;
        log.info("Nacos config updated dataId={}, group={}", dataId, group);
        eventPublisher.publishEvent(new AppConfigChangedEvent(
                this, dataId, group, previous.rawContent(), next.rawContent()));
    }

    private ConfigSnapshot parse(String content) {
        if (content == null || content.isBlank()) {
            return ConfigSnapshot.empty();
        }
        return switch (properties.getFormat()) {
            case YAML -> parseYaml(content);
            case JSON -> parseJson(content);
            case PROPERTIES -> parseProperties(content);
            case AUTO -> parseAuto(content);
        };
    }

    private ConfigSnapshot parseAuto(String content) {
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
            List<Object> list = new java.util.ArrayList<>();
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

    public String getDataId() {
        return dataId;
    }

    public String getGroup() {
        return group;
    }

    /** 最近一次加载/更新的原始配置内容；未加载到配置时为 null */
    public String getRawConfig() {
        return snapshot.rawContent();
    }

    public boolean isLoaded() {
        return snapshot.rawContent() != null;
    }

    /** 结构化配置：YAML 为嵌套 Map，properties 为扁平 Map */
    public Map<String, Object> getConfig() {
        return snapshot.structured();
    }

    /** 扁平化 key=value 配置 */
    public Map<String, String> getProperties() {
        return snapshot.flat();
    }

    public String getProperty(String key) {
        return snapshot.flat().get(key);
    }

    public String getProperty(String key, String defaultValue) {
        return snapshot.flat().getOrDefault(key, defaultValue);
    }

    @Override
    public void destroy() {
        ScheduledFuture<?> future = subscribeRetryFuture;
        if (future != null) {
            future.cancel(false);
        }
        retryExecutor.shutdownNow();
        try {
            configService.removeListener(dataId, group, listener);
        } catch (Exception e) {
            log.warn("Failed to remove Nacos config listener dataId={}, group={}: {}",
                    dataId, group, e.getMessage());
        }
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
