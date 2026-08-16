package org.example.rpc.governance;

import java.util.List;
import java.util.Map;

/**
 * 从 Nacos soa.json 解析出的服务治理配置（已按当前 appId 合并）。
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
