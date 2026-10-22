package com.armeria.grpc.config.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.armeria.grpc.config.api.AppConfigChangedEvent;
import com.armeria.grpc.config.api.ConfigParser;
import com.armeria.grpc.config.api.ConfigProperties;
import com.armeria.grpc.config.api.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nacos 配置中心客户端：按数组方式（data-ids）拉取所有配置的配置文件，并订阅变更实时更新。
 * 实现 {@link ConfigSource} 抽象接口，业务只依赖 config-api，不感知具体配置中心。
 *
 * <p>每个 dataId 独立加载、解析和订阅；读取方式：
 * <ul>
 *   <li>{@link #getRawConfig(String)} / {@link #getConfig(String)} / {@link #getProperties(String)}
 *       —— 按 dataId 读取单个文件；</li>
 *   <li>{@link #getProperties()} —— 所有配置文件合并后的扁平视图
 *       （后配置的 dataId 覆盖先配置的同名 key）。</li>
 * </ul>
 *
 * <p>默认拉取 {@value ConfigSource#SOA_CONFIG_DATA_ID}（服务治理配置：限流/熔断/黑白名单）；
 * 文件未配置或拉取不到时仅记录日志，不影响启动。
 */
public class AppConfigService implements ConfigSource, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AppConfigService.class);

    private final ConfigService configService;
    private final ConfigProperties properties;
    private final List<String> dataIds;
    private final String group;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService retryExecutor;

    private final Map<String, ConfigParser.ConfigSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> retryFutures = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> initialLoadFutures = new ConcurrentHashMap<>();

    public AppConfigService(ConfigService configService, ConfigProperties properties,
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
            snapshots.put(dataId, ConfigParser.ConfigSnapshot.empty());
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
            log.warn("Failed to load Nacos config dataId={}, group={}, will retry in {}ms: {}",
                    dataId, group, properties.getRetryIntervalMs(), e.getMessage());
            scheduleInitialLoadRetry(dataId);
        }
    }

    /**
     * Retries the initial config load after a startup-time Nacos failure, so a
     * transient Nacos outage does not leave the config permanently empty.
     */
    private void scheduleInitialLoadRetry(String dataId) {
        ScheduledFuture<?> existing = initialLoadFutures.get(dataId);
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> future = retryExecutor.scheduleWithFixedDelay(() -> {
            try {
                String content = configService.getConfig(dataId, group, properties.getTimeoutMs());
                if (content == null || content.isBlank()) {
                    log.info("Nacos config dataId={}, group={} not configured, stop retrying", dataId, group);
                    ScheduledFuture<?> retry = initialLoadFutures.remove(dataId);
                    if (retry != null) {
                        retry.cancel(false);
                    }
                    return;
                }
                snapshots.put(dataId, parse(dataId, content));
                log.info("Loaded Nacos config dataId={}, group={} after retry", dataId, group);
                ScheduledFuture<?> retry = initialLoadFutures.remove(dataId);
                if (retry != null) {
                    retry.cancel(false);
                }
            } catch (Exception e) {
                log.warn("Retry to load Nacos config dataId={}, group={} failed: {}",
                        dataId, group, e.getMessage());
            }
        }, properties.getRetryIntervalMs(), properties.getRetryIntervalMs(), TimeUnit.MILLISECONDS);
        initialLoadFutures.put(dataId, future);
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
        ConfigParser.ConfigSnapshot next;
        try {
            next = parse(dataId, configInfo);
        } catch (Exception e) {
            log.error("Failed to parse updated Nacos config dataId={}, group={}, keep previous config: {}",
                    dataId, group, e.getMessage());
            return;
        }
        ConfigParser.ConfigSnapshot previous = snapshots.getOrDefault(dataId, ConfigParser.ConfigSnapshot.empty());
        if (Objects.equals(previous.rawContent(), next.rawContent())) {
            return;
        }
        snapshots.put(dataId, next);
        log.info("Nacos config updated dataId={}, group={}", dataId, group);
        eventPublisher.publishEvent(new AppConfigChangedEvent(
                this, dataId, previous.rawContent(), next.rawContent()));
    }

    private ConfigParser.ConfigSnapshot parse(String dataId, String content) {
        return ConfigParser.parse(properties.getFormat(), dataId, content);
    }

    @Override
    public String type() {
        return "nacos";
    }

    public List<String> getDataIds() {
        return dataIds;
    }

    public String getGroup() {
        return group;
    }

    /** 最近一次加载/更新的指定 dataId 原始配置内容；未配置或拉取不到时为 null。 */
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

    /** 是否存在任意一个已加载的配置文件。 */
    @Override
    public boolean isLoaded() {
        return dataIds.stream().anyMatch(this::isLoaded);
    }

    /** 指定 dataId 的结构化配置：YAML 为嵌套 Map，properties 为扁平 Map。 */
    @Override
    public Map<String, Object> getConfig(String dataId) {
        ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? Map.of() : snapshot.structured();
    }

    /** 指定 dataId 的扁平化 key=value 配置。 */
    @Override
    public Map<String, String> getProperties(String dataId) {
        ConfigParser.ConfigSnapshot snapshot = snapshots.get(dataId);
        return snapshot == null ? Map.of() : snapshot.flat();
    }

    /** 所有已配置文件的合并扁平视图（后配置的 dataId 覆盖先配置的同名 key）。 */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> merged = new ConcurrentHashMap<>();
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
    public void destroy() {
        initialLoadFutures.values().forEach(future -> future.cancel(false));
        initialLoadFutures.clear();
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

    @Override
    public void close() {
        destroy();
    }
}
