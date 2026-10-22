package com.armeria.grpc.server.governance;

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
 *     "blacklist": ["order-service", "legacy-caller"],
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
 * <p>黑白名单的匹配维度是<b>调用方 appId</b>（随请求的 {@code x-client-app-id} 头传入），
 * 精确匹配、大小写不敏感；不是 IP。
 * <p>
 * 没有 "global" 节点时，整个文档视为当前应用的配置；soa.json 缺失或为空时返回
 * 「全部未配置」的空配置（不限流、不熔断、名单为空）。治理配置只认 soa.json。
 */
public final class SoaConfigParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SoaConfig parse(String content, String appId) {
        SoaConfig emptyConfig = empty();
        if (content == null || content.isBlank()) {
            return emptyConfig;
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(content);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid soa.json: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            return emptyConfig;
        }

        JsonNode global = objectNode(root, "global");
        JsonNode app = appId == null ? null : objectNode(root, appId);
        if (global == null && app == null) {
            app = root;
        }

        // 限流：应用配置优先，其次全局；未配置（null）表示不限流，qps <= 0（约定 -1）表示不限制
        Long qps = null;
        boolean rateLimitEnabled = false;
        JsonNode rateLimit = firstNonNull(app, global, "rateLimit");
        if (rateLimit != null && rateLimit.isObject()) {
            qps = longValue(rateLimit, "qps", null);
            rateLimitEnabled = booleanValue(rateLimit, "enabled", true) && qps != null && qps > 0;
        }

        // 熔断：全局参数，应用可覆盖 enabled；节点存在时默认开启，未配置参数的字段为 null
        //（构建 CircuitBreaker 时落 Armeria 默认值）
        boolean circuitBreakerEnabled = false;
        CircuitBreakerSettings circuitBreaker = new CircuitBreakerSettings(null, null, null, null, null);
        JsonNode cbNode = firstNonNull(app, global, "circuitBreaker");
        if (cbNode != null && cbNode.isObject()) {
            circuitBreakerEnabled = booleanValue(cbNode, "enabled", true);
            circuitBreaker = new CircuitBreakerSettings(
                    doubleValue(cbNode, "failureRateThreshold", null),
                    longValue(cbNode, "minimumRequestThreshold", null),
                    longValue(cbNode, "circuitOpenWindowMillis", null),
                    longValue(cbNode, "trialRequestIntervalMillis", null),
                    longValue(cbNode, "counterSlidingWindowMillis", null));
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
                rateLimitEnabled, qps == null ? -1 : qps, interfaces,
                circuitBreakerEnabled, circuitBreaker,
                blacklist, whitelist);
    }

    /** 全部未配置的空配置：不限流、不熔断、名单为空（启动时先铺底，等待 Nacos 配置加载）。 */
    public SoaConfig empty() {
        return new SoaConfig(false, -1, Map.of(), false,
                new CircuitBreakerSettings(null, null, null, null, null),
                List.of(), List.of());
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

    private static Long longValue(JsonNode node, String field, Long defaultValue) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? Long.valueOf(value.longValue()) : defaultValue;
    }

    private static Double doubleValue(JsonNode node, String field, Double defaultValue) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? Double.valueOf(value.doubleValue()) : defaultValue;
    }
}
