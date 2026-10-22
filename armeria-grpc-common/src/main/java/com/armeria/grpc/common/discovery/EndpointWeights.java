package com.armeria.grpc.common.discovery;

/**
 * Converts Nacos instance scheduling weights to Armeria endpoint weights.
 * Shared by the gRPC client and the gateway so weighted round-robin behaves
 * identically everywhere.
 */
public final class EndpointWeights {

    private EndpointWeights() {
    }

    /**
     * Nacos weights are doubles (default 1.0); Armeria endpoint weights are
     * positive ints with a default of 1000. Scale by 1000 and keep the result
     * positive so zero/negative weights still route.
     */
    public static int toArmeriaWeight(double weight) {
        if (weight <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.round(weight * 1000));
    }
}
