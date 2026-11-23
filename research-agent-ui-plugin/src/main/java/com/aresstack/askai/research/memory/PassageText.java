package com.aresstack.askai.research.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Component 7: the two text rules of the research memory, both plain program checks.
 * <ul>
 * <li>{@link #key}: when two passages are the same text (a text counts once as evidence, whatever source carries
 * it);</li>
 * <li>{@link #holdsQuote}: whether a quote stands verbatim in a passage. What only comes from layout, quoting or
 * punctuation does not count as a difference: line breaks and runs of blanks, a hyphen at a line end (with or without
 * the hyphen in the quote), a hyphen inside a word, soft hyphens, list marks at the start of a line, upper or lower
 * case, quotation marks, dashes, brackets, commas and the other signs between words. The words, the numbers (a sign
 * inside a number splits it, so "2,5" and "25" differ), the word breaks and the order within a part do: a quote glued
 * together from places of the passage without an ellipsis does not hold. A quote shortened with an ellipsis holds
 * when every part stands in the passage at a place of its own, in any order: the model often quotes the decisive
 * sentence first and an earlier one after it.</li>
 * </ul>
 */
public final class PassageText {

    /** Characters of the normalised text that make the key. */
    public static final int KEY_CHARS = 700;

    /** A shorter quote proves nothing: it stands in almost every passage. */
    public static final int MIN_QUOTE_CHARS = 20;

    /** Each part of a quote shortened with an ellipsis needs this many letters or digits. */
    public static final int MIN_PART_LETTERS = 6;

    private static final Pattern LINE_END_HYPHEN = Pattern.compile("(?<=\\p{L})-[ \\t]*\\r?\\n\\s*(?=\\p{Ll})");
    /** For the quote check: a hyphen at a line end between letters, whatever case follows. */
    private static final Pattern BROKEN_WORD = Pattern.compile("(?<=\\p{L})-[ \\t]*\\r?\\n\\s*(?=\\p{L})");
    /** For the quote check: a hyphen inside a word ("Funk-Sende-Empfänger" reads as "FunkSendeEmpfänger"). */
    private static final Pattern WORD_HYPHEN = Pattern.compile("(?<=\\p{L})-(?=\\p{L})");
    /** For the quote check: a list mark at the start of a line is layout. */
    private static final Pattern LIST_MARK = Pattern.compile("(?m)^[ \\t]*[-•*·▪●◦‣⁃][ \\t]+");
    private static final Pattern ELISION = Pattern.compile(
            "\\[\\s*(?:\\.\\.\\.|…)\\s*\\]|\\(\\s*(?:\\.\\.\\.|…)\\s*\\)|\\.\\.\\.|…");
    /** For the quote check: every run of signs that are no letter or digit is one word break. */
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** The places tried before the quote check gives up: a bound for quotes of many short, frequent parts. */
    private static final int MAX_PLACES_TRIED = 10000;

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
     * Whether {@code quote} stands verbatim in {@code passageText}, compared in the form {@link #comparable} gives with
     * only letters, digits and word breaks left ({@link #words}). A quote the model shortened with an ellipsis holds
     * when each part has at least {@value #MIN_PART_LETTERS} letters or digits and every part stands in the passage at
     * a place of its own (no two parts on the same words), in any order. Every part starts and ends at a word break,
     * so a quote cut from inside a word or a number ("wirksam" from "unwirksam", "25" from "125") does not hold. A
     * quote of fewer than {@value #MIN_QUOTE_CHARS} characters never holds.
     * <p>
     * Stock test of component 11 on 22.11.2026: four of the seven quotes of gemma4:12b that this check turned down
     * were right; three gave the decisive sentence first and an earlier one after the ellipsis, one differed only in
     * commas and brackets. A quote that leaves words out without an ellipsis still does not hold.
     */
    public static boolean holdsQuote(String passageText, String quote) {
        String text = words(comparable(passageText));
        String wanted = comparable(stripOuterQuotes(quote));
        if (text.isEmpty() || wanted.replace("...", "").replace("…", "").trim().length() < MIN_QUOTE_CHARS) {
            return false;
        }
        List<String> pieces = new ArrayList<String>();
        for (String part : ELISION.split(wanted)) {
            String piece = words(part);
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.replace(" ", "").length() < MIN_PART_LETTERS) {
                return false;
            }
            pieces.add(piece);
        }
        return !pieces.isEmpty() && placed(text, pieces, 0, new ArrayList<int[]>(), new int[]{MAX_PLACES_TRIED});
    }

    /** Letters and digits of a comparable text, every run of other signs one blank. */
    static String words(String comparable) {
        return NOT_A_WORD.matcher(comparable).replaceAll(" ").trim();
    }

    /** Whether the parts from {@code index} on find places of their own beside the places {@code taken}. */
    private static boolean placed(String text, List<String> pieces, int index, List<int[]> taken, int[] budget) {
        if (index == pieces.size()) {
            return true;
        }
        String piece = pieces.get(index);
        for (int at = text.indexOf(piece); at >= 0; at = text.indexOf(piece, at + 1)) {
            if (--budget[0] < 0) {
                return false;
            }
            int end = at + piece.length();
            if ((at > 0 && text.charAt(at - 1) != ' ') || (end < text.length() && text.charAt(end) != ' ')) {
                continue; // a part starts and ends at a word break: "wirksam" does not stand in "unwirksam"
            }
            boolean free = true;
            for (int[] place : taken) {
                free &= end <= place[0] || at >= place[1];
            }
            if (free) {
                taken.add(new int[]{at, end});
                if (placed(text, pieces, index + 1, taken, budget)) {
                    return true;
                }
                taken.remove(taken.size() - 1);
            }
        }
        return false;
    }

    /**
     * The form in which quote and passage are compared: soft hyphens out, every dash a hyphen, list marks at a line
     * start out, a hyphen at a line end or inside a word out (the word reads the same with and without it),
     * quotation marks of one shape, blanks single, lower case. Every other sign stays, so "2,5" and "25" differ.
     */
    static String comparable(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("­", "").replaceAll("[‐‑‒–—−]", "-");
        text = LIST_MARK.matcher(text).replaceAll("");
        text = BROKEN_WORD.matcher(text).replaceAll("");
        text = WORD_HYPHEN.matcher(text).replaceAll("");
        text = text.replaceAll("[“”„‟«»‹›]", "\"").replaceAll("[‘’‚‛]", "'");
        return text.replaceAll("[\\s   ]+", " ").trim().toLowerCase(Locale.ROOT);
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
