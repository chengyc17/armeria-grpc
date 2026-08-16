package org.example.rpc.governance;

import org.junit.jupiter.api.Test;

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
}
