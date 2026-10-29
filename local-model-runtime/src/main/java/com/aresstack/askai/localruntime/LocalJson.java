package com.aresstack.askai.localruntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON reader/writer for the sidecar's small, fixed payloads. Parses into
 * Map/List/String/Double/Long/Boolean/null; writes the same shapes. Strict: malformed input throws
 * with a readable offset.
 */
final class LocalJson {

    private LocalJson() {
    }

    // ------------------------------------------------------------------ writing

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Boolean b -> sb.append(b);
            case Double d -> sb.append(d % 1 == 0 && Math.abs(d) < 1e15 ? String.valueOf(d.longValue())
                    : String.valueOf(d));
            case Number n -> sb.append(n);
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    quote(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    append(sb, entry.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object entry : list) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    append(sb, entry);
                }
                sb.append(']');
            }
            default -> quote(sb, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
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

    // ------------------------------------------------------------------ parsing

    static Object parse(String text) {
        Parser parser = new Parser(text == null ? "" : text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos < parser.text.length()) {
            throw parser.fail("trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> map, String key) {
        List<String> result = new ArrayList<>();
        if (map.get(key) instanceof List<?> list) {
            for (Object entry : list) {
                result.add(String.valueOf(entry));
            }
        }
        return result;
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) {
            this.text = text;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObjectValue();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Object literal(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw fail("unexpected token");
            }
            pos += literal.length();
            return value;
        }

        private Map<String, Object> parseObjectValue() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw fail("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw fail("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw fail("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw fail("truncated \\u escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw fail("unknown escape '\\" + escape + "'");
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < text.length() && "-+.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String token = text.substring(start, pos);
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException ex) {
                throw fail("invalid number '" + token + "'");
            }
        }

        private void expect(char expected) {
            if (next() != expected) {
                pos--;
                throw fail("expected '" + expected + "'");
            }
        }

        private char next() {
            if (pos >= text.length()) {
                throw fail("unexpected end of input");
            }
            return text.charAt(pos++);
        }

        private char peek() {
            if (pos >= text.length()) {
                throw fail("unexpected end of input");
            }
            return text.charAt(pos);
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException fail(String reason) {
            return new IllegalArgumentException("malformed JSON at offset " + pos + ": " + reason);
        }
    }
}
