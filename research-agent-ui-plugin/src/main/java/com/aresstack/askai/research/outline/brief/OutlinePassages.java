package com.aresstack.askai.research.outline.brief;

import java.util.List;
import java.util.Map;

/**
 * The stored passages an outline entry points at (plan PR 24): their text for the label calls and their stored
 * document vectors for re-clustering. Nothing is embedded again.
 */
public interface OutlinePassages {

    /** The stored vector of every known id among {@code passageIds}; unknown ids are left out. */
    Map<String, float[]> vectors(List<String> passageIds);

    /** The passage text, or an empty string for an unknown id. */
    String text(String passageId);
}
