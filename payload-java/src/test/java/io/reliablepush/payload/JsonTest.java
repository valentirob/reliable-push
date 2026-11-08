package io.reliablepush.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliablepush.payload.internal.Json;
import io.reliablepush.payload.internal.JsonWriter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNumbersAsGiven() {
        String in = "{\"a\":[1,-0,1.50,1E3,2e-2,12345678901234567890,0.1],\"b\":true,\"c\":null,\"d\":{}}";
        assertThat(JsonWriter.write(Json.read(in))).isEqualTo(in);
    }

    @Test
    void escapesPerRfc8259AndKeepsNonAscii() {
        String s = "q\"b\\n\n\r\t\b\f\u0001\u001f/\u00e9\u65e5";
        assertThat(JsonWriter.write(s)).isEqualTo("\"q\\\"b\\\\n\\n\\r\\t\\b\\f\\u0001\\u001f/\u00e9\u65e5\"");
        assertThat(Json.read(JsonWriter.write(s))).isEqualTo(s);
    }

    @Test
    void escapesLoneSurrogatesAndKeepsPairs() {
        assertThat(JsonWriter.write("\ud83d")).isEqualTo("\"\\ud83d\"");
        assertThat(JsonWriter.write("\ude00x")).isEqualTo("\"\\ude00x\"");
        assertThat(JsonWriter.write("\ud83d\ude80")).isEqualTo("\"\ud83d\ude80\"");
    }

    @Test
    void parsesUnicodeEscapes() {
        assertThat(Json.read("\"\\u00e9\\ud83d\\ude80\\/\"")).isEqualTo("\u00e9\ud83d\ude80/");
    }

    @Test
    void writesJavaNumbersAndCollections() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("i", 1);
        m.put("l", 2L);
        m.put("d", 1.5d);
        m.put("bd", new BigDecimal("3.10"));
        m.put("list", Arrays.asList("x", null, false));
        assertThat(JsonWriter.write(m)).isEqualTo("{\"i\":1,\"l\":2,\"d\":1.5,\"bd\":3.10,\"list\":[\"x\",null,false]}");
    }

    @Test
    void rejectsUnrepresentableValues() {
        assertThatThrownBy(() -> JsonWriter.write(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonWriter.write(Float.POSITIVE_INFINITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonWriter.write(new Object())).isInstanceOf(IllegalArgumentException.class);
        Map<Object, Object> badKey = new LinkedHashMap<Object, Object>();
        badKey.put(1, "x");
        assertThatThrownBy(() -> JsonWriter.write(badKey)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExcessiveNestingOnWriteAndRead() {
        Object v = "x";
        for (int i = 0; i < 70; i++) {
            v = Arrays.asList(v);
        }
        final Object deep = v;
        assertThatThrownBy(() -> JsonWriter.write(deep)).isInstanceOf(IllegalArgumentException.class);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            sb.append("{\"a\":");
        }
        sb.append('1');
        for (int i = 0; i < 70; i++) {
            sb.append('}');
        }
        assertThatThrownBy(() -> Json.read(sb.toString())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedDocuments() {
        for (String bad : new String[] {"", " ", "tru", "nul", "[1,]", "[1 2]", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "-", "1.", "1e", "+1",
            ".5", "\"abc", "\"\\u12\"", "\"\\u12G4\"", "\"\\u\u0663\u0663\u0663\u0663\"", "\"\\u\uff11000\"", "[", "{\"a\"", "{1:2}", "1 2", "'x'", "\"a\nb\"", "{\"a\":1,\"a\":1}"}) {
            assertThatThrownBy(() -> Json.read(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Json.read(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertThat(JsonWriter.write(Json.read(" \t\r\n{ \"a\" : [ 1 , 2 ] }\n"))).isEqualTo("{\"a\":[1,2]}");
    }
}
