package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.OutlineTitles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The title of a section the label call could not name: its most frequent key terms (capitalised content words,
 * the nouns of a German text; any content word otherwise), never a passage sentence. Words of the chapter title are
 * left out, so the title names the section's part of the chapter. Live retest of #243: 19 sections kept raw passage
 * sentences cut at 80 characters.
 */
final class KeyTermTitle {

    private KeyTermTitle() {
    }

    /** "Unterweisung, Sensorweste und Akkulaufzeit"; empty when the passages have no key term. */
    static String of(List<String> passages, String chapterTitle, int terms) {
        Set<String> chapterStems = OutlineTitles.stems(chapterTitle);
        Map<String, Integer> count = new HashMap<String, Integer>();
        Map<String, String> form = new LinkedHashMap<String, String>();
        for (String passage : passages) {
            if (passage == null) {
                continue;
            }
            // The first word of a sentence is capitalised in any case ("Diese", "Wenn") and is no key term.
            for (String sentence : passage.replace("\u00AD", "").split("[.!?:;]+\\s+")) {
            String[] words = sentence.trim().split("[^\\p{L}\\p{N}-]+");
            for (int w = 1; w < words.length; w++) {
                String word = words[w].replaceAll("^-+|-+$", "");
                if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))
                        || word.equals(word.toUpperCase(java.util.Locale.ROOT))) {
                    continue;
                }
                Set<String> stems = OutlineTitles.stems(word);
                if (stems.size() != 1) {
                    continue;
                }
                String stem = stems.iterator().next();
                if (chapterStems.contains(stem)) {
                    continue;
                }
                Integer previous = count.get(stem);
                count.put(stem, previous == null ? 1 : previous + 1);
                if (!form.containsKey(stem)) {
                    form.put(stem, word);
                }
            }
            }
        }
        List<String> ranked = new ArrayList<String>(form.keySet());
        java.util.Collections.sort(ranked, (a, b) -> count.get(b) - count.get(a));
        List<String> chosen = new ArrayList<String>();
        for (String stem : ranked) {
            if (chosen.size() == terms) {
                break;
            }
            chosen.add(form.get(stem));
        }
        if (chosen.isEmpty()) {
            return "";
        }
        if (chosen.size() == 1) {
            return chosen.get(0);
        }
        return String.join(", ", chosen.subList(0, chosen.size() - 1)) + " und " + chosen.get(chosen.size() - 1);
    }
}
