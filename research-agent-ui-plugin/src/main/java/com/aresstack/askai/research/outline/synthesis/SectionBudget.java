package com.aresstack.askai.research.outline.synthesis;

/**
 * Repair plan step 5a: how a book length becomes chapter and section words. Two rules, one arithmetic:
 * <ul>
 * <li>every part gets the floor first, the rest of the budget follows the weights (so no part falls below the floor
 * while the budget allows it);</li>
 * <li>a part with an explicit share gets that share of the whole; the parts without one share the rest by weight.</li>
 * </ul>
 * Before step 5a the words followed the distinct passages alone, without floor: the 17 Nov outline had sections of
 * 210 to 300 words. Stateless.
 */
final class SectionBudget {

    private SectionBudget() {
    }

    /**
     * {@code total} spread over parts with {@code weights}, each part at least {@code floor} words while the budget
     * allows it; {@code shares[i]} in (0, 1] fixes part i's share of the whole ({@code NaN} or {@code null} = none).
     * Results are rounded to the length step.
     */
    static int[] spread(int total, long[] weights, double[] shares, int floor, OutlineLengthSettings length) {
        int n = weights.length;
        int[] words = new int[n];
        if (n == 0 || total <= 0) {
            return words;
        }
        double fixed = 0;
        int open = 0;
        for (int i = 0; i < n; i++) {
            if (hasShare(shares, i)) {
                fixed += shares[i];
            } else {
                open++;
            }
        }
        // Shares beyond the whole are scaled down; parts without a share then get nothing beyond what is left.
        double scale = fixed > 1.0 ? 1.0 / fixed : 1.0;
        long rest = total;
        for (int i = 0; i < n; i++) {
            if (hasShare(shares, i)) {
                words[i] = length.round(Math.round(total * shares[i] * scale));
                rest -= words[i];
            }
        }
        rest = Math.max(0, rest);
        if (open == 0) {
            return words;
        }
        long[] openWeights = new long[open];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (!hasShare(shares, i)) {
                openWeights[k++] = Math.max(1, weights[i]);
            }
        }
        int[] openWords = withFloor(rest, openWeights, floor, length);
        k = 0;
        for (int i = 0; i < n; i++) {
            if (!hasShare(shares, i)) {
                words[i] = openWords[k++];
            }
        }
        return words;
    }

    /** {@code total} by weight, each at least {@code floor} when {@code total} holds a floor for every part. */
    static int[] withFloor(long total, long[] weights, int floor, OutlineLengthSettings length) {
        int n = weights.length;
        int[] words = new int[n];
        if (n == 0 || total <= 0) {
            return words;
        }
        long base = (long) floor * n <= total ? floor : 0;
        long rest = total - base * n;
        long sum = 0;
        for (long weight : weights) {
            sum += Math.max(1, weight);
        }
        for (int i = 0; i < n; i++) {
            long share = Math.round((double) rest * Math.max(1, weights[i]) / sum);
            // Rounding to the step must not take a part below the floor.
            int rounded = length.round(base + share);
            words[i] = (int) (rounded < base ? base : rounded);
        }
        // The rounding residue goes to the largest part, so the parts add up to the total.
        long residue = total;
        int largest = 0;
        for (int i = 0; i < n; i++) {
            residue -= words[i];
            if (words[i] > words[largest]) {
                largest = i;
            }
        }
        if (words[largest] + residue >= Math.max(base, 1)) {
            words[largest] += (int) residue;
        }
        return words;
    }

    private static boolean hasShare(double[] shares, int i) {
        return shares != null && i < shares.length && !Double.isNaN(shares[i]) && shares[i] > 0;
    }
}
