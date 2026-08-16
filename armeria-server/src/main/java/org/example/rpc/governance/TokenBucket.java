package org.example.rpc.governance;

/**
 * 简单的单机令牌桶限流器，线程安全。
 */
public final class TokenBucket {

    private final double maxTokens;
    private final double refillPerSecond;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double qps) {
        this.maxTokens = Math.max(qps, 1);
        this.refillPerSecond = Math.max(qps, 0.001);
        this.tokens = this.maxTokens;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 尝试获取一个令牌。
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        tokens = Math.min(maxTokens, tokens + elapsedSeconds * refillPerSecond);
    }
}
