package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.TopicClusterer;
import com.aresstack.askai.research.knowledge.TopicDendrogram;
import com.aresstack.askai.research.knowledge.VectorMath;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Phase 3a: the topic analysis. Pure code, no model, no embedding call: it reads the stored passages and vectors,
 * the ONE clustering run of the outline generation ({@link LiveTopicTree}: flat topics plus their merge tree) and the
 * concept nodes with their query vectors, and measures per topic material, diversity, sources, sub-topics, relations,
 * concept alignment and redundancy, plus the evidence of every concept node.
 * <p>
 * Everything comes from cuts of the one merge tree, so nothing is clustered twice: near duplicates are the groups
 * of the duplicate cut, sub-topics the groups of the sub-topic cut inside a topic, topic groups the groups of the
 * group cut. Every order is defined; the same input gives the same analysis.
 */
public final class TopicAnalyzer {

    private final TopicAnalysisSettings settings;

    public TopicAnalyzer(TopicAnalysisSettings settings) {
        this.settings = settings == null ? TopicAnalysisSettings.defaults() : settings;
    }

    /**
     * @param passages every eligible passage (the concept coverage counts them all; topics cover the clustered ones)
     * @param vectors  their stored document vectors
     * @param tree     the clustering run of these passages (possibly of a limited subset)
     * @param anchors  the concept nodes below the book frame, in concept order, with their query vectors
     * @param fence    the pinned search fence (the search scope gate's anchors and thresholds); {@code null} = none
     * @throws ClusteringMonitor.ClusteringCancelledException when the monitor cancels
     */
    public TopicAnalysis analyze(List<AnalysisPassage> passages, Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                 LiveTopicTree tree, List<ConceptAnchor> anchors, ScopeFence fence,
                                 ClusteringMonitor monitor) {
        ClusteringMonitor m = monitor == null ? ClusteringMonitor.NONE : monitor;
        Map<String, AnalysisPassage> byId = new LinkedHashMap<String, AnalysisPassage>();
        Map<String, String> sourceOf = new LinkedHashMap<String, String>();
        for (AnalysisPassage passage : passages) {
            byId.put(passage.getPassageId(), passage);
            sourceOf.put(passage.getPassageId(), passage.getSourceId());
        }
        List<ConceptAnchor> nodes = anchors == null ? Collections.<ConceptAnchor>emptyList() : anchors;
        TopicDendrogram dendrogram = tree.getDendrogram();
        double topicCutoff = dendrogram == null ? 0 : dendrogram.getTopicCutoff();

        Map<String, String> duplicateGroupOf = new LinkedHashMap<String, String>();
        if (dendrogram != null) {
            for (List<String> group : dendrogram.cut(Math.min(settings.getDuplicateDistance(), topicCutoff))) {
                for (String id : group) {
                    duplicateGroupOf.put(id, group.get(0));
                }
            }
        }
        for (String id : byId.keySet()) {
            if (!duplicateGroupOf.containsKey(id)) {
                duplicateGroupOf.put(id, id);
            }
        }
        List<List<String>> subtopicCut = dendrogram == null || settings.getSubtopicDistance() >= topicCutoff
                ? Collections.<List<String>>emptyList() : dendrogram.cut(settings.getSubtopicDistance());
        Map<String, String> groupRootOf = new HashMap<String, String>();
        if (dendrogram != null) {
            for (List<String> group : dendrogram.cut(Math.max(settings.getTopicGroupDistance(), topicCutoff))) {
                for (String id : group) {
                    groupRootOf.put(id, group.get(0));
                }
            }
        }

        // Passage-level concept assignment over ALL eligible passages (nearest node above threshold and margin).
        Map<String, Integer> nodeOf = new HashMap<String, Integer>();
        if (!nodes.isEmpty()) {
            int done = 0;
            for (AnalysisPassage passage : byId.values()) {
                if (m.isCancelled()) {
                    throw new ClusteringMonitor.ClusteringCancelledException();
                }
                EmbeddingPort.EmbeddingVector vector = vectors.get(passage.getPassageId());
                if (vector != null) {
                    int best = nearest(nodes, vector);
                    if (best >= 0) {
                        nodeOf.put(passage.getPassageId(), best);
                    }
                }
                done++;
                if (done % 256 == 0 || done == byId.size()) {
                    m.progress("Comparing the evidence with the concept", done, byId.size());
                }
            }
        }

        // Topic profiles (first pass: everything that does not need the totals).
        List<Draft> drafts = new ArrayList<Draft>();
        for (LiveTopicProjection topic : tree.getTopics()) {
            if (m.isCancelled()) {
                throw new ClusteringMonitor.ClusteringCancelledException();
            }
            Draft draft = draft(topic, byId, vectors, duplicateGroupOf, subtopicCut, groupRootOf, nodes, fence);
            draft.conceptMaterial = conceptMaterial(topic.getMemberPassageIds(), nodeOf, duplicateGroupOf, nodes);
            List<Subtopic> parts = conceptParts(topic.getMemberPassageIds(), draft.conceptMaterial, nodeOf, nodes,
                    byId, vectors, duplicateGroupOf, subtopicCut);
            if (parts != null) {
                draft.subtopics = parts;
            }
            drafts.add(draft);
        }
        int significantDistinct = 0;
        for (Draft draft : drafts) {
            if (draft.significant) {
                significantDistinct += draft.distinct;
            }
        }

        // Relations: cophenetic distances between the topics of one group.
        List<List<String>> memberLists = new ArrayList<List<String>>();
        for (Draft draft : drafts) {
            memberLists.add(draft.topic.getMemberPassageIds());
        }
        Map<String, Double> joins = dendrogram == null ? Collections.<String, Double>emptyMap()
                : dendrogram.joinHeights(memberLists);

        List<TopicProfile> profiles = new ArrayList<TopicProfile>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            final List<TopicRelation> related = new ArrayList<TopicRelation>();
            for (int j = 0; j < drafts.size(); j++) {
                Draft other = drafts.get(j);
                if (i == j || !draft.groupRoot.equals(other.groupRoot)) {
                    continue;
                }
                Double height = joins.get(Math.min(i, j) + ":" + Math.max(i, j));
                related.add(new TopicRelation(other.topic.getClusterId(), height == null ? 1.0 : height,
                        VectorMath.cosine(draft.centroid, other.centroid)));
            }
            Collections.sort(related, new Comparator<TopicRelation>() {
                public int compare(TopicRelation a, TopicRelation b) {
                    int byHeight = Double.compare(a.getJoinDistance(), b.getJoinDistance());
                    return byHeight != 0 ? byHeight : a.getTopicId().compareTo(b.getTopicId());
                }
            });
            double share = draft.significant && significantDistinct > 0
                    ? (double) draft.distinct / significantDistinct : 0;
            profiles.add(new TopicProfile(draft.topic.getClusterId(), draft.topic.getTitle(),
                    draft.topic.getMemberPassageIds(), representatives(draft.topic), draft.distinct, draft.sources,
                    draft.domains, draft.materialChars, draft.dispersion, draft.subtopics,
                    groupId(draft.groupRoot, drafts), related, draft.anchorId, draft.anchorSimilarity,
                    draft.aligned, draft.materialCovered, draft.sourceConfirmed, share,
                    draft.significant && share >= settings.getMajorTopicShare(), draft.scope,
                    draft.conceptMaterial, restSubtopicOf(draft.topic.getMemberPassageIds(), draft.subtopics,
                            vectors)));
        }
        Collections.sort(profiles, new Comparator<TopicProfile>() {
            public int compare(TopicProfile a, TopicProfile b) {
                int byDistinct = Integer.compare(b.getDistinctPassageCount(), a.getDistinctPassageCount());
                if (byDistinct != 0) {
                    return byDistinct;
                }
                int byPassages = Integer.compare(b.getPassageCount(), a.getPassageCount());
                return byPassages != 0 ? byPassages : a.getTopicId().compareTo(b.getTopicId());
            }
        });

