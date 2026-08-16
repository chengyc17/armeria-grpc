package org.example.rpc.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 服务治理的本地保底配置，前缀 {@code rpc.governance}。
 * <p>
 * 这些值在 Nacos 的 soa.json 未提供对应配置时生效（保底值）。
 */
@ConfigurationProperties("rpc.governance")
public class RpcGovernanceProperties {

    /** 是否启用服务治理（限流/熔断/黑白名单） */
    private boolean enabled = true;

    /** 全局单机限流保底 QPS；-1（或小于等于 0）表示不限制，即关闭限流 */
    private long baselineRateLimitQps = 10_000;

    /** 熔断保底参数 */
    private boolean baselineCircuitBreakerEnabled = true;
    private double baselineFailureRateThreshold = 0.5;
    private long baselineMinimumRequestThreshold = 10;
    private long baselineCircuitOpenWindowMillis = 10_000;
    private long baselineTrialRequestIntervalMillis = 3_000;
    private long baselineCounterSlidingWindowMillis = 20_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getBaselineRateLimitQps() {
        return baselineRateLimitQps;
    }

    public void setBaselineRateLimitQps(long baselineRateLimitQps) {
        this.baselineRateLimitQps = baselineRateLimitQps;
    }

    public boolean isBaselineCircuitBreakerEnabled() {
        return baselineCircuitBreakerEnabled;
    }

    public void setBaselineCircuitBreakerEnabled(boolean baselineCircuitBreakerEnabled) {
        this.baselineCircuitBreakerEnabled = baselineCircuitBreakerEnabled;
    }

    public double getBaselineFailureRateThreshold() {
        return baselineFailureRateThreshold;
    }

    public void setBaselineFailureRateThreshold(double baselineFailureRateThreshold) {
        this.baselineFailureRateThreshold = baselineFailureRateThreshold;
    }

    public long getBaselineMinimumRequestThreshold() {
        return baselineMinimumRequestThreshold;
    }

    public void setBaselineMinimumRequestThreshold(long baselineMinimumRequestThreshold) {
        this.baselineMinimumRequestThreshold = baselineMinimumRequestThreshold;
    }

    public long getBaselineCircuitOpenWindowMillis() {
        return baselineCircuitOpenWindowMillis;
    }

    public void setBaselineCircuitOpenWindowMillis(long baselineCircuitOpenWindowMillis) {
        this.baselineCircuitOpenWindowMillis = baselineCircuitOpenWindowMillis;
    }

    public long getBaselineTrialRequestIntervalMillis() {
        return baselineTrialRequestIntervalMillis;
    }

    public void setBaselineTrialRequestIntervalMillis(long baselineTrialRequestIntervalMillis) {
        this.baselineTrialRequestIntervalMillis = baselineTrialRequestIntervalMillis;
    }

    public long getBaselineCounterSlidingWindowMillis() {
        return baselineCounterSlidingWindowMillis;
    }

    public void setBaselineCounterSlidingWindowMillis(long baselineCounterSlidingWindowMillis) {
        this.baselineCounterSlidingWindowMillis = baselineCounterSlidingWindowMillis;
    }
}
