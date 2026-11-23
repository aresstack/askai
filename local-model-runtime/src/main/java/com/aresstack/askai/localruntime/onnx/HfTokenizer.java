package com.aresstack.askai.localruntime.onnx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ported from aresstack/enterprise-ai-client text-local (commit f3ae743), extended with pair encoding.
 *
 * Ein Hugging-Face-Tokenizer aus {@code tokenizer.json} in reinem Java 8, ohne native Bibliothek: Zusatz-Token
 * ({@code added_tokens}) werden zuerst abgetrennt, der übrige Text läuft durch Normalisierer, Vorzerlegung und Modell
 * (WordPiece, BPE, Unigram), danach setzt die Nachbearbeitung die Sonder-Token. Thread-sicher.
 */
final class HfTokenizer {

    private final TextNormalizer normalizer;
    private final PreTokenizer preTokenizer;
    private final TokenModel model;
    private final Map<String, Object> postProcessor;
    private final TokenDecoder decoder;
    /** Zusatz-Token je erstem Zeichen, längste zuerst. */
    private final Map<Character, List<AddedToken>> addedByFirstChar = new HashMap<Character, List<AddedToken>>();
    private final Map<Integer, AddedToken> addedById = new HashMap<Integer, AddedToken>();
    private final Map<String, Integer> addedByContent = new HashMap<String, Integer>();

    private HfTokenizer(Map<String, Object> json) throws TokenizerFormatException {
        normalizer = TextNormalizer.of(json.get("normalizer"));
        preTokenizer = PreTokenizer.of(json.get("pre_tokenizer"));
        model = TokenModel.of(json.get("model"));
        postProcessor = Json.object(json.get("post_processor"));
        decoder = TokenDecoder.of(json.get("decoder"));
        List<AddedToken> tokens = new ArrayList<AddedToken>();
        for (Object entry : Json.list(json.get("added_tokens"))) {
            Map<String, Object> token = Json.object(entry);
            String content = Json.text(token.get("content"), "");
            if (content.isEmpty() || !(token.get("id") instanceof Number)) {
                continue;
            }
            AddedToken addedToken = new AddedToken(((Number) token.get("id")).intValue(), content,
                    Json.bool(token.get("special"), false), Json.bool(token.get("lstrip"), false),
                    Json.bool(token.get("rstrip"), false));
            tokens.add(addedToken);
            addedById.put(addedToken.id, addedToken);
            addedByContent.put(content, addedToken.id);
        }
        Collections.sort(tokens, new Comparator<AddedToken>() {
            @Override
            public int compare(AddedToken a, AddedToken b) {
                return b.content.length() - a.content.length();
            }
        });
        for (AddedToken token : tokens) {
            List<AddedToken> bucket = addedByFirstChar.get(token.content.charAt(0));
            if (bucket == null) {
                bucket = new ArrayList<AddedToken>();
                addedByFirstChar.put(token.content.charAt(0), bucket);
            }
            bucket.add(token);
        }
    }

    static HfTokenizer load(Path file) throws IOException, TokenizerFormatException {
        return new HfTokenizer(Json.read(file));
    }

    static HfTokenizer parse(String json) throws TokenizerFormatException {
        return new HfTokenizer(Json.object(Json.parse(json)));
    }

    /** Token-Ids des Textes; mit {@code special} auch die Sonder-Token der Nachbearbeitung (CLS/SEP, BOS …). */
    int[] encode(String text, boolean special) {
        List<Integer> ids = new ArrayList<Integer>();
        boolean first = true;
        int position = 0;
        int segmentStart = 0;
        while (position < text.length()) {
            AddedToken match = addedAt(text, position);
            if (match == null) {
                position++;
                continue;
            }
            String before = text.substring(segmentStart, position);
            int end = position + match.content.length();
            if (match.lstrip) {
                before = stripTrailingWhitespace(before);
            }
            encodeSegment(before, first, ids);
            first = false;
            ids.add(match.id);
            if (match.rstrip) {
                while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
            }
            position = end;
            segmentStart = end;
        }
        encodeSegment(text.substring(segmentStart), first, ids);
        List<Integer> result = special ? postProcess(ids) : ids;
        int[] out = new int[result.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = result.get(i);
        }
        return out;
    }

    /**
     * Wie {@link #encode} mit Sonder-Token, gekürzt auf höchstens {@code maxLength} Ids: gekürzt wird der Text, die
     * Sonder-Token der Nachbearbeitung bleiben.
     */
    int[] encodeTruncated(String text, int maxLength) {
        int[] plain = encode(text, false);
        int specials = encode("", true).length;
        int keep = Math.max(1, maxLength - specials);
        if (plain.length <= keep) {
            return encode(text, true);
        }
        List<Integer> ids = new ArrayList<Integer>(keep);
        for (int i = 0; i < keep; i++) {
            ids.add(plain[i]);
        }
        List<Integer> processed = postProcess(ids);
        int[] out = new int[processed.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = processed.get(i);
        }
        return out;
    }

