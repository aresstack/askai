package com.aresstack.askai.config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One entry of the Install panel's search dropdown: a HuggingFace search term, the input modalities the
 * suggested model accepts, and an optional TARGET marker distinguishing a general Ollama/GGUF import from a
 * model catalogued for AskAI's local win-directml engine. Persisted one per line as
 * {@code <term> | <modality>,<modality> | <target>} (e.g. {@code google/gemma-3-270m-it | text | local}).
 *
 * <p>The third field is real meta-information, NOT a modality and NOT a name heuristic. Two-column lines
 * ({@code <term> | <modality>}) and one-column lines ({@code <term>}) stay fully backward compatible and
 * default to {@link Target#GENERAL}. Unknown tags are ignored so the format stays forgiving.</p>
 */
public final class HuggingFaceSearchSuggestion {

    /** Input modalities a suggested model accepts; rendered as icons in the dropdown. */
    public enum Modality {
        TEXT, AUDIO, VISION
    }

    /** Where a suggestion belongs: a general Ollama/GGUF import, or AskAI's local win-directml engine. */
    public enum Target {
        GENERAL,
        ASKAI_LOCAL_ENGINE;

        /** The persisted third-field token, or "" for the default {@link #GENERAL}. */
        public String token() {
            return this == ASKAI_LOCAL_ENGINE ? "local" : "";
        }
    }

    private final String term;
    private final Set<Modality> modalities;
    private final Target target;

    public HuggingFaceSearchSuggestion(String term, Set<Modality> modalities) {
        this(term, modalities, Target.GENERAL);
    }

    public HuggingFaceSearchSuggestion(String term, Set<Modality> modalities, Target target) {
        this.term = term == null ? "" : term.trim();
        this.modalities = modalities == null || modalities.isEmpty()
                ? EnumSet.of(Modality.TEXT) : EnumSet.copyOf(modalities);
        this.target = target == null ? Target.GENERAL : target;
    }

    public String getTerm() {
        return term;
    }

    public Set<Modality> getModalities() {
        return modalities;
    }

    public Target getTarget() {
        return target;
    }

    /** True when this entry is catalogued for AskAI's local win-directml engine (LOCAL/DirectML group). */
    public boolean isLocalEngine() {
        return target == Target.ASKAI_LOCAL_ENGINE;
    }

    /** The editable combo editor shows the plain search term. */
    public String toString() {
        return term;
    }

    /**
     * The canonical persisted line for this suggestion. A GENERAL entry keeps the historical two-column
     * form ({@code term | modalities}); a local-engine entry adds the third field ({@code | local}).
     */
    public String toLine() {
        StringBuilder sb = new StringBuilder(term).append(" | ").append(modalitiesToken());
        if (target == Target.ASKAI_LOCAL_ENGINE) {
            sb.append(" | ").append(target.token());
        }
        return sb.toString();
    }

    private String modalitiesToken() {
        StringBuilder sb = new StringBuilder();
        // Stable, lowercase, comma-separated order matching the enum declaration.
        for (Modality m : Modality.values()) {
            if (modalities.contains(m)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(m.name().toLowerCase());
            }
        }
        return sb.length() == 0 ? "text" : sb.toString();
    }

    /** Parses the persisted newline-separated list; blank lines and duplicates are dropped. */
    public static List<HuggingFaceSearchSuggestion> parseList(String raw) {
        List<HuggingFaceSearchSuggestion> suggestions = new ArrayList<HuggingFaceSearchSuggestion>();
        List<String> seenTerms = new ArrayList<String>();
        String[] lines = (raw == null ? "" : raw).split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            HuggingFaceSearchSuggestion suggestion = parseLine(lines[i]);
            if (suggestion != null && !seenTerms.contains(suggestion.getTerm())) {
                seenTerms.add(suggestion.getTerm());
                suggestions.add(suggestion);
            }
        }
        return suggestions;
    }

    /** @return the parsed suggestion, or {@code null} for a blank line. */
    public static HuggingFaceSearchSuggestion parseLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        String[] parts = trimmed.split("\\|", -1);
        String term = parts[0].trim();
        if (term.length() == 0) {
            return null;
        }
        EnumSet<Modality> modalities = EnumSet.noneOf(Modality.class);
        if (parts.length >= 2) {
            String[] tags = parts[1].split(",");
            for (int i = 0; i < tags.length; i++) {
                String tag = tags[i].trim().toUpperCase();
                if (tag.length() == 0) {
                    continue;
                }
                try {
                    modalities.add(Modality.valueOf(tag));
                } catch (IllegalArgumentException ignored) {
                    // Unknown tag: skip, keep the suggestion usable.
                }
            }
        }
        // Third field: real meta-information (target). Only "local" promotes to the local engine; any other
        // value (or an absent field) stays GENERAL so old two-column lines are unaffected.
        Target target = Target.GENERAL;
        if (parts.length >= 3 && "local".equalsIgnoreCase(parts[2].trim())) {
            target = Target.ASKAI_LOCAL_ENGINE;
        }
        return new HuggingFaceSearchSuggestion(term, modalities, target);
    }
}
