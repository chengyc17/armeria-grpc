package org.example.rpc.governance;

/**
 * 熔断器参数。
 */
public record CircuitBreakerSettings(
        double failureRateThreshold,
        long minimumRequestThreshold,
        long circuitOpenWindowMillis,
        long trialRequestIntervalMillis,
        long counterSlidingWindowMillis) {
}
