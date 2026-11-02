package com.aresstack.askai.hf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pagination state for a merged (OR / multi-value-group) search, where the single result stream the
 * UI sees is actually several independently-cursored HuggingFace requests merged by repo id. Carried
 * on {@link HuggingFaceSearchResult} so a later "load more" can advance each underlying stream and
 * keep the combined result de-duplicated across pages.
 *
 * <ul>
 *   <li>{@code cursors} — the {@code rel="next"} URL for each sub-stream, in request order; a
 *       {@code null} entry marks a stream that has no further pages.</li>
 *   <li>{@code seenIds} — every repo id already returned to the UI across all pages so far, so the
 *       next round can drop repeats (the same model can surface in more than one stream).</li>
 * </ul>
 */
public final class MergedPagination {

    private final List<String> cursors;
    private final Set<String> seenIds;

    public MergedPagination(List<String> cursors, Set<String> seenIds) {
        this.cursors = cursors == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(cursors));
        this.seenIds = seenIds == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(seenIds));
    }

    /** @return the per-stream next-page cursors (null entry == that stream is exhausted). */
    public List<String> getCursors() {
        return cursors;
    }

    /** @return the repo ids already delivered across every page so far. */
    public Set<String> getSeenIds() {
        return seenIds;
    }

    /** @return true when at least one sub-stream still has a next page to fetch. */
    public boolean hasMore() {
        for (int i = 0; i < cursors.size(); i++) {
            if (cursors.get(i) != null) {
                return true;
            }
        }
        return false;
    }
}
