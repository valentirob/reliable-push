package io.reliablepush.client.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void writesNestedStructures() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", java.util.Arrays.asList(1, true, null));
        m.put("b", "x\"\n\u0001");
        assertThat(Json.write(m)).isEqualTo("{\"a\":[1,true,null],\"b\":\"x\\\"\\n\\u0001\"}");
    }

    @Test
    void writesScalarsAndEscapes() {
        assertThat(Json.write(null)).isEqualTo("null");
        assertThat(Json.write(1.5)).isEqualTo("1.5");
        assertThat(Json.write(7L)).isEqualTo("7");
        assertThat(Json.write("a\\b\r\t\b\f/")).isEqualTo("\"a\\\\b\\r\\t\\b\\f/\"");
        assertThat(Json.write(Map.of())).isEqualTo("{}");
        assertThat(Json.write(List.of())).isEqualTo("[]");
    }

    @Test
    void readsWhatItWrites() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("k", "é 😀");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "é 😀 \"q\" \\ \n");
        m.put("n", 3L);
        m.put("d", 2.5);
        m.put("t", true);
        m.put("f", false);
        m.put("z", null);
        m.put("l", new ArrayList<Object>(List.of(inner, 1L)));
        m.put("e", new LinkedHashMap<String, Object>());
        Object back = Json.read(Json.write(m));
        assertThat(back).isEqualTo(m).isInstanceOf(LinkedHashMap.class);
        assertThat(((Map<?, ?>) back).get("l")).isInstanceOf(ArrayList.class);
    }

    @Test
    void readsEscapesAndWhitespace() {
        assertThat(Json.read(" \"\\u00e9\\ud83d\\ude00\\/\\b\\f\\n\\r\\t\\\\\\\"\" ")).isEqualTo("é😀/\b\f\n\r\t\\\"");
        assertThat(Json.read(" [ ] ")).isEqualTo(new ArrayList<>());
        assertThat(Json.read(" { } ")).isEqualTo(new LinkedHashMap<>());
        assertThat(Json.read("null")).isNull();
    }

    @Test
    void readsNumbers() {
        assertThat(Json.read("1")).isEqualTo(1L);
        assertThat(Json.read("1.5")).isEqualTo(1.5);
        assertThat(Json.read("-0")).isEqualTo(0L);
        assertThat(Json.read("1e3")).isEqualTo(1000.0);
        assertThat(Json.read("-2E-1")).isEqualTo(-0.2);
        assertThat(Json.read("[12,-3]")).isEqualTo(List.of(12L, -3L));
    }

    @Test
    void rejectsMalformed() {
        for (String bad : new String[] {
                "{\"a\":}", "[1,]", "\"\\x\"", "{} x", "", "{", "[1", "\"abc", "tru", "nul", "01", "1.", "-", "1e",
                "{\"a\" 1}", "{a:1}", "[1 2]", "\"\\u12\"", "\"a\nb\"", "{\"a\":1,}", "+1", ".5",
                "[".repeat(65) + "]".repeat(65), "{\"a\":".repeat(65) + "1" + "}".repeat(65), "99999999999999999999" }) {
            assertThatThrownBy(() -> Json.read(bad))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid JSON at");
        }
        assertThatThrownBy(() -> Json.read(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsMaxDepth() {
        assertThat(Json.read("[".repeat(64) + "]".repeat(64))).isNotNull();
    }

    @Test
    void rejectsUnsupportedTypes() {
        assertThatThrownBy(() -> Json.write(new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(Map.of(1, 2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.write(Double.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
        List<Object> cyc = new ArrayList<>();
        Object deep = cyc;
        for (int i = 0; i < 65; i++) {
            List<Object> n = new ArrayList<>();
            n.add(deep);
            deep = n;
        }
        Object tooDeep = deep;
        assertThatThrownBy(() -> Json.write(tooDeep)).isInstanceOf(IllegalArgumentException.class);
    }
}
