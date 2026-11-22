package com.aresstack.askai.research.memory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Component 7: the two text rules of the research memory, both plain program checks.
 * <ul>
 * <li>{@link #key}: when two passages are the same text (a text counts once as evidence, whatever source carries
 * it);</li>
 * <li>{@link #holdsQuote}: whether a quote stands verbatim in a passage. Line breaks, runs of blanks, hyphenation
 * at a line end, soft hyphens and the shape of quotation marks and dashes do not count as a difference; the words
 * and their order do.</li>
 * <li>{@link #numbersHeld}: whether every number a statement names stands in the quote (build plan, second review:
 * for numbers and efficacy claims the number must stand in the quote).</li>
 * </ul>
 */
public final class PassageText {

    /** Characters of the normalised text that make the key. */
    public static final int KEY_CHARS = 700;

    /** A shorter quote proves nothing: it stands in almost every passage. */
    public static final int MIN_QUOTE_CHARS = 20;

    /** Each part of a quote shortened with an ellipsis needs this many characters. */
    public static final int MIN_PART_CHARS = 8;

    private static final Pattern LINE_END_HYPHEN = Pattern.compile("(?<=\\p{L})-[ \\t]*\\r?\\n\\s*(?=\\p{Ll})");
    private static final Pattern ELISION = Pattern.compile(
            "\\[\\s*(?:\\.\\.\\.|…)\\s*\\]|\\(\\s*(?:\\.\\.\\.|…)\\s*\\)|\\.\\.\\.|…");

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,\\u00A0\\u202F]\\d+)*");

    private PassageText() {
    }

    /**
     * The first {@value #KEY_CHARS} characters, lower case, everything but letters and digits folded to a blank, with
     * no blank at either end (a verdict stores its key trimmed, so a key cut on a blank must not keep it).
     */
    public static String key(String passageText) {
        if (passageText == null) {
            return "";
        }
        String folded = unhyphenated(passageText).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return folded.length() > KEY_CHARS ? folded.substring(0, KEY_CHARS).trim() : folded;
    }

    /**
     * Whether {@code quote} stands verbatim in {@code passageText}. A quote the model shortened with an ellipsis
     * holds when its parts stand in the passage in that order and each part has at least {@value #MIN_PART_CHARS}
     * characters. A quote of fewer than {@value #MIN_QUOTE_CHARS} characters never holds.
     */
    public static boolean holdsQuote(String passageText, String quote) {
        String text = plain(passageText);
        String wanted = plain(stripOuterQuotes(quote));
        if (text.isEmpty() || wanted.replace("...", "").replace("…", "").trim().length() < MIN_QUOTE_CHARS) {
            return false;
        }
        int from = 0;
        for (String part : ELISION.split(wanted)) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.length() < MIN_PART_CHARS) {
                return false;
            }
            int at = text.indexOf(piece, from);
            if (at < 0) {
                return false;
            }
            from = at + piece.length();
        }
        return true;
    }

    /**
     * Whether every number {@code statement} names stands in {@code quote} too; a statement without a number always
     * holds. Thousands separators do not count ("1.500" holds "1500"), and the decimal sign may be a point or a comma
     * ("2,5" holds "2.5"). Only the digits count: a number written as a word is no number here.
     */
    public static boolean numbersHeld(String statement, String quote) {
        Set<String> wanted = numbers(statement);
        return wanted.isEmpty() || numbers(stripOuterQuotes(quote)).containsAll(wanted);
    }

    /** The numbers of a text in one form: thousands separators dropped, any other separator a point. */
    static Set<String> numbers(String text) {
        Set<String> out = new LinkedHashSet<String>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String[] groups = m.group().split("[.,\\u00A0\\u202F]");
            boolean thousands = groups.length > 1;
            for (int i = 1; i < groups.length; i++) {
                thousands &= groups[i].length() == 3;
            }
            out.add(String.join(thousands ? "" : ".", groups));
        }
        return out;
    }

    private static String plain(String value) {
        if (value == null) {
            return "";
        }
        String text = unhyphenated(value);
        text = text.replaceAll("[“”„‟«»‹›]", "\"")
                .replaceAll("[‘’‚‛]", "'")
                .replaceAll("[‐‑‒–—−]", "-");
        return text.replaceAll("[\\s   ]+", " ").trim();
    }

    private static String unhyphenated(String value) {
        return LINE_END_HYPHEN.matcher(value.replace("­", "")).replaceAll("");
    }

    private static String stripOuterQuotes(String quote) {
        if (quote == null) {
            return "";
        }
        String text = quote.trim();
        while (text.length() >= 2 && isQuoteMark(text.charAt(0)) && isQuoteMark(text.charAt(text.length() - 1))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static boolean isQuoteMark(char c) {
        return "\"'“”„‟«»‘’‚".indexOf(c) >= 0;
    }
}
