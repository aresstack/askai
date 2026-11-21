package com.aresstack.askai.research.domain.scope;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a book length from free text ("20–30 Seiten", "20 bis 30 Seiten", "ca. 25 Seiten", "8.000 Wörter",
 * "20-30 pages") and writes it back in the one form later phases read ("20-30 pages", "8000 words").
 * <p>
 * Repair plan step 1 (2026-11-21): the scoping grammar cannot carry a length, so in four stored workspaces the
 * deliverable stayed empty although the mission named "20 bis 30 Seiten"; and the outline's length reader knew only
 * the ASCII hyphen, so "20–30 Seiten" read as 30 pages. This reader is the one place both now use. It understands
 * the hyphen, en and em dash, "bis", "to", approximation words in front ("ca.", "etwa", "rund", "about") and thousands
 * separators ("8.000", "8,000", "8 000"). A decimal number ("2,5 Seiten") is no length. Stateless.
 */
public final class DeliverableLengthText {

    private static final String NUMBER = "(\\d{1,3}(?:[.,\\u00a0\\u202f ]\\d{3})+|\\d+)(?![.,]\\d)";
    private static final String RANGE = "\\s*(?:-|\\u2013|\\u2014|bis(?:\\s+zu)?|to|until)\\s*";
    private static final String UNIT = "(normseiten|druckseiten|seiten|seite|s\\.|pages?|pp\\.|w(?:\\u00f6|oe|o)rter"
            + "|worte|words?)";
    private static final Pattern LENGTH = Pattern.compile(
            "(?<![\\w.,])" + NUMBER + "(?:" + RANGE + NUMBER + ")?\\s*(?:din[- ]?a4[- ]?)?" + UNIT + "(?!\\w)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private DeliverableLengthText() {
    }

    /**
     * The first length {@code text} names, as a deliverable with the standard synthesis policy; {@code null} when
     * it names none. A reversed range is kept as the user's intent ({@link ResearchDeliverable} orders it).
     */
    public static ResearchDeliverable read(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = LENGTH.matcher(text);
        while (matcher.find()) {
            int min = number(matcher.group(1));
            int max = matcher.group(2) == null ? min : number(matcher.group(2));
            if (min <= 0 || max <= 0) {
                continue;
            }
            String unit = matcher.group(3).toLowerCase(Locale.ROOT);
            ResearchDeliverable.LengthUnit lengthUnit = unit.startsWith("w")
                    ? ResearchDeliverable.LengthUnit.WORDS : ResearchDeliverable.LengthUnit.PAGES;
            return new ResearchDeliverable(min, max, lengthUnit, SynthesisPolicy.defaults());
        }
        return null;
    }

    /** {@code "20-30 pages"}, {@code "25 pages"} or {@code "8000 words"}; {@code ""} without a length. */
    public static String describe(ResearchDeliverable deliverable) {
        if (deliverable == null || !deliverable.hasTargetLength()) {
            return "";
        }
        String unit = deliverable.getLengthUnit().name().toLowerCase(Locale.ROOT);
        int min = deliverable.getTargetLengthMin();
        int max = deliverable.getTargetLengthMax();
        if (min > 0 && max > 0 && min != max) {
            return min + "-" + max + " " + unit;
        }
        return Math.max(min, max) + " " + unit;
    }

    /**
     * The deliverable later phases get: the stated one when it has a length, else the length the mission names
     * (when {@code fromMission} is on), else {@code ""}.
     */
    public static String effective(ResearchDeliverable stated, String mission, boolean fromMission) {
        String described = describe(stated);
        if (!described.isEmpty() || !fromMission) {
            return described;
        }
        return describe(read(mission));
    }

    private static int number(String digits) {
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c >= '0' && c <= '9') {
                plain.append(c);
            }
        }
        if (plain.length() == 0 || plain.length() > 9) {
            return 0;
        }
        return Integer.parseInt(plain.toString());
    }
}
