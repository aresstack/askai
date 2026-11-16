package com.aresstack.askai.localruntime.onnx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vorzerlegung aus {@code tokenizer.json} ({@code pre_tokenizer}): BertPreTokenizer, Whitespace, WhitespaceSplit,
 * Metaspace, ByteLevel, Split, Punctuation, Digits und Sequence. Jede Stufe zerlegt jedes Stück weiter.
 */
abstract class PreTokenizer {

    /** GPT-2-Zerlegung von ByteLevel ({@code use_regex}). */
    static final String GPT2_REGEX =
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+";

    /**
     * @param first ob das Stück am Anfang des Textes steht (Metaspace {@code prepend_scheme "first"})
     */
    abstract List<String> split(String piece, boolean first);

    List<String> splitAll(List<String> pieces, boolean first) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < pieces.size(); i++) {
            out.addAll(split(pieces.get(i), first && i == 0));
        }
        return out;
    }

    static PreTokenizer of(Object config) throws TokenizerFormatException {
        Map<String, Object> map = Json.object(config);
        if (map.isEmpty()) {
            return null;
        }
        String type = Json.text(map.get("type"), "");
        if ("Sequence".equals(type)) {
            final List<PreTokenizer> steps = new ArrayList<PreTokenizer>();
            for (Object step : Json.list(map.get("pretokenizers"))) {
                PreTokenizer pre = of(step);
                if (pre != null) {
                    steps.add(pre);
                }
            }
            return new PreTokenizer() {
                @Override
                List<String> split(String piece, boolean first) {
                    List<String> current = Collections.singletonList(piece);
                    for (PreTokenizer step : steps) {
                        current = step.splitAll(current, first);
                    }
                    return current;
                }
            };
        }
        if ("BertPreTokenizer".equals(type)) {
            return new PreTokenizer() {
                @Override
                List<String> split(String piece, boolean first) {
                    List<String> out = new ArrayList<String>();
                    StringBuilder word = new StringBuilder();
                    for (int i = 0; i < piece.length(); ) {
                        int c = piece.codePointAt(i);
                        i += Character.charCount(c);
                        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                            flush(word, out);
                        } else if (isPunctuation(c)) {
                            flush(word, out);
                            out.add(new String(Character.toChars(c)));
                        } else {
                            word.appendCodePoint(c);
                        }
                    }
                    flush(word, out);
                    return out;
                }
            };
        }
        if ("Whitespace".equals(type)) {
            return new RegexSplit(TokenizerPatterns.regex("\\w+|[^\\w\\s]+"), Behavior.ISOLATED, true);
        }
        if ("WhitespaceSplit".equals(type)) {
            return new RegexSplit(TokenizerPatterns.regex("\\s+"), Behavior.REMOVED, false);
        }
        if ("Metaspace".equals(type)) {
            String replacement = Json.text(map.get("replacement"), "▁");
            String scheme = Json.text(map.get("prepend_scheme"), null);
            if (scheme == null) {
                scheme = Json.bool(map.get("add_prefix_space"), true) ? "always" : "never";
            }
            return new Metaspace(replacement, scheme, Json.bool(map.get("split"), true));
        }
        if ("ByteLevel".equals(type)) {
            final boolean prefixSpace = Json.bool(map.get("add_prefix_space"), false);
            final RegexSplit regex = Json.bool(map.get("use_regex"), true)
                    ? new RegexSplit(TokenizerPatterns.regex(GPT2_REGEX), Behavior.ISOLATED, false) : null;
            return new PreTokenizer() {
                @Override
                List<String> split(String piece, boolean first) {
                    String text = prefixSpace && !piece.isEmpty() && !Character.isWhitespace(piece.charAt(0))
                            ? " " + piece : piece;
                    List<String> parts = regex == null ? Collections.singletonList(text) : regex.split(text, first);
                    List<String> out = new ArrayList<String>(parts.size());
                    for (String part : parts) {
                        out.add(ByteLevelCodec.encode(part));
                    }
                    return out;
                }
            };
        }
        if ("Split".equals(type)) {
            return new RegexSplit(TokenizerPatterns.pattern(map.get("pattern")),
                    Behavior.of(Json.text(map.get("behavior"), "Isolated")), Json.bool(map.get("invert"), false));
        }
        if ("Punctuation".equals(type)) {
            return new PreTokenizer() {
                private final Behavior behavior = Behavior.of(Json.text(map.get("behavior"), "Isolated"));

                @Override
                List<String> split(String piece, boolean first) {
                    List<int[]> spans = new ArrayList<int[]>();
                    for (int i = 0; i < piece.length(); ) {
                        int c = piece.codePointAt(i);
                        int next = i + Character.charCount(c);
                        if (isPunctuation(c)) {
                            spans.add(new int[] {i, next});
                        }
                        i = next;
                    }
                    return behavior.apply(piece, spans);
                }
            };
        }
        if ("Digits".equals(type)) {
            return new RegexSplit(TokenizerPatterns.regex(Json.bool(map.get("individual_digits"), false)
                    ? "\\p{N}" : "\\p{N}+"), Behavior.ISOLATED, false);
        }
        if ("UnicodeScripts".equals(type)) {
            return null;
        }
        throw new TokenizerFormatException("Vorzerlegung " + type + " wird nicht unterstützt");
    }

    private static void flush(StringBuilder word, List<String> out) {
        if (word.length() > 0) {
            out.add(word.toString());
            word.setLength(0);
        }
    }

    static boolean isPunctuation(int c) {
        if ((c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)) {
            return true;
        }
        int type = Character.getType(c);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    /** Was mit den Treffern eines Trenners geschieht (SplitDelimiterBehavior). */
    enum Behavior {
        REMOVED, ISOLATED, MERGED_WITH_PREVIOUS, MERGED_WITH_NEXT, CONTIGUOUS;

        static Behavior of(String name) throws TokenizerFormatException {
            if ("Removed".equals(name)) {
                return REMOVED;
            }
            if ("Isolated".equals(name)) {
                return ISOLATED;
            }
            if ("MergedWithPrevious".equals(name)) {
                return MERGED_WITH_PREVIOUS;
            }
            if ("MergedWithNext".equals(name)) {
                return MERGED_WITH_NEXT;
            }
            if ("Contiguous".equals(name)) {
                return CONTIGUOUS;
            }
            throw new TokenizerFormatException("Trenner-Verhalten " + name + " wird nicht unterstützt");
        }

        /** Zerlegt {@code text} an den Trennern {@code delimiters} (aufsteigend, ohne Überlappung). */
        List<String> apply(String text, List<int[]> delimiters) {
            List<String> out = new ArrayList<String>();
            int position = 0;
            StringBuilder pending = new StringBuilder();
            int previousEnd = -1;
            for (int[] span : delimiters) {
                String before = text.substring(position, span[0]);
                String match = text.substring(span[0], span[1]);
                switch (this) {
                    case REMOVED:
                        add(out, before);
                        break;
                    case ISOLATED:
                        add(out, before);
                        add(out, match);
                        break;
                    case MERGED_WITH_PREVIOUS:
                        add(out, before + match);
                        break;
                    case MERGED_WITH_NEXT:
                        add(out, pending.append(before).toString());
                        pending.setLength(0);
                        pending.append(match);
                        break;
                    case CONTIGUOUS:
                        if (before.isEmpty() && previousEnd == span[0] && !out.isEmpty()) {
                            out.set(out.size() - 1, out.get(out.size() - 1) + match);
                        } else {
                            add(out, before);
                            add(out, match);
                        }
                        break;
                    default:
                        throw new IllegalStateException();
                }
                position = span[1];
                previousEnd = span[1];
            }
            String rest = text.substring(position);
            if (this == MERGED_WITH_NEXT) {
                add(out, pending.append(rest).toString());
            } else {
                add(out, rest);
            }
            return out;
        }

        private static void add(List<String> out, String piece) {
            if (!piece.isEmpty()) {
                out.add(piece);
            }
        }
    }

    /** Zerlegung an einem Muster; {@code invert}: die Treffer sind die Stücke, der Rest die Trenner. */
    static final class RegexSplit extends PreTokenizer {
        private final Pattern pattern;
        private final Behavior behavior;
        private final boolean invert;

        RegexSplit(Pattern pattern, Behavior behavior, boolean invert) {
            this.pattern = pattern;
            this.behavior = behavior;
            this.invert = invert;
        }

        @Override
        List<String> split(String piece, boolean first) {
            List<int[]> matches = new ArrayList<int[]>();
            Matcher matcher = pattern.matcher(piece);
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) {
                    matches.add(new int[] {matcher.start(), matcher.end()});
                }
            }
            if (!invert) {
                return behavior.apply(piece, matches);
            }
            List<int[]> gaps = new ArrayList<int[]>();
            int position = 0;
            for (int[] match : matches) {
                if (match[0] > position) {
                    gaps.add(new int[] {position, match[0]});
                }
                position = match[1];
            }
            if (position < piece.length()) {
                gaps.add(new int[] {position, piece.length()});
            }
            return behavior.apply(piece, gaps);
        }
    }

    /** SentencePiece-Stil: Leerzeichen werden zu ▁, optional mit ▁ vorn, Stücke beginnen an jedem ▁. */
    static final class Metaspace extends PreTokenizer {
        private final String replacement;
        private final String scheme;
        private final boolean splitPieces;

        Metaspace(String replacement, String scheme, boolean splitPieces) {
            this.replacement = replacement;
            this.scheme = scheme;
            this.splitPieces = splitPieces;
        }

        @Override
        List<String> split(String piece, boolean first) {
            String text = piece.replace(" ", replacement);
            boolean prepend = "always".equals(scheme) || ("first".equals(scheme) && first);
            if (prepend && !text.startsWith(replacement)) {
                text = replacement + text;
            }
            if (!splitPieces) {
                return Collections.singletonList(text);
            }
            List<int[]> spans = new ArrayList<int[]>();
            int index = text.indexOf(replacement);
            while (index >= 0) {
                spans.add(new int[] {index, index + replacement.length()});
                index = text.indexOf(replacement, index + replacement.length());
            }
            return Behavior.MERGED_WITH_NEXT.apply(text, spans);
        }
    }
}
