package com.aresstack.askai.research.outline.synthesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3b: one section of a planned chapter: title, purpose and the material keys (topics or sub-topics of its
 * chapter) it draws on. {@code untitled} marks a title code had to make (the label call names it later). Immutable.
 */
public final class PlannedSection {

    private final String title;
    private final String purpose;
    private final List<String> materialKeys;
    private final boolean untitled;

    public PlannedSection(String title, String purpose, List<String> materialKeys, boolean untitled) {
        this.title = title == null ? "" : title.trim();
        this.purpose = purpose == null ? "" : purpose.trim();
        this.materialKeys = Collections.unmodifiableList(new ArrayList<String>(
                materialKeys == null ? Collections.<String>emptyList() : materialKeys));
        this.untitled = untitled;
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

    public boolean isUntitled() {
        return untitled;
    }

    public PlannedSection withMaterialKeys(List<String> keys) {
        return new PlannedSection(title, purpose, keys, untitled);
    }
}
