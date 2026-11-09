package com.aresstack.askai.research.benchmark.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The shuffled, anonymous labels of one rated item (a section or the sample chapter): each model gets a letter,
 * drawn per item from a seeded shuffle so the owner cannot carry a letter from one item to the next. The rating
 * sheet shows only letters; the key (letter to model) is written to a separate file and read back for the decision.
 */
public final class BlindAssignment {

    private final String item;
    private final Map<String, String> letterToModel;

    private BlindAssignment(String item, Map<String, String> letterToModel) {
        this.item = item;
        this.letterToModel = Collections.unmodifiableMap(letterToModel);
    }

    public static BlindAssignment shuffle(String item, List<String> models, Random random) {
        List<String> order = new ArrayList<String>(models);
        Collections.shuffle(order, random);
        Map<String, String> key = new LinkedHashMap<String, String>();
        for (int i = 0; i < order.size(); i++) {
            key.put(String.valueOf((char) ('A' + i)), order.get(i));
        }
        return new BlindAssignment(item, key);
    }

    public static BlindAssignment of(String item, Map<String, String> letterToModel) {
        return new BlindAssignment(item, new LinkedHashMap<String, String>(letterToModel));
    }

    public String getItem() {
        return item;
    }

    public Map<String, String> getLetterToModel() {
        return letterToModel;
    }

    public String letterOf(String model) {
        for (Map.Entry<String, String> entry : letterToModel.entrySet()) {
            if (entry.getValue().equals(model)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("model not in this item: " + model);
    }

    /** Turns the owner's ranking (letters, best first) into models, best first. */
    public List<String> resolve(List<String> lettersBestFirst) {
        if (lettersBestFirst.size() != letterToModel.size()
                || !letterToModel.keySet().containsAll(lettersBestFirst)) {
            throw new IllegalArgumentException("ranking of " + item + " must name each of " + letterToModel.keySet()
                    + " once: " + lettersBestFirst);
        }
        List<String> models = new ArrayList<String>();
        for (String letter : lettersBestFirst) {
            models.add(letterToModel.get(letter));
        }
        return models;
    }
}