    /**
     * A text PAIR for cross-encoders ({@code query}, {@code document}) with the pair template of the post-processor
     * (BERT: {@code [CLS] a [SEP] b [SEP]}, RoBERTa/XLM-R: {@code <s> a </s></s> b </s>}, or TemplateProcessing
     * {@code pair}). The longer side is cut first until the pair fits {@code maxLength}. Added for AskAI.
     */
    PairEncoding encodePair(String first, String second, int maxLength) {
        List<Integer> a = toList(encode(first, false));
        List<Integer> b = toList(encode(second, false));
        int specials = pairSpecialCount();
        int budget = Math.max(2, maxLength - specials);
        while (a.size() + b.size() > budget) {
            if (b.size() >= a.size() && !b.isEmpty()) {
                b.remove(b.size() - 1);
            } else if (!a.isEmpty()) {
                a.remove(a.size() - 1);
            } else {
                break;
            }
        }
        List<Integer> ids = new ArrayList<Integer>();
        List<Integer> types = new ArrayList<Integer>();
        if (!pairProcess(postProcessor, a, b, ids, types)) {
            ids.addAll(a);
            ids.addAll(b);
            for (int i = 0; i < a.size(); i++) {
                types.add(0);
            }
            for (int i = 0; i < b.size(); i++) {
                types.add(1);
            }
        }
        return new PairEncoding(toArray(ids), toArray(types));
    }

    private int pairSpecialCount() {
        List<Integer> ids = new ArrayList<Integer>();
        List<Integer> types = new ArrayList<Integer>();
        pairProcess(postProcessor, new ArrayList<Integer>(), new ArrayList<Integer>(), ids, types);
        return ids.size();
    }

    private boolean pairProcess(Map<String, Object> config, List<Integer> a, List<Integer> b,
                                List<Integer> ids, List<Integer> types) {
        String type = Json.text(config.get("type"), "");
        if ("Sequence".equals(type)) {
            for (Object step : Json.list(config.get("processors"))) {
                if (pairProcess(Json.object(step), a, b, ids, types)) {
                    return true;
                }
            }
            return false;
        }
        if ("BertProcessing".equals(type) || "RobertaProcessing".equals(type)) {
            boolean roberta = "RobertaProcessing".equals(type);
            Integer cls = pairId(config.get("cls"));
            Integer sep = pairId(config.get("sep"));
            add(ids, types, cls, 0);
            addAll(ids, types, a, 0);
            add(ids, types, sep, 0);
            if (roberta) {
                add(ids, types, sep, 0);
            }
            addAll(ids, types, b, roberta ? 0 : 1);
            add(ids, types, sep, roberta ? 0 : 1);
            return true;
        }
        if ("TemplateProcessing".equals(type)) {
            List<Object> pair = Json.list(config.get("pair"));
            if (pair.isEmpty()) {
                return false;
            }
            Map<String, Object> specials = Json.object(config.get("special_tokens"));
            for (Object item : pair) {
                Map<String, Object> entry = Json.object(item);
                if (entry.containsKey("Sequence")) {
                    Map<String, Object> sequence = Json.object(entry.get("Sequence"));
                    int typeId = Json.integer(sequence.get("type_id"), 0);
                    addAll(ids, types, "B".equals(Json.text(sequence.get("id"), "A")) ? b : a, typeId);
                } else if (entry.containsKey("SpecialToken")) {
                    Map<String, Object> token = Json.object(entry.get("SpecialToken"));
                    String name = Json.text(token.get("id"), "");
                    int typeId = Json.integer(token.get("type_id"), 0);
                    List<Object> special = Json.list(Json.object(specials.get(name)).get("ids"));
                    if (special.isEmpty()) {
                        add(ids, types, id(name), typeId);
                    }
                    for (Object id : special) {
                        add(ids, types, Json.integer(id, 0), typeId);
                    }
                }
            }
            return true;
        }
        return false;
    }

    private static void add(List<Integer> ids, List<Integer> types, Integer id, int typeId) {
        if (id != null) {
            ids.add(id);
            types.add(typeId);
        }
    }

