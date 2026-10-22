package com.armeria.grpc.common.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EndpointWeightsTest {

    @Test
    void defaultWeightScalesByThousand() {
        assertEquals(1000, EndpointWeights.toArmeriaWeight(1.0));
    }

    @Test
    void zeroOrNegativeWeightsFallBackToOne() {
        assertEquals(1, EndpointWeights.toArmeriaWeight(0));
        assertEquals(1, EndpointWeights.toArmeriaWeight(-1.5));
    }

    @Test
    void proportionalWeightsAreKept() {
        assertEquals(2000, EndpointWeights.toArmeriaWeight(2.0));
        assertEquals(500, EndpointWeights.toArmeriaWeight(0.5));
    }
}
