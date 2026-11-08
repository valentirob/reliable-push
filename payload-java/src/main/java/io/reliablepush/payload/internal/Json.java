package io.reliablepush.payload.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency JSON parser for Java 8 (adapted from the reliable-push client's parser).
 *
 * <p><b>Internal API:</b> not part of the supported surface; it may change without notice. It is {@code public} only so
 * that classes in {@code io.reliablepush.payload} can use it.
 *
 * <p>{@link #read(String)} returns {@code null}, {@link String}, {@link Num} (the number exactly as written, so that
 * re-serializing never changes it), {@link Boolean}, {@link LinkedHashMap} (keys in input order; duplicate keys are
 * rejected, so nothing is silently dropped) or {@link ArrayList}. Nesting is limited to {@value #MAX_DEPTH} levels.
 */
public final class Json {

    /** Maximum nesting depth of arrays/objects. */
    public static final int MAX_DEPTH = 64;

    private Json() {
    }

    /** A JSON number kept as its source text. Immutable. */
    public static final class Num {
        private final String text;

        Num(String text) {
            this.text = text;
        }

        /** The literal as it appeared in the input. */
        public String text() {
            return text;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Num && ((Num) o).text.equals(text);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }

        @Override
        public String toString() {
            return text;
        }
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
            char c = peek();
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
            Map<String, Object> m = new LinkedHashMap<String, Object>();
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
                int keyStart = pos;
                String k = string();
                skipWs();
                expect(':');
                skipWs();
                Object v = value(depth);
                if (m.containsKey(k)) {
                    pos = keyStart;
                    throw error(); // duplicate key
                }
                m.put(k, v);
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
            List<Object> l = new ArrayList<Object>();
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
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 >= s.length()) {
                            throw error();
                        }
                        int cp = 0;
                        for (int i = 1; i <= 4; i++) {
                            int d = hexValue(s.charAt(pos + i));
                            if (d < 0) {
                                throw error();
                            }
                            cp = cp * 16 + d;
                        }
                        sb.append((char) cp);
                        pos += 4;
                        break;
                    default:
                        throw error();
                }
                pos++;
            }
        }

        /** ASCII hex digit value, or -1 ({@code Character.digit} would also accept non-ASCII digits). */
        private int hexValue(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            return -1;
        }

        Num number() {
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
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                requireDigit();
                digits();
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                requireDigit();
                digits();
            }
            return new Num(s.substring(start, pos));
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
