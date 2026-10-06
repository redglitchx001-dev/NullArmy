package redglitchx.nullarmy.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A strictly bounded JSON reader/writer.
 *
 * <p>Spec 1.1 forbids a third-party runtime dependency and explicitly allows
 * "a small, strictly bounded in-project codec". This is it. It exists because
 * the deterministic builder and other internal endpoints use JSON, and the spec demands
 * "strict JSON/schema validation" (7.4) - so the parser must reject malformed
 * input rather than guess.</p>
 *
 * <p>Values are represented as: {@code null}, {@code Boolean}, {@code Double},
 * {@code String}, {@code List<Object>}, {@code Map<String, Object>}.</p>
 *
 * <p>Deliberately limited: no streaming, no annotations, no reflection, no
 * arbitrary Object binding (which is how gadget-chain deserialisation bugs get
 * in). Only these six types are ever produced.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Json {

    private static final int MAX_DEPTH = 32;

    /** Strict JSON number grammar, used to reject tokens like {@code 01} or {@code 1.}. */
    private static final Pattern NUMBER = Pattern.compile(
            "-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String src;
    private int pos;
    private int depth;

    private Json(String src) {
        this.src = src;
    }

    /** @throws JsonException on malformed input */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("input is null");
        }
        Json j = new Json(text);
        j.skipWhitespace();
        Object value = j.readValue();
        j.skipWhitespace();
        if (j.pos != j.src.length()) {
            throw new JsonException("trailing content at " + j.pos);
        }
        return value;
    }

    /** Parses with a hard size cap, per spec 7.4 (reject oversized responses). */
    public static Object parse(String text, int maxChars) {
        if (text != null && text.length() > maxChars) {
            throw new JsonException("response too large: " + text.length() + " > " + maxChars);
        }
        return parse(text);
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    // ------------------------------------------------------------- typed access

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        if (!(o instanceof Map)) {
            throw new JsonException("expected object, got " + describe(o));
        }
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object o) {
        if (!(o instanceof List)) {
            throw new JsonException("expected array, got " + describe(o));
        }
        return (List<Object>) o;
    }

    /** Reads a required integer, rejecting anything not integral (7.4: clamp/validate). */
    public static int requireInt(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof Double)) {
            throw new JsonException("expected number at '" + key + "'");
        }
        double d = (Double) v;
        if (d != Math.rint(d)) {
            throw new JsonException("expected integer at '" + key + "', got " + d);
        }
        return (int) d;
    }

    public static String requireString(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof String)) {
            throw new JsonException("expected string at '" + key + "'");
        }
        return (String) v;
    }

    private static String describe(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------- parser

    private Object readValue() {
        if (depth++ > MAX_DEPTH) {
            throw new JsonException("nesting too deep (max " + MAX_DEPTH + ")");
        }
        try {
            char c = peek();
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return readNumber();
            }
        } finally {
            depth--;
        }
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new JsonException("expected ',' or '}' at " + (pos - 1));
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new JsonException("expected ',' or ']' at " + (pos - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw new JsonException("truncated unicode escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw new JsonException("bad escape \\" + esc);
                }
                continue;
            }
            if (c < 0x20) {
                throw new JsonException("raw control character in string");
            }
            sb.append(c);
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String text = src.substring(start, pos);
        if (text.isEmpty()) {
            throw new JsonException("expected value at " + start);
        }
        // Strict JSON number grammar (RFC 8259): no leading zeros ("01"), no
        // bare trailing dot ("1.") and no dangling exponent ("1e"). A model
        // that emits any of those is not speaking JSON, and 7.4 says reject
        // rather than guess what it meant.
        if (!NUMBER.matcher(text).matches()) {
            throw new JsonException("bad number '" + text + "'");
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            throw new JsonException("bad number '" + text + "'");
        }
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new JsonException("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        char actual = next();
        if (actual != c) {
            throw new JsonException("expected '" + c + "' but got '" + actual + "' at " + (pos - 1));
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new JsonException("expected '" + literal + "' at " + pos);
        }
        pos += literal.length();
    }

    // ------------------------------------------------------------------- writer

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o);
            }
            sb.append(']');
        } else {
            throw new JsonException("cannot serialise " + v.getClass());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** Thrown for any malformed JSON. Deliberately unchecked: bad input is a bug, not a flow. */
    public static class JsonException extends RuntimeException {
        public JsonException(String message) { super(message); }
    }
}
