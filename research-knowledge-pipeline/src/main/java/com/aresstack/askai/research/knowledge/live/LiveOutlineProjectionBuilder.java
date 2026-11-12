package com.aresstack.askai.research.knowledge.live;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.TopicProposal;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.TopicClusterer;
import com.aresstack.askai.research.knowledge.TopicDendrogram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@link LiveOutlineProjection} from the ACTIVE corpus in TWO independently invokable stages
 * (issue #29 — topic discovery must never be hidden inside outline building):
 * <ol>
 * <li>{@link #discoverTopics(List, Map)} — deterministic clustering: passages are sorted by id, the EXISTING
 *     {@link TopicClusterer} math is reused verbatim (cosine distance, average linkage, merge cutoff), cluster
 *     identities are derived from their sorted members (no counter ids), topics ordered by size;</li>
 * <li>{@link #buildOutline(long, String, long, List, List, List)} — sections FROM an explicit topic result,
 *     with question-coverage analysis; it runs NO clustering of its own.</li>
 * </ol>
 * Both stages are pure and deterministic: the same corpus always yields the SAME topics, the same topics the
 * SAME projection. Confirmed brief questions that no cluster's text covers land in a final "open questions"
 * gap section — visible, never silently dropped. There is deliberately NO single-shot convenience method that
 * chains the stages — the caller (an explicit user action) orchestrates them visibly.
 */
public final class LiveOutlineProjectionBuilder {

    private final double mergeDistanceCutoff;
    private final int representativesPerTopic;

    /** The defaults of the topic analysis settings: topic distance and representatives. */
    public LiveOutlineProjectionBuilder() {
        this(TopicClusterer.DEFAULT_TOPIC_DISTANCE, TopicClusterer.DEFAULT_REPRESENTATIVES);
    }

    public LiveOutlineProjectionBuilder(double mergeDistanceCutoff, int representativesPerTopic) {
        this.mergeDistanceCutoff = mergeDistanceCutoff;
        this.representativesPerTopic = representativesPerTopic;
    }

    /**
     * STAGE 1 — topic discovery: deterministic clustering of the corpus into topics. Independently invokable
     * and testable; produces only topics, never sections.
     */
    public List<LiveTopicProjection> discoverTopics(List<Passage> passages,
                                                    Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        return discoverTopics(passages, vectors, ClusteringMonitor.NONE);
    }

    /**
     * PR 20: the same discovery with progress and cancel. The topics are exactly those of
     * {@link #discoverTopicTree}: one cut, one truth for every consumer.
     */
    public List<LiveTopicProjection> discoverTopics(List<Passage> passages,
                                                    Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                                    ClusteringMonitor monitor) {
        return discoverTopics(passages, vectors, monitor, TopicDendrogram.SplitRule.NONE);
    }

    /** The same discovery with a split rule over the cut (the topic analysis' concept split). */
    public List<LiveTopicProjection> discoverTopics(List<Passage> passages,
                                                    Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                                    ClusteringMonitor monitor, TopicDendrogram.SplitRule rule) {
        if (passages == null || passages.isEmpty()) {
            return Collections.emptyList();
        }
        return discoverTopicTree(passages, vectors, monitor, rule).getTopics();
    }

    /** The same builder with the clustering values of the topic analysis settings. */
    public LiveOutlineProjectionBuilder with(double topicDistance, int representatives) {
        return topicDistance == mergeDistanceCutoff && representatives == representativesPerTopic ? this
                : new LiveOutlineProjectionBuilder(topicDistance, representatives);
    }

    /**
     * Phase 3: the topics from ONE clustering run that also keeps the complete merge tree (sub-topics, topic groups)
     * for the topic analysis. Deterministic input order (sorted by id) gives deterministic topics; the counter-based
     * proposal ids are ignored, live identity comes from the sorted member ids.
     */
    public LiveTopicTree discoverTopicTree(List<Passage> passages,
                                           Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                           ClusteringMonitor monitor) {
        return discoverTopicTree(passages, vectors, monitor, TopicDendrogram.SplitRule.NONE);
    }

    /** The same run with a split rule over the cut (the topic analysis' concept split). */
    public LiveTopicTree discoverTopicTree(List<Passage> passages,
                                           Map<String, EmbeddingPort.EmbeddingVector> vectors,
                                           ClusteringMonitor monitor, TopicDendrogram.SplitRule rule) {
        List<Passage> sorted = sortedById(passages == null ? Collections.<Passage>emptyList() : passages);
        TopicClusterer.Tree tree = new TopicClusterer(IdSequence.counting(), mergeDistanceCutoff,
                representativesPerTopic).clusterTree(sorted, vectors, monitor, rule);
        return new LiveTopicTree(liveTopics(tree.getTopics()), tree.getDendrogram());
    }

    private static List<LiveTopicProjection> liveTopics(List<TopicProposal> proposals) {
        List<LiveTopicProjection> topics = new ArrayList<LiveTopicProjection>();
        for (TopicProposal proposal : proposals) {
            topics.add(new LiveTopicProjection(proposal.getMemberPassageIds(),
                    proposal.getRepresentativePassageIds(), proposal.getSuggestedTitle(),
                    proposal.getConfidence()));
        }
        // Largest evidence first; the deterministic cluster id breaks ties.
        Collections.sort(topics, new Comparator<LiveTopicProjection>() {
            public int compare(LiveTopicProjection a, LiveTopicProjection b) {
                int bySize = Integer.compare(b.getMemberPassageIds().size(),
                        a.getMemberPassageIds().size());
                return bySize != 0 ? bySize : a.getClusterId().compareTo(b.getClusterId());
            }
        });
        return topics;
    }

    /**
     * STAGE 2 — outline building from an EXPLICIT topic result: sections in topic order plus the
     * question-coverage gap section. Runs no clustering — the topics are an input, not a side effect.
     */
    public LiveOutlineProjection buildOutline(long projectionRevision, String embeddingFingerprint,
                                              long generatedAtMillis, List<Passage> passages,
                                              List<LiveTopicProjection> topics,
                                              List<String> briefQuestions) {
        return buildOutline(projectionRevision, embeddingFingerprint, generatedAtMillis, passages, topics,
                briefQuestions, null);
    }

    /**
     * PR 20: the same build with an explicit corpus fingerprint — a corpus limited for clustering still
     * records the identity of the WHOLE active corpus, so the staleness check compares like with like.
     * {@code null} fingerprints the given passages.
     */
    public LiveOutlineProjection buildOutline(long projectionRevision, String embeddingFingerprint,
                                              long generatedAtMillis, List<Passage> passages,
                                              List<LiveTopicProjection> topics,
                                              List<String> briefQuestions, String corpusFingerprint) {
        if (passages == null || passages.isEmpty()) {
            return LiveOutlineProjection.empty(projectionRevision, embeddingFingerprint, generatedAtMillis);
        }
        List<Passage> sorted = sortedById(passages);
        List<String> includedIds = new ArrayList<String>();
        for (Passage p : sorted) {
            includedIds.add(p.getPassageId());
        }

        // Question coverage: a confirmed brief question is covered when any member passage of a section
        // shares a meaningful token (>= 4 chars) with it — the same evidence-led heuristic the proposal
        // builder uses. Uncovered questions surface in a final gap section.
        List<String> open = new ArrayList<String>(briefQuestions == null
                ? Collections.<String>emptyList() : briefQuestions);
        List<LiveOutlineSection> sections = new ArrayList<LiveOutlineSection>();
        Map<String, Passage> byId = byId(sorted);
        List<LiveTopicProjection> topicList = topics == null
                ? Collections.<LiveTopicProjection>emptyList() : topics;
        for (LiveTopicProjection topic : topicList) {
            List<String> covered = new ArrayList<String>();
            for (String question : new ArrayList<String>(open)) {
                if (questionCoveredBy(question, topic, byId)) {
                    covered.add(question);
                    open.remove(question);
                }
            }
            sections.add(new LiveOutlineSection("sec-" + topic.getClusterId(), topic.getTitle(), "",
                    Collections.singletonList(topic.getClusterId()), topic.getMemberPassageIds(),
                    Collections.<String>emptyList()));
        }
        if (!open.isEmpty()) {
            sections.add(new LiveOutlineSection("sec-open-questions", "Open questions", "",
                    Collections.<String>emptyList(), Collections.<String>emptyList(), open));
        }

        return new LiveOutlineProjection(projectionRevision, corpusFingerprint != null ? corpusFingerprint
                : LiveOutlineProjection.corpusFingerprintOf(includedIds), embeddingFingerprint,
                generatedAtMillis, topicList, sections);
    }

    private static List<Passage> sortedById(List<Passage> passages) {
        List<Passage> sorted = new ArrayList<Passage>(passages);
        Collections.sort(sorted, new Comparator<Passage>() {
            public int compare(Passage a, Passage b) {
                return a.getPassageId().compareTo(b.getPassageId());
            }
        });
        return sorted;
    }

    private static Map<String, Passage> byId(List<Passage> passages) {
        Map<String, Passage> map = new java.util.HashMap<String, Passage>();
        for (Passage p : passages) {
            map.put(p.getPassageId(), p);
        }
        return map;
    }

    private static boolean questionCoveredBy(String question, LiveTopicProjection topic,
                                             Map<String, Passage> byId) {
        for (String passageId : topic.getMemberPassageIds()) {
            Passage passage = byId.get(passageId);
            if (passage == null) {
                continue;
            }
            String text = passage.getText().toLowerCase(Locale.ROOT);
            for (String token : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
                if (token.length() >= 4 && text.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }
}
