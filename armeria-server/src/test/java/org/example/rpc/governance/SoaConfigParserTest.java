package org.example.rpc.governance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoaConfigParserTest {

    private final RpcGovernanceProperties baseline = new RpcGovernanceProperties();

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

        SoaConfig config = new SoaConfigParser().parse(json, "user-service", baseline);

        assertTrue(config.rateLimitEnabled());
        assertEquals(5000, config.rateLimitQps());
        assertEquals(1000, config.interfaceRateLimits().get("com.example.UserService/GetUser"));
        assertEquals(500, config.interfaceRateLimits().get("CreateUser"));
        assertTrue(config.circuitBreakerEnabled());
        assertEquals(0.3, config.circuitBreaker().failureRateThreshold());
        assertEquals(5, config.circuitBreaker().minimumRequestThreshold());
        assertEquals(8000, config.circuitBreaker().circuitOpenWindowMillis());
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

        SoaConfig config = new SoaConfigParser().parse(json, "user-service", baseline);

        assertEquals(3000, config.rateLimitQps());
        assertEquals(100, config.interfaceRateLimits().get("GetUser"));
        assertEquals(List.of("9.9.9.9"), config.blacklist());
    }

    @Test
    void returnsBaselineWhenSoaJsonMissing() {
        SoaConfig config = new SoaConfigParser().parse(null, "user-service", baseline);

        assertTrue(config.rateLimitEnabled());
        assertEquals(10_000, config.rateLimitQps());
        assertTrue(config.circuitBreakerEnabled());
        assertEquals(0.5, config.circuitBreaker().failureRateThreshold());
        assertTrue(config.blacklist().isEmpty());
        assertTrue(config.whitelist().isEmpty());
    }

    @Test
    void respectsEnabledFalse() {
        String json = """
                {
                  "rateLimit": { "enabled": false, "qps": 100 },
                  "circuitBreaker": { "enabled": false }
                }
                """;

        SoaConfig config = new SoaConfigParser().parse(json, "user-service", baseline);

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

        SoaConfig config = new SoaConfigParser().parse(json, "user-service", baseline);

        assertFalse(config.rateLimitEnabled());
    }

    @Test
    void baselineQpsMinusOneDisablesRateLimit() {
        RpcGovernanceProperties noLimit = new RpcGovernanceProperties();
        noLimit.setBaselineRateLimitQps(-1);

        SoaConfig config = new SoaConfigParser().parse(null, "user-service", noLimit);

        assertFalse(config.rateLimitEnabled());
    }
}
