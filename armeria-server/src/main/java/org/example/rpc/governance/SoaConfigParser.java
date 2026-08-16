package org.example.rpc.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 Nacos 中 soa.json 的服务治理配置。
 * <p>
 * 支持两种结构：
 * <pre>
 * {
 *   "global": {
 *     "rateLimit": { "enabled": true, "qps": 20000 },
 *     "circuitBreaker": { "enabled": true, "failureRateThreshold": 0.3, ... },
 *     "blacklist": ["1.2.3.4", "10.0.0.0/8"],
 *     "whitelist": []
 *   },
 *   "&lt;appId&gt;": {
 *     "rateLimit": { "qps": 5000 },
 *     "interfaces": {
 *       "com.example.UserService/GetUser": { "qps": 1000 },
 *       "GetUser": 500
 *     },
 *     "blacklist": [...],
 *     "whitelist": [...]
 *   }
 * }
 * </pre>
 * 没有 "global" 节点时，整个文档视为当前应用的配置；soa.json 缺失或为空时返回保底配置。
 */
public final class SoaConfigParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SoaConfig parse(String content, String appId, RpcGovernanceProperties baseline) {
        SoaConfig defaultConfig = baseline(baseline);
        if (content == null || content.isBlank()) {
            return defaultConfig;
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid soa.json: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            return defaultConfig;
        }

        JsonNode global = objectNode(root, "global");
        JsonNode app = appId == null ? null : objectNode(root, appId);
        if (global == null && app == null) {
            app = root;
        }

        // 限流：应用配置优先，其次全局，缺省用保底；qps <= 0（约定 -1）表示不限制
        boolean rateLimitEnabled = baselineRateLimitEnabled(baseline);
        long qps = baseline.getBaselineRateLimitQps();
        JsonNode rateLimit = firstNonNull(app, global, "rateLimit");
        if (rateLimit != null && rateLimit.isObject()) {
            qps = longValue(rateLimit, "qps", qps);
            rateLimitEnabled = booleanValue(rateLimit, "enabled", rateLimitEnabled) && qps > 0;
        }

        // 熔断：全局参数，应用可覆盖 enabled
        boolean circuitBreakerEnabled = baseline.isBaselineCircuitBreakerEnabled();
        CircuitBreakerSettings circuitBreaker = baselineCircuitBreaker(baseline);
        JsonNode cbNode = firstNonNull(app, global, "circuitBreaker");
        if (cbNode != null && cbNode.isObject()) {
            circuitBreakerEnabled = booleanValue(cbNode, "enabled", circuitBreakerEnabled);
            circuitBreaker = new CircuitBreakerSettings(
                    doubleValue(cbNode, "failureRateThreshold", circuitBreaker.failureRateThreshold()),
                    longValue(cbNode, "minimumRequestThreshold", circuitBreaker.minimumRequestThreshold()),
                    longValue(cbNode, "circuitOpenWindowMillis", circuitBreaker.circuitOpenWindowMillis()),
                    longValue(cbNode, "trialRequestIntervalMillis", circuitBreaker.trialRequestIntervalMillis()),
                    longValue(cbNode, "counterSlidingWindowMillis", circuitBreaker.counterSlidingWindowMillis()));
        }

        // 指定接口限流：只有应用段可配（不含熔断）
        Map<String, Long> interfaces = new LinkedHashMap<>();
        JsonNode interfacesNode = app == null ? null : app.get("interfaces");
        if (interfacesNode != null && interfacesNode.isObject()) {
            interfacesNode.fields().forEachRemaining(entry -> {
                long interfaceQps = interfaceQps(entry.getValue());
                if (interfaceQps > 0) {
                    interfaces.put(entry.getKey(), interfaceQps);
                }
            });
        }

        List<String> blacklist = strings(firstNonNull(app, global, "blacklist"));
        List<String> whitelist = strings(firstNonNull(app, global, "whitelist"));

        return new SoaConfig(
                rateLimitEnabled, qps, interfaces,
                circuitBreakerEnabled, circuitBreaker,
                blacklist, whitelist);
    }

    public SoaConfig baseline(RpcGovernanceProperties baseline) {
        return new SoaConfig(
                baselineRateLimitEnabled(baseline),
                baseline.getBaselineRateLimitQps(),
                Map.of(),
                baseline.isBaselineCircuitBreakerEnabled(),
                baselineCircuitBreaker(baseline),
                List.of(),
                List.of());
    }

    private static boolean baselineRateLimitEnabled(RpcGovernanceProperties baseline) {
        // 保底限流始终开启（由 rpc.governance.baseline-rate-limit-qps 决定大小，-1 或 <= 0 表示不限制）
        return baseline.getBaselineRateLimitQps() > 0;
    }

    private static CircuitBreakerSettings baselineCircuitBreaker(RpcGovernanceProperties baseline) {
        return new CircuitBreakerSettings(
                baseline.getBaselineFailureRateThreshold(),
                baseline.getBaselineMinimumRequestThreshold(),
                baseline.getBaselineCircuitOpenWindowMillis(),
                baseline.getBaselineTrialRequestIntervalMillis(),
                baseline.getBaselineCounterSlidingWindowMillis());
    }

    private static JsonNode objectNode(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isObject() ? node : null;
    }

    private static JsonNode firstNonNull(JsonNode app, JsonNode global, String field) {
        if (app != null && app.has(field)) {
            return app.get(field);
        }
        if (global != null && global.has(field)) {
            return global.get(field);
        }
        return null;
    }

    private static long interfaceQps(JsonNode node) {
        if (node == null) {
            return -1;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        if (node.isObject()) {
            JsonNode qps = node.get("qps");
            return qps != null && qps.isNumber() ? qps.longValue() : -1;
        }
        return -1;
    }

    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item != null && item.isTextual()) {
                    result.add(item.textValue());
                }
            });
        }
        return result;
    }

    private static boolean booleanValue(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : defaultValue;
    }

    private static long longValue(JsonNode node, String field, long defaultValue) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.longValue() : defaultValue;
    }

    private static double doubleValue(JsonNode node, String field, double defaultValue) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.doubleValue() : defaultValue;
    }
}
