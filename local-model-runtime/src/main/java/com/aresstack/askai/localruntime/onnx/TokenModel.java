package com.aresstack.askai.localruntime.onnx;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Das Modell aus {@code tokenizer.json} ({@code model}): WordPiece, BPE oder Unigram. Thread-sicher. */
abstract class TokenModel {

    /** Ids eines vorzerlegten Stücks. */
    abstract void tokenize(String piece, List<Integer> ids);

    /** Token-Text zur Id; {@code null}, wenn unbekannt. */
    abstract String token(int id);

    abstract Integer id(String token);

    abstract int size();

    static TokenModel of(Object config) throws TokenizerFormatException {
        Map<String, Object> map = Json.object(config);
        String type = Json.text(map.get("type"), "");
        if (type.isEmpty()) {
            // Ältere Dateien ohne Typ: an den Feldern erkennen.
            type = map.containsKey("merges") ? "BPE" : map.get("vocab") instanceof List ? "Unigram" : "WordPiece";
        }
        if ("WordPiece".equals(type)) {
            return new WordPiece(vocabulary(map.get("vocab")), Json.text(map.get("unk_token"), "[UNK]"),
                    Json.text(map.get("continuing_subword_prefix"), "##"),
                    Json.integer(map.get("max_input_chars_per_word"), 100));
        }
        if ("BPE".equals(type)) {
            return new Bpe(vocabulary(map.get("vocab")), Json.list(map.get("merges")),
                    Json.text(map.get("unk_token"), null), Json.text(map.get("continuing_subword_prefix"), null),
                    Json.text(map.get("end_of_word_suffix"), null), Json.bool(map.get("fuse_unk"), false),
                    Json.bool(map.get("byte_fallback"), false), Json.bool(map.get("ignore_merges"), false));
        }
        if ("Unigram".equals(type)) {
            return new Unigram(Json.list(map.get("vocab")), map.get("unk_id") instanceof Number
                    ? ((Number) map.get("unk_id")).intValue() : -1, Json.bool(map.get("byte_fallback"), false));
        }
        throw new TokenizerFormatException("Tokenizer-Modell " + type + " wird nicht unterstützt");
    }

