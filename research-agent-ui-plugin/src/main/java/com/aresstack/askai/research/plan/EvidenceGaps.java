package com.aresstack.askai.research.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plan T4 gap review: turns the shared evidence analysis of the material read so far (phase 3's topic analysis, used
 * here as phase 2's consumer) into structured research gaps for the existing query planner. It plans nothing itself
 * and builds no outline: a gap is a concept node whose material is thin, redundant or missing, with what is there and
 * which queries found nothing, or a new topic of the material that lies inside the Weidezaun.
 *
 * <p>No gap is reported while sources still wait for processing: their passages are not in the analysis yet, so a
 * node that looks thin may only be waiting.
 */
public final class EvidenceGaps {

    /** The coverage status of a concept node in the analysis. */
    public enum Status { OK, THIN, REDUNDANT, MISSING }

    /** Where a new topic lies against the Weidezaun (scope alignment, separate from the concept alignment). */
    public enum Scope { IN, OUT, UNCLASSIFIED }

    /** One concept node of the analysis. */
    public static final class NodeEvidence {
        final String nodeId;
        final Status status;
        final int passages;
        final int distinctPassages;
        final int sources;
        final List<String> keyTerms;

        public NodeEvidence(String nodeId, Status status, int passages, int distinctPassages, int sources,
                            List<String> keyTerms) {
            this.nodeId = nodeId == null ? "" : nodeId;
            this.status = status == null ? Status.OK : status;
            this.passages = Math.max(0, passages);
            this.distinctPassages = Math.max(0, distinctPassages);
            this.sources = Math.max(0, sources);
            this.keyTerms = keyTerms == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(keyTerms));
        }
    }

    /** One topic of the material that no concept node holds. */
    public static final class NewTopic {
        final String title;
        final List<String> keyTerms;
        final int distinctPassages;
        final Scope scope;
        final String nearestOut;

        public NewTopic(String title, List<String> keyTerms, int distinctPassages, Scope scope, String nearestOut) {
            this.title = title == null ? "" : title.trim();
            this.keyTerms = keyTerms == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(keyTerms));
            this.distinctPassages = Math.max(0, distinctPassages);
            this.scope = scope == null ? Scope.UNCLASSIFIED : scope;
            this.nearestOut = nearestOut == null ? "" : nearestOut.trim();
        }
    }

    /** What the analysis says right now; the session adapts the shared snapshot to it. */
    public interface Analysis {
        /** The snapshot id: a review runs once per snapshot. */
        String id();

        /** {@code true} when no source waits for processing (every accepted source is in the analysis). */
        boolean settled();

        List<NodeEvidence> nodes();

        List<NewTopic> newTopics();
    }

    /** The source of the current analysis; {@code null} when none ran yet or the knowledge capability is off. */
    public interface Source {
        Analysis latest();
    }

    /** The gaps of one analysis. */
    public static final class Gaps {
        public static final Gaps NONE = new Gaps("", Collections.<String, List<String>>emptyMap(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList());

        private final String analysisId;
        private final Map<String, List<String>> evidenceByNode;
        private final List<String> newTopics;
        private final List<String> dropped;
        private final List<String> coveredNodes;

        Gaps(String analysisId, Map<String, List<String>> evidenceByNode, List<String> newTopics,
             List<String> dropped, List<String> coveredNodes) {
            this.coveredNodes = Collections.unmodifiableList(new ArrayList<String>(coveredNodes));
            this.analysisId = analysisId;
            this.evidenceByNode = Collections.unmodifiableMap(new LinkedHashMap<String, List<String>>(evidenceByNode));
            this.newTopics = Collections.unmodifiableList(new ArrayList<String>(newTopics));
            this.dropped = Collections.unmodifiableList(new ArrayList<String>(dropped));
        }

        public String getAnalysisId() {
            return analysisId;
        }

        /** Per gap node: the findings for the planner (status, what is there, what found nothing). */
        public Map<String, List<String>> getEvidenceByNode() {
            return evidenceByNode;
        }

        /** The new topics inside the Weidezaun, as "title: key terms". */
        public List<String> getNewTopics() {
            return newTopics;
        }

        /** The new topics left out, each with its reason ("outside scope …", "blacklist …"). */
        public List<String> getDropped() {
            return dropped;
        }

        /** The active nodes the analysis finds well covered (status OK): not deepened for now. */
        public List<String> getCoveredNodes() {
            return coveredNodes;
        }

        public boolean isEmpty() {
            return evidenceByNode.isEmpty() && newTopics.isEmpty();
        }
    }

    private EvidenceGaps() {
    }

    /**
     * @param analysis    the current analysis, or {@code null}
     * @param activeNodes the ids of the plan's active concept nodes; nodes outside are ignored
     * @param entries     the plan entries (the queries that found nothing are named per node)
     * @param blacklist   the typed blacklist terms; a new topic that names one is dropped
     * @param maxTopics   the most new topics handed on (setting), the ones with the most distinct material first
     * @param maxFailed   the most queries that found nothing named per node (setting)
     */
    public static Gaps of(Analysis analysis, Collection<String> activeNodes, Collection<SearchPlanEntry> entries,
                          List<String> blacklist, int maxTopics, int maxFailed) {
        if (analysis == null || !analysis.settled()) {
            return Gaps.NONE;
        }
        Map<String, List<String>> byNode = new LinkedHashMap<String, List<String>>();
        List<String> covered = new ArrayList<String>();
        for (NodeEvidence node : analysis.nodes()) {
            if (!activeNodes.contains(node.nodeId)) {
                continue;
            }
            if (node.status == Status.OK) {
                covered.add(node.nodeId);
                continue;
            }
            List<String> findings = new ArrayList<String>();
            findings.add(statusLine(node));
            if (node.status != Status.MISSING && !node.keyTerms.isEmpty()) {
                findings.add("the material so far is about: " + join(node.keyTerms));
            }
            List<String> nothing = foundNothing(entries, node.nodeId, maxFailed);
            if (!nothing.isEmpty()) {
                findings.add("found nothing: " + String.join("; ", nothing));
            }
            byNode.put(node.nodeId, findings);
        }
        List<NewTopic> topics = new ArrayList<NewTopic>(analysis.newTopics());
        Collections.sort(topics, new Comparator<NewTopic>() {
            public int compare(NewTopic a, NewTopic b) {
                return Integer.compare(b.distinctPassages, a.distinctPassages);
            }
        });
        List<String> kept = new ArrayList<String>();
        List<String> dropped = new ArrayList<String>();
        for (NewTopic topic : topics) {
            String text = topic.title + (topic.keyTerms.isEmpty() ? "" : ": " + join(topic.keyTerms));
            if (topic.title.isEmpty() && topic.keyTerms.isEmpty()) {
                continue;
            }
            String term = blacklisted(text, blacklist);
            if (term != null) {
                dropped.add("topic \"" + text + "\" dropped: blacklist \"" + term + "\"");
            } else if (topic.scope == Scope.OUT) {
                dropped.add("topic \"" + text + "\" dropped: outside scope"
                        + (topic.nearestOut.isEmpty() ? "" : " (nearest exclusion: " + topic.nearestOut + ")"));
            } else if (kept.size() < Math.max(0, maxTopics)) {
                kept.add(text);
            }
        }
        return new Gaps(analysis.id(), byNode, kept, dropped, covered);
    }

    private static String statusLine(NodeEvidence node) {
        switch (node.status) {
            case MISSING:
                return "MISSING: no material for this node yet";
            case REDUNDANT:
                return "REDUNDANT: " + node.passages + " passages but only " + node.distinctPassages
                        + " distinct, from " + node.sources + " sources";
            default:
                return "THIN: " + node.distinctPassages + " distinct passages from " + node.sources + " sources";
        }
    }

    /** The node's queries that ran and brought no source, newest last, at most {@code max}. */
    static List<String> foundNothing(Collection<SearchPlanEntry> entries, String nodeId, int max) {
        List<SearchPlanEntry> ofNode = new ArrayList<SearchPlanEntry>();
        for (SearchPlanEntry entry : entries) {
            if (nodeId.equals(entry.getNodeId()) && entry.getAcceptedSourceIds().isEmpty()
                    && (entry.getStatus() == SearchPlanEntry.Status.DONE
                    || entry.getStatus() == SearchPlanEntry.Status.FAILED)) {
                ofNode.add(entry);
            }
        }
        Collections.sort(ofNode, new Comparator<SearchPlanEntry>() {
            public int compare(SearchPlanEntry a, SearchPlanEntry b) {
                return Long.compare(a.getCreatedAtMillis(), b.getCreatedAtMillis());
            }
        });
        List<String> queries = new ArrayList<String>();
        for (SearchPlanEntry entry : ofNode) {
            if (!queries.contains(entry.getQuery())) {
                queries.add(entry.getQuery());
            }
        }
        int keep = Math.max(0, max);
        return queries.size() <= keep ? queries : queries.subList(queries.size() - keep, queries.size());
    }

    private static String blacklisted(String text, List<String> blacklist) {
        if (blacklist == null) {
            return null;
        }
        String lower = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        for (String term : blacklist) {
            String t = term == null ? "" : term.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
            if (!t.isEmpty() && lower.contains(" " + t + " ")) {
                return term.trim();
            }
        }
        return null;
    }

    private static String join(List<String> terms) {
        return String.join(", ", terms);
    }
}
