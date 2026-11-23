package com.aresstack.askai.research.visualize;

import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the SOURCES MINDMAP completely MECHANICALLY — no model in the loop, so it always renders:
 * the root is the research question, each USER SEARCH QUERY becomes a branch, and each branch
 * carries its best-rated sources as leaves (top {@value #MAX_PER_QUERY} by rerank score, plus
 * everything the user marked relevant; duplicates/excluded/superseded never appear). This is the
 * "take the model by the hand" answer to LLM-generated Mermaid that would not parse: structure
 * comes from DATA, labels are sanitized for the mindmap grammar, and the assembly is plain code.
 */
public final class SourceMindmap {

    static final int MAX_PER_QUERY = 5;
    private static final int MAX_LABEL_CHARS = 48;

    private SourceMindmap() {
    }

    /**
     * @return the Mermaid {@code mindmap} source, or {@code null} when not a single source
     *         qualifies (the caller shows an honest hint instead of an empty diagram).
     */
    public static String mermaid(String rootTitle, List<ResearchSourceRecord> sources) {
        Map<String, List<ResearchSourceRecord>> byQuery = groupQualifying(sources);
        if (byQuery.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("mindmap\n");
        boolean noRootTitle = rootTitle == null || rootTitle.trim().isEmpty();
        if (noRootTitle && byQuery.size() == 1 && !byQuery.keySet().iterator().next().isEmpty()) {
            // No research question, ONE search query: that query IS the center — never a
            // helper node like "Research" above it.
            Map.Entry<String, List<ResearchSourceRecord>> only = byQuery.entrySet().iterator().next();
            out.append("  root((").append(label(only.getKey(), "Research")).append("))\n");
            for (ResearchSourceRecord source : only.getValue()) {
                out.append("    [").append(label(source.getTitle(), source.getUrl())).append("]\n");
            }
            return out.toString();
        }
        out.append("  root((").append(label(rootTitle, "Research")).append("))\n");
        for (Map.Entry<String, List<ResearchSourceRecord>> branch : byQuery.entrySet()) {
            out.append("    (").append(label(branch.getKey(), "Other sources")).append(")\n");
            for (ResearchSourceRecord source : branch.getValue()) {
                out.append("      [").append(label(source.getTitle(), source.getUrl())).append("]\n");
            }
        }
        return out.toString();
    }

    /** Group by search query (recency order of first appearance), keep only the branch's best. */
    private static Map<String, List<ResearchSourceRecord>> groupQualifying(
            List<ResearchSourceRecord> sources) {
        Map<String, List<ResearchSourceRecord>> byQuery =
                new LinkedHashMap<String, List<ResearchSourceRecord>>();
        if (sources == null) {
            return byQuery;
        }
        for (ResearchSourceRecord source : sources) {
            if (source == null || !qualifies(source)) {
                continue;
            }
            String query = source.getSearchQuery();
            String key = query == null || query.trim().isEmpty() ? "" : query.trim();
            List<ResearchSourceRecord> branch = byQuery.get(key);
            if (branch == null) {
                branch = new ArrayList<ResearchSourceRecord>();
                byQuery.put(key, branch);
            }
            branch.add(source);
        }
        for (Map.Entry<String, List<ResearchSourceRecord>> entry : byQuery.entrySet()) {
            entry.setValue(bestOf(entry.getValue()));
        }
        return byQuery;
    }

    private static boolean qualifies(ResearchSourceRecord source) {
        SourceStatus status = source.getStatus();
        return status != SourceStatus.DUPLICATE && status != SourceStatus.EXCLUDED
                && status != SourceStatus.SUPERSEDED;
    }

    /** The branch's top {@link #MAX_PER_QUERY} by score — user-marked sources always stay in. */
    private static List<ResearchSourceRecord> bestOf(List<ResearchSourceRecord> branch) {
        List<ResearchSourceRecord> sorted = new ArrayList<ResearchSourceRecord>(branch);
        Collections.sort(sorted, new Comparator<ResearchSourceRecord>() {
            public int compare(ResearchSourceRecord a, ResearchSourceRecord b) {
                return Double.compare(b.getRerankScore(), a.getRerankScore());
            }
        });
        List<ResearchSourceRecord> best = new ArrayList<ResearchSourceRecord>();
        for (ResearchSourceRecord source : sorted) {
            if (best.size() < MAX_PER_QUERY || source.isUserRelevant()) {
                best.add(source);
            }
        }
        return best;
    }

    /** A mindmap-safe label — the shared {@link MindmapLabels} rules with this map's length cap. */
    static String label(String text, String fallback) {
        return MindmapLabels.sanitize(text, fallback, MAX_LABEL_CHARS);
    }
}
