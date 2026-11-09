package com.aresstack.askai.research.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The brief of an outline entry (D6): purpose, key points and a word target (0 = none yet). */
public final class OutlineBrief {

    public static final OutlineBrief EMPTY = new OutlineBrief("", Collections.<String>emptyList(), 0);

    private final String purpose;
    private final List<String> keyPoints;
    private final int wordTarget;

    public OutlineBrief(String purpose, List<String> keyPoints, int wordTarget) {
        if (wordTarget < 0) {
            throw new IllegalArgumentException("a word target is never negative");
        }
        this.purpose = purpose == null ? "" : purpose;
        this.keyPoints = Collections.unmodifiableList(new ArrayList<String>(
                keyPoints == null ? Collections.<String>emptyList() : keyPoints));
        this.wordTarget = wordTarget;
    }

    public String getPurpose() {
        return purpose;
    }

    public List<String> getKeyPoints() {
        return keyPoints;
    }

    public int getWordTarget() {
        return wordTarget;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OutlineBrief)) {
            return false;
        }
        OutlineBrief that = (OutlineBrief) other;
        return purpose.equals(that.purpose) && keyPoints.equals(that.keyPoints) && wordTarget == that.wordTarget;
    }

    @Override
    public int hashCode() {
        return (purpose.hashCode() * 31 + keyPoints.hashCode()) * 31 + wordTarget;
    }
}
