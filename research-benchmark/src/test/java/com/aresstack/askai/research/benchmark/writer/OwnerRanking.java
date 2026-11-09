package com.aresstack.askai.research.benchmark.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the owner's blind ranking back: from the filled rating sheet (lines {@code Ranking: B, A, C} under each
 * {@code ## item} heading) and the key file ({@code item: A=model, B=model, ...}), resolved to models best first.
 */
public final class OwnerRanking {

    private OwnerRanking() {
    }

    public static Map<String, List<String>> resolve(String filledSheet, String key) {
        Map<String, BlindAssignment> assignments = parseKey(key);
        Map<String, List<String>> resolved = new LinkedHashMap<String, List<String>>();
        String item = null;
        for (String raw : filledSheet.replace("\r\n", "\n").split("\n")) {
            String line = raw.trim();
            if (line.startsWith("## ")) {
                item = line.substring(3).trim();
            } else if (line.startsWith("Ranking:") && item != null) {
                BlindAssignment assignment = assignments.get(item);
                if (assignment == null) {
                    throw new IllegalArgumentException("no key for " + item);
                }
                List<String> letters = new ArrayList<String>();
                for (String letter : line.substring("Ranking:".length()).split("[,\\s>]+")) {
                    if (!letter.trim().isEmpty()) {
                        letters.add(letter.trim().toUpperCase(java.util.Locale.ROOT));
                    }
                }
                resolved.put(item, assignment.resolve(letters));
            }
        }
        if (resolved.size() != assignments.size()) {
            throw new IllegalArgumentException("ranked " + resolved.keySet() + ", expected " + assignments.keySet());
        }
        return resolved;
    }

    static Map<String, BlindAssignment> parseKey(String key) {
        Map<String, BlindAssignment> result = new LinkedHashMap<String, BlindAssignment>();
        for (String raw : key.replace("\r\n", "\n").split("\n")) {
            int colon = raw.lastIndexOf(':', raw.indexOf('='));
            if (raw.trim().isEmpty() || colon < 0) {
                continue;
            }
            String item = raw.substring(0, colon).trim();
            Map<String, String> letters = new LinkedHashMap<String, String>();
            for (String pair : raw.substring(colon + 1).split(",")) {
                String[] kv = pair.trim().split("=", 2);
                letters.put(kv[0].trim(), kv[1].trim());
            }
            result.put(item, BlindAssignment.of(item, letters));
        }
        return result;
    }
}
