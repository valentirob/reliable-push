package io.reliablepush.client.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency JSON codec.
 *
 * <p><b>Internal API:</b> not part of the supported public surface of the reliable-push client; it may change or
 * disappear without notice. It is {@code public} only so that classes in {@code io.reliablepush.client} can use it.
 *
 * <p>{@link #write(Object)} accepts {@code null}, {@link String}, {@link Number} (finite), {@link Boolean},
 * {@code Map<String, ?>} and {@link List} (nested, at most {@value #MAX_DEPTH} levels). {@link #read(String)} returns
 * {@code null}, {@link String}, {@link Long} (integers; values outside the {@code long} range are rejected),
 * {@link Double}, {@link Boolean}, {@link LinkedHashMap} or {@link ArrayList}.
 */
public final class Json {

    /** Maximum nesting depth of arrays/objects. */
    public static final int MAX_DEPTH = 64;

    private Json() {
    }

    /** Serializes a supported value to compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    /** Parses a JSON document; throws {@link IllegalArgumentException} ("invalid JSON at offset") if malformed. */
    public static Object read(String json) {
        if (json == null) {
            throw new IllegalArgumentException("invalid JSON at 0");
        }
        Reader r = new Reader(json);
        r.skipWs();
        Object v = r.value(0);
        r.skipWs();
        if (r.pos != json.length()) {
            throw r.error();
        }
        return v;
    }

    private static void writeValue(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Number n) {
            if ((n instanceof Double d && (d.isNaN() || d.isInfinite()))
                    || (n instanceof Float f && (f.isNaN() || f.isInfinite()))) {
                throw new IllegalArgumentException("non-finite number is not representable in JSON");
            }
            sb.append(n);
        } else if (v instanceof Map<?, ?> m) {
            checkDepth(depth + 1);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, k);
                sb.append(':');
                writeValue(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            checkDepth(depth + 1);
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o, depth + 1);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON type: " + v.getClass().getName());
        }
    }

    private static void checkDepth(int containerDepth) {
        if (containerDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON value nested deeper than " + MAX_DEPTH);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        IllegalArgumentException error() {
            return new IllegalArgumentException("invalid JSON at " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (pos >= s.length()) {
                throw error();
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth + 1);
                case '[':
                    return array(depth + 1);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error();
            }
        }

        void literal(String word) {
            if (!s.startsWith(word, pos)) {
                throw error();
            }
            pos += word.length();
        }

        Map<String, Object> object(int depth) {
            if (depth > MAX_DEPTH) {
                throw error();
            }
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error();
                }
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                m.put(k, value(depth));
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    pos--;
                    throw error();
                }
            }
        }

        List<Object> array(int depth) {
            if (depth > MAX_DEPTH) {
                throw error();
            }
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value(depth));
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    pos--;
                    throw error();
                }
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error();
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw error();
            }
            pos++;
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                if (c == '"') {
                    pos++;
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error();
                }
                if (c != '\\') {
                    sb.append(c);
                    pos++;
                    continue;
                }
                pos++;
                char e = peek();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 >= s.length()) {
                            throw error();
                        }
                        int cp = 0;
                        for (int i = 1; i <= 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            if (d < 0) {
                                throw error();
                            }
                            cp = cp * 16 + d;
                        }
                        sb.append((char) cp);
                        pos += 4;
                    }
                    default -> throw error();
                }
                pos++;
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            char c = peek();
            if (c == '0') {
                pos++;
            } else if (c >= '1' && c <= '9') {
                digits();
            } else {
                throw error();
            }
            boolean integral = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                requireDigit();
                digits();
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                requireDigit();
                digits();
            }
            String text = s.substring(start, pos);
            try {
                if (integral) {
                    return Long.parseLong(text);
                }
                double d = Double.parseDouble(text);
                if (Double.isInfinite(d)) {
                    pos = start;
                    throw error();
                }
                return d;
            } catch (NumberFormatException ex) {
                pos = start;
                throw error();
            }
        }

        void requireDigit() {
            char c = peek();
            if (c < '0' || c > '9') {
                throw error();
            }
        }

        void digits() {
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
        }
    }
}
