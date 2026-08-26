package org.example.rpc.governance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoaConfigParserTest {

    @Test
    void mergesGlobalAndAppIdSections() {
        String json = """
                {
                  "global": {
                    "rateLimit": { "qps": 20000 },
                    "circuitBreaker": {
                      "failureRateThreshold": 0.3,
                      "minimumRequestThreshold": 5,
                      "circuitOpenWindowMillis": 8000,
                      "trialRequestIntervalMillis": 2000,
                      "counterSlidingWindowMillis": 15000
                    },
                    "blacklist": ["1.2.3.4", "10.0.0.0/8"]
                  },
                  "user-service": {
                    "rateLimit": { "qps": 5000 },
                    "interfaces": {
                      "com.example.UserService/GetUser": { "qps": 1000 },
                      "CreateUser": 500
                    },
                    "whitelist": ["10.0.0.0/8"]
                  }
                }
                """;

        SoaConfig config = new SoaConfigParser().parse(json, "user-service");

        assertTrue(config.rateLimitEnabled());
        assertEquals(5000, config.rateLimitQps());
        assertEquals(1000, config.interfaceRateLimits().get("com.example.UserService/GetUser"));
        assertEquals(500, config.interfaceRateLimits().get("CreateUser"));
        assertTrue(config.circuitBreakerEnabled());
        assertEquals(0.3, config.circuitBreaker().failureRateThreshold().doubleValue());
        assertEquals(5L, config.circuitBreaker().minimumRequestThreshold().longValue());
        assertEquals(8000L, config.circuitBreaker().circuitOpenWindowMillis().longValue());
        assertEquals(List.of("1.2.3.4", "10.0.0.0/8"), config.blacklist());
        assertEquals(List.of("10.0.0.0/8"), config.whitelist());
    }

    @Test
    void treatsWholeDocumentAsAppConfigWhenNoGlobal() {
        String json = """
                {
                  "rateLimit": { "qps": 3000 },
                  "interfaces": { "GetUser": 100 },
                  "blacklist": ["9.9.9.9"]
                }
                """;

        SoaConfig config = new SoaConfigParser().parse(json, "user-service");

        assertEquals(3000, config.rateLimitQps());
        assertEquals(100, config.interfaceRateLimits().get("GetUser"));
        assertEquals(List.of("9.9.9.9"), config.blacklist());
    }

    @Test
    void allGovernanceDisabledWhenSoaJsonMissing() {
        // soa.json 缺失时不限流、不熔断、名单为空
        SoaConfig config = new SoaConfigParser().parse(null, "user-service");

        assertFalse(config.rateLimitEnabled());
        assertEquals(-1, config.rateLimitQps());
        assertFalse(config.circuitBreakerEnabled());
        assertNull(config.circuitBreaker().failureRateThreshold());
        assertNull(config.circuitBreaker().circuitOpenWindowMillis());
        assertTrue(config.blacklist().isEmpty());
        assertTrue(config.whitelist().isEmpty());
    }

    @Test
    void missingCircuitBreakerFieldsStayNull() {
        // soa.json 只配置了部分熔断参数，未配置的字段保持 null（构建时用 Armeria 默认值）
        String json = """
                {
                  "circuitBreaker": { "failureRateThreshold": 0.3 }
                }
                """;

        SoaConfig config = new SoaConfigParser().parse(json, "user-service");

        assertTrue(config.circuitBreakerEnabled());
        assertEquals(0.3, config.circuitBreaker().failureRateThreshold().doubleValue());
        assertNull(config.circuitBreaker().minimumRequestThreshold());
        assertNull(config.circuitBreaker().circuitOpenWindowMillis());
        assertNull(config.circuitBreaker().trialRequestIntervalMillis());
        assertNull(config.circuitBreaker().counterSlidingWindowMillis());
    }

    @Test
    void respectsEnabledFalse() {
        String json = """
                {
                  "rateLimit": { "enabled": false, "qps": 100 },
                  "circuitBreaker": { "enabled": false }
                }
                """;

        SoaConfig config = new SoaConfigParser().parse(json, "user-service");

        assertFalse(config.rateLimitEnabled());
        assertFalse(config.circuitBreakerEnabled());
    }

    @Test
    void qpsMinusOneDisablesRateLimit() {
        String json = """
                {
                  "rateLimit": { "qps": -1 }
                }
                """;

        SoaConfig config = new SoaConfigParser().parse(json, "user-service");

        assertFalse(config.rateLimitEnabled());
    }
}
