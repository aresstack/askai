package com.aresstack.askai.research.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The live progress of a running search plan as the chat's thought bubble shows it (live run 5, 2026-11-17: Phase 2
 * showed only its divider for minutes). Pure projection of what the queue, its store and the research team already
 * report: plan entries done/open, the running searches, accepted sources, open pages, node coverage, the research
 * cycle, and the team's own lines (worker notes, derived searches, dropped suggestions with their reason). No model
 * call is made for it. Numbers are written without grouping separators.
 */
public final class SearchPlanProgress {

    private final int done;
    private final int total;
    private final List<String> running;
    private final int sources;
    private final int openPages;
    private final int coveredNodes;
    private final int nodes;
    private final int cycle;
    private final int cycles;
    private final boolean replanning;
    private final List<String> activity;

    private SearchPlanProgress(int done, int total, List<String> running, int sources, int openPages,
                               int coveredNodes, int nodes, int cycle, int cycles, boolean replanning,
                               List<String> activity) {
        this.done = done;
        this.total = total;
        this.running = Collections.unmodifiableList(new ArrayList<String>(running));
        this.sources = sources;
        this.openPages = openPages;
        this.coveredNodes = coveredNodes;
        this.nodes = nodes;
        this.cycle = cycle;
        this.cycles = cycles;
        this.replanning = replanning;
        this.activity = Collections.unmodifiableList(new ArrayList<String>(activity));
    }

    /**
     * Projects the plan.
     *
     * @param record     the plan record (nodes, order, cycle); {@code null} gives an empty progress
     * @param entries    the plan entries by id
     * @param caps       the plan caps (cycles, sources per node)
     * @param openPages  the still open pages of the running team searches
     * @param replanning whether the planner of the next cycle runs
     * @param activity   the latest activity lines of the searches and the team, oldest first
     */
    public static SearchPlanProgress of(SearchPlanRecord record, Map<String, SearchPlanEntry> entries,
                                        SearchPlanCoverage.Caps caps, int openPages, boolean replanning,
                                        List<String> activity) {
        List<String> runningQueries = new ArrayList<String>();
        int doneCount = 0;
        int totalCount = 0;
        Set<String> accepted = new HashSet<String>();
        Map<String, Set<String>> byNode = new LinkedHashMap<String, Set<String>>();
        if (record != null && entries != null) {
            for (String id : record.getOrder()) {
                SearchPlanEntry entry = entries.get(id);
                if (entry == null || entry.getStatus() == SearchPlanEntry.Status.SKIPPED) {
                    continue;
                }
                totalCount++;
                if (entry.getStatus() == SearchPlanEntry.Status.DONE
                        || entry.getStatus() == SearchPlanEntry.Status.FAILED) {
                    doneCount++;
                } else if (entry.getStatus() == SearchPlanEntry.Status.RUNNING) {
                    runningQueries.add(entry.getQuery());
                }
                accepted.addAll(entry.getAcceptedSourceIds());
                Set<String> ofNode = byNode.get(entry.getNodeId());
                if (ofNode == null) {
                    ofNode = new HashSet<String>();
                    byNode.put(entry.getNodeId(), ofNode);
                }
                ofNode.addAll(entry.getAcceptedSourceIds());
            }
        }
        int nodeCount = 0;
        int covered = 0;
        int perNode = caps == null ? 1 : Math.max(1, caps.getSourcesPerNode());
        if (record != null) {
            for (SearchPlanRecord.Node node : record.getNodes()) {
                if (!node.isActive()) {
                    continue;
                }
                nodeCount++;
                Set<String> ofNode = byNode.get(node.getId());
                if (ofNode != null && ofNode.size() >= perNode) {
                    covered++;
                }
            }
        }
        return new SearchPlanProgress(doneCount, totalCount, runningQueries, accepted.size(), Math.max(0, openPages),
                covered, nodeCount, record == null ? 0 : Math.max(1, record.getCycle()),
                caps == null ? 0 : caps.getCycles(), replanning,
                activity == null ? Collections.<String>emptyList() : activity);
    }

    /** The bubble title: what runs right now. */
    public String title() {
        if (replanning) {
            return "Planning research cycle " + (cycle + 1) + " …";
        }
        if (running.isEmpty()) {
            return "Sources phase: waiting for the next search …";
        }
        if (running.size() == 1) {
            return "Searching: " + running.get(0);
        }
        return "Searching " + running.size() + " queries in parallel";
    }

    /** The counter line, e.g. "3/12 queries · 17 sources · 4 pages open · 2/5 nodes covered · cycle 1/3". */
    public String counters() {
        StringBuilder line = new StringBuilder();
        line.append(done).append('/').append(total).append(" queries");
        line.append(" · ").append(sources).append(sources == 1 ? " source" : " sources");
        if (openPages > 0) {
            line.append(" · ").append(openPages).append(openPages == 1 ? " page open" : " pages open");
        }
        if (nodes > 0) {
            line.append(" · ").append(coveredNodes).append('/').append(nodes).append(" nodes covered");
        }
        if (cycles > 0) {
            line.append(" · cycle ").append(Math.min(cycle, cycles)).append('/').append(cycles);
        }
        return line.toString();
    }

    /** The bubble body: counters, the running queries, then the latest team activity. */
    public String body() {
        StringBuilder text = new StringBuilder(counters());
        if (running.size() > 1) {
            for (String query : running) {
                text.append("\n- Searching: ").append(query);
            }
        }
        for (String line : activity) {
            text.append("\n- ").append(line);
        }
        return text.toString();
    }

    public int getDone() {
        return done;
    }

    public int getTotal() {
        return total;
    }

    public int getSources() {
        return sources;
    }

    public int getCoveredNodes() {
        return coveredNodes;
    }

    public int getNodes() {
        return nodes;
    }

    public int getCycle() {
        return cycle;
    }

    public List<String> getRunning() {
        return running;
    }
}
