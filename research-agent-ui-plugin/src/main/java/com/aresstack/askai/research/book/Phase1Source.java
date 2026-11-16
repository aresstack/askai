package com.aresstack.askai.research.book;

import com.aresstack.askai.research.store.FileConceptStore;

import java.util.List;

/** What the concept freeze reads from the live session at {@code SUBMIT_SCOPE}. */
public interface Phase1Source {

    /** The concept store, or {@code null} when the session has none. */
    FileConceptStore conceptStore();

    String mission();

    /** A short description of the deliverable ("" when unspecified). */
    String deliverable();

    /** The working language code at the moment of the approval. */
    String language();

    /** The blacklist terms the user typed or the agent recorded, at the moment of the approval. */
    List<String> blacklist();
}
