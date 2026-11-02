package com.aresstack.askai.hf.meta;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the quantization level from a GGUF file name, but only when it is an exact, known token
 * (e.g. {@code ...-Q4_K_M.gguf} → {@code Q4_K_M}). An unknown or ambiguous name yields {@code null}
 * rather than a guess, so a wrong level is never sent to Ollama.
 */
public final class GgufQuantization {

    /** Canonical k-quant / legacy quant tokens Ollama reports back via {@code /api/show}. */
    private static final Set<String> KNOWN = new LinkedHashSet<String>(Arrays.asList(
            "F32", "F16", "BF16",
            "Q8_0", "Q6_K", "Q5_K_M", "Q5_K_S", "Q5_0", "Q5_1",
            "Q4_K_M", "Q4_K_S", "Q4_0", "Q4_1",
            "Q3_K_L", "Q3_K_M", "Q3_K_S", "Q2_K"));

    // Importance-matrix quants such as IQ4_XS, IQ3_M, IQ2_XXS — matched structurally.
    private static final Pattern IQ = Pattern.compile("IQ[1-4]_[A-Z]{1,3}");

    private GgufQuantization() {
    }

    /**
     * @return the canonical (upper-case) quantization token found as a whole token in {@code fileName},
     *         or {@code null} when none is recognised.
     */
    public static String fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String upper = fileName.toUpperCase(Locale.ROOT);
        // Split on anything that is not part of a quant token so we only match whole tokens.
        String[] tokens = upper.split("[^A-Z0-9_]+");
        for (String token : tokens) {
            if (KNOWN.contains(token)) {
                return token;
            }
            Matcher matcher = IQ.matcher(token);
            if (matcher.matches()) {
                return token;
            }
        }
        return null;
    }

    /**
     * @return a MEDIUM-confidence quantization value from the file name, or {@code null} when unknown. A
     *         file name can be wrong or renamed, and {@code info.quantization_level} can override Ollama's
     *         own GGUF detection — so a name-only value is kept for provenance and is not sent to Ollama
     *         unless a structured source (GGUF/HF metadata) confirms it.
     */
    public static MetadataValue<String> fromFileNameValue(String fileName) {
        String level = fromFileName(fileName);
        return level == null ? null
                : MetadataValue.of(level, MetadataSource.FILE_NAME, Confidence.MEDIUM);
    }
}
