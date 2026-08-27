package org.example.common.discovery;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Picks the instance pool for idc-aware local access. Shared by the gRPC client
 * and the gateway so both sides use the exact same routing preference.
 */
public final class IdcPoolSelector {

    /** Nacos instance metadata key for the data center id. */
    public static final String IDC_METADATA_KEY = "idc";

    private IdcPoolSelector() {
    }

    /**
     * Prefers instances whose metadata {@code idc} matches the caller's idc;
     * falls back to all instances when no same-idc instance exists or the
     * caller has no idc configured. The match is case-insensitive after trim.
     */
    public static <T> List<T> selectPool(List<T> instances, String idc,
                                         Function<T, Map<String, String>> metadata) {
        if (idc == null || idc.isBlank()) {
            return instances;
        }
        String expected = idc.trim();
        List<T> preferred = instances.stream()
                .filter(instance -> expected.equalsIgnoreCase(idcOf(metadata.apply(instance))))
                .toList();
        return preferred.isEmpty() ? instances : preferred;
    }

    private static String idcOf(Map<String, String> metadata) {
        String idc = metadata == null ? null : metadata.get(IDC_METADATA_KEY);
        return idc == null ? "" : idc.trim();
    }
}
