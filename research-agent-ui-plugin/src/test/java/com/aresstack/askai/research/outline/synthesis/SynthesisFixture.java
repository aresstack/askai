package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import com.aresstack.askai.research.knowledge.topic.AnalysisPassage;
import com.aresstack.askai.research.knowledge.topic.ConceptAnchor;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import com.aresstack.askai.research.knowledge.topic.TopicAnalyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A research corpus with known geometry for the synthesis tests: every passage is a weighted sum of one-hot
 * directions (topic base, aspect, own direction) plus a tiny jitter, so topics, sub-topics and duplicates follow from
 * the weights (see the pipeline's topic fixture). Concept nodes are query vectors along a topic's base, or along an
 * unused direction for a requirement without evidence.
 */
final class SynthesisFixture {

    static final int DIMENSION = 512;

    final List<Passage> passages = new ArrayList<Passage>();
    final List<AnalysisPassage> analysisPassages = new ArrayList<AnalysisPassage>();
    final Map<String, EmbeddingPort.EmbeddingVector> vectors =
            new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
    final Map<String, String> texts = new LinkedHashMap<String, String>();
    final List<ConceptAnchor> anchors = new ArrayList<ConceptAnchor>();
    /** The search fence of the analysis; {@code null} = none. */
    com.aresstack.askai.research.knowledge.topic.ScopeFence fence;
    /** The deliverable of the book (its length steers the word budgets). */
    String deliverable = "A practical book";
    private final Random jitter = new Random(11L);
    private int nextDirection;
    private int nextPassage;

    int direction() {
        return nextDirection++;
    }

    /** A topic of {@code aspects} sub-topics with {@code perAspect} distinct passages each; returns its base. */
    int diverse(String topic, int aspects, int perAspect, int sources) {
        int base = direction();
        int index = 0;
        for (int a = 0; a < aspects; a++) {
            int aspect = direction();
            for (int i = 0; i < perAspect; i++) {
                add(topic + " aspect" + a, topic, index++ % sources, new int[]{base, aspect, direction()},
                        new double[]{1.2, 0.9, 0.6});
            }
        }
        return base;
    }

    /** {@code count} passages along an existing topic base only (no aspect): the topic's rest; distinct each. */
    void rest(String topic, int base, int count, int sources) {
        for (int i = 0; i < count; i++) {
            add(topic + " rest", topic, i % sources, new int[]{base, direction()}, new double[]{1.2, 0.6});
        }
    }

    /** {@code count} copies of one statement; returns the base. */
    int redundant(String topic, int count, int sources) {
        int base = direction();
        for (int i = 0; i < count; i++) {
            add(topic, topic, i % sources, new int[]{base, direction()}, new double[]{1.0, 0.05});
        }
        return base;
    }

    /**
     * {@code statements} distinct statements of one topic, each repeated {@code copies} times over the sources: much
     * material, little of it distinct; returns the base.
     */
    int repeated(String topic, int statements, int copies, int sources) {
        int base = direction();
        int index = 0;
        for (int s = 0; s < statements; s++) {
            int statement = direction();
            for (int c = 0; c < copies; c++) {
                add(topic, topic, index++ % sources, new int[]{base, statement, direction()},
                        new double[]{1.0, 0.35, 0.03});
            }
        }
        return base;
    }

    /** A plain topic without sub-topics; returns its base. */
    int plain(String topic, int count, int sources) {
        int base = direction();
        for (int i = 0; i < count; i++) {
            add(topic, topic, i % sources, new int[]{base, direction()}, new double[]{1.0, 0.6});
        }
        return base;
    }

    /** A first-level concept node (below the book frame) along {@code direction}. */
    void concept(String id, String label, int direction) {
        float[] values = new float[DIMENSION];
        values[direction] = 1f;
        anchors.add(new ConceptAnchor(id, label, "Book > " + label, 1, "",
                new EmbeddingPort.EmbeddingVector("m", "fp", values)));
    }

    TopicAnalysis analyse() {
        LiveTopicTree tree = new LiveOutlineProjectionBuilder().discoverTopicTree(passages, vectors,
                ClusteringMonitor.NONE);
        return new TopicAnalyzer(TopicAnalysisSettings.defaults()).analyze(analysisPassages, vectors, tree, anchors,
                fence, ClusteringMonitor.NONE);
    }

    SynthesisBrief brief() {
        return brief(OutlineSynthesisSettings.defaults());
    }

    SynthesisBrief brief(OutlineSynthesisSettings settings) {
        return SynthesisBrief.of(new SynthesisContext("The Smart Site", "Wearables on construction sites",
                deliverable, "en", Collections.<String>emptyList()), analyse(),
                new SynthesisBrief.Texts() {
                    public String text(String passageId) {
                        return texts.get(passageId);
                    }
                }, settings);
    }

    private void add(String phrase, String topic, int source, int[] directions, double[] weights) {
        float[] values = new float[DIMENSION];
        for (int i = 0; i < directions.length; i++) {
            values[directions[i]] += (float) weights[i];
        }
        for (int d = 0; d < DIMENSION; d++) {
            values[d] += (float) (jitter.nextGaussian() * 1e-4);
        }
        String id = String.format("p%04d", nextPassage++);
        String text = phrase + " statement " + id + ". More about " + topic + ".";
        passages.add(new Passage(id, "cap-" + topic, Arrays.asList("s-" + id), "", text, "fp"));
        String sourceId = topic + "-src-" + source;
        analysisPassages.add(new AnalysisPassage(id, sourceId, sourceId + ".example", text));
        vectors.put(id, new EmbeddingPort.EmbeddingVector("m", "fp", values));
        texts.put(id, text);
    }
}