    private static void addAll(List<Integer> ids, List<Integer> types, List<Integer> values, int typeId) {
        for (Integer value : values) {
            add(ids, types, value, typeId);
        }
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> list = new ArrayList<Integer>(values.length);
        for (int value : values) {
            list.add(value);
        }
        return list;
    }

    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** Ids and token type ids of an encoded pair. */
    static final class PairEncoding {
        final int[] ids;
        final int[] typeIds;

        PairEncoding(int[] ids, int[] typeIds) {
            this.ids = ids;
            this.typeIds = typeIds;
        }
    }

    private AddedToken addedAt(String text, int position) {
        List<AddedToken> candidates = addedByFirstChar.get(text.charAt(position));
        if (candidates == null) {
            return null;
        }
        for (AddedToken token : candidates) {
            if (text.startsWith(token.content, position)) {
                return token;
            }
        }
        return null;
    }

    private static String stripTrailingWhitespace(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private void encodeSegment(String segment, boolean first, List<Integer> ids) {
        if (segment.isEmpty()) {
            return;
        }
        String normalized = normalizer == null ? segment : normalizer.normalize(segment);
        if (normalized.isEmpty()) {
            return;
        }
        List<String> pieces = preTokenizer == null ? Collections.singletonList(normalized)
                : preTokenizer.split(normalized, first);
        for (String piece : pieces) {
            model.tokenize(piece, ids);
        }
    }

    private List<Integer> postProcess(List<Integer> ids) {
        return postProcess(postProcessor, ids);
    }

    private List<Integer> postProcess(Map<String, Object> config, List<Integer> ids) {
        String type = Json.text(config.get("type"), "");
        if ("Sequence".equals(type)) {
            List<Integer> current = ids;
            for (Object step : Json.list(config.get("processors"))) {
                current = postProcess(Json.object(step), current);
            }
            return current;
        }
        if ("BertProcessing".equals(type) || "RobertaProcessing".equals(type)) {
            List<Integer> out = new ArrayList<Integer>();
            Integer cls = pairId(config.get("cls"));
            Integer sep = pairId(config.get("sep"));
            if (cls != null) {
                out.add(cls);
            }
            out.addAll(ids);
            if (sep != null) {
                out.add(sep);
            }
            return out;
        }
        if ("TemplateProcessing".equals(type)) {
            List<Integer> out = new ArrayList<Integer>();
            Map<String, Object> specials = Json.object(config.get("special_tokens"));
            for (Object item : Json.list(config.get("single"))) {
                Map<String, Object> entry = Json.object(item);
                if (entry.containsKey("Sequence")) {
                    out.addAll(ids);
                } else if (entry.containsKey("SpecialToken")) {
                    String name = Json.text(Json.object(entry.get("SpecialToken")).get("id"), "");
                    List<Object> special = Json.list(Json.object(specials.get(name)).get("ids"));
                    if (special.isEmpty()) {
                        Integer id = id(name);
                        if (id != null) {
                            out.add(id);
                        }
                    }
                    for (Object id : special) {
                        out.add(Json.integer(id, 0));
                    }
                }
            }
            return out;
        }
        return ids;
    }

    private static Integer pairId(Object pair) {
        List<Object> values = Json.list(pair);
        return values.size() == 2 && values.get(1) instanceof Number ? ((Number) values.get(1)).intValue() : null;
    }

    /** Text zu den Ids; Sonder-Token auf Wunsch weggelassen. Alle übrigen Token laufen durch den Decoder. */
    String decode(int[] ids, boolean skipSpecial) {
        List<String> tokens = new ArrayList<String>(ids.length);
        for (int id : ids) {
            AddedToken addedToken = addedById.get(id);
            if (addedToken != null) {
                if (!(skipSpecial && addedToken.special)) {
                    tokens.add(addedToken.content);
                }
                continue;
            }
            String token = model.token(id);
            if (token != null) {
                tokens.add(token);
            }
        }
        List<String> decoded = decoder == null ? tokens : decoder.decode(tokens);
        StringBuilder out = new StringBuilder();
        for (String part : decoded) {
            out.append(part);
        }
        return out.toString();
    }

    /** Id eines Tokens (Zusatz-Token zuerst); {@code null}, wenn unbekannt. */
    Integer id(String token) {
        Integer id = addedByContent.get(token);
        return id != null ? id : model.id(token);
    }

    boolean isSpecial(int id) {
        AddedToken token = addedById.get(id);
        return token != null && token.special;
    }

    private static final class AddedToken {
        final int id;
        final String content;
        final boolean special;
        final boolean lstrip;
        final boolean rstrip;

        AddedToken(int id, String content, boolean special, boolean lstrip, boolean rstrip) {
            this.id = id;
            this.content = content;
            this.special = special;
            this.lstrip = lstrip;
            this.rstrip = rstrip;
        }
    }
}
