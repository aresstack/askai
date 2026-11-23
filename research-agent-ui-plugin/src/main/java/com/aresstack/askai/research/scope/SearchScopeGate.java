package com.aresstack.askai.research.scope;

import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator.AnchorVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SC1 Search-Control: the PURE acquisition filter over the existing fence geometry — no new
 * cosine logic, no second threshold family, no LLM. One batch of candidate texts is measured
 * against the SAME {@link ScopeFenceEvaluator} anchors and thresholds AP3 uses; the verdict is
 * deliberately EXTREMELY conservative:
 *
 * <pre>
 * LIKELY_OUT (canonical OUT side, CLEAR by definition)  → OUT   (hard reject)
 * LIKELY_IN / BOUNDARY / NOVEL                          → KEEP
 * no usable text                                        → UNCLASSIFIED (not visited, run continues)
 * </pre>
 *
 * A document is never rejected because a blacklist WORD appears somewhere — only when its
 * semantic content clearly sits on the canonical OUT side of the fence. Query relevance stays a
 * separate, orthogonal decision owned by the reranker/relevance model.
 */
public final class SearchScopeGate {

    public enum Verdict { KEEP, OUT, UNCLASSIFIED }

    /** One candidate: an opaque id (usually the URL) and its best available semantic text. */
    public static final class Item {
        public final String id;
        public final String text;

        public Item(String id, String text) {
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
        }
    }

    /**
     * One verdict; {@code nearestOutLabel} names the OUT post only for OUT (observability).
     * SC2a: {@code nearIn} is the IN side of the SAME reading ({@code hint == LIKELY_IN},
     * no new threshold, no new mathematics) — shadow measurement only, never a filter.
     */
    public static final class Decision {
        public final String id;
        public final Verdict verdict;
        public final String nearestOutLabel;
        public final boolean nearIn;
        public final String nearestInLabel;
        /**
         * The reading behind the verdict, with the similarity to the nearest IN and OUT post; {@code null} for
         * UNCLASSIFIED. The search hygiene logs it for a plan query and never decides by it (component 4).
         */
        public final ScopeFenceEvaluator.Reading reading;

        Decision(String id, Verdict verdict, String nearestOutLabel, boolean nearIn,
                 String nearestInLabel, ScopeFenceEvaluator.Reading reading) {
            this.id = id;
            this.verdict = verdict;
            this.nearestOutLabel = nearestOutLabel;
            this.nearIn = nearIn;
            this.nearestInLabel = nearestInLabel;
            this.reading = reading;
        }
    }

    private SearchScopeGate() {
    }

    /**
     * Judge one batch against one pinned snapshot. Item order is preserved. A broken embedding
     * batch is an INFRASTRUCTURE failure and throws — the caller ends the run typed and
     * fail-closed, never silently unfiltered.
     */
    public static List<Decision> evaluate(List<Item> items, List<AnchorVector> anchorVectors,
                                          Map<String, String> labelsById,
                                          ScopeSweepService.SweepEmbedder embedder,
                                          ScopeFenceEvaluator.Thresholds thresholds) {
        List<Item> usable = new ArrayList<Item>();
        for (Item item : items) {
            if (!item.text.trim().isEmpty()) {
                usable.add(item);
            }
        }
        List<float[]> vectors = null;
        if (!usable.isEmpty()) {
            try {
                List<String> texts = new ArrayList<String>();
                for (Item item : usable) {
                    texts.add(item.text.trim());
                }
                vectors = embedder.embed(texts);
            } catch (RuntimeException transport) {
                throw new IllegalStateException("scope-control embedding failed: " + transport);
            }
            if (vectors == null || vectors.size() != usable.size()) {
                throw new IllegalStateException("scope-control embedding returned "
                        + (vectors == null ? "null" : vectors.size() + " vectors")
                        + " for " + usable.size() + " texts");
            }
        }
        ScopeFenceEvaluator evaluator = new ScopeFenceEvaluator(anchorVectors);
        List<Decision> decisions = new ArrayList<Decision>();
        int next = 0;
        for (Item item : items) {
            if (item.text.trim().isEmpty()) {
                // Conservative: nothing to judge → never auto-visited, never a run abort.
                decisions.add(new Decision(item.id, Verdict.UNCLASSIFIED, null, false, null, null));
                continue;
            }
            ScopeFenceEvaluator.Reading reading =
                    evaluator.evaluate(vectors.get(next++), thresholds);
            if (reading.hint == ScopeFenceEvaluator.Hint.LIKELY_OUT) {
                String label = labelsById.get(reading.nearestOutAnchorId);
                decisions.add(new Decision(item.id, Verdict.OUT,
                        label == null || label.trim().isEmpty()
                                ? reading.nearestOutAnchorId : label, false, null, reading));
            } else if (reading.hint == ScopeFenceEvaluator.Hint.LIKELY_IN) {
                // SC2a shadow: the KEEP additionally reports its IN affinity — observation
                // only, the acquisition decision stays untouched.
                String inLabel = labelsById.get(reading.nearestInAnchorId);
                decisions.add(new Decision(item.id, Verdict.KEEP, null, true,
                        inLabel == null || inLabel.trim().isEmpty()
                                ? reading.nearestInAnchorId : inLabel, reading));
            } else {
                // BOUNDARY and NOVEL pass without an affinity claim — SC1 keeps everything
                // the user has not clearly ruled out; steering is SC2b, after measurement.
                decisions.add(new Decision(item.id, Verdict.KEEP, null, false, null, reading));
            }
        }
        return decisions;
    }
}
