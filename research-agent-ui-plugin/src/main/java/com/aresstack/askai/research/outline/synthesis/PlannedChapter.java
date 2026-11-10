package com.aresstack.askai.research.outline.synthesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3b: one planned chapter: title, purpose, the material keys (topics or sub-topics) and concept requirement
 * keys it covers, and its sections (empty until the section step ran). Immutable.
 */
public final class PlannedChapter {

    private final String title;
    private final String purpose;
    private final List<String> materialKeys;
    private final List<String> conceptKeys;
    private final List<PlannedSection> sections;
    private final boolean untitled;

    public PlannedChapter(String title, String purpose, List<String> materialKeys, List<String> conceptKeys,
                          List<PlannedSection> sections, boolean untitled) {
        this.title = title == null ? "" : title.trim();
        this.purpose = purpose == null ? "" : purpose.trim();
        this.materialKeys = copy(materialKeys);
        this.conceptKeys = copy(conceptKeys);
        this.sections = Collections.unmodifiableList(new ArrayList<PlannedSection>(
                sections == null ? Collections.<PlannedSection>emptyList() : sections));
        this.untitled = untitled;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
    }

    public String getTitle() {
        return title;
    }

    public String getPurpose() {
        return purpose;
    }

    public List<String> getMaterialKeys() {
        return materialKeys;
    }

    public List<String> getConceptKeys() {
        return conceptKeys;
    }

    public List<PlannedSection> getSections() {
        return sections;
    }

    public boolean isUntitled() {
        return untitled;
    }

    public PlannedChapter withSections(List<PlannedSection> newSections) {
        return new PlannedChapter(title, purpose, materialKeys, conceptKeys, newSections, untitled);
    }

    /** Whether both chapters name the same material (sections can then be kept on a revision). */
    boolean sameMaterial(PlannedChapter other) {
        return new java.util.TreeSet<String>(materialKeys).equals(new java.util.TreeSet<String>(other.materialKeys));
    }
}
