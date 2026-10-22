package com.armeria.grpc.server.governance;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerBuilder;
import com.armeria.grpc.config.api.AppConfigChangedEvent;
import com.armeria.grpc.config.api.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 服务治理配置管理：从 Nacos（config 模块）读取 soa.json，
 * 启动时加载、变更后热更新，并把配置应用到限流/熔断/黑白名单。
 * <p>
 * 只认 soa.json 中的配置；soa.json 未配置的功能不生效：不限流、不熔断、黑白名单为空全部放行。
 */
public class GovernanceConfigManager {

    private static final Logger log = LoggerFactory.getLogger(GovernanceConfigManager.class);
    private static final String CIRCUIT_BREAKER_NAME = "rpc-governance";

    private final ObjectProvider<ConfigSource> configProvider;
    private final String appId;
    private final SoaConfigParser parser = new SoaConfigParser();

    private volatile SoaConfig config;
    private volatile CircuitBreaker circuitBreaker;
    private volatile Set<String> blacklist;
    private volatile Set<String> whitelist;
    private volatile TokenBucket globalBucket;
    private final ConcurrentHashMap<String, TokenBucket> interfaceBuckets = new ConcurrentHashMap<>();

    public GovernanceConfigManager(ObjectProvider<ConfigSource> configProvider,
                                   String appId) {
        this.configProvider = configProvider;
        this.appId = appId;
        apply(parser.empty());
    }

    /**
     * 上下文刷新完成后加载一次 Nacos 配置（此时 config 模块已完成启动拉取）。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onApplicationReady() {
        reload();
    }

    /**
     * Nacos 配置变更（soa.json 修改）时热更新。
     */
    @EventListener(AppConfigChangedEvent.class)
    public void onConfigChanged(AppConfigChangedEvent event) {
        if (!ConfigSource.SOA_CONFIG_DATA_ID.equalsIgnoreCase(event.getDataId())) {
            return;
        }
        reload();
    }

    public synchronized void reload() {
        ConfigSource configSource = configProvider.getIfAvailable();
        // 服务治理配置只认配置中心（nacos/apollo）的 soa.json；未配置或拉取不到时保持空配置（全部放行）
        String content = configSource == null ? null
                : configSource.getRawConfig(ConfigSource.SOA_CONFIG_DATA_ID);
        try {
            SoaConfig next = parser.parse(content, appId);
            apply(next);
            log.info("Governance config applied. appId={}, rateLimit(enabled={}, qps={}), interfaces={}, "
                            + "circuitBreaker(enabled={}), blacklist={}, whitelist={}",
                    appId, next.rateLimitEnabled(), next.rateLimitQps(), next.interfaceRateLimits(),
                    next.circuitBreakerEnabled(), next.blacklist(), next.whitelist());
        } catch (Exception e) {
            log.error("Failed to reload governance config, keep previous config: {}", e.getMessage());
        }
    }

    private synchronized void apply(SoaConfig next) {
        config = next;
        globalBucket = next.rateLimitEnabled() && next.rateLimitQps() > 0
                ? new TokenBucket(next.rateLimitQps()) : null;
        interfaceBuckets.clear();
        circuitBreaker = next.circuitBreakerEnabled() ? buildCircuitBreaker(next.circuitBreaker()) : null;
        blacklist = toAppIdSet(next.blacklist());
        whitelist = toAppIdSet(next.whitelist());
    }

    /** appId 精确匹配集合：忽略空白项，统一 trim + 小写，配置大小写不敏感 */
    private static Set<String> toAppIdSet(List<String> appIds) {
        return appIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static CircuitBreaker buildCircuitBreaker(CircuitBreakerSettings settings) {
        CircuitBreakerBuilder builder = CircuitBreaker.builder(CIRCUIT_BREAKER_NAME);
        // 未配置的字段不设置，使用 Armeria CircuitBreakerBuilder 的默认值
        if (settings.failureRateThreshold() != null) {
            builder.failureRateThreshold(settings.failureRateThreshold());
        }
        if (settings.minimumRequestThreshold() != null) {
            builder.minimumRequestThreshold(settings.minimumRequestThreshold());
        }
        if (settings.circuitOpenWindowMillis() != null) {
            builder.circuitOpenWindowMillis(settings.circuitOpenWindowMillis());
        }
        if (settings.trialRequestIntervalMillis() != null) {
            builder.trialRequestIntervalMillis(settings.trialRequestIntervalMillis());
        }
        if (settings.counterSlidingWindowMillis() != null) {
            builder.counterSlidingWindowMillis(settings.counterSlidingWindowMillis());
        }
        return builder.build();
    }

    /**
     * 黑白名单校验（匹配维度：调用方 appId，随请求的 x-client-app-id 头传入）：
     * 命中黑名单拒绝；白名单非空时仅白名单放行（未携带 appId 的调用方不在白名单内，同样拒绝）。
     */
    public boolean isAppIdAllowed(String appId) {
        if (appId != null && blacklist.contains(appId.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return whitelist.isEmpty()
                || (appId != null && whitelist.contains(appId.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 限流：先扣全局单机 QPS，再扣指定接口 QPS。
     */
    public boolean tryAcquire(String fullMethod, String bareMethod) {
        SoaConfig current = config;
        if (!current.rateLimitEnabled()) {
            return true;
        }
        TokenBucket global = globalBucket;
        if (global != null && !global.tryAcquire()) {
            return false;
        }
        Long qps = current.interfaceRateLimits().get(fullMethod);
        if (qps == null) {
            qps = current.interfaceRateLimits().get(bareMethod);
        }
        if (qps != null && qps > 0) {
            final long interfaceQps = qps;
            TokenBucket bucket = interfaceBuckets.computeIfAbsent(fullMethod, key -> new TokenBucket(interfaceQps));
            if (!bucket.tryAcquire()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 全局熔断器放行判断。
     */
    public boolean tryAcquireCircuitBreaker() {
        CircuitBreaker breaker = circuitBreaker;
        return breaker == null || breaker.tryRequest();
    }

    /**
     * 熔断器是否已启用（启用时才需要在调用结束后上报结果）。
     */
    public boolean isCircuitBreakerEnabled() {
        return circuitBreaker != null;
    }

    /**
     * 上报一次调用结果给熔断器。
     */
    public void reportCircuitBreakerResult(boolean success) {
        CircuitBreaker breaker = circuitBreaker;
        if (breaker == null) {
            return;
        }
        if (success) {
            breaker.onSuccess();
        } else {
            breaker.onFailure();
        }
    }

    public SoaConfig getConfig() {
        return config;
    }
}
