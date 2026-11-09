package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The later research cycles of the search plan. A cycle ends when the queue has no pending entry left; the nodes
 * that are still not {@link SearchPlanCoverage.State#COVERED} are then planned again, once per cycle, from
 * another angle: the planner gets every query already run and the domains the earlier cycles found for the node
 * ({@code DOMAINS FOUND SO FAR}), so three shop listings of one handbook are no reason to stop. Pure: it builds the
 * planner request and turns the answer into the entries and the record of the next cycle.
 * <p>
 * The live run of 2026-11-09 ran one pass of 16 queries, skipped 11 of them as "covered" and announced 5 of 5
 * nodes covered with shop pages, a 404 page and two search-result pages as the sources. A plan that stops after
 * one pass is not research; one that reports that stop as coverage is not honest.
 */
public final class SearchPlanCycles {

    /** The planner may write at most this many queries per node and cycle (the per-call limit of D5). */
    public static final int MAX_QUERIES_PER_NODE_AND_CYCLE = SearchPlanBuilder.MAX_VARIANTS;

    /** What the next cycle adds. */
    public static final class Result {
        private final SearchPlanRecord record;
        private final List<SearchPlanEntry> added;

        Result(SearchPlanRecord record, List<SearchPlanEntry> added) {
            this.record = record;
            this.added = java.util.Collections.unmodifiableList(added);
        }

        /** The record of the next cycle (entries written first, record last). */
        public SearchPlanRecord getRecord() {
            return record;
        }

        /** The new pending entries of the cycle, in plan order. */
        public List<SearchPlanEntry> getAdded() {
            return added;
        }
    }

    private SearchPlanCycles() {
    }

    /**
     * The planner request of the next cycle, or {@code null} when every active node is covered.
     *
     * @param coverage the coverage of the current cycle ({@link SearchPlanCoverage#of})
     */
    public static ConceptQueryPlan.Request nextCycleRequest(SearchPlanRecord record,
                                                            Collection<SearchPlanEntry> entries,
                                                            Map<String, SearchPlanCoverage.NodeCoverage> coverage,
                                                            SearchPlanCoverage.Caps caps) {
        List<ConceptQueryPlan.Node> uncovered = new ArrayList<ConceptQueryPlan.Node>();
        for (SearchPlanRecord.Node node : record.getNodes()) {
            if (!node.isActive()) {
                continue;
            }
            SearchPlanCoverage.NodeCoverage nc = coverage.get(node.getId());
            if (nc != null && nc.getState() == SearchPlanCoverage.State.COVERED) {
                continue;
            }
            ConceptQueryPlan.Node concept = node.node();
            uncovered.add(new ConceptQueryPlan.Node(concept.getId(), concept.getLabel(), concept.getPath(),
                    concept.getParentId(), concept.getBaseQuery(),
                    nc == null ? null : new ArrayList<String>(nc.getDomains())));
        }
        if (uncovered.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> run = new LinkedHashSet<String>();
        for (SearchPlanEntry entry : entries) {
            if (entry.getStatus() != SearchPlanEntry.Status.PENDING && !entry.getQuery().isEmpty()) {
                run.add(entry.getQuery());
            }
        }
        int perNode = Math.max(1, Math.min(MAX_QUERIES_PER_NODE_AND_CYCLE, caps.getQueriesPerNode()));
        return new ConceptQueryPlan.Request(record.getLanguage(), "", uncovered, new ArrayList<String>(run),
                java.util.Collections.<String>emptyList(), perNode, record.getCycle() + 1);
    }

    /**
     * The entries and record of the next cycle from the planner's answer: per requested node at most
     * {@code maxPerNode} new, distinct, usable queries (blacklist terms removed, never one that already ran),
     * tagged {@link SearchPlanEntry.Origin#CYCLE}. A node the planner left without a usable query gets none;
     * an answer without any usable query yields a result with no entries, and the record stays as it is.
     */
    public static Result apply(SearchPlanRecord record, Collection<SearchPlanEntry> entries,
                               ConceptQueryPlan.Request request, ConceptQueryPlan.Result answer,
                               List<String> blacklist, SearchPlanMerger.IdSource ids, long nowMillis) {
        List<SearchPlanEntry> added = new ArrayList<SearchPlanEntry>();
        if (answer == null) {
            return new Result(record, added);
        }
        Set<String> known = new HashSet<String>();
        for (SearchPlanEntry entry : entries) {
            known.add(key(entry.getQuery()));
        }
        Set<String> requested = new HashSet<String>();
        for (ConceptQueryPlan.Node node : request.getNodes()) {
            requested.add(node.getId());
        }
        int perNode = Math.max(1, request.getMaxVariantsPerNode());
        for (ConceptQueryPlan.NodePlan plan : answer.getNodes()) {
            if (!requested.contains(plan.getNodeId())) {
                continue; // never a node the cycle did not ask for
            }
            SearchPlanRecord.Node node = record.node(plan.getNodeId());
            if (node == null || !node.isActive()) {
                continue;
            }
            int count = 0;
            for (String variant : plan.getVariants()) {
                if (count >= perNode) {
                    break;
                }
                String cleaned = ConceptPlanNodes.removeTerms(variant, blacklist);
                if (cleaned.length() < ConceptQueryPlan.MIN_QUERY_LENGTH
                        || cleaned.length() > ConceptQueryPlan.MAX_QUERY_LENGTH || !known.add(key(cleaned))) {
                    continue;
                }
                added.add(SearchPlanEntry.submit(ids.next(), record.getPhase(), node.getId(), node.getLabel(),
                        cleaned, SearchPlanEntry.Origin.CYCLE, nowMillis));
                count++;
            }
        }
        if (added.isEmpty()) {
            return new Result(record, added);
        }
        List<String> addedIds = new ArrayList<String>();
        for (SearchPlanEntry entry : added) {
            addedIds.add(entry.getId());
        }
        return new Result(record.nextCycle(addedIds), added);
    }

    private static String key(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
