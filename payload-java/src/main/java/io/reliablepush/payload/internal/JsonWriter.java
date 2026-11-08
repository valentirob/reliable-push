package io.reliablepush.payload.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Compact JSON writer (RFC 8259): keys in iteration order, numbers as given, control characters, {@code "} and
 * {@code \} escaped, other characters (including non-ASCII) written as they are, lone surrogates escaped so that the
 * UTF-8 encoding is always valid.
 *
 * <p><b>Internal API:</b> not part of the supported surface; it may change without notice.
 *
 * <p>Accepts {@code null}, {@link String}, {@link Boolean}, finite {@link Number}, {@link Json.Num}, {@code Map<String, ?>}
 * and {@link List}, nested at most {@value Json#MAX_DEPTH} levels.
 */
public final class JsonWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonWriter() {
    }

    /** Serializes a supported value to compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    /** Size of {@code json} in UTF-8 bytes. */
    public static int utf8Length(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeValue(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue());
        } else if (v instanceof Json.Num) {
            sb.append(((Json.Num) v).text());
        } else if (v instanceof Number) {
            if ((v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite()))
                    || (v instanceof Float && (((Float) v).isNaN() || ((Float) v).isInfinite()))) {
                throw new IllegalArgumentException("non-finite number is not representable in JSON");
            }
            sb.append(v);
        } else if (v instanceof Map) {
            checkDepth(depth + 1);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, (String) e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (v instanceof List) {
            checkDepth(depth + 1);
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
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
        if (containerDepth > Json.MAX_DEPTH) {
            throw new IllegalArgumentException("JSON value nested deeper than " + Json.MAX_DEPTH);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        unicodeEscape(sb, c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(i + 1));
                        i++;
                    } else if (Character.isSurrogate(c)) {
                        unicodeEscape(sb, c);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void unicodeEscape(StringBuilder sb, char c) {
        sb.append("\\u").append(HEX[(c >> 12) & 0xf]).append(HEX[(c >> 8) & 0xf]).append(HEX[(c >> 4) & 0xf])
                .append(HEX[c & 0xf]);
    }
}
