package com.aresstack.askai.hf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a HuggingFace model search: cross-group OR expansion (the server only ANDs repeated
 * {@code filter=} values) and the Base-only best-effort backfill loop. The {@link
 * HuggingFaceSearchGateway} only speaks HTTP; this class owns the "how many requests, in what order"
 * decisions so the gateway stays a pure API-communication class.
 *
 * <p>Filter semantics (spec §14): different groups (libraries, tasks, languages, licenses, other,
 * apps) are ANDed; multiple values within one group are ORed. HuggingFace ANDs every repeated
 * {@code filter=}, so an OR is realized by issuing one request per combination and merging by repo
 * id. When more than one group has multiple values that is the cartesian product of the groups —
 * bounded by {@link #MAX_MERGE_REQUESTS} so a broad selection can't fan out without limit.</p>
 *
 * <p>A merged (OR) search paginates too: each underlying request keeps its own {@code rel="next"}
 * cursor, carried in the result's {@link MergedPagination}. "Load more" advances every still-open
 * stream one page and merges the new hits, de-duplicated against everything returned so far — so
 * filters and pagination work together, not one or the other.</p>
 */
public final class HuggingFaceSearchUseCase {

    /**
     * How many extra server pages Base-only is allowed to auto-fetch to backfill toward the
     * requested page size, since HuggingFace cannot filter "has any base_model relation" server-side
     * (it only matches a full {@code base_model:<relation>:<id>} tag, not a prefix).
     */
    private static final int MAX_BASE_ONLY_EXTRA_PAGES = 4;

    /** Upper bound on the number of merged requests a multi-value-group selection may fan out to. */
    private static final int MAX_MERGE_REQUESTS = 12;

    private final HuggingFaceSearchGateway gateway;

    public HuggingFaceSearchUseCase(HuggingFaceSearchGateway gateway) {
        this.gateway = gateway;
    }

    /** Runs a fresh search (first page), discarding any prior pagination state. */
    public HuggingFaceSearchResult search(ModelSearchCriteria criteria) throws IOException {
        List<ModelSearchCriteria> requests = expandToRequests(criteria);
        if (requests.size() == 1) {
            HuggingFaceSearchPage page = gateway.searchModels(requests.get(0));
            return applyBaseOnly(criteria, page, null);
        }
        return searchMerged(criteria, requests);
    }

    /** Continues from a previous result's pagination state; a no-op result when nothing more remains. */
    public HuggingFaceSearchResult loadMore(ModelSearchCriteria criteria, HuggingFaceSearchResult previous)
            throws IOException {
        if (previous.getMerged() != null) {
            return loadMoreMerged(criteria, previous.getMerged());
        }
        if (!previous.isLoadMoreSupported() || previous.getNextPageUrl() == null) {
            return new HuggingFaceSearchResult(Collections.<HuggingFaceModel>emptyList(),
                    null, false, "Keine weiteren Treffer.");
        }
        HuggingFaceSearchPage page = gateway.loadMore(previous.getNextPageUrl());
        return applyBaseOnly(criteria, page, null);
    }

    /**
     * Expands a criteria into the set of all-ANDed request criteria whose union realizes the OR of
     * each multi-valued group. One request when every group has 0 or 1 value (full pagination
     * preserved); otherwise the cartesian product across the multi-valued groups, capped at
     * {@link #MAX_MERGE_REQUESTS}.
     */
    private List<ModelSearchCriteria> expandToRequests(ModelSearchCriteria criteria) {
        List<List<String>> groups = new ArrayList<List<String>>();
        groups.add(criteria.getLibraries());
        groups.add(criteria.getTasks());
        groups.add(criteria.getLanguages());
        groups.add(criteria.getLicenses());
        groups.add(criteria.getOther());
        groups.add(criteria.getApps());

        boolean anyMulti = false;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).size() > 1) {
                anyMulti = true;
                break;
            }
        }
        List<ModelSearchCriteria> requests = new ArrayList<ModelSearchCriteria>();
        if (!anyMulti) {
            requests.add(criteria);
            return requests;
        }
        // Cartesian product: each combination pins one value per multi-valued group (single/empty
        // groups pass through unchanged). Encoded as index counters over the group value lists.
        int[] sizes = new int[groups.size()];
        int total = 1;
        for (int i = 0; i < groups.size(); i++) {
            sizes[i] = Math.max(1, groups.get(i).size());
            total *= sizes[i];
        }
        int[] indices = new int[groups.size()];
        for (int n = 0; n < total && requests.size() < MAX_MERGE_REQUESTS; n++) {
            requests.add(reduceGroups(criteria, groups, indices));
            increment(indices, sizes);
        }
        return requests;
    }

    private ModelSearchCriteria reduceGroups(ModelSearchCriteria criteria, List<List<String>> groups, int[] indices) {
        return criteria.toBuilder()
                .libraries(pick(groups.get(0), indices[0]))
                .tasks(pick(groups.get(1), indices[1]))
                .languages(pick(groups.get(2), indices[2]))
                .licenses(pick(groups.get(3), indices[3]))
                .other(pick(groups.get(4), indices[4]))
                .apps(pick(groups.get(5), indices[5]))
                .build();
    }

    /** @return the whole group when it has 0/1 values (nothing to OR), else the single picked value. */
    private static List<String> pick(List<String> group, int index) {
        if (group.size() <= 1) {
            return group;
        }
        return Collections.singletonList(group.get(index));
    }

    private static void increment(int[] indices, int[] sizes) {
        for (int i = 0; i < indices.length; i++) {
            indices[i]++;
            if (indices[i] < sizes[i]) {
                return;
            }
            indices[i] = 0;
        }
    }

    /**
     * First page of a merged search: fetch page one of every request and merge, de-duplicated by
     * repo id. Each request's {@code rel="next"} cursor is retained in the {@link MergedPagination}
     * so {@link #loadMoreMerged} can page the streams forward together.
     */
    private HuggingFaceSearchResult searchMerged(ModelSearchCriteria criteria, List<ModelSearchCriteria> requests)
            throws IOException {
        List<HuggingFaceSearchPage> pages = new ArrayList<HuggingFaceSearchPage>();
        for (int i = 0; i < requests.size(); i++) {
            pages.add(gateway.searchModels(requests.get(i)));
        }
        String note = requests.size() + " OR-Kombinationen zusammengeführt.";
        return mergePages(criteria, pages, Collections.<String>emptySet(), note);
    }

    /**
     * Next page of a merged search: for every sub-stream that still has a cursor, fetch its next page;
     * merge the new hits, dropping any repo id already returned in an earlier page. Streams whose
     * cursor is {@code null} are exhausted and skipped. "Load more" stays available until every stream
     * is exhausted.
     */
    private HuggingFaceSearchResult loadMoreMerged(ModelSearchCriteria criteria, MergedPagination previous)
            throws IOException {
        List<String> cursors = previous.getCursors();
        List<HuggingFaceSearchPage> pages = new ArrayList<HuggingFaceSearchPage>();
        for (int i = 0; i < cursors.size(); i++) {
            String cursor = cursors.get(i);
            pages.add(cursor == null ? null : gateway.loadMore(cursor));
        }
        HuggingFaceSearchResult result = mergePages(criteria, pages, previous.getSeenIds(), null);
        if (result.getModels().isEmpty() && !result.isLoadMoreSupported()) {
            return new HuggingFaceSearchResult(Collections.<HuggingFaceModel>emptyList(), null, false,
                    "Keine weiteren Treffer.", result.getMerged());
        }
        return result;
    }

    /**
     * Merges one round of pages (one per sub-stream, {@code null} for a stream not fetched this round)
     * into a single de-duplicated result. New models are those whose id was not already returned
     * ({@code priorSeen}); Base-only, when on, then drops hits carrying a {@code base_model:} relation.
     * The updated cursor list and the grown seen-id set are carried forward on the result.
     */
    private HuggingFaceSearchResult mergePages(ModelSearchCriteria criteria, List<HuggingFaceSearchPage> pages,
                                               Set<String> priorSeen, String note) {
        Map<String, HuggingFaceModel> fresh = new LinkedHashMap<String, HuggingFaceModel>();
        Set<String> seen = new LinkedHashSet<String>(priorSeen);
        List<String> cursors = new ArrayList<String>();
        for (int i = 0; i < pages.size(); i++) {
            HuggingFaceSearchPage page = pages.get(i);
            cursors.add(page == null ? null : page.getNextPageUrl());
            if (page == null) {
                continue;
            }
            List<HuggingFaceModel> models = page.getModels();
            for (int j = 0; j < models.size(); j++) {
                HuggingFaceModel model = models.get(j);
                if (seen.add(model.getId())) {
                    fresh.put(model.getId(), model);
                }
            }
        }
        List<HuggingFaceModel> models = new ArrayList<HuggingFaceModel>(fresh.values());
        if (criteria.isBaseOnly()) {
            models = filterBaseOnly(models);
        }
        MergedPagination merged = new MergedPagination(cursors, seen);
        return new HuggingFaceSearchResult(models, null, merged.hasMore(), note, merged);
    }

    /**
     * When Base-only is off, passes the page through unchanged. When on, drops any hit with a
     * {@code base_model:} relation tag and, if that leaves fewer than the requested page size, keeps
     * following {@code nextPageUrl} (up to {@link #MAX_BASE_ONLY_EXTRA_PAGES} extra requests) to
     * backfill. Reports a shortfall note instead of silently returning a short page.
     */
    private HuggingFaceSearchResult applyBaseOnly(ModelSearchCriteria criteria, HuggingFaceSearchPage page,
                                                  String priorNote) throws IOException {
        if (!criteria.isBaseOnly()) {
            boolean hasMore = page.getNextPageUrl() != null;
            return new HuggingFaceSearchResult(page.getModels(), page.getNextPageUrl(), hasMore, priorNote);
        }
        List<HuggingFaceModel> filtered = new ArrayList<HuggingFaceModel>(filterBaseOnly(page.getModels()));
        String nextUrl = page.getNextPageUrl();
        int desired = criteria.getPageSize();
        int extraPages = 0;
        while (filtered.size() < desired && nextUrl != null && extraPages < MAX_BASE_ONLY_EXTRA_PAGES) {
            HuggingFaceSearchPage extra = gateway.loadMore(nextUrl);
            filtered.addAll(filterBaseOnly(extra.getModels()));
            nextUrl = extra.getNextPageUrl();
            extraPages++;
        }
        String note = priorNote;
        if (filtered.size() < desired) {
            String shortfall = "Base only: " + filtered.size() + " von " + desired + " angefragten Treffern "
                    + "gefunden" + (extraPages > 0 ? " (nach " + extraPages + " zusätzlichen Seiten)." : ".");
            note = note == null ? shortfall : note + " " + shortfall;
        }
        return new HuggingFaceSearchResult(filtered, nextUrl, nextUrl != null, note);
    }

    private static List<HuggingFaceModel> filterBaseOnly(List<HuggingFaceModel> models) {
        List<HuggingFaceModel> result = new ArrayList<HuggingFaceModel>();
        for (int i = 0; i < models.size(); i++) {
            HuggingFaceModel model = models.get(i);
            if (!model.hasBaseModelRelation()) {
                result.add(model);
            }
        }
        return result;
    }
}
