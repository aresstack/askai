package com.aresstack.askai.localruntime.onnx;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Muster aus {@code tokenizer.json}: {@code {"String": "…"}} wörtlich oder {@code {"Regex": "…"}} (Unicode-Klassen). */
final class TokenizerPatterns {

    private TokenizerPatterns() {
    }

    static Pattern pattern(Object config) throws TokenizerFormatException {
        Map<String, Object> map = Json.object(config);
        if (map.get("String") instanceof String) {
            return Pattern.compile(Pattern.quote((String) map.get("String")));
        }
        if (map.get("Regex") instanceof String) {
            return regex((String) map.get("Regex"));
        }
        if (config instanceof String) {
            return Pattern.compile(Pattern.quote((String) config));
        }
        throw new TokenizerFormatException("Muster ohne String/Regex: " + config);
    }

    static Pattern regex(String expression) throws TokenizerFormatException {
        try {
            return Pattern.compile(expression, Pattern.UNICODE_CHARACTER_CLASS);
        } catch (PatternSyntaxException e) {
            throw new TokenizerFormatException("Regulärer Ausdruck nicht lesbar: " + e.getDescription());
        }
    }
}
