package org.example.rpc.governance;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import org.example.config.AppConfigChangedEvent;
import org.example.config.AppConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务治理配置管理：从 Nacos（config 模块）读取 soa.json，
 * 启动时加载、变更后热更新，并把配置应用到限流/熔断/黑白名单。
 */
public class GovernanceConfigManager {

    private static final Logger log = LoggerFactory.getLogger(GovernanceConfigManager.class);
    private static final String CIRCUIT_BREAKER_NAME = "rpc-governance";

    private final RpcGovernanceProperties properties;
    private final ObjectProvider<AppConfigService> configProvider;
    private final String appId;
    private final SoaConfigParser parser = new SoaConfigParser();

    private volatile SoaConfig config;
    private volatile CircuitBreaker circuitBreaker;
    private volatile IpMatcher blacklist;
    private volatile IpMatcher whitelist;
    private volatile TokenBucket globalBucket;
    private final ConcurrentHashMap<String, TokenBucket> interfaceBuckets = new ConcurrentHashMap<>();

    public GovernanceConfigManager(RpcGovernanceProperties properties,
                                   ObjectProvider<AppConfigService> configProvider,
                                   String appId) {
        this.properties = properties;
        this.configProvider = configProvider;
        this.appId = appId;
        apply(parser.baseline(properties));
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
        reload();
    }

    public synchronized void reload() {
        AppConfigService appConfig = configProvider.getIfAvailable();
        String content = appConfig == null ? null : appConfig.getRawConfig();
        try {
            SoaConfig next = parser.parse(content, appId, properties);
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
        blacklist = new IpMatcher(next.blacklist());
        whitelist = new IpMatcher(next.whitelist());
    }

    private static CircuitBreaker buildCircuitBreaker(CircuitBreakerSettings settings) {
        return CircuitBreaker.builder(CIRCUIT_BREAKER_NAME)
                .failureRateThreshold(settings.failureRateThreshold())
                .minimumRequestThreshold(settings.minimumRequestThreshold())
                .circuitOpenWindowMillis(settings.circuitOpenWindowMillis())
                .trialRequestIntervalMillis(settings.trialRequestIntervalMillis())
                .counterSlidingWindowMillis(settings.counterSlidingWindowMillis())
                .build();
    }

    /**
     * 黑白名单校验：命中黑名单拒绝；白名单非空时仅白名单放行。
     */
    public boolean isIpAllowed(String ip) {
        if (blacklist.matches(ip)) {
            return false;
        }
        return whitelist.isEmpty() || whitelist.matches(ip);
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
