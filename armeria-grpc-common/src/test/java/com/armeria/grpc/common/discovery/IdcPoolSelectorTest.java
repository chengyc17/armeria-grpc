package com.armeria.grpc.common.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdcPoolSelectorTest {

    private record Item(String name, Map<String, String> metadata) {
    }

    private static Item item(String name, String idc) {
        return new Item(name,
                idc == null ? Map.of() : Map.of(IdcPoolSelector.IDC_METADATA_KEY, idc));
    }

    @Test
    void prefersSameIdcInstances() {
        List<Item> all = List.of(item("bj-1", "bj"), item("sh-1", "sh"), item("bj-2", "bj"));

        List<Item> pool = IdcPoolSelector.selectPool(all, "bj", Item::metadata);

        assertEquals(List.of("bj-1", "bj-2"), pool.stream().map(Item::name).toList());
    }

    @Test
    void idcMatchIsCaseInsensitiveAndTrimmed() {
        List<Item> all = List.of(item("a", " BJ "), item("b", "sh"));

        List<Item> pool = IdcPoolSelector.selectPool(all, "bj", Item::metadata);

        assertEquals(1, pool.size());
        assertEquals("a", pool.get(0).name());
    }

    @Test
    void fallsBackToAllWhenNoSameIdcInstanceExists() {
        List<Item> all = List.of(item("sh-1", "sh"), item("gz-1", "gz"));

        List<Item> pool = IdcPoolSelector.selectPool(all, "bj", Item::metadata);

        assertEquals(all, pool);
    }

    @Test
    void fallsBackToAllWhenClientIdcIsBlank() {
        List<Item> all = List.of(item("sh-1", "sh"));

        assertEquals(all, IdcPoolSelector.selectPool(all, null, Item::metadata));
        assertEquals(all, IdcPoolSelector.selectPool(all, "  ", Item::metadata));
    }

    @Test
    void instancesWithoutIdcMetadataDoNotMatch() {
        List<Item> all = List.of(item("no-idc", null), item("bj-1", "bj"));

        List<Item> pool = IdcPoolSelector.selectPool(all, "bj", Item::metadata);

        assertEquals(1, pool.size());
        assertEquals("bj-1", pool.get(0).name());
    }
}
