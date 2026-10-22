package com.armeria.grpc.server.governance;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单的单机令牌桶限流器，线程安全。
 *
 * <p>无锁实现：把「令牌数（定点缩放）」和「上次补充时间（毫秒，mod 2^32）」打包进一个
 * {@link AtomicLong}，用 CAS 更新，避免高 QPS 下 {@code synchronized} 锁竞争。
 * 令牌数以 1000 为缩放存高位（1 令牌 = 1000 内部单位），毫秒时间戳存低 32 位
 * （约 49.7 天回绕一次）。回绕只可能发生在桶已闲置超过一个补充周期（容量/速率 = 1 秒）
 * 之后，此时桶本就该是满的，因此不影响正确性。
 */
public final class TokenBucket {

    /** 令牌定点缩放：1 令牌 = 1000 个内部单位，支持毫秒级补充精度 */
    private static final int TOKEN_SCALE = 1_000;

    /** 低 32 位毫秒时间戳掩码（回绕周期约 49.7 天） */
    private static final long MILLIS_MASK = 0xFFFF_FFFFL;

    private final long maxTokens;
    private final double refillPerMillis;
    private final AtomicLong state;

    public TokenBucket(double qps) {
        double rate = Math.max(qps, 0.001);
        // 高位 32 位存令牌数，限制在 int 范围内，保证左移 32 位不溢出
        this.maxTokens = Math.max(Math.min((long) Math.ceil(rate * TOKEN_SCALE), Integer.MAX_VALUE),
                TOKEN_SCALE);
        this.refillPerMillis = rate * TOKEN_SCALE / 1_000.0;
        long now = System.currentTimeMillis();
        this.state = new AtomicLong(encode(maxTokens, now));
    }

    /**
     * 尝试获取一个令牌。
     */
    public boolean tryAcquire() {
        while (true) {
            long current = state.get();
            long tokens = current >>> 32;
            long lastMillis = current & MILLIS_MASK;
            long now = System.currentTimeMillis();
            long elapsed = (now - lastMillis) & MILLIS_MASK;
            if (elapsed > 0) {
                tokens = Math.min(maxTokens, tokens + (long) (elapsed * refillPerMillis));
            }
            if (tokens >= TOKEN_SCALE) {
                long next = encode(tokens - TOKEN_SCALE, now);
                if (state.compareAndSet(current, next)) {
                    return true;
                }
            } else {
                long next = encode(tokens, now);
                if (state.compareAndSet(current, next)) {
                    return false;
                }
            }
        }
    }

    private static long encode(long tokens, long millis) {
        return (tokens << 32) | (millis & MILLIS_MASK);
    }
}