    private static Map<String, Integer> vocabulary(Object config) throws TokenizerFormatException {
        Map<String, Object> raw = Json.object(config);
        if (raw.isEmpty()) {
            throw new TokenizerFormatException("Vokabular fehlt");
        }
        Map<String, Integer> vocab = new HashMap<String, Integer>(raw.size() * 2);
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            vocab.put(entry.getKey(), Json.integer(entry.getValue(), -1));
        }
        return vocab;
    }

    private static String[] inverse(Map<String, Integer> vocab) {
        int max = -1;
        for (int id : vocab.values()) {
            max = Math.max(max, id);
        }
        String[] tokens = new String[max + 1];
        for (Map.Entry<String, Integer> entry : vocab.entrySet()) {
            if (entry.getValue() >= 0) {
                tokens[entry.getValue()] = entry.getKey();
            }
        }
        return tokens;
    }

    /** Bytes als {@code <0xAB>}-Token, wie byte_fallback sie erwartet. */
    static String byteToken(int b) {
        return String.format("<0x%02X>", b & 0xff);
    }

    private abstract static class VocabularyModel extends TokenModel {
        final Map<String, Integer> vocab;
        final String[] tokens;

        VocabularyModel(Map<String, Integer> vocab) {
            this.vocab = vocab;
            this.tokens = inverse(vocab);
        }

        @Override
        String token(int id) {
            return id >= 0 && id < tokens.length ? tokens[id] : null;
        }

        @Override
        Integer id(String token) {
            return vocab.get(token);
        }

        @Override
        int size() {
            return tokens.length;
        }
    }

    /** Längster Treffer zuerst, Fortsetzungen mit Präfix ({@code ##}); passt nichts, das Ganze als unbekannt. */
    static final class WordPiece extends VocabularyModel {
        private final String unknown;
        private final String prefix;
        private final int maxChars;

        WordPiece(Map<String, Integer> vocab, String unknown, String prefix, int maxChars) {
            super(vocab);
            this.unknown = unknown;
            this.prefix = prefix;
            this.maxChars = maxChars;
        }

        @Override
        void tokenize(String piece, List<Integer> ids) {
            if (piece.codePointCount(0, piece.length()) > maxChars) {
                addUnknown(ids);
                return;
            }
            List<Integer> found = new ArrayList<Integer>();
            int start = 0;
            while (start < piece.length()) {
                int end = piece.length();
                Integer match = null;
                while (start < end) {
                    String candidate = piece.substring(start, end);
                    if (start > 0) {
                        candidate = prefix + candidate;
                    }
                    match = vocab.get(candidate);
                    if (match != null) {
                        break;
                    }
                    end = piece.offsetByCodePoints(end, -1);
                }
                if (match == null) {
                    addUnknown(ids);
                    return;
                }
                found.add(match);
                start = end;
            }
            ids.addAll(found);
        }

        private void addUnknown(List<Integer> ids) {
            Integer unk = vocab.get(unknown);
            if (unk != null) {
                ids.add(unk);
            }
        }
    }

    /** Byte-Pair-Encoding: Zeichen, dann wiederholt das Paar mit dem kleinsten Rang verschmelzen. */
    static final class Bpe extends VocabularyModel {
        private static final int CACHE_LIMIT = 20000;
        private final Map<String, Integer> ranks = new HashMap<String, Integer>();
        private final String unknown;
        private final String prefix;
        private final String suffix;
        private final boolean fuseUnknown;
        private final boolean byteFallback;
        private final boolean ignoreMerges;
        private final Map<String, int[]> cache = new LinkedHashMap<String, int[]>();

        Bpe(Map<String, Integer> vocab, List<Object> merges, String unknown, String prefix, String suffix,
            boolean fuseUnknown, boolean byteFallback, boolean ignoreMerges) throws TokenizerFormatException {
            super(vocab);
            int rank = 0;
            for (Object merge : merges) {
                String left;
                String right;
                if (merge instanceof String) {
                    String text = (String) merge;
                    int space = text.indexOf(' ', 1);
                    if (space < 0) {
                        throw new TokenizerFormatException("Merge ohne Leerzeichen: " + text);
                    }
                    left = text.substring(0, space);
                    right = text.substring(space + 1);
                } else {
                    List<Object> pair = Json.list(merge);
                    if (pair.size() != 2) {
                        throw new TokenizerFormatException("Merge ist kein Paar: " + merge);
                    }
                    left = String.valueOf(pair.get(0));
                    right = String.valueOf(pair.get(1));
                }
                ranks.put(key(left, right), rank++);
            }
            this.unknown = unknown;
            this.prefix = prefix == null ? "" : prefix;
            this.suffix = suffix == null ? "" : suffix;
            this.fuseUnknown = fuseUnknown;
            this.byteFallback = byteFallback;
            this.ignoreMerges = ignoreMerges;
        }

        private static String key(String left, String right) {
            return left + '\u0000' + right;
        }

        @Override
        void tokenize(String piece, List<Integer> ids) {
            if (piece.isEmpty()) {
                return;
            }
            int[] cached;
            synchronized (cache) {
                cached = cache.get(piece);
            }
            if (cached == null) {
                cached = merge(piece);
                synchronized (cache) {
                    if (cache.size() >= CACHE_LIMIT) {
                        cache.clear();
                    }
                    cache.put(piece, cached);
                }
            }
            for (int id : cached) {
                ids.add(id);
            }
        }

        private int[] merge(String piece) {
            if (ignoreMerges && vocab.containsKey(piece)) {
                return new int[] {vocab.get(piece)};
            }
            // Anfangssymbole: Zeichen mit Präfix/Suffix; unbekannte als Bytes oder unbekannt.
            List<String> symbols = new ArrayList<String>();
            List<Boolean> known = new ArrayList<Boolean>();
            int count = piece.codePointCount(0, piece.length());
            int index = 0;
            for (int i = 0; i < piece.length(); index++) {
                int c = piece.codePointAt(i);
                String text = new String(Character.toChars(c));
                i += Character.charCount(c);
                if (index > 0) {
                    text = prefix + text;
                }
                if (index == count - 1) {
                    text = text + suffix;
                }
                symbols.add(text);
                known.add(vocab.containsKey(text));
            }
            while (symbols.size() > 1) {
                int best = -1;
                int bestRank = Integer.MAX_VALUE;
                for (int i = 0; i + 1 < symbols.size(); i++) {
                    if (!known.get(i) || !known.get(i + 1)) {
                        continue;
                    }
                    Integer rank = ranks.get(key(symbols.get(i), symbols.get(i + 1)));
                    if (rank != null && rank < bestRank) {
                        bestRank = rank;
                        best = i;
                    }
                }
                if (best < 0) {
                    break;
                }
                String right = symbols.get(best + 1);
                if (!prefix.isEmpty() && right.startsWith(prefix)) {
                    right = right.substring(prefix.length());
                }
                String merged = symbols.get(best) + right;
                symbols.set(best, merged);
                known.set(best, vocab.containsKey(merged));
                symbols.remove(best + 1);
                known.remove(best + 1);
            }
            List<Integer> ids = new ArrayList<Integer>();
            boolean previousUnknown = false;
            for (int i = 0; i < symbols.size(); i++) {
                Integer id = vocab.get(symbols.get(i));
                if (id != null) {
                    ids.add(id);
                    previousUnknown = false;
                    continue;
                }
                if (byteFallback && fallback(symbols.get(i), ids)) {
                    previousUnknown = false;
                    continue;
                }
                Integer unk = unknown == null ? null : vocab.get(unknown);
                if (unk != null && !(fuseUnknown && previousUnknown)) {
                    ids.add(unk);
                }
                previousUnknown = true;
            }
            int[] result = new int[ids.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = ids.get(i);
            }
            return result;
        }

        private boolean fallback(String symbol, List<Integer> ids) {
            String raw = symbol;
            if (!prefix.isEmpty() && raw.startsWith(prefix)) {
                raw = raw.substring(prefix.length());
            }
            if (!suffix.isEmpty() && raw.endsWith(suffix)) {
                raw = raw.substring(0, raw.length() - suffix.length());
            }
            List<Integer> bytes = new ArrayList<Integer>();
            for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
                Integer id = vocab.get(byteToken(b));
                if (id == null) {
                    return false;
                }
                bytes.add(id);
            }
            ids.addAll(bytes);
            return true;
        }
    }

    /** Unigram (SentencePiece): Viterbi über die Stück-Wahrscheinlichkeiten, Unbekanntes mit Strafwert. */
    static final class Unigram extends TokenModel {
        private final Map<String, Integer> ids = new HashMap<String, Integer>();
        private final String[] pieces;
        private final double[] scores;
        private final int unknownId;
        private final boolean byteFallback;
        private final int maxLength;
        private final double unknownScore;

        Unigram(List<Object> vocab, int unknownId, boolean byteFallback) throws TokenizerFormatException {
            if (vocab.isEmpty()) {
                throw new TokenizerFormatException("Vokabular fehlt");
            }
            pieces = new String[vocab.size()];
            scores = new double[vocab.size()];
            double min = Double.MAX_VALUE;
            int longest = 1;
            for (int i = 0; i < vocab.size(); i++) {
                List<Object> entry = Json.list(vocab.get(i));
                if (entry.size() < 2) {
                    throw new TokenizerFormatException("Unigram-Eintrag ohne Wert: " + vocab.get(i));
                }
                pieces[i] = String.valueOf(entry.get(0));
                scores[i] = Json.number(entry.get(1), 0);
                min = Math.min(min, scores[i]);
                longest = Math.max(longest, pieces[i].length());
                if (!ids.containsKey(pieces[i])) {
                    ids.put(pieces[i], i);
                }
            }
            this.unknownId = unknownId;
            this.byteFallback = byteFallback;
            this.maxLength = longest;
            this.unknownScore = min - 10.0;
        }

        @Override
        void tokenize(String piece, List<Integer> out) {
            int n = piece.length();
            if (n == 0) {
                return;
            }
            double[] best = new double[n + 1];
            int[] from = new int[n + 1];
            int[] token = new int[n + 1];
            java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
            best[0] = 0;
            for (int start = 0; start < n; start++) {
                if (best[start] == Double.NEGATIVE_INFINITY) {
                    continue;
                }
                if (Character.isLowSurrogate(piece.charAt(start))) {
                    continue;
                }
                boolean single = false;
                int charEnd = start + Character.charCount(piece.codePointAt(start));
                for (int end = start + 1; end <= Math.min(n, start + maxLength); end++) {
                    if (end < n && Character.isLowSurrogate(piece.charAt(end))) {
                        continue;
                    }
                    Integer id = ids.get(piece.substring(start, end));
                    if (id == null) {
                        continue;
                    }
                    if (end == charEnd) {
                        single = true;
                    }
                    double score = best[start] + scores[id];
                    if (score > best[end]) {
                        best[end] = score;
                        from[end] = start;
                        token[end] = id;
                    }
                }
                if (!single) {
                    double score = best[start] + unknownScore;
                    if (score > best[charEnd]) {
                        best[charEnd] = score;
                        from[charEnd] = start;
                        token[charEnd] = -1;
                    }
                }
            }
            List<int[]> path = new ArrayList<int[]>();
            for (int end = n; end > 0; end = from[end]) {
                path.add(0, new int[] {from[end], end, token[end]});
            }
            boolean previousUnknown = false;
            for (int[] step : path) {
                if (step[2] >= 0) {
                    out.add(step[2]);
                    previousUnknown = false;
                    continue;
                }
                if (byteFallback) {
                    List<Integer> bytes = new ArrayList<Integer>();
                    for (byte b : piece.substring(step[0], step[1]).getBytes(StandardCharsets.UTF_8)) {
                        Integer id = ids.get(byteToken(b));
                        if (id == null) {
                            bytes = null;
                            break;
                        }
                        bytes.add(id);
                    }
                    if (bytes != null) {
                        out.addAll(bytes);
                        previousUnknown = false;
                        continue;
                    }
                }
                if (!previousUnknown && unknownId >= 0) {
                    out.add(unknownId);
                }
                previousUnknown = true;
            }
        }

        @Override
        String token(int id) {
            return id >= 0 && id < pieces.length ? pieces[id] : null;
        }

        @Override
        Integer id(String token) {
            return ids.get(token);
        }

        @Override
        int size() {
            return pieces.length;
        }
    }
}
