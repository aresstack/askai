package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the search plan and merges a later {@code START_RESEARCH} into the stored one by concept node id (plan
 * PR 14, D5). Pure: it reads the stored record and entries and returns the next record plus the entries to write.
 * <ul>
 * <li>A node whose base query is unchanged keeps its entries with their status, edits and accepted sources.</li>
 * <li>A new node or a node with a changed base query gets pending entries (base query first, then the planner's
 * variants); its old pending entries become skipped.</li>
 * <li>The pending entries of a removed or newly blacklisted node become skipped; the node stays on record as
 * inactive.</li>
 * </ul>
 */
public final class SearchPlanMerger {

    static final String CONCEPT_CHANGED = "The concept node changed";
    static final String NODE_REMOVED = "The node is no longer part of the concept";

    /** Creates entry ids; tests pass a counter, the session a UUID source. */
    public interface IdSource {
        String next();
    }

    /** The merged plan. */
    public static final class Result {
        private final SearchPlanRecord record;
        private final List<SearchPlanEntry> changed;
        private final int added;

        Result(SearchPlanRecord record, List<SearchPlanEntry> changed, int added) {
            this.record = record;
            this.changed = Collections.unmodifiableList(changed);
            this.added = added;
        }

        public SearchPlanRecord getRecord() {
            return record;
        }

        /** The new and the changed entries, to be written before the record. */
        public List<SearchPlanEntry> getChanged() {
            return changed;
        }

        /** How many pending entries the merge added. */
        public int getAdded() {
            return added;
        }
    }

    private SearchPlanMerger() {
    }

    /**
     * @param stored     the stored record, or {@code null} for the first plan
     * @param entries    the stored entries by id
     * @param phase      the phase the plan belongs to
     * @param revision   the approved concept revision
     * @param language   the working language of the Phase-1 record
     * @param nodes      the nodes of the effective frozen concept, in concept order
     * @param queries    per node id: the base query followed by the planner's variants
     */
    public static Result merge(SearchPlanRecord stored, Map<String, SearchPlanEntry> entries, String phase,
                               int revision, String language, List<ConceptQueryPlan.Node> nodes,
                               Map<String, List<String>> queries, IdSource ids, long nowMillis) {
        Map<String, SearchPlanEntry> current = new LinkedHashMap<String, SearchPlanEntry>(entries);
        List<SearchPlanEntry> changed = new ArrayList<SearchPlanEntry>();
        List<String> order = new ArrayList<String>(stored == null ? new ArrayList<String>() : stored.getOrder());
        List<String> priority = new ArrayList<String>(
                stored == null ? new ArrayList<String>() : stored.getPriority());
        List<SearchPlanRecord.Node> recordNodes = new ArrayList<SearchPlanRecord.Node>();
        Set<String> liveIds = new HashSet<String>();
        int added = 0;
        for (ConceptQueryPlan.Node node : nodes) {
            liveIds.add(node.getId());
            SearchPlanRecord.Node before = stored == null ? null : stored.node(node.getId());
            boolean unchanged = before != null && before.isActive()
                    && before.getBaseQuery().equals(node.getBaseQuery());
            recordNodes.add(new SearchPlanRecord.Node(node, true));
            if (unchanged) {
                continue;
            }
            skipPending(current, node.getId(), CONCEPT_CHANGED, nowMillis, changed);
            List<String> planned = queries.get(node.getId());
            if (planned == null || planned.isEmpty()) {
                planned = node.getBaseQuery().isEmpty() ? Collections.<String>emptyList()
                        : Collections.singletonList(node.getBaseQuery());
            }
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < planned.size(); i++) {
                String query = planned.get(i) == null ? "" : planned.get(i).trim();
                if (query.isEmpty() || !seen.add(query.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                boolean base = query.equals(node.getBaseQuery());
                SearchPlanEntry entry = SearchPlanEntry.submit(ids.next(), phase, node.getId(), node.getLabel(),
                        query, base ? SearchPlanEntry.Origin.BASE : SearchPlanEntry.Origin.VARIANT, nowMillis);
                current.put(entry.getId(), entry);
                changed.add(entry);
                order.add(entry.getId());
                added++;
            }
        }
        if (stored != null) {
            for (SearchPlanRecord.Node old : stored.getNodes()) {
                if (!liveIds.contains(old.getId())) {
                    recordNodes.add(new SearchPlanRecord.Node(old.node(), false));
                    skipPending(current, old.getId(), NODE_REMOVED, nowMillis, changed);
                }
            }
        }
        // a re-merge (another START_RESEARCH) keeps the research cycle the plan is in
        return new Result(new SearchPlanRecord(phase, revision, language, recordNodes, order, priority,
                stored == null ? 1 : stored.getCycle()), changed, added);
    }

    /**
     * "Search everything again": every active node gets one new pending entry per distinct query it ran before
     * (base, variants and the user's own), in plan order, as the user's entries (coverage does not skip them).
     * Done entries stay on record.
     */
    public static Result searchAgain(SearchPlanRecord stored, Map<String, SearchPlanEntry> entries, IdSource ids,
                                     long nowMillis) {
        List<SearchPlanEntry> changed = new ArrayList<SearchPlanEntry>();
        List<String> order = new ArrayList<String>(stored.getOrder());
        Set<String> seen = new HashSet<String>();
        int added = 0;
        for (String id : stored.getOrder()) {
            SearchPlanEntry entry = entries.get(id);
            if (entry == null || entry.getOrigin() == SearchPlanEntry.Origin.PRIORITY) {
                continue;
            }
            SearchPlanRecord.Node node = stored.node(entry.getNodeId());
            if (node == null || !node.isActive() || entry.getStatus() == SearchPlanEntry.Status.PENDING) {
                continue;
            }
            if (!seen.add(entry.getNodeId() + "\u0000" + entry.getQuery().toLowerCase(Locale.ROOT))) {
                continue;
            }
            SearchPlanEntry again = SearchPlanEntry.submit(ids.next(), entry.getPhase(), entry.getNodeId(),
                    entry.getNodeLabel(), entry.getQuery(), SearchPlanEntry.Origin.USER, nowMillis);
            changed.add(again);
            order.add(again.getId());
            added++;
        }
        return new Result(stored.withOrder(order, stored.getPriority()), changed, added);
    }

    private static void skipPending(Map<String, SearchPlanEntry> current, String nodeId, String reason,
                                    long nowMillis, List<SearchPlanEntry> changed) {
        for (Map.Entry<String, SearchPlanEntry> e : current.entrySet()) {
            SearchPlanEntry entry = e.getValue();
            if (nodeId.equals(entry.getNodeId()) && entry.getStatus() == SearchPlanEntry.Status.PENDING) {
                SearchPlanEntry skipped = entry.skipped(reason, nowMillis);
                e.setValue(skipped);
                changed.add(skipped);
            }
        }
    }
}
