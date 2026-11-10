package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2/3: the pinned search fence (Weidezaun) the analysis measures topics against: the SAME IN/OUT anchor vectors,
 * labels and thresholds the search scope gate uses ({@link ScopeFenceEvaluator}), plus the scope revision they come
 * from. It is a second, separate reading next to the concept alignment: a topic may be new to the concept and still
 * lie inside the fence. The anchors must be embedded in the same vector world as the passages (same model AND the
 * same document role, e.g. a prompted model's document prefix); the fence carries that world's fingerprint and gives
 * no reading for a centroid of another world, even when the dimensions agree. Immutable.
 */
public final class ScopeFence {

    private final List<ScopeFenceEvaluator.AnchorVector> anchors;
    private final Map<String, String> labelsById;
    private final ScopeFenceEvaluator.Thresholds thresholds;
    private final long revision;
    private final String embeddingFingerprint;

    public ScopeFence(List<ScopeFenceEvaluator.AnchorVector> anchors, Map<String, String> labelsById,
                      ScopeFenceEvaluator.Thresholds thresholds, long revision, String embeddingFingerprint) {
        if (thresholds == null) {
            throw new IllegalArgumentException("a fence needs its thresholds");
        }
        this.anchors = Collections.unmodifiableList(new ArrayList<ScopeFenceEvaluator.AnchorVector>(
                anchors == null ? Collections.<ScopeFenceEvaluator.AnchorVector>emptyList() : anchors));
        this.labelsById = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                labelsById == null ? Collections.<String, String>emptyMap() : labelsById));
        this.thresholds = thresholds;
        this.revision = revision;
        this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
    }

    public List<ScopeFenceEvaluator.AnchorVector> getAnchors() {
        return anchors;
    }

    public ScopeFenceEvaluator.Thresholds getThresholds() {
        return thresholds;
    }

    /** The scope draft revision the anchors come from (part of a snapshot's identity). */
    public long getRevision() {
        return revision;
    }

    /** The fingerprint of the vector world the anchors were embedded in (empty: unknown, no reading). */
    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    /** The label of an anchor, or its id when it has none. */
    public String label(String anchorId) {
        if (anchorId == null) {
            return "";
        }
        String label = labelsById.get(anchorId);
        return label == null || label.trim().isEmpty() ? anchorId : label;
    }

    /**
     * Where a topic centroid lies relative to the fence; UNCLASSIFIED without anchors or without comparable vectors
     * (another fingerprint or dimension).
     */
    ScopeAlignment align(EmbeddingPort.EmbeddingVector centroid) {
        if (anchors.isEmpty() || centroid == null || centroid.getDimension() == 0
                || embeddingFingerprint.isEmpty() || !embeddingFingerprint.equals(centroid.getModelFingerprint())) {
            // anchors of another vector world (model or document role): no reading, never a guess
            return ScopeAlignment.UNKNOWN;
        }
        ScopeFenceEvaluator.Reading reading;
        try {
            reading = new ScopeFenceEvaluator(anchors).evaluate(centroid.getValues(), thresholds);
        } catch (IllegalArgumentException otherDimension) {
            return ScopeAlignment.UNKNOWN;
        }
        ScopeAlignment.Verdict verdict = reading.hint == ScopeFenceEvaluator.Hint.LIKELY_IN
                ? ScopeAlignment.Verdict.IN
                : reading.hint == ScopeFenceEvaluator.Hint.LIKELY_OUT ? ScopeAlignment.Verdict.OUT
                : ScopeAlignment.Verdict.UNCLASSIFIED;
        return new ScopeAlignment(verdict, reading.hint,
                reading.nearestInAnchorId == null ? "" : label(reading.nearestInAnchorId),
                reading.nearestInSimilarity,
                reading.nearestOutAnchorId == null ? "" : label(reading.nearestOutAnchorId),
                reading.nearestOutSimilarity);
    }
}
