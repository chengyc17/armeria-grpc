package com.armeria.grpc.common.env;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIdResolverTest {

    @Test
    void missingConfigFailsFast() {
        assertThrows(IllegalStateException.class, () -> AppIdResolver.resolve(null, null));
        assertThrows(IllegalStateException.class, () -> AppIdResolver.resolve(null, "100004337"));
        assertThrows(IllegalStateException.class, () -> AppIdResolver.resolve("  ", null));
    }

    @Test
    void pureClientWithoutProtoUsesConfigAppId() {
        assertEquals("10000784", AppIdResolver.resolve("10000784", null));
    }

    @Test
    void equalValuesResolveToTheSameId() {
        assertEquals("100004337", AppIdResolver.resolve("100004337", "100004337"));
    }

    @Test
    void mismatchFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AppIdResolver.resolve("10000784", "100004337"));
        assertTrue(e.getMessage().contains("does not match"));
    }
}
