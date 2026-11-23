package com.aresstack.askai.research.outline.synthesis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repair plan step 5a: an explicit share belongs to a concept card, not to one chapter. When several chapters rest
 * on the same card, they split its share by their weights, so the card's chapters together hold the share the user
 * gave. Stateless.
 */
final class ChapterShares {

    private ChapterShares() {
    }

    /**
     * @param shares  per chapter the share of its card ({@code NaN} = none), as read from the context
     * @param cards   per chapter its concept card (anchor id)
     * @param weights per chapter its weight (distinct material)
     * @return per chapter its part of the card's share ({@code NaN} where the card has none)
     */
    static double[] splitAmongSiblings(double[] shares, List<String> cards, long[] weights) {
        double[] split = shares.clone();
        Map<String, Long> cardWeight = new LinkedHashMap<String, Long>();
        for (int i = 0; i < shares.length; i++) {
            if (!Double.isNaN(shares[i])) {
                String card = cards.get(i);
                Long sum = cardWeight.get(card);
                cardWeight.put(card, (sum == null ? 0L : sum) + Math.max(1, weights[i]));
            }
        }
        for (int i = 0; i < shares.length; i++) {
            if (!Double.isNaN(shares[i])) {
                split[i] = shares[i] * Math.max(1, weights[i]) / cardWeight.get(cards.get(i));
            }
        }
        return split;
    }
}