        List<TopicGroup> groups = groups(profiles);
        List<ConceptCoverage> coverage = coverage(nodes, byId, nodeOf, duplicateGroupOf, profiles);
        return new TopicAnalysis(profiles, groups, coverage, duplicateGroupOf, sourceOf, settings);
    }

    /** The per-topic measurements before the totals are known. */
    private static final class Draft {
        LiveTopicProjection topic;
        int distinct;
        int sources;
        int domains;
        long materialChars;
        double dispersion;
        EmbeddingPort.EmbeddingVector centroid;
        List<Subtopic> subtopics;
        String groupRoot;
        String anchorId = "";
        double anchorSimilarity;
        boolean aligned;
        boolean materialCovered;
        boolean sourceConfirmed;
        boolean significant;
        ScopeAlignment scope = ScopeAlignment.UNKNOWN;
        List<String> conceptMaterial = Collections.emptyList();
    }

    /**
     * The concept nodes whose own material lies in the topic: nodes to which at least the minimum of the topic's
     * DISTINCT passages are assigned (nearest node above threshold and margin, the assignment of the coverage), in
     * concept order. A topic carrying such material is the concept's research even when its centroid lies between
     * several nodes and so aligns with none.
     * <p>
     * Live run 4 (2026-11-12): one topic held 99.5 % of the passages and the covered material of four concept nodes;
     * its centroid aligned with no node, so the topic counted as new and the gap review reported the whole research
     * as one "new topic" to search for.
     */
    private List<String> conceptMaterial(List<String> members, Map<String, Integer> nodeOf,
                                         Map<String, String> duplicateGroupOf, List<ConceptAnchor> nodes) {
        Map<Integer, Set<String>> distinctByNode = new TreeMap<Integer, Set<String>>();
        for (String id : members) {
            Integer node = nodeOf.get(id);
            if (node == null) {
                continue;
            }
            Set<String> distinct = distinctByNode.get(node);
            if (distinct == null) {
                distinct = new HashSet<String>();
                distinctByNode.put(node, distinct);
            }
            String group = duplicateGroupOf.get(id);
            distinct.add(group == null ? id : group);
        }
        List<String> result = new ArrayList<String>();
        for (Map.Entry<Integer, Set<String>> entry : distinctByNode.entrySet()) {
            if (settings.isMaterialCovered(entry.getValue().size())) {
                result.add(nodes.get(entry.getKey()).getId());
            }
        }
        return result;
    }

    private Draft draft(LiveTopicProjection topic, Map<String, AnalysisPassage> byId,
                        Map<String, EmbeddingPort.EmbeddingVector> vectors, Map<String, String> duplicateGroupOf,
                        List<List<String>> subtopicCut, Map<String, String> groupRootOf, List<ConceptAnchor> nodes,
                        ScopeFence fence) {
        Draft draft = new Draft();
        draft.topic = topic;
        List<String> members = topic.getMemberPassageIds();
        Set<String> seenGroups = new HashSet<String>();
        Set<String> sources = new TreeSet<String>();
        Set<String> domains = new TreeSet<String>();
        long chars = 0;
        List<EmbeddingPort.EmbeddingVector> memberVectors = new ArrayList<EmbeddingPort.EmbeddingVector>();
        for (String id : members) {
            AnalysisPassage passage = byId.get(id);
            String group = duplicateGroupOf.get(id);
            if (seenGroups.add(group == null ? id : group) && passage != null) {
                chars += passage.getText().length();
            }
            if (passage != null) {
                if (!passage.getSourceId().isEmpty()) {
                    sources.add(passage.getSourceId());
                }
                if (!passage.getDomain().isEmpty()) {
                    domains.add(passage.getDomain());
                }
            }
            EmbeddingPort.EmbeddingVector vector = vectors.get(id);
            if (vector != null) {
                memberVectors.add(unit(vector));
            }
        }
        draft.distinct = seenGroups.size();
        draft.sources = sources.size();
        draft.domains = domains.size();
        draft.materialChars = chars;
        // Material counts DISTINCT passages; confirmation counts sources and domains (D6, separate criteria).
        draft.materialCovered = settings.isMaterialCovered(draft.distinct);
        draft.sourceConfirmed = settings.isSourceConfirmed(draft.sources, draft.domains);
        draft.significant = draft.materialCovered && draft.sourceConfirmed;
        if (memberVectors.isEmpty()) {
            throw new IllegalArgumentException("topic " + topic.getClusterId() + " has no stored vectors");
        }
        draft.centroid = unit(VectorMath.mean(memberVectors));
        if (fence != null) {
            draft.scope = fence.align(draft.centroid);
        }
        double similarity = 0;
        for (EmbeddingPort.EmbeddingVector vector : memberVectors) {
            similarity += VectorMath.cosine(vector, draft.centroid);
        }
        draft.dispersion = Math.max(0, 1.0 - similarity / memberVectors.size());
        draft.subtopics = subtopics(members, byId, vectors, duplicateGroupOf, subtopicCut);
        String root = groupRootOf.get(members.isEmpty() ? "" : members.get(0));
        draft.groupRoot = root == null ? topic.getClusterId() : root;
        if (!nodes.isEmpty()) {
            double[] scores = scores(nodes, draft.centroid);
            int best = best(scores);
            double bestScore = scores[best];
            draft.anchorId = nodes.get(best).getId();
            draft.anchorSimilarity = bestScore;
            draft.aligned = bestScore >= settings.getConceptThreshold()
                    && bestScore - nearestOfAnotherLineage(nodes, scores, best) >= settings.getConceptMargin();
        }
        return draft;
    }

    /** One aspect of a topic from a passage group, or {@code null} below the minimum of distinct passages. */
    private Subtopic subtopic(List<String> group, Map<String, AnalysisPassage> byId,
                              Map<String, EmbeddingPort.EmbeddingVector> vectors, Map<String, String> duplicateGroupOf,
                              String conceptAnchorId) {
        Set<String> groupsSeen = new HashSet<String>();
        Set<String> sources = new TreeSet<String>();
        List<EmbeddingPort.EmbeddingVector> unitVectors = new ArrayList<EmbeddingPort.EmbeddingVector>();
        for (String id : group) {
            String duplicate = duplicateGroupOf.get(id);
            groupsSeen.add(duplicate == null ? id : duplicate);
            AnalysisPassage passage = byId.get(id);
            if (passage != null && !passage.getSourceId().isEmpty()) {
                sources.add(passage.getSourceId());
            }
            unitVectors.add(unit(vectors.get(id)));
        }
        if (!settings.isMaterialCovered(groupsSeen.size())) {
            return null; // a sub-topic needs distinct material too, repeated copies are no aspect
        }
        EmbeddingPort.EmbeddingVector centre = VectorMath.mean(unitVectors);
        String representative = group.get(0);
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < group.size(); i++) {
            double score = VectorMath.cosine(unitVectors.get(i), centre);
            if (score > best) {
                best = score;
                representative = group.get(i);
            }
        }
        AnalysisPassage passage = byId.get(representative);
        String title = TopicClusterer.suggestedTitle(passage == null ? "" : passage.getText());
        String id = "subtopic-" + LiveTopicProjection.deterministicClusterId(group).substring("topic-".length());
        return new Subtopic(id, title, representative, group, groupsSeen.size(), sources.size(), conceptAnchorId);
    }

    /**
     * The concept parts of a topic that carries the own material of two concept nodes of independent lineage (see
     * {@link #conceptMaterial}): one aspect per such node, made of the topic's passages assigned to that node, in
     * concept order; passages assigned to no node are the topic's rest. {@code null} when the topic carries no two
     * independent concept areas, so its aspects stay the sub-topic cut.
     * <p>
     * Live run 4 (2026-11-12): with an embedding model whose passages lie close together, one topic held the material
     * of four concept nodes with no sub-topic above the sub-topic distance. The outline planned four chapters on that
     * one topic, and three were dropped as repeated material although the coverage had assigned 392, 251, 341 and 5
     * passages to the four nodes. The parts use the coverage's own assignment: one analysis, one truth.
     */
    private List<Subtopic> conceptParts(List<String> members, List<String> conceptMaterial,
                                        Map<String, Integer> nodeOf, List<ConceptAnchor> nodes,
                                        Map<String, AnalysisPassage> byId,
                                        Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                        Map<String, String> duplicateGroupOf, List<List<String>> subtopicCut) {
        Map<String, String> parentOf = new HashMap<String, String>();
        Map<String, Integer> indexOf = new HashMap<String, Integer>();
        for (int i = 0; i < nodes.size(); i++) {
            parentOf.put(nodes.get(i).getId(), nodes.get(i).getParentId());
            indexOf.put(nodes.get(i).getId(), i);
        }
        if (!independentAreas(conceptMaterial, parentOf)) {
            return null;
        }
        List<Subtopic> parts = new ArrayList<Subtopic>();
        for (String nodeId : conceptMaterial) {
            Integer index = indexOf.get(nodeId);
            List<String> group = new ArrayList<String>();
            for (String id : members) {
                if (index.equals(nodeOf.get(id))) {
                    group.add(id);
                }
            }
            parts.addAll(piecesOf(group, nodeId, byId, vectors, duplicateGroupOf, subtopicCut));
        }
        return parts.size() < 2 ? null : parts;
    }

    /**
     * The aspects of one concept node's material in a topic: the groups of the sub-topic cut within that material
     * that keep the minimum of distinct passages, each marked with the node; the node's passages in no such group
     * join its largest group. Without such a group the whole material is one aspect.
     * <p>
     * Live run 4 (2026-11-12): one aspect per concept node gave every chapter exactly one section, also the chapter
     * holding 809 passages of 74 sources; the sub-topic cut inside the node's material gives the section planning the
     * aspects it can treat separately.
     */
    private List<Subtopic> piecesOf(List<String> group, String nodeId, Map<String, AnalysisPassage> byId,
                                    Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                    Map<String, String> duplicateGroupOf, List<List<String>> subtopicCut) {
        Set<String> inNode = new HashSet<String>(group);
        List<List<String>> pieces = new ArrayList<List<String>>();
        Set<String> placed = new HashSet<String>();
        for (List<String> cut : subtopicCut) {
            List<String> piece = new ArrayList<String>();
            for (String id : cut) {
                if (inNode.contains(id)) {
                    piece.add(id);
                }
            }
            if (!piece.isEmpty() && subtopic(piece, byId, vectors, duplicateGroupOf, nodeId) != null) {
                pieces.add(piece);
                placed.addAll(piece);
            }
        }
        if (pieces.isEmpty()) {
            Subtopic whole = subtopic(group, byId, vectors, duplicateGroupOf, nodeId);
            return whole == null ? Collections.<Subtopic>emptyList() : Collections.singletonList(whole);
        }
        List<String> largest = pieces.get(0);
        for (List<String> piece : pieces) {
            if (piece.size() > largest.size()) {
                largest = piece;
            }
        }
        for (String id : group) {
            if (!placed.contains(id)) {
                largest.add(id);
            }
        }
        List<Subtopic> result = new ArrayList<Subtopic>();
        for (List<String> piece : pieces) {
            result.add(subtopic(piece, byId, vectors, duplicateGroupOf, nodeId));
        }
        return result;
    }

    /** Whether two of the nodes lie in independent lineages (neither below the other). */
    private static boolean independentAreas(List<String> nodeIds, Map<String, String> parentOf) {
        for (int a = 0; a < nodeIds.size(); a++) {
            for (int b = a + 1; b < nodeIds.size(); b++) {
                if (!descends(nodeIds.get(a), nodeIds.get(b), parentOf)
                        && !descends(nodeIds.get(b), nodeIds.get(a), parentOf)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Subtopic> subtopics(List<String> members, Map<String, AnalysisPassage> byId,
                                     Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                     Map<String, String> duplicateGroupOf, List<List<String>> subtopicCut) {
        Set<String> inside = new HashSet<String>(members);
        List<Subtopic> result = new ArrayList<Subtopic>();
        for (List<String> group : subtopicCut) {
            if (group.isEmpty() || !inside.contains(group.get(0))) {
                continue;
            }
            Subtopic subtopic = subtopic(group, byId, vectors, duplicateGroupOf, "");
            if (subtopic != null) {
                result.add(subtopic);
            }
        }
        if (result.size() < 2) {
            return Collections.emptyList(); // one aspect is the topic itself, not a sub-topic
        }
        Collections.sort(result, new Comparator<Subtopic>() {
            public int compare(Subtopic a, Subtopic b) {
                int byDistinct = Integer.compare(b.getDistinctPassageCount(), a.getDistinctPassageCount());
                return byDistinct != 0 ? byDistinct : a.getSubtopicId().compareTo(b.getSubtopicId());
            }
        });
        return result;
    }

    /**
     * Each passage of the topic in none of its sub-topics, mapped to the sub-topic with the nearest centroid. Live
     * outline acceptance 2026-11-12 (bge-m3): the rest of one topic came from three different areas and went as a
     * whole to the section of the largest sub-topic, which got 8200 instead of 2450 words.
     */
    private static Map<String, String> restSubtopicOf(List<String> members, List<Subtopic> subtopics,
                                                      Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        Map<String, String> home = new LinkedHashMap<String, String>();
        if (subtopics.isEmpty()) {
            return home;
        }
        Set<String> inSubtopics = new HashSet<String>();
        List<EmbeddingPort.EmbeddingVector> centroids = new ArrayList<EmbeddingPort.EmbeddingVector>();
        for (Subtopic subtopic : subtopics) {
            inSubtopics.addAll(subtopic.getPassageIds());
            List<EmbeddingPort.EmbeddingVector> memberVectors = new ArrayList<EmbeddingPort.EmbeddingVector>();
            for (String id : subtopic.getPassageIds()) {
                EmbeddingPort.EmbeddingVector vector = vectors.get(id);
                if (vector != null) {
                    memberVectors.add(unit(vector));
                }
            }
            centroids.add(memberVectors.isEmpty() ? null : unit(VectorMath.mean(memberVectors)));
        }
        for (String id : members) {
            EmbeddingPort.EmbeddingVector vector = vectors.get(id);
            if (inSubtopics.contains(id) || vector == null) {
                continue;
            }
            int best = -1;
            double bestSimilarity = -2;
            for (int i = 0; i < centroids.size(); i++) {
                if (centroids.get(i) == null) {
                    continue;
                }
                double similarity = VectorMath.cosine(vector, centroids.get(i));
                if (similarity > bestSimilarity) {
                    best = i;
                    bestSimilarity = similarity;
                }
            }
            if (best >= 0) {
                home.put(id, subtopics.get(best).getSubtopicId());
            }
        }
        return home;
    }

    private List<String> representatives(LiveTopicProjection topic) {
        List<String> ids = topic.getRepresentativePassageIds();
        return ids.size() > settings.getRepresentatives() ? ids.subList(0, settings.getRepresentatives()) : ids;
    }

    /** The group of a root: a stable id from the group's topics. */
    private static String groupId(String root, List<Draft> drafts) {
        List<String> topicIds = new ArrayList<String>();
        for (Draft draft : drafts) {
            if (draft.groupRoot.equals(root)) {
                topicIds.add(draft.topic.getClusterId());
            }
        }
        return "group-" + LiveTopicProjection.deterministicClusterId(topicIds).substring("topic-".length());
    }

    private static List<TopicGroup> groups(List<TopicProfile> profiles) {
        Map<String, List<TopicProfile>> byGroup = new LinkedHashMap<String, List<TopicProfile>>();
        for (TopicProfile profile : profiles) {
            List<TopicProfile> members = byGroup.get(profile.getGroupId());
            if (members == null) {
                members = new ArrayList<TopicProfile>();
                byGroup.put(profile.getGroupId(), members);
            }
            members.add(profile);
        }
        List<TopicGroup> groups = new ArrayList<TopicGroup>();
        for (Map.Entry<String, List<TopicProfile>> entry : byGroup.entrySet()) {
            List<String> ids = new ArrayList<String>();
            int distinct = 0;
            for (TopicProfile profile : entry.getValue()) {
                ids.add(profile.getTopicId());
                distinct += profile.getDistinctPassageCount();
            }
            groups.add(new TopicGroup(entry.getKey(), ids, distinct));
        }
        // Profiles are largest first, so groups come in the order of their largest topic.
        return groups;
    }

    private List<ConceptCoverage> coverage(List<ConceptAnchor> nodes, Map<String, AnalysisPassage> byId,
                                           Map<String, Integer> nodeOf, Map<String, String> duplicateGroupOf,
                                           List<TopicProfile> profiles) {
        Map<String, Integer> indexOfId = new HashMap<String, Integer>();
        for (int i = 0; i < nodes.size(); i++) {
            indexOfId.put(nodes.get(i).getId(), i);
        }
        List<List<String>> passagesOf = new ArrayList<List<String>>();
        for (int i = 0; i < nodes.size(); i++) {
            passagesOf.add(new ArrayList<String>());
        }
        for (String passageId : byId.keySet()) {
            Integer node = nodeOf.get(passageId);
            // Roll the passage up to the node and every ancestor.
            Set<Integer> visited = new HashSet<Integer>();
            while (node != null && visited.add(node)) {
                passagesOf.get(node).add(passageId);
                node = indexOfId.get(nodes.get(node).getParentId());
            }
        }
        List<List<String>> topicsOf = new ArrayList<List<String>>();
        for (int i = 0; i < nodes.size(); i++) {
            topicsOf.add(new ArrayList<String>());
        }
        for (TopicProfile profile : profiles) {
            if (!profile.isSignificant() || !profile.isConceptAligned()) {
                continue;
            }
            Integer node = indexOfId.get(profile.getConceptAnchorId());
            Set<Integer> visited = new HashSet<Integer>();
            while (node != null && visited.add(node)) {
                topicsOf.get(node).add(profile.getTopicId());
                node = indexOfId.get(nodes.get(node).getParentId());
            }
        }
        Map<String, String> topicOfPassage = new HashMap<String, String>();
        for (TopicProfile profile : profiles) {
            for (String id : profile.getPassageIds()) {
                topicOfPassage.put(id, profile.getTopicId());
            }
        }
        List<List<Integer>> childrenOf = new ArrayList<List<Integer>>();
        for (int i = 0; i < nodes.size(); i++) {
            childrenOf.add(new ArrayList<Integer>());
        }
        for (int i = 0; i < nodes.size(); i++) {
            Integer parent = indexOfId.get(nodes.get(i).getParentId());
            if (parent != null && parent != i) {
                childrenOf.get(parent).add(i);
            }
        }
        // Children first: a parent's breadth needs its children's status.
        ConceptCoverage[] result = new ConceptCoverage[nodes.size()];
        int[] distinctCount = new int[nodes.size()];
        for (int i = nodes.size() - 1; i >= 0; i--) {
            ConceptAnchor node = nodes.get(i);
            List<String> ids = passagesOf.get(i);
            Set<String> distinct = new HashSet<String>();
            Set<String> sources = new TreeSet<String>();
            Set<String> domains = new TreeSet<String>();
            Map<String, Set<String>> distinctByTopic = new HashMap<String, Set<String>>();
            for (String id : ids) {
                String group = duplicateGroupOf.get(id);
                String key = group == null ? id : group;
                distinct.add(key);
                AnalysisPassage passage = byId.get(id);
                if (!passage.getSourceId().isEmpty()) {
                    sources.add(passage.getSourceId());
                }
                if (!passage.getDomain().isEmpty()) {
                    domains.add(passage.getDomain());
                }
                String topic = topicOfPassage.get(id);
                if (topic != null) {
                    Set<String> inTopic = distinctByTopic.get(topic);
                    if (inTopic == null) {
                        inTopic = new HashSet<String>();
                        distinctByTopic.put(topic, inTopic);
                    }
                    inTopic.add(key);
                }
            }
            distinctCount[i] = distinct.size();
            int largestTopic = 0;
            for (Set<String> inTopic : distinctByTopic.values()) {
                largestTopic = Math.max(largestTopic, inTopic.size());
            }
            List<Integer> children = childrenOf.get(i);
            int covered = 0;
            int largestChild = 0;
            for (Integer child : children) {
                if (result[child].getStatus() == ConceptCoverage.Status.COVERED) {
                    covered++;
                }
                largestChild = Math.max(largestChild, distinctCount[child]);
            }
            double dominantChild = children.isEmpty() || distinct.isEmpty() ? 0
                    : (double) largestChild / distinct.size();
            boolean broad = children.isEmpty()
                    || (double) covered / children.size() >= settings.getMinimumChildCoverage();
            boolean balanced = children.isEmpty() || children.size() == 1
                    || dominantChild <= settings.getMaximumDominantShare();
            result[i] = new ConceptCoverage(node.getId(), node.getLabel(), node.getPath(), node.getDepth(),
                    node.getParentId(), ids, distinct.size(), sources.size(), domains.size(), topicsOf.get(i),
                    settings.isMaterialCovered(distinct.size()),
                    settings.isSourceConfirmed(sources.size(), domains.size()), children.size(), covered,
                    dominantChild, broad, balanced, distinctByTopic.size(),
                    distinct.isEmpty() ? 0 : (double) largestTopic / distinct.size());
        }
        List<ConceptCoverage> coverage = new ArrayList<ConceptCoverage>();
        Collections.addAll(coverage, result);
        return coverage;
    }

    /**
     * The split rule of the clustering run: a topic that holds the material of two independent concept areas (each
     * with at least the minimum of distinct passages, from nodes of which neither lies below the other) is not one
     * topic. It falls apart along the merge tree into the two groups it was merged from, and those are judged again,
     * never below the sub-topic distance (deeper parts are sub-topics or near duplicates of one topic). Topics new to
     * the concept are never split; without concept nodes the rule splits nothing.
     * <p>
     * Live run 4 (2026-11-12): with an embedding model whose passages lie close together, average linkage merged the
     * whole corpus below the topic distance into one topic although four concept nodes each held their own material;
     * the outline then had one topic with eighteen sub-topics, the search one "new topic" for the whole research.
     * Uses only settings that exist anyway: the concept threshold and margin, the minimum distinct passages, the
     * duplicate and the sub-topic distance.
     *
     * @param vectors the stored document vectors of the clustered passages
     * @param anchors the concept nodes below the book frame with their query vectors
     */
    public TopicDendrogram.SplitRule conceptSplit(final Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                                  List<ConceptAnchor> anchors) {
        if (anchors == null || anchors.size() < 2 || vectors == null) {
            return TopicDendrogram.SplitRule.NONE;
        }
        final List<ConceptAnchor> nodes = new ArrayList<ConceptAnchor>(anchors);
        final Map<String, String> parentOf = new HashMap<String, String>();
        for (ConceptAnchor node : nodes) {
            parentOf.put(node.getId(), node.getParentId());
        }
        return new TopicDendrogram.SplitRule() {
            private final Map<String, Integer> nodeOf = new HashMap<String, Integer>();
            private Map<String, String> duplicateGroupOf;

            public boolean split(TopicDendrogram tree, List<String> members, double height) {
                if (height <= settings.getSubtopicDistance()) {
                    return false;
                }
                if (duplicateGroupOf == null) {
                    duplicateGroupOf = new HashMap<String, String>();
                    for (List<String> group : tree.cut(settings.getDuplicateDistance())) {
                        for (String id : group) {
                            duplicateGroupOf.put(id, group.get(0));
                        }
                    }
                }
                Map<String, Integer> assigned = new HashMap<String, Integer>();
                for (String id : members) {
                    int node = nodeOf(id);
                    if (node >= 0) {
                        assigned.put(id, node);
                    }
                }
                return independentAreas(conceptMaterial(members, assigned, duplicateGroupOf, nodes), parentOf);
            }

            private int nodeOf(String passageId) {
                Integer known = nodeOf.get(passageId);
                if (known == null) {
                    EmbeddingPort.EmbeddingVector vector = vectors.get(passageId);
                    known = vector == null ? -1 : nearest(nodes, vector);
                    nodeOf.put(passageId, known);
                }
                return known;
            }
        };
    }

    /** The index of the nearest node above threshold and margin, or -1. */
    private int nearest(List<ConceptAnchor> nodes, EmbeddingPort.EmbeddingVector vector) {
        double[] scores = scores(nodes, vector);
        int best = best(scores);
        double bestScore = scores[best];
        return bestScore >= settings.getConceptThreshold()
                && bestScore - nearestOfAnotherLineage(nodes, scores, best) >= settings.getConceptMargin() ? best : -1;
    }

    private static double[] scores(List<ConceptAnchor> nodes, EmbeddingPort.EmbeddingVector vector) {
        double[] scores = new double[nodes.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = VectorMath.cosine(nodes.get(i).getVector(), vector);
        }
        return scores;
    }

    /** The index of the highest score; ties keep the earlier node. */
    private static int best(double[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * The best score among nodes outside the best node's lineage (its ancestors and descendants), or negative
     * infinity when there is none. A section anchor lies close to its own chapter anchor by construction (live run
     * 3: about 0.01 apart), so measuring the lead against them would reject passages that clearly belong there.
     */
    static double nearestOfAnotherLineage(List<ConceptAnchor> nodes, double[] scores, int best) {
        Map<String, String> parentOf = new HashMap<String, String>();
        for (ConceptAnchor node : nodes) {
            parentOf.put(node.getId(), node.getParentId());
        }
        String bestId = nodes.get(best).getId();
        double nearest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < nodes.size(); i++) {
            String id = nodes.get(i).getId();
            if (i != best && !descends(id, bestId, parentOf) && !descends(bestId, id, parentOf)) {
                nearest = Math.max(nearest, scores[i]);
            }
        }
        return nearest;
    }

    /** Whether {@code node} lies below {@code ancestor} in the concept. */
    private static boolean descends(String node, String ancestor, Map<String, String> parentOf) {
        Set<String> seen = new HashSet<String>();
        String current = parentOf.get(node);
        while (current != null && !current.isEmpty() && seen.add(current)) {
            if (current.equals(ancestor)) {
                return true;
            }
            current = parentOf.get(current);
        }
        return false;
    }

    private static EmbeddingPort.EmbeddingVector unit(EmbeddingPort.EmbeddingVector vector) {
        if (vector == null) {
            throw new IllegalArgumentException("a clustered passage has no stored vector");
        }
        float[] raw = vector.getValues();
        double norm = 0;
        for (float v : raw) {
            norm += v * v;
        }
        float[] out = new float[raw.length];
        if (norm > 0) {
            double scale = 1.0 / Math.sqrt(norm);
            for (int i = 0; i < raw.length; i++) {
                out[i] = (float) (raw[i] * scale);
            }
        }
        return new EmbeddingPort.EmbeddingVector(vector.getModelId(), vector.getModelFingerprint(), out);
    }
}
