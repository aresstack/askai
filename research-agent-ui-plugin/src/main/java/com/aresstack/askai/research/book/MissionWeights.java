package com.aresstack.askai.research.book;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repair plan step 5a: the user's explicit weight of a main theme, written into the mission ("Gewichtung: Recht
 * 40 %", "Schwerpunkt Technik mit 20 Prozent", "30 % des Buchs für Implementierung"). A theme is a first-level card of
 * the concept; the mission names it by its full name or by a word of five or more letters that only this card's name
 * holds. A percentage belongs to the nearest card name in the same clause; a percentage two cards claim goes to the
 * nearer one.
 * <p>
 * A percentage near a card name is often a fact of the subject, not a weight ("Förderung bis zu 20 % Zuschuss",
 * architect review of 21.11.). So a percentage counts as a weight only when its sentence holds a weight word
 * (Gewicht, Gewichtung, Anteil, Schwerpunkt, des Umfangs, des Buchs, ausmachen, weight, share, focus, of the book);
 * without any such sentence, the percentages count only when at least two cards carry one and they add up to at most
 * 100 %. A percentage after a comparison ("bis zu", "um", "über", "mindestens", "up to", ...) is never a weight. A bare
 * "Umfang" is no weight word, because the length sentence ("Umfang 20 Seiten") often names the themes too. Shares
 * above 100 % in sum are scaled down later ({@code SectionBudget}). Stateless.
 */
public final class MissionWeights {

    private static final String PERCENT = "(\\d{1,2}(?:[.,]\\d)?|100)\\s*(?:%|prozent\\b|percent\\b)";
    private static final int GAP = 40;

    /** Words that make a percentage in the same sentence a weight of the book. */
    private static final Pattern WEIGHT_WORD = Pattern.compile("gewicht|anteil|schwerpunkt|des umfangs|vom umfang"
            + "|am umfang|des buch(?:e)?s|vom buch|am buch|ausmach|weight|\\bshare|\\bfocus|of the book");

    /** A comparison right before the number makes it a fact of the subject, not a weight. */
    private static final Pattern COMPARISON_BEFORE = Pattern.compile("(?:^|[^\\p{L}])(?:bis zu|bis|um|über|ueber"
            + "|unter|mindestens|höchstens|hoechstens|maximal|max|mehr als|weniger als|up to|over|under|at least"
            + "|at most|more than|less than)\\s*$");

    /** Dots of abbreviations ("ca.", "z. B.", "max.") are no sentence ends. */
    private static final Pattern ABBREVIATION_DOT = Pattern.compile(
            "(?<=(?:^|[^\\p{L}])(?:ca|bzw|approx|vgl|ggf|inkl|max|min|etc|[\\p{L}]))\\.");

    /** Sentence ends; a dot between digits is a decimal point. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?;](?!\\d)|\\n");

    private MissionWeights() {
    }

    /** Per card name (as given) its share in (0, 1]; cards without a stated weight are absent. */
    public static Map<String, Double> read(String mission, List<String> cards) {
        Map<String, Double> shares = new LinkedHashMap<String, Double>();
        if (mission == null || mission.trim().isEmpty() || cards == null || cards.isEmpty()) {
            return shares;
        }
        String text = ABBREVIATION_DOT.matcher(mission.toLowerCase(Locale.ROOT)).replaceAll(" ");
        // per number position: the card that claims it and the distance of its name
        Map<Integer, String> owner = new LinkedHashMap<Integer, String>();
        Map<Integer, Integer> distance = new LinkedHashMap<Integer, Integer>();
        Map<Integer, Double> value = new LinkedHashMap<Integer, Double>();
        for (String card : cards) {
            if (card == null || card.trim().isEmpty()) {
                continue;
            }
            for (String name : namesOf(card, cards)) {
                String quoted = Pattern.quote(name);
                Matcher after = Pattern.compile("(?<![\\p{L}])" + quoted + "(?![\\p{L}])[^.;\\n%]{0," + GAP + "}?"
                        + PERCENT).matcher(text);
                while (after.find()) {
                    if (afterComparison(text, after.start(1))) {
                        continue;
                    }
                    claim(card, after.start(1), after.start(1) - (after.start() + name.length()),
                            number(after.group(1)), owner, distance, value);
                }
                Matcher before = Pattern.compile("(?<![\\d.,])" + PERCENT + "[^.;\\n%\\d]{0," + GAP + "}?(?<![\\p{L}])"
                        + quoted + "(?![\\p{L}])").matcher(text);
                while (before.find()) {
                    if (afterComparison(text, before.start(1))) {
                        continue;
                    }
                    int gap = before.end() - name.length() - before.end(1);
                    claim(card, before.start(1), gap, number(before.group(1)), owner, distance, value);
                }
            }
        }
        for (Integer position : weights(text, owner, value)) {
            double share = value.get(position) / 100.0;
            if (share > 0 && share <= 1 && !shares.containsKey(owner.get(position))) {
                shares.put(owner.get(position), share);
            }
        }
        // keep the cards' order
        Map<String, Double> ordered = new LinkedHashMap<String, Double>();
        for (String card : cards) {
            if (shares.containsKey(card)) {
                ordered.put(card, shares.get(card));
            }
        }
        return ordered;
    }

