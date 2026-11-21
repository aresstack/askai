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
 * Repair plan step 5a: the user's explicit weight of a main theme, written into the mission ("Recht 40 %",
 * "Technik: 20 Prozent", "30 % für Implementierung"). A theme is a first-level card of the concept; the mission names
 * it by its full name or by a word of five or more letters that only this card's name holds. A percentage belongs to
 * the nearest card name in the same clause; a percentage two cards claim goes to the nearer one. Shares above 100 %
 * in sum are scaled down later ({@code SectionBudget}). Stateless.
 */
public final class MissionWeights {

    private static final String PERCENT = "(\\d{1,2}(?:[.,]\\d)?|100)\\s*(?:%|prozent\\b|percent\\b)";
    private static final int GAP = 40;

    private MissionWeights() {
    }

    /** Per card name (as given) its share in (0, 1]; cards without a stated weight are absent. */
    public static Map<String, Double> read(String mission, List<String> cards) {
        Map<String, Double> shares = new LinkedHashMap<String, Double>();
        if (mission == null || mission.trim().isEmpty() || cards == null || cards.isEmpty()) {
            return shares;
        }
        String text = mission.toLowerCase(Locale.ROOT);
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
                    claim(card, after.start(1), after.start(1) - (after.start() + name.length()),
                            number(after.group(1)), owner, distance, value);
                }
                Matcher before = Pattern.compile("(?<![\\d.,])" + PERCENT + "[^.;\\n%\\d]{0," + GAP + "}?(?<![\\p{L}])"
                        + quoted + "(?![\\p{L}])").matcher(text);
                while (before.find()) {
                    int gap = before.end() - name.length() - before.end(1);
                    claim(card, before.start(1), gap, number(before.group(1)), owner, distance, value);
                }
            }
        }
        for (Map.Entry<Integer, String> claimed : owner.entrySet()) {
            double share = value.get(claimed.getKey()) / 100.0;
            if (share > 0 && share <= 1 && !shares.containsKey(claimed.getValue())) {
                shares.put(claimed.getValue(), share);
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
