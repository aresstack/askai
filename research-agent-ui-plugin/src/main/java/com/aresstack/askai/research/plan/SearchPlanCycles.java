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
        return nextCycleRequest(record, entries, coverage, caps, EvidenceGaps.Gaps.NONE);
    }

    /**
     * The planner request of the next cycle with the gaps of the evidence analysis: a node the analysis finds
     * thin, redundant or missing is planned again even when its source count covers it (many pages saying the same
     * are no coverage), and carries the findings; a covered node without a gap is not deepened; the new topics
     * inside the Weidezaun ride along. A gap node also counts as uncovered in the plan's outcome.
     */
    public static ConceptQueryPlan.Request nextCycleRequest(SearchPlanRecord record,
                                                            Collection<SearchPlanEntry> entries,
                                                            Map<String, SearchPlanCoverage.NodeCoverage> coverage,
                                                            SearchPlanCoverage.Caps caps, EvidenceGaps.Gaps gaps) {
        EvidenceGaps.Gaps g = gaps == null ? EvidenceGaps.Gaps.NONE : gaps;
        List<ConceptQueryPlan.Node> uncovered = new ArrayList<ConceptQueryPlan.Node>();
        for (SearchPlanRecord.Node node : record.getNodes()) {
            if (!node.isActive()) {
                continue;
            }
            SearchPlanCoverage.NodeCoverage nc = coverage.get(node.getId());
            List<String> evidence = g.getEvidenceByNode().get(node.getId());
            if (evidence == null && nc != null && nc.getState() == SearchPlanCoverage.State.COVERED) {
                continue; // covered by its sources and no gap in the analysis: not deepened for now
            }
            uncovered.add(planNode(node, nc, entries, caps, evidence));
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
                java.util.Collections.<String>emptyList(), perNode, record.getCycle() + 1)
                .withNewTopics(g.getNewTopics());
    }

    private static ConceptQueryPlan.Node planNode(SearchPlanRecord.Node node, SearchPlanCoverage.NodeCoverage nc,
                                                  Collection<SearchPlanEntry> entries, SearchPlanCoverage.Caps caps,
                                                  List<String> evidence) {
        ConceptQueryPlan.Node concept = node.node();
        return new ConceptQueryPlan.Node(concept.getId(), concept.getLabel(), concept.getPath(),
                concept.getParentId(), concept.getBaseQuery(),
                nc == null ? null : new ArrayList<String>(nc.getDomains()),
                teamNotes(entries, node.getId(), 2 * Math.max(1, caps.getQueriesPerNode())), evidence);
    }

    /**
     * Plan T4: the newest {@code max} page worker reports of the node's queries, oldest first. Bounded by the plan's
     * own queries-per-node setting, so a node's planner prompt stays the same size however many cycles ran.
     */
    static List<String> teamNotes(Collection<SearchPlanEntry> entries, String nodeId, int max) {
        List<SearchPlanEntry> ofNode = new ArrayList<SearchPlanEntry>();
        for (SearchPlanEntry entry : entries) {
            if (nodeId.equals(entry.getNodeId()) && !entry.getTeamNotes().isEmpty()) {
                ofNode.add(entry);
            }
        }
        java.util.Collections.sort(ofNode, new java.util.Comparator<SearchPlanEntry>() {
            public int compare(SearchPlanEntry a, SearchPlanEntry b) {
                return Long.compare(a.getCreatedAtMillis(), b.getCreatedAtMillis());
            }
        });
        LinkedHashSet<String> notes = new LinkedHashSet<String>();
        for (SearchPlanEntry entry : ofNode) {
            notes.addAll(entry.getTeamNotes());
        }
        List<String> all = new ArrayList<String>(notes);
        return all.size() <= max ? all : new ArrayList<String>(all.subList(all.size() - max, all.size()));
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
        return apply(record, entries, request, answer, blacklist, ids, nowMillis, SourcePreference.NONE);
    }

    /**
     * Like {@link #apply(SearchPlanRecord, Collection, ConceptQueryPlan.Request, ConceptQueryPlan.Result, List,
     * SearchPlanMerger.IdSource, long)}, and every requested node also gets the mission's publisher queries of this
     * cycle ({@link SourcePreference#queriesFor}), next to the planner's budget: whatever the planner was told (gaps,
     * worker notes, new topics), the deepening goes to the preferred sources too. With a preference a cycle starts
     * even when the planner gave no answer.
     */
    public static Result apply(SearchPlanRecord record, Collection<SearchPlanEntry> entries,
                               ConceptQueryPlan.Request request, ConceptQueryPlan.Result answer,
                               List<String> blacklist, SearchPlanMerger.IdSource ids, long nowMillis,
                               SourcePreference preference) {
        List<SearchPlanEntry> added = new ArrayList<SearchPlanEntry>();
        SourcePreference prefer = preference == null ? SourcePreference.NONE : preference;
        if (answer == null && prefer.getTargets().isEmpty()) {
            return new Result(record, added);
        }
        List<String> requestedOrder = new ArrayList<String>();
        for (ConceptQueryPlan.Node node : request.getNodes()) {
            requestedOrder.add(node.getId());
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
        List<ConceptQueryPlan.NodePlan> plans = answer == null
                ? java.util.Collections.<ConceptQueryPlan.NodePlan>emptyList() : answer.getNodes();
        for (ConceptQueryPlan.NodePlan plan : plans) {
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
        // The mission's publishers for every node of the cycle, also one the planner left without a query: a
        // different publisher is a new angle, and it lets a cycle start when the planner is unavailable.
        for (int i = 0; i < requestedOrder.size(); i++) {
            SearchPlanRecord.Node node = record.node(requestedOrder.get(i));
            if (node == null || !node.isActive()) {
                continue;
            }
            for (String query : prefer.queriesFor(node.getBaseQuery(), i, request.getCycle())) {
                String cleaned = ConceptPlanNodes.removeTerms(query, blacklist);
                if (cleaned.length() < ConceptQueryPlan.MIN_QUERY_LENGTH
                        || cleaned.length() > ConceptQueryPlan.MAX_QUERY_LENGTH || !known.add(key(cleaned))) {
                    continue;
                }
                added.add(SearchPlanEntry.submit(ids.next(), record.getPhase(), node.getId(), node.getLabel(),
                        cleaned, SearchPlanEntry.Origin.CYCLE, nowMillis));
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
