package org.example.rpc.governance;

import java.util.List;
import java.util.Map;

/**
 * 从 Nacos soa.json 解析出的服务治理配置（已按当前 appId 合并）。
 * <p>
 * {@code rateLimitQps} 为 -1 表示未配置（不限流）；{@code circuitBreaker} 的字段为 null
 * 表示未配置（构建熔断器时使用 Armeria 默认值）。
 */
public record SoaConfig(
        boolean rateLimitEnabled,
        long rateLimitQps,
        Map<String, Long> interfaceRateLimits,
        boolean circuitBreakerEnabled,
        CircuitBreakerSettings circuitBreaker,
        List<String> blacklist,
        List<String> whitelist) {

    public SoaConfig {
        interfaceRateLimits = Map.copyOf(interfaceRateLimits);
        blacklist = List.copyOf(blacklist);
        whitelist = List.copyOf(whitelist);
    }
}
