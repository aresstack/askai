package com.aresstack.askai.research.outline.brief;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Splits one chapter's passages into exactly {@code k} sections (plan PR 24, "Regenerate chapter" with a number
 * of sections): spherical k-means over the stored vectors with a deterministic farthest-first start, so the same
 * input always gives the same groups. Groups come back ordered by their first passage id; each group lists its
 * passages nearest to its centre first.
 */
final class SectionReclusterer {

    static final int MAX_ITERATIONS = 50;

    private SectionReclusterer() {
    }

    static List<List<String>> split(Map<String, float[]> vectors, int k) {
        List<String> ids = new ArrayList<String>(new TreeMap<String, float[]>(vectors).keySet());
        int n = ids.size();
        if (k < 1 || n < k) {
            throw new IllegalArgumentException("The chapter has " + n + " passage" + (n == 1 ? "" : "s")
                    + "; it cannot be split into " + k + " sections.");
        }
        float[][] unit = new float[n][];
        for (int i = 0; i < n; i++) {
            unit[i] = normalised(vectors.get(ids.get(i)));
            if (i > 0 && unit[i].length != unit[0].length) {
                throw new IllegalArgumentException("vectors of different dimensions must never be compared");
            }
        }
        double[][] centres = start(unit, k);
        int[] assignment = new int[n];
        Arrays.fill(assignment, -1);
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            boolean changed = assign(unit, centres, assignment);
            refillEmpty(unit, centres, assignment, k);
            centres = centres(unit, assignment, k, unit[0].length);
            if (!changed && iteration > 0) {
                break;
            }
        }
        List<List<Integer>> groups = new ArrayList<List<Integer>>();
        for (int c = 0; c < k; c++) {
            groups.add(new ArrayList<Integer>());
        }
        for (int i = 0; i < n; i++) {
            groups.get(assignment[i]).add(i);
        }
        Collections.sort(groups, new Comparator<List<Integer>>() {
            @Override
            public int compare(List<Integer> a, List<Integer> b) {
                return Integer.compare(a.get(0), b.get(0));
            }
        });
        List<List<String>> result = new ArrayList<List<String>>();
        for (List<Integer> group : groups) {
            final double[] centre = mean(unit, group, unit[0].length);
            final float[][] u = unit;
            List<Integer> ordered = new ArrayList<Integer>(group);
            Collections.sort(ordered, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    int bySimilarity = Double.compare(dot(u[b], centre), dot(u[a], centre));
                    return bySimilarity != 0 ? bySimilarity : Integer.compare(a, b);
                }
            });
            List<String> members = new ArrayList<String>();
            for (Integer index : ordered) {
                members.add(ids.get(index));
            }
            result.add(members);
        }
        return result;
    }

    /** First centre: the passage nearest the mean; then always the passage farthest from every chosen centre. */
    private static double[][] start(float[][] unit, int k) {
        int n = unit.length;
        List<Integer> all = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        double[] mean = mean(unit, all, unit[0].length);
        int first = 0;
        for (int i = 1; i < n; i++) {
            if (dot(unit[i], mean) > dot(unit[first], mean)) {
                first = i;
            }
        }
        double[][] centres = new double[k][];
        centres[0] = toDouble(unit[first]);
        double[] nearest = new double[n];
        Arrays.fill(nearest, Double.NEGATIVE_INFINITY);
        for (int c = 1; c < k; c++) {
            int farthest = -1;
            for (int i = 0; i < n; i++) {
                nearest[i] = Math.max(nearest[i], dot(unit[i], centres[c - 1]));
                if (farthest < 0 || nearest[i] < nearest[farthest]) {
                    farthest = i;
                }
            }
            centres[c] = toDouble(unit[farthest]);
        }
        return centres;
    }

    private static boolean assign(float[][] unit, double[][] centres, int[] assignment) {
        boolean changed = false;
        for (int i = 0; i < unit.length; i++) {
            int best = 0;
            double bestSimilarity = dot(unit[i], centres[0]);
            for (int c = 1; c < centres.length; c++) {
                double similarity = dot(unit[i], centres[c]);
                if (similarity > bestSimilarity) {
                    best = c;
                    bestSimilarity = similarity;
                }
            }
            if (assignment[i] != best) {
                assignment[i] = best;
                changed = true;
            }
        }
        return changed;
    }

    /** An empty group takes the passage that fits its own group worst (from a group with more than one). */
    private static void refillEmpty(float[][] unit, double[][] centres, int[] assignment, int k) {
        for (int c = 0; c < k; c++) {
            int[] sizes = new int[k];
            for (int a : assignment) {
                sizes[a]++;
            }
            if (sizes[c] > 0) {
                continue;
            }
            int worst = -1;
            double worstSimilarity = Double.POSITIVE_INFINITY;
            for (int i = 0; i < unit.length; i++) {
                if (sizes[assignment[i]] < 2) {
                    continue;
                }
                double similarity = dot(unit[i], centres[assignment[i]]);
                if (similarity < worstSimilarity) {
                    worst = i;
                    worstSimilarity = similarity;
                }
            }
            assignment[worst] = c;
            centres[c] = toDouble(unit[worst]);
        }
    }

    private static double[][] centres(float[][] unit, int[] assignment, int k, int dimension) {
        double[][] centres = new double[k][dimension];
        for (int i = 0; i < unit.length; i++) {
            for (int d = 0; d < dimension; d++) {
                centres[assignment[i]][d] += unit[i][d];
            }
        }
        for (double[] centre : centres) {
            normalise(centre);
        }
        return centres;
    }

    private static double[] mean(float[][] unit, List<Integer> members, int dimension) {
        double[] mean = new double[dimension];
        for (Integer index : members) {
            for (int d = 0; d < dimension; d++) {
                mean[d] += unit[index][d];
            }
        }
        normalise(mean);
        return mean;
    }

    private static float[] normalised(float[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("a passage has no stored vector");
        }
        double norm = 0;
        for (float value : raw) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        float[] unit = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            unit[i] = norm == 0 ? 0f : (float) (raw[i] / norm);
        }
        return unit;
    }

    private static void normalise(double[] vector) {
        double norm = 0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    private static double[] toDouble(float[] vector) {
        double[] copy = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            copy[i] = vector[i];
        }
        return copy;
    }

    private static double dot(float[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
