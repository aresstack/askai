package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.OutlineTitles;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The title of a section the label call could not name: its most frequent key terms (capitalised content words,
 * the nouns of a German text), never a passage sentence. Words of the chapter title and the polite pronouns are left
 * out; two capitalised words that always stand together stay one term ("Erste Hilfe"). Live retests of #243 and #245:
 * raw sentences as titles, then "Dokumentation, Erste und Hilfe" and "Datenschutz, Unternehmen und Ihnen".
 */
final class KeyTermTitle {

    /** Capitalised pronouns of polite address, never a key term. */
    private static final Set<String> PRONOUNS = new HashSet<String>(Arrays.asList("sie", "ihnen", "ihr", "ihre",
            "ihrer", "ihren", "ihrem", "ihres", "euch", "dich", "dir", "dein", "deine", "unser", "unsere"));

    private KeyTermTitle() {
    }

    /** "Unterweisung, Sensorweste und Akkulaufzeit"; empty when the passages have no key term. */
    static String of(List<String> passages, String chapterTitle, int terms) {
        Set<String> chapterStems = OutlineTitles.stems(chapterTitle);
        Map<String, Integer> count = new HashMap<String, Integer>();
        Map<String, String> form = new LinkedHashMap<String, String>();
        Map<String, Integer> pairs = new HashMap<String, Integer>();
        Map<String, String> pairForm = new HashMap<String, String>();
        for (String passage : passages) {
            if (passage == null) {
                continue;
            }
            // The first word of a sentence is capitalised in any case ("Diese", "Wenn") and is no key term.
            for (String sentence : passage.replace("­", "").split("[.!?:;]+\\s+")) {
                String[] words = sentence.trim().split("[^\\p{L}\\p{N}-]+");
                String previous = null;
                for (int w = 1; w < words.length; w++) {
                    String stem = stem(words[w], chapterStems);
                    if (stem == null) {
                        previous = null;
                        continue;
                    }
                    String word = words[w].replaceAll("^-+|-+$", "");
                    increment(count, stem);
                    if (!form.containsKey(stem)) {
                        form.put(stem, word);
                    }
                    if (previous != null) {
                        String pair = previous + " " + stem;
                        increment(pairs, pair);
                        if (!pairForm.containsKey(pair)) {
                            pairForm.put(pair, words[w - 1].replaceAll("^-+|-+$", "") + " " + word);
                        }
                    }
                    previous = stem;
                }
            }
        }
        List<String> ranked = new ArrayList<String>(form.keySet());
        java.util.Collections.sort(ranked, (a, b) -> count.get(b) - count.get(a));
        List<String> chosen = new ArrayList<String>();
        Set<String> used = new HashSet<String>();
        for (String stem : ranked) {
            if (chosen.size() == terms) {
                break;
            }
            if (used.contains(stem)) {
                continue;
            }
            String term = form.get(stem);
            for (Map.Entry<String, Integer> pair : pairs.entrySet()) {
                String[] parts = pair.getKey().split(" ");
                if ((parts[0].equals(stem) || parts[1].equals(stem))
                        && pair.getValue() >= Math.min(count.get(parts[0]), count.get(parts[1]))) {
                    term = pairForm.get(pair.getKey());
                    used.add(parts[0]);
                    used.add(parts[1]);
                    break;
                }
            }
            used.add(stem);
            chosen.add(term);
        }
        if (chosen.isEmpty()) {
            return "";
        }
        if (chosen.size() == 1) {
            return chosen.get(0);
        }
        return String.join(", ", chosen.subList(0, chosen.size() - 1)) + " und " + chosen.get(chosen.size() - 1);
    }

    /** The stem of a key-term candidate, or null when the word is none. */
    private static String stem(String raw, Set<String> chapterStems) {
        String word = raw.replaceAll("^-+|-+$", "");
        if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))
                || word.equals(word.toUpperCase(Locale.ROOT)) || PRONOUNS.contains(word.toLowerCase(Locale.ROOT))) {
            return null;
        }
        Set<String> stems = OutlineTitles.stems(word);
        if (stems.size() != 1) {
            return null;
        }
        String stem = stems.iterator().next();
        return chapterStems.contains(stem) ? null : stem;
    }

    private static void increment(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        map.put(key, value == null ? 1 : value + 1);
    }
}
