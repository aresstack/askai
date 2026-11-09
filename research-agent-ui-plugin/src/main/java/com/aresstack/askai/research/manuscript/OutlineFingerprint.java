package com.aresstack.askai.research.manuscript;

import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineProvenance;

/**
 * The fingerprint of an outline entry a section was written from (plan PR 26, D4): title, brief and provenance.
 * Order, the user-edited flag, stale marks and a chapter's sections are not part of it, so moving a chapter or
 * adding a sibling section never makes a section stale.
 */
public final class OutlineFingerprint {

    private OutlineFingerprint() {
    }

    public static String of(OutlineEntry entry) {
        if (entry == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append("title\u0000").append(entry.getTitle()).append('\u0001');
        OutlineBrief brief = entry.getBrief();
        text.append("purpose\u0000").append(brief.getPurpose()).append('\u0001');
        for (String point : brief.getKeyPoints()) {
            text.append("point\u0000").append(point).append('\u0001');
        }
        text.append("words\u0000").append(brief.getWordTarget()).append('\u0001');
        OutlineProvenance provenance = entry.getProvenance();
        text.append("node\u0000").append(provenance.getConceptNodeId()).append('\u0001');
        for (String cluster : provenance.getClusterIds()) {
            text.append("cluster\u0000").append(cluster).append('\u0001');
        }
        for (String passage : provenance.getPassageIds()) {
            text.append("passage\u0000").append(passage).append('\u0001');
        }
        return ManuscriptCitation.sha256(text.toString());
    }
}
