package com.armeria.grpc.server.governance;

/**
 * 熔断器参数。
 * <p>
 * 字段为 null 表示未配置；构建 Armeria
 * {@link com.linecorp.armeria.client.circuitbreaker.CircuitBreaker} 时，
 * null 的字段不设置，使用 Armeria {@code CircuitBreakerBuilder} 的默认值。
 */
public record CircuitBreakerSettings(
        Double failureRateThreshold,
        Long minimumRequestThreshold,
        Long circuitOpenWindowMillis,
        Long trialRequestIntervalMillis,
        Long counterSlidingWindowMillis) {
}
