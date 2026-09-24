package com.lgmediabridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer.
 *
 * The webOS second-screen protocol speaks JSON, and DLNA/UPnP payloads are XML -
 * both are handled here without pulling in a general purpose library. The reader
 * is strict about structure but tolerant about unknown fields, and it never
 * throws on malformed input (returns null instead) so a chatty or unusual TV
 * cannot crash the control channel.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- parse

    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            Parser parser = new Parser(text);
            parser.skipWhitespace();
            Object value = parser.readValue();
            parser.skipWhitespace();
            return value;
        } catch (RuntimeException e) {
            LogBus.get().d("Json", "parse failed: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    /** Reads a nested value with a dotted path, e.g. {@code get(obj, "payload.volume")}. */
    public static Object get(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            Map<String, Object> map = asObject(current);
            if (map == null) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    public static String getString(Object root, String path, String fallback) {
        Object value = get(root, path);
        return value == null ? fallback : String.valueOf(value);
    }

    public static int getInt(Object root, String path, int fallback) {
        Object value = get(root, path);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    public static boolean getBoolean(Object root, String path, boolean fallback) {
        Object value = get(root, path);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalStateException("unexpected end of input");
            }
            char c = text.charAt(index);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            index++; // {
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == '}') {
                index++;
                return map;
            }
            while (index < text.length()) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != ':') {
                    throw new IllegalStateException("expected ':'");
                }
                index++;
                map.put(key, readValue());
                skipWhitespace();
                if (index >= text.length()) {
                    throw new IllegalStateException("unterminated object");
                }
                char c = text.charAt(index++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalStateException("expected ',' or '}'");
                }
            }
            throw new IllegalStateException("unterminated object");
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            index++; // [
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == ']') {
                index++;
                return list;
            }
            while (index < text.length()) {
                list.add(readValue());
                skipWhitespace();
                if (index >= text.length()) {
                    throw new IllegalStateException("unterminated array");
                }
                char c = text.charAt(index++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalStateException("expected ',' or ']'");
                }
            }
            throw new IllegalStateException("unterminated array");
        }

        private String readString() {
            if (index >= text.length() || text.charAt(index) != '"') {
                throw new IllegalStateException("expected string");
            }
            index++;
            StringBuilder sb = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        break;
                    }
                    char esc = text.charAt(index++);
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
                            if (index + 4 <= text.length()) {
                                sb.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                                index += 4;
                            }
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("unterminated string");
        }

        private Object readNumber() {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                    index++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw new IllegalStateException("expected number");
            }
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                return Long.parseLong(raw);
            }
            return Double.parseDouble(raw);
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalStateException("expected " + literal);
            }
            index += literal.length();
        }
    }

    // ---------------------------------------------------------------- write

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
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

    /** Convenience builder used by the SSAP client. */
    public static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
