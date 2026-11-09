package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;

/** Port: the stored passage of an id from the active passage set (a provenance passage of an outline entry). */
public interface EvidencePassageLookup {

    /** The passage, or {@code null} when the id is unknown (for example after a re-embedding renamed it). */
    PassageSearchHit passage(String projectId, String passageId);
}
