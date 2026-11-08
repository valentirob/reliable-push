package io.reliablepush.payload;

import java.util.Collections;
import java.util.Map;

/** Tiny test helper: Java 8 has no Map.of. */
final class TestMaps {
    private TestMaps() {
    }

    static <V> Map<String, V> map(String k, V v) {
        return Collections.singletonMap(k, v);
    }
}
