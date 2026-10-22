package com.armeria.grpc.config.nacos;

import com.armeria.grpc.config.api.ConfigSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NacosConfigAutoConfigurationTest {

    private static final String SOA_DATA_ID = ConfigSource.SOA_CONFIG_DATA_ID;

    @Test
    void emptyListAddsDefaultSoaJson() {
        assertEquals(List.of(SOA_DATA_ID), NacosConfigSourceFactory.resolveDataIds(List.of()));
    }

    @Test
    void nullListAddsDefaultSoaJson() {
        assertEquals(List.of(SOA_DATA_ID), NacosConfigSourceFactory.resolveDataIds(null));
    }

    @Test
    void blankAndNullEntriesAreFilteredAndSoaJsonAddedFirst() {
        List<String> result = NacosConfigSourceFactory.resolveDataIds(
                Arrays.asList(null, "  ", "app.yaml"));
        assertEquals(List.of(SOA_DATA_ID, "app.yaml"), result);
    }

    @Test
    void existingSoaJsonIsNotDuplicated() {
        List<String> result = NacosConfigSourceFactory.resolveDataIds(
                List.of("app.yaml", SOA_DATA_ID));
        assertEquals(List.of("app.yaml", SOA_DATA_ID), result);
    }

    @Test
    void existingSoaJsonWithDifferentCaseOrSpacesIsNotDuplicated() {
        List<String> result = NacosConfigSourceFactory.resolveDataIds(
                Arrays.asList(" soa.json ", "app.yaml"));
        assertEquals(List.of(SOA_DATA_ID, "app.yaml"), result);
    }

    @Test
    void soaJsonIsAddedFirstWhenMissing() {
        List<String> result = NacosConfigSourceFactory.resolveDataIds(List.of("app.yaml"));
        assertEquals(List.of(SOA_DATA_ID, "app.yaml"), result);
    }
}
