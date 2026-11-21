package com.aresstack.askai.research.candidates;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Component 5: when two passages are the same text. The key is the rule of the research memory (component 7,
 * {@code PassageText.key}): the first {@value #KEY_CHARS} characters, lower case, hyphenation at a line end and soft
 * hyphens removed, everything but letters and digits folded to one blank. Both live in separate draft PRs; once both
 * are merged, one of them goes.
 */
public final class CandidateText {

    /** Characters of the normalised text that make the key. */
    public static final int KEY_CHARS = 700;

    private static final Pattern LINE_END_HYPHEN = Pattern.compile("(?<=\\p{L})-[ \\t]*\\r?\\n\\s*(?=\\p{Ll})");
    private static final Pattern NO_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private CandidateText() {
    }

    /** The key of a passage text; empty for a text without letters or digits. */
    public static String key(String passageText) {
        if (passageText == null) {
            return "";
        }
        String unhyphenated = LINE_END_HYPHEN.matcher(passageText.replace("­", "")).replaceAll("");
        String folded = NO_LETTER_OR_DIGIT.matcher(unhyphenated.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        return folded.length() > KEY_CHARS ? folded.substring(0, KEY_CHARS) : folded;
    }
}
