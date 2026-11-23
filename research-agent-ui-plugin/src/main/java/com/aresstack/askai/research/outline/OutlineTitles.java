package com.aresstack.askai.research.outline;

import com.aresstack.askai.research.runtime.writer.ClaimCarryCheck;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Near-duplicate titles of an outline, one rule for the quality check and the label call: two titles are near
 * duplicates when they are equal or their content words (and numbers: "Part 1" is no "Part 2"), cut to a stem, overlap
 * by a share of the shorter title. Stemming follows the claim check's defaults. Live retest of #242: the label call
 * named 1.9 and 1.20 both "Technologien und Sensorik".
 */
public final class OutlineTitles {

    /**
     * Default overlap share, the share of the writer's repeat check (#221); the outline setting "Similar section
     * titles" starts here.
     */
    public static final double DEFAULT_MAX_OVERLAP = 0.75;

    private static final int WORD_LETTERS = ClaimCarryCheck.Criteria.DEFAULT_MIN_WORD_LENGTH;
    private static final int STEM_LETTERS = ClaimCarryCheck.Criteria.DEFAULT_STEM_LENGTH;

    private OutlineTitles() {
    }

    /** Lower case, letters and digits only, single spaces. */
    public static String normal(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The content-word stems of a title. */
    public static Set<String> stems(String title) {
        Set<String> stems = new LinkedHashSet<String>();
        for (String word : normal(title).split(" ")) {
            if (word.length() >= WORD_LETTERS || word.matches(".*\\d.*")) {
                stems.add(word.length() > STEM_LETTERS ? word.substring(0, STEM_LETTERS) : word);
            }
        }
        return stems;
    }

    /** Whether {@code a} and {@code b} are near duplicates at {@code maxOverlap} (1 = only equal stems). */
    public static boolean similar(String a, String b, double maxOverlap) {
        String left = normal(a);
        String right = normal(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        Set<String> l = stems(a);
        Set<String> r = stems(b);
        if (l.isEmpty() || r.isEmpty()) {
            return false;
        }
        if (l.equals(r)) {
            return true;
        }
        Set<String> shared = new LinkedHashSet<String>(l);
        shared.retainAll(r);
        return shared.size() >= maxOverlap * Math.min(l.size(), r.size()) && maxOverlap < 1.0;
    }
}
