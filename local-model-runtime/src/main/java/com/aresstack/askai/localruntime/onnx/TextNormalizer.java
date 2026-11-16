package com.aresstack.askai.localruntime.onnx;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalisierer aus {@code tokenizer.json} ({@code normalizer}): BertNormalizer, Lowercase, NFC/NFD/NFKC/NFKD,
 * StripAccents, Strip, Replace, Prepend, Precompiled (SentencePiece-Tabelle, angenähert durch NFKC) und Sequence.
 */
abstract class TextNormalizer {

    abstract String normalize(String text);

    /** {@code null}, wenn kein Normalisierer konfiguriert ist. */
    static TextNormalizer of(Object config) throws TokenizerFormatException {
        Map<String, Object> map = Json.object(config);
        if (map.isEmpty()) {
            return null;
        }
        String type = Json.text(map.get("type"), "");
        if ("Sequence".equals(type)) {
            final List<TextNormalizer> steps = new ArrayList<TextNormalizer>();
            for (Object step : Json.list(map.get("normalizers"))) {
                TextNormalizer normalizer = of(step);
                if (normalizer != null) {
                    steps.add(normalizer);
                }
            }
            return new TextNormalizer() {
                @Override
                String normalize(String text) {
                    String current = text;
                    for (TextNormalizer step : steps) {
                        current = step.normalize(current);
                    }
                    return current;
                }
            };
        }
        if ("BertNormalizer".equals(type)) {
            return new Bert(Json.bool(map.get("clean_text"), true), Json.bool(map.get("handle_chinese_chars"), true),
                    map.get("strip_accents") instanceof Boolean ? (Boolean) map.get("strip_accents") : null,
                    Json.bool(map.get("lowercase"), true));
        }
        if ("Lowercase".equals(type)) {
            return new TextNormalizer() {
                @Override
                String normalize(String text) {
                    return text.toLowerCase(Locale.ROOT);
                }
            };
        }
        if ("NFC".equals(type) || "NFD".equals(type) || "NFKC".equals(type) || "NFKD".equals(type)) {
            return unicode(Normalizer.Form.valueOf(type));
        }
        if ("Precompiled".equals(type)) {
            // Die SentencePiece-Tabelle ist im Wesentlichen NFKC.
            return unicode(Normalizer.Form.NFKC);
        }
        if ("StripAccents".equals(type)) {
            return new TextNormalizer() {
                @Override
                String normalize(String text) {
                    return stripAccents(text);
                }
            };
        }
        if ("Strip".equals(type)) {
            final boolean left = Json.bool(map.get("left"), true);
            final boolean right = Json.bool(map.get("right"), true);
            return new TextNormalizer() {
                @Override
                String normalize(String text) {
                    int start = 0;
                    int end = text.length();
                    while (left && start < end && Character.isWhitespace(text.charAt(start))) {
                        start++;
                    }
                    while (right && end > start && Character.isWhitespace(text.charAt(end - 1))) {
                        end--;
                    }
                    return text.substring(start, end);
                }
            };
        }
        if ("Replace".equals(type)) {
            final Pattern pattern = TokenizerPatterns.pattern(map.get("pattern"));
            final String content = Matcher.quoteReplacement(Json.text(map.get("content"), ""));
            return new TextNormalizer() {
                @Override
                String normalize(String text) {
                    return pattern.matcher(text).replaceAll(content);
                }
            };
        }
        if ("Prepend".equals(type)) {
            final String prefix = Json.text(map.get("prepend"), "");
            return new TextNormalizer() {
                @Override
                String normalize(String text) {
                    return text.isEmpty() ? text : prefix + text;
                }
            };
        }
        if ("Nmt".equals(type) || "ByteLevel".equals(type)) {
            return null;
        }
        throw new TokenizerFormatException("Normalisierer " + type + " wird nicht unterstützt");
    }

    private static TextNormalizer unicode(final Normalizer.Form form) {
        return new TextNormalizer() {
            @Override
            String normalize(String text) {
                return Normalizer.normalize(text, form);
            }
        };
    }

    static String stripAccents(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Wie BertNormalizer in tokenizers: Steuerzeichen weg, Leerraum zu Leerzeichen, CJK abgesetzt, klein. */
    private static final class Bert extends TextNormalizer {
        private final boolean cleanText;
        private final boolean chineseChars;
        private final Boolean stripAccents;
        private final boolean lowercase;

        Bert(boolean cleanText, boolean chineseChars, Boolean stripAccents, boolean lowercase) {
            this.cleanText = cleanText;
            this.chineseChars = chineseChars;
            this.stripAccents = stripAccents;
            this.lowercase = lowercase;
        }

        @Override
        String normalize(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); ) {
                int c = text.codePointAt(i);
                i += Character.charCount(c);
                if (cleanText) {
                    if (c == 0 || c == 0xFFFD || isControl(c)) {
                        continue;
                    }
                    if (isWhitespace(c)) {
                        out.append(' ');
                        continue;
                    }
                }
                if (chineseChars && isChinese(c)) {
                    out.append(' ').appendCodePoint(c).append(' ');
                } else {
                    out.appendCodePoint(c);
                }
            }
            String result = out.toString();
            boolean strip = stripAccents == null ? lowercase : stripAccents;
            if (strip) {
                result = stripAccents(result);
            }
            return lowercase ? result.toLowerCase(Locale.ROOT) : result;
        }

        private static boolean isWhitespace(int c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || Character.getType(c) == Character.SPACE_SEPARATOR;
        }

        private static boolean isControl(int c) {
            if (c == '\t' || c == '\n' || c == '\r') {
                return false;
            }
            int type = Character.getType(c);
            return type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.SURROGATE || type == Character.UNASSIGNED;
        }

        private static boolean isChinese(int c) {
            return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x20000 && c <= 0x2A6DF)
                    || (c >= 0x2A700 && c <= 0x2B73F) || (c >= 0x2B740 && c <= 0x2B81F)
                    || (c >= 0x2B820 && c <= 0x2CEAF) || (c >= 0xF900 && c <= 0xFAFF)
                    || (c >= 0x2F800 && c <= 0x2FA1F);
        }
    }
}
