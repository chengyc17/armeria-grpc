package org.example.rpc.governance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpMatcherTest {

    @Test
    void matchesExactIpAndCidr() {
        IpMatcher matcher = new IpMatcher(List.of("1.2.3.4", "10.0.0.0/8"));

        assertTrue(matcher.matches("1.2.3.4"));
        assertTrue(matcher.matches("10.20.30.40"));
        assertFalse(matcher.matches("1.2.3.5"));
        assertFalse(matcher.matches("11.0.0.1"));
    }

    @Test
    void emptyMatcherMatchesNothing() {
        IpMatcher matcher = new IpMatcher(List.of());

        assertTrue(matcher.isEmpty());
        assertFalse(matcher.matches("1.1.1.1"));
    }
}
