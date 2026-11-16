package com.aresstack.askai.research.outline.synthesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3b: the chapter hierarchy the synthesis produced, in keys of its {@link SynthesisBrief}: chapters in reading
 * order with their sections, and the concept requirements listed as gaps (no evidence). {@code fromAnalysisOnly}
 * marks the plan code built when the model gave no valid plan. Immutable.
 */
public final class OutlinePlan {

    private final List<PlannedChapter> chapters;
    private final List<String> gapKeys;
    private final boolean fromAnalysisOnly;

    public OutlinePlan(List<PlannedChapter> chapters, List<String> gapKeys, boolean fromAnalysisOnly) {
        this.chapters = Collections.unmodifiableList(new ArrayList<PlannedChapter>(
                chapters == null ? Collections.<PlannedChapter>emptyList() : chapters));
        this.gapKeys = Collections.unmodifiableList(new ArrayList<String>(
                gapKeys == null ? Collections.<String>emptyList() : gapKeys));
        this.fromAnalysisOnly = fromAnalysisOnly;
    }

    public List<PlannedChapter> getChapters() {
        return chapters;
    }

    public List<String> getGapKeys() {
        return gapKeys;
    }

    public boolean isFromAnalysisOnly() {
        return fromAnalysisOnly;
    }

    public OutlinePlan withChapters(List<PlannedChapter> newChapters) {
        return new OutlinePlan(newChapters, gapKeys, fromAnalysisOnly);
    }

    public OutlinePlan withGapKeys(List<String> newGapKeys) {
        return new OutlinePlan(chapters, newGapKeys, fromAnalysisOnly);
    }

    /** The plan as compact lines for a revision prompt and the log. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (PlannedChapter chapter : chapters) {
            index++;
            text.append(index).append(". ").append(chapter.getTitle()).append(" [material ")
                    .append(String.join(", ", chapter.getMaterialKeys())).append(']');
            if (!chapter.getConceptKeys().isEmpty()) {
                text.append(" [concept ").append(String.join(", ", chapter.getConceptKeys())).append(']');
            }
            text.append('\n');
            for (PlannedSection section : chapter.getSections()) {
                text.append("   - ").append(section.getTitle()).append(" [")
                        .append(String.join(", ", section.getMaterialKeys())).append("]\n");
            }
        }
        if (!gapKeys.isEmpty()) {
            text.append("Gaps: ").append(String.join(", ", gapKeys)).append('\n');
        }
        return text.toString();
    }
}
