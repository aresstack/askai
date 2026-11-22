package com.aresstack.askai.research.sectionplan;

/**
 * How the book's words become chapter and section words: the rules of step 5a of the repair plan (PR #256,
 * {@code SectionBudget}), here without the length step so the parts add up exactly.
 * <ul>
 * <li>every part gets the floor first, while the budget allows it; the rest follows the weights;</li>
 * <li>a part with an explicit share gets that share of the whole; the parts without one share the rest by weight,
 * each still at least the floor (the explicit shares shrink to make room for it).</li>
 * </ul>
 * Rounding uses the largest remainders, so the parts always add up to the total. Stateless.
 */
final class WordBudget {

    private WordBudget() {
    }

    /**
     * {@code total} spread over parts with {@code weights}, each part at least {@code floor} words while the budget
     * allows it; {@code shares[i]} in (0, 1] fixes part i's share of the whole ({@code NaN} or 0 = none).
     */
    static int[] spread(int total, long[] weights, double[] shares, int floor) {
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
        if (open == n) {
            return withFloor(total, weights, floor);
        }
        double[] exact = new double[n];
        if (open == 0) {
            // Only explicit shares: they fill the whole, so 0.5/0.3 means five to three of the book.
            for (int i = 0; i < n; i++) {
                exact[i] = total * shares[i] / fixed;
            }
            return largestRemainder(total, exact);
        }
        // The explicit shares leave every open part its floor: they shrink to what remains after those floors, and
        // to the whole when they add up to more.
        double limit = Math.max(0.0, 1.0 - (double) floor * open / total);
        double scale = fixed > limit ? limit / fixed : 1.0;
        double sharedWords = 0;
        for (int i = 0; i < n; i++) {
            if (hasShare(shares, i)) {
                exact[i] = total * shares[i] * scale;
                sharedWords += exact[i];
            }
        }
        long openTotal = Math.max(0, Math.round(total - sharedWords));
        long[] openWeights = new long[open];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (!hasShare(shares, i)) {
                openWeights[k++] = weights[i];
            }
        }
        int[] openWords = withFloor(openTotal, openWeights, floor);
        int[] sharedRounded = largestRemainder(total - openTotal, onlyShares(exact, shares));
        k = 0;
        for (int i = 0; i < n; i++) {
            words[i] = hasShare(shares, i) ? sharedRounded[i] : openWords[k++];
        }
        return words;
    }

    /** {@code total} by weight, each part at least {@code floor} when {@code total} holds a floor for every part. */
    static int[] withFloor(long total, long[] weights, int floor) {
        int n = weights.length;
        if (n == 0 || total <= 0) {
            return new int[n];
        }
        long base = (long) floor * n <= total ? floor : 0;
        long rest = total - base * n;
        long sum = 0;
        for (long weight : weights) {
            sum += Math.max(1, weight);
        }
        double[] exact = new double[n];
        for (int i = 0; i < n; i++) {
            exact[i] = (double) rest * Math.max(1, weights[i]) / sum;
        }
        int[] words = largestRemainder(rest, exact);
        for (int i = 0; i < n; i++) {
            words[i] += (int) base;
        }
        return words;
    }

    /** Whole numbers that add up to {@code total}: every part its floor, the rest to the largest remainders. */
    static int[] largestRemainder(long total, double[] exact) {
        int n = exact.length;
        int[] whole = new int[n];
        long given = 0;
        for (int i = 0; i < n; i++) {
            whole[i] = (int) Math.floor(exact[i]);
            given += whole[i];
        }
        long left = total - given;
        boolean[] taken = new boolean[n];
        while (left > 0 && n > 0) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!taken[i] && exact[i] > 0 && (best < 0 || exact[i] - whole[i] > exact[best] - whole[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            whole[best]++;
            taken[best] = true;
            left--;
            if (left > 0 && allTaken(taken, exact)) {
                java.util.Arrays.fill(taken, false);
            }
        }
        return whole;
    }

    private static boolean allTaken(boolean[] taken, double[] exact) {
        for (int i = 0; i < taken.length; i++) {
            if (!taken[i] && exact[i] > 0) {
                return false;
            }
        }
        return true;
    }

    private static double[] onlyShares(double[] exact, double[] shares) {
        double[] out = new double[exact.length];
        for (int i = 0; i < exact.length; i++) {
            out[i] = hasShare(shares, i) ? exact[i] : 0;
        }
        return out;
    }

    static boolean hasShare(double[] shares, int i) {
        return shares != null && i < shares.length && !Double.isNaN(shares[i]) && shares[i] > 0;
    }
}
