package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;

/**
 * Phase 2/3: where a topic lies relative to the search fence (Weidezaun), read with the search scope gate's anchors
 * and thresholds: IN (clearly on the canonical IN side), OUT (clearly on the OUT side) or UNCLASSIFIED (near the
 * fence, far from every post, or no fence). Kept apart from the concept alignment: a NOVEL topic is not OUT by
 * itself. Immutable.
 */
public final class ScopeAlignment {

    public enum Verdict { IN, OUT, UNCLASSIFIED }

    /** No fence, or anchors of another vector world. */
    public static final ScopeAlignment UNKNOWN = new ScopeAlignment(Verdict.UNCLASSIFIED, null, "", 0, "", 0);

    private final Verdict verdict;
    private final ScopeFenceEvaluator.Hint hint;
    private final String nearestInLabel;
    private final double nearestInSimilarity;
    private final String nearestOutLabel;
    private final double nearestOutSimilarity;

    public ScopeAlignment(Verdict verdict, ScopeFenceEvaluator.Hint hint, String nearestInLabel,
                          double nearestInSimilarity, String nearestOutLabel, double nearestOutSimilarity) {
        this.verdict = verdict == null ? Verdict.UNCLASSIFIED : verdict;
        this.hint = hint;
        this.nearestInLabel = nearestInLabel == null ? "" : nearestInLabel;
        this.nearestInSimilarity = nearestInSimilarity;
        this.nearestOutLabel = nearestOutLabel == null ? "" : nearestOutLabel;
        this.nearestOutSimilarity = nearestOutSimilarity;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    /** The fence evaluator's reading (LIKELY_IN, LIKELY_OUT, BOUNDARY, NOVEL); {@code null} without a reading. */
    public ScopeFenceEvaluator.Hint getHint() {
        return hint;
    }

    public String getNearestInLabel() {
        return nearestInLabel;
    }

    public double getNearestInSimilarity() {
        return nearestInSimilarity;
    }

    public String getNearestOutLabel() {
        return nearestOutLabel;
    }

    public double getNearestOutSimilarity() {
        return nearestOutSimilarity;
    }
}
