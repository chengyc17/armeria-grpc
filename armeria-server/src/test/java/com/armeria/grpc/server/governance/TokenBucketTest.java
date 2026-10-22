package com.armeria.grpc.server.governance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {

    @Test
    void burstsUpToCapacityThenRejects() {
        TokenBucket bucket = new TokenBucket(3);

        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }

    @Test
    void refillsOverTime() throws Exception {
        TokenBucket bucket = new TokenBucket(100);
        for (int i = 0; i < 100; i++) {
            assertTrue(bucket.tryAcquire());
        }
        assertFalse(bucket.tryAcquire());

        Thread.sleep(50);
        assertTrue(bucket.tryAcquire());
    }

    @Test
    void concurrentAcquiresNeverExceedCapacity() throws Exception {
        // 低速率桶的容量只有 1 个令牌，8 个线程共 1600 次竞争，恰好只有 1 次能成功
        TokenBucket bucket = new TokenBucket(0.001);
        int threads = 8;
        int perThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int j = 0; j < perThread; j++) {
                    if (bucket.tryAcquire()) {
                        accepted.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertEquals(1, accepted.get());
        assertFalse(bucket.tryAcquire());
    }
}
