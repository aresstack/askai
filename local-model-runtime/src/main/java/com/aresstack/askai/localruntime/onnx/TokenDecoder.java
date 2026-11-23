package com.aresstack.askai.localruntime.onnx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rückweg aus {@code tokenizer.json} ({@code decoder}): ByteLevel, WordPiece, Metaspace, Replace, ByteFallback, Fuse,
 * Strip, BPEDecoder und Sequence. Wie in tokenizers arbeitet jede Stufe auf der Liste der Token-Texte.
 */
abstract class TokenDecoder {

    private static final Pattern BYTE_TOKEN = Pattern.compile("<0x([0-9A-Fa-f]{2})>");

    abstract List<String> decode(List<String> tokens);

    static TokenDecoder of(Object config) throws TokenizerFormatException {
        Map<String, Object> map = Json.object(config);
        if (map.isEmpty()) {
            return null;
        }
        String type = Json.text(map.get("type"), "");
        if ("Sequence".equals(type)) {
            final List<TokenDecoder> steps = new ArrayList<TokenDecoder>();
            for (Object step : Json.list(map.get("decoders"))) {
                TokenDecoder decoder = of(step);
                if (decoder != null) {
                    steps.add(decoder);
                }
            }
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    List<String> current = tokens;
                    for (TokenDecoder step : steps) {
                        current = step.decode(current);
                    }
                    return current;
                }
            };
        }
        if ("ByteLevel".equals(type)) {
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    StringBuilder joined = new StringBuilder();
                    for (String token : tokens) {
                        joined.append(token);
                    }
                    return Collections.singletonList(ByteLevelCodec.decode(joined.toString()));
                }
            };
        }
        if ("WordPiece".equals(type)) {
            final String prefix = Json.text(map.get("prefix"), "##");
            final boolean cleanup = Json.bool(map.get("cleanup"), true);
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    List<String> out = new ArrayList<String>(tokens.size());
                    for (int i = 0; i < tokens.size(); i++) {
                        String token = tokens.get(i);
                        if (i > 0) {
                            token = token.startsWith(prefix) ? token.substring(prefix.length()) : " " + token;
                        }
                        out.add(cleanup ? cleanup(token) : token);
                    }
                    return out;
                }
            };
        }
        if ("Metaspace".equals(type)) {
            final String replacement = Json.text(map.get("replacement"), "▁");
            String scheme = Json.text(map.get("prepend_scheme"), null);
            if (scheme == null) {
                scheme = Json.bool(map.get("add_prefix_space"), true) ? "always" : "never";
            }
            final boolean prepended = !"never".equals(scheme);
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    List<String> out = new ArrayList<String>(tokens.size());
                    for (int i = 0; i < tokens.size(); i++) {
                        String token = tokens.get(i).replace(replacement, " ");
                        if (i == 0 && prepended && token.startsWith(" ")) {
                            token = token.substring(1);
                        }
                        out.add(token);
                    }
                    return out;
                }
            };
        }
        if ("Replace".equals(type)) {
            final Pattern pattern = TokenizerPatterns.pattern(map.get("pattern"));
            final String content = Matcher.quoteReplacement(Json.text(map.get("content"), ""));
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    List<String> out = new ArrayList<String>(tokens.size());
                    for (String token : tokens) {
                        out.add(pattern.matcher(token).replaceAll(content));
                    }
                    return out;
                }
            };
        }
        if ("ByteFallback".equals(type)) {
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    List<String> out = new ArrayList<String>(tokens.size());
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int pending = 0;
                    for (String token : tokens) {
                        Matcher matcher = BYTE_TOKEN.matcher(token);
                        if (matcher.matches()) {
                            bytes.write(Integer.parseInt(matcher.group(1), 16));
                            pending++;
                            continue;
                        }
                        flush(bytes, pending, out);
                        pending = 0;
                        out.add(token);
                    }
                    flush(bytes, pending, out);
                    return out;
                }
            };
        }
        if ("Fuse".equals(type)) {
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    StringBuilder joined = new StringBuilder();
                    for (String token : tokens) {
                        joined.append(token);
                    }
                    return Collections.singletonList(joined.toString());
                }
            };
        }
        if ("Strip".equals(type)) {
            final String content = Json.text(map.get("content"), " ");
            final int start = Json.integer(map.get("start"), 0);
            final int stop = Json.integer(map.get("stop"), 0);
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    List<String> out = new ArrayList<String>(tokens.size());
                    for (String token : tokens) {
                        String current = token;
                        for (int i = 0; i < start && current.startsWith(content); i++) {
                            current = current.substring(content.length());
                        }
                        for (int i = 0; i < stop && current.endsWith(content); i++) {
                            current = current.substring(0, current.length() - content.length());
                        }
                        out.add(current);
                    }
                    return out;
                }
            };
        }
        if ("BPEDecoder".equals(type)) {
            final String suffix = Json.text(map.get("suffix"), "</w>");
            return new TokenDecoder() {
                @Override
                List<String> decode(List<String> tokens) {
                    List<String> out = new ArrayList<String>(tokens.size());
                    for (int i = 0; i < tokens.size(); i++) {
                        out.add(tokens.get(i).replace(suffix, i == tokens.size() - 1 ? "" : " "));
                    }
                    return out;
                }
            };
        }
        throw new TokenizerFormatException("Decoder " + type + " wird nicht unterstützt");
    }

    private static void flush(ByteArrayOutputStream bytes, int count, List<String> out) {
        if (count == 0) {
            return;
        }
        String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        bytes.reset();
        if (text.indexOf('�') >= 0) {
            // ungültige Bytefolge: je Byte ein Ersatzzeichen, wie tokenizers
            StringBuilder replaced = new StringBuilder();
            for (int i = 0; i < count; i++) {
                replaced.append('�');
            }
            out.add(replaced.toString());
        } else {
            out.add(text);
        }
    }

    /** Leerzeichen vor Satzzeichen und Kurzformen entfernen (wie {@code cleanup} in tokenizers). */
    static String cleanup(String text) {
        return text.replace(" .", ".").replace(" ?", "?").replace(" !", "!").replace(" ,", ",")
                .replace(" ' ", "'").replace(" n't", "n't").replace(" 'm", "'m").replace(" do not", " don't")
                .replace(" 's", "'s").replace(" 've", "'ve").replace(" 're", "'re");
    }
}
