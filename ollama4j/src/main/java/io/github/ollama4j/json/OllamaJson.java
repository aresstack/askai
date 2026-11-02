package io.github.ollama4j.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OllamaJson {

    private OllamaJson() {
    }

    /**
     * Parses a JSON document into plain Java values: {@link LinkedHashMap} for objects (with
     * {@link String} keys), {@link ArrayList} for arrays, {@link String}, {@link Long}/{@link Double}
     * for numbers, {@link Boolean}, or {@code null}.
     *
     * <p>This is a self-contained parser on purpose: the previous implementation relied on a
     * JavaScript engine ({@code getEngineByName("javascript")} plus the Nashorn-only
     * {@code Java.asJSONCompatible}), which fails on JVMs where Nashorn was removed (Java 15+) or where
     * only GraalVM JS is present, producing "No JavaScript engine available for JSON parsing".</p>
     *
     * @throws IllegalArgumentException when the input is not valid JSON.
     */
    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("Invalid JSON: input was null.");
        }
        JsonParser parser = new JsonParser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Invalid JSON: unexpected trailing content at index " + parser.index());
        }
        return value;
    }

    /** Minimal recursive-descent JSON parser with no external dependencies. */
    private static final class JsonParser {

        private final String source;
        private int index;

        JsonParser(String source) {
            this.source = source;
        }

        int index() {
            return index;
        }

        boolean atEnd() {
            return index >= source.length();
        }

        void skipWhitespace() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("Invalid JSON: unexpected end of input.");
            }
            char c = source.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                case 'f':
                    return parseBoolean();
                case 'n':
                    return parseNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw new IllegalArgumentException("Invalid JSON: unexpected character '" + c + "' at index " + index);
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            index++; // consume '{'
            skipWhitespace();
            if (!atEnd() && source.charAt(index) == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || source.charAt(index) != '"') {
                    throw new IllegalArgumentException("Invalid JSON: expected string key at index " + index);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = next("Invalid JSON: unterminated object.");
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Invalid JSON: expected ',' or '}' at index " + (index - 1));
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<Object>();
            index++; // consume '['
            skipWhitespace();
            if (!atEnd() && source.charAt(index) == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next("Invalid JSON: unterminated array.");
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Invalid JSON: expected ',' or ']' at index " + (index - 1));
                }
            }
        }

        private String parseString() {
            index++; // consume opening quote
            StringBuilder builder = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("Invalid JSON: unterminated string.");
                }
                char c = source.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    builder.append(parseEscape());
                } else {
                    builder.append(c);
                }
            }
        }

        private char parseEscape() {
            char c = next("Invalid JSON: unterminated escape sequence.");
            switch (c) {
                case '"':
                    return '"';
                case '\\':
                    return '\\';
                case '/':
                    return '/';
                case 'b':
                    return '\b';
                case 'f':
                    return '\f';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'u':
                    if (index + 4 > source.length()) {
                        throw new IllegalArgumentException("Invalid JSON: truncated \\u escape.");
                    }
                    String hex = source.substring(index, index + 4);
                    index += 4;
                    try {
                        return (char) Integer.parseInt(hex, 16);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Invalid JSON: bad \\u escape '" + hex + "'.");
                    }
                default:
                    throw new IllegalArgumentException("Invalid JSON: unsupported escape '\\" + c + "'.");
            }
        }

        private Object parseNumber() {
            int start = index;
            if (!atEnd() && source.charAt(index) == '-') {
                index++;
            }
            boolean floating = false;
            while (!atEnd()) {
                char c = source.charAt(index);
                if (c >= '0' && c <= '9') {
                    index++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    index++;
                } else {
                    break;
                }
            }
            String token = source.substring(start, index);
            try {
                if (!floating) {
                    return Long.valueOf(Long.parseLong(token));
                }
            } catch (NumberFormatException ex) {
                // Fall through to double (e.g. an integer larger than Long).
            }
            try {
                return Double.valueOf(Double.parseDouble(token));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid JSON: bad number '" + token + "'.");
            }
        }

        private Boolean parseBoolean() {
            if (source.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (source.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid JSON: expected boolean at index " + index);
        }

        private Object parseNull() {
            if (source.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid JSON: expected null at index " + index);
        }

        private void expect(char expected) {
            char c = next("Invalid JSON: expected '" + expected + "' but reached end of input.");
            if (c != expected) {
                throw new IllegalArgumentException("Invalid JSON: expected '" + expected + "' at index " + (index - 1));
            }
        }

        private char next(String errorMessage) {
            if (atEnd()) {
                throw new IllegalArgumentException(errorMessage);
            }
            return source.charAt(index++);
        }
    }

    public static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return quote((String) value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            StringBuilder builder = new StringBuilder();
            builder.append('{');
            Iterator iterator = ((Map) value).entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry entry = (Map.Entry) iterator.next();
                builder.append(quote(String.valueOf(entry.getKey()))).append(':').append(toJson(entry.getValue()));
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append('}');
            return builder.toString();
        }
        if (value instanceof List) {
            StringBuilder builder = new StringBuilder();
            builder.append('[');
            Iterator iterator = ((List) value).iterator();
            while (iterator.hasNext()) {
                builder.append(toJson(iterator.next()));
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append(']');
            return builder.toString();
        }
        return quote(String.valueOf(value));
    }

    public static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                builder.append('\\').append(c);
            } else if (c == '\n') {
                builder.append("\\n");
            } else if (c == '\r') {
                builder.append("\\r");
            } else if (c == '\t') {
                builder.append("\\t");
            } else if (c < 32) {
                String hex = Integer.toHexString(c);
                builder.append("\\u");
                for (int j = hex.length(); j < 4; j++) {
                    builder.append('0');
                }
                builder.append(hex);
            } else {
                builder.append(c);
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