    /** {@code "Recht 40 %, Technik 20 %"}; {@code ""} without weights. */
    public static String describe(Map<String, Double> shares) {
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Double> share : shares.entrySet()) {
            double percent = share.getValue() * 100;
            String number = Math.abs(percent - Math.rint(percent)) < 0.05
                    ? String.valueOf((long) Math.rint(percent)) : String.format(Locale.ROOT, "%.1f", percent);
            parts.add(share.getKey() + " " + number + " %");
        }
        return String.join(", ", parts);
    }

    /**
     * The claimed percentages that are weights: those in a sentence with a weight word; without any, all of them when
     * at least two cards carry one and they add up to at most 100 %; else none.
     */
    private static List<Integer> weights(String text, Map<Integer, String> owner, Map<Integer, Double> value) {
        List<Integer> signalled = new ArrayList<Integer>();
        Set<String> cards = new LinkedHashSet<String>();
        double sum = 0;
        for (Map.Entry<Integer, String> claimed : owner.entrySet()) {
            if (WEIGHT_WORD.matcher(sentenceAt(text, claimed.getKey())).find()) {
                signalled.add(claimed.getKey());
            }
            cards.add(claimed.getValue());
            sum += value.get(claimed.getKey());
        }
        if (!signalled.isEmpty()) {
            return signalled;
        }
        if (cards.size() >= 2 && sum <= 100.5) {
            return new ArrayList<Integer>(owner.keySet());
        }
        return Collections.emptyList();
    }

    private static boolean afterComparison(String text, int numberStart) {
        return COMPARISON_BEFORE.matcher(text.substring(Math.max(0, numberStart - 24), numberStart)).find();
    }

    private static String sentenceAt(String text, int position) {
        int start = 0;
        int end = text.length();
        Matcher boundary = SENTENCE_END.matcher(text);
        while (boundary.find()) {
            if (boundary.end() <= position) {
                start = boundary.end();
            } else {
                end = boundary.start();
                break;
            }
        }
        return text.substring(start, Math.max(start, end));
    }

    private static void claim(String card, int position, int gap, double number, Map<Integer, String> owner,
                              Map<Integer, Integer> distance, Map<Integer, Double> value) {
        Integer known = distance.get(position);
        if (known == null || gap < known) {
            owner.put(position, card);
            distance.put(position, gap);
            value.put(position, number);
        }
    }

    /** The card's full name and each word of 5+ letters no other card's name holds, lower case. */
    static List<String> namesOf(String card, List<String> cards) {
        Set<String> names = new LinkedHashSet<String>();
        names.add(card.trim().toLowerCase(Locale.ROOT));
        Set<String> others = new LinkedHashSet<String>();
        for (String other : cards) {
            if (other != null && !other.equals(card)) {
                others.addAll(words(other));
            }
        }
        for (String word : words(card)) {
            if (word.length() >= 5 && !others.contains(word)) {
                names.add(word);
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(names));
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<String>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static double number(String digits) {
        return Double.parseDouble(digits.replace(',', '.'));
    }
}
