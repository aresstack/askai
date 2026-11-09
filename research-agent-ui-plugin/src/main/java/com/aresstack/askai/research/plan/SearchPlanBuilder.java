package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.transition.CorruptProjectFileException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds the search plan on {@code START_RESEARCH} (plan PR 14, D5): the nodes of the effective frozen concept with
 * their base queries, the planner role's variants for every new or changed node ({@code plan_queries}; any failure
 * keeps the base query), merged into the stored plan by node id and written entries first, record last.
 */
public final class SearchPlanBuilder {

    /** The most variants the planner may add per node (D5). */
    public static final int MAX_VARIANTS = 3;

    /** The planner role over the {@code plan_queries} wire; may return {@code null} on any failure. */
    public interface VariantPlanner {
        ConceptQueryPlan.Result plan(ConceptQueryPlan.Request request);
    }

    /** What the frozen concept gives the plan. */
    public static final class Input {
        final String phase;
        final int revision;
        final String language;
        final String bookTitle;
        final List<ConceptQueryPlan.Node> nodes;
        final List<String> blacklist;

        public Input(String phase, int revision, String language, String bookTitle,
                     List<ConceptQueryPlan.Node> nodes, List<String> blacklist) {
            this.phase = phase;
            this.revision = revision;
            this.language = language;
            this.bookTitle = bookTitle == null ? "" : bookTitle;
            this.nodes = nodes;
            this.blacklist = blacklist == null ? new ArrayList<String>() : blacklist;
        }
    }

    private SearchPlanBuilder() {
    }

    /** Build or merge the plan and persist it; @return the merge result. */
    public static SearchPlanMerger.Result build(SearchPlanStore store, Input input, VariantPlanner planner,
                                                int queriesPerNode, long nowMillis)
            throws IOException, CorruptProjectFileException {
        SearchPlanRecord stored = store.loadRecord();
        Map<String, SearchPlanEntry> entries = store.loadEntries();
        List<ConceptQueryPlan.Node> toPlan = new ArrayList<ConceptQueryPlan.Node>();
        for (ConceptQueryPlan.Node node : input.nodes) {
            SearchPlanRecord.Node before = stored == null ? null : stored.node(node.getId());
            boolean unchanged = before != null && before.isActive()
                    && before.getBaseQuery().equals(node.getBaseQuery());
            if (!unchanged) {
                toPlan.add(node);
            }
        }
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        for (ConceptQueryPlan.Node node : toPlan) {
            List<String> base = new ArrayList<String>();
            if (!node.getBaseQuery().isEmpty()) {
                base.add(node.getBaseQuery());
            }
            queries.put(node.getId(), base);
        }
        int variants = Math.max(0, Math.min(MAX_VARIANTS, queriesPerNode - 1));
        if (planner != null && variants > 0 && !toPlan.isEmpty()) {
            LinkedHashSet<String> run = new LinkedHashSet<String>();
            for (SearchPlanEntry entry : entries.values()) {
                if (entry.getStatus() != SearchPlanEntry.Status.PENDING) {
                    run.add(entry.getQuery());
                }
            }
            ConceptQueryPlan.Result result;
            try {
                result = planner.plan(new ConceptQueryPlan.Request(input.language, input.bookTitle, toPlan,
                        new ArrayList<String>(run), input.blacklist, variants));
            } catch (RuntimeException failed) {
                result = null;
            }
            if (result != null) {
                for (ConceptQueryPlan.NodePlan plan : result.getNodes()) {
                    List<String> list = queries.get(plan.getNodeId());
                    if (list == null) {
                        continue; // never a node the plan did not ask for
                    }
                    int added = 0;
                    for (String variant : plan.getVariants()) {
                        if (added >= variants) {
                            break;
                        }
                        String cleaned = ConceptPlanNodes.removeTerms(variant, input.blacklist);
                        if (cleaned.length() >= ConceptQueryPlan.MIN_QUERY_LENGTH
                                && cleaned.length() <= ConceptQueryPlan.MAX_QUERY_LENGTH) {
                            list.add(cleaned);
                            added++;
                        }
                    }
                }
            }
        }
        SearchPlanMerger.Result merged = SearchPlanMerger.merge(stored, entries, input.phase, input.revision,
                input.language, input.nodes, queries, SearchPlanQueue.ids(), nowMillis);
        store.saveAll(merged.getChanged());
        store.saveRecord(merged.getRecord());
        return merged;
    }
}
