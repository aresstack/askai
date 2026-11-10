package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A corpus with a known geometry: every passage is a weighted sum of one-hot directions (an area shared by related
 * topics, the topic's base, an aspect inside the topic, and a direction of its own), plus a tiny jitter that breaks
 * exact ties. Cosine distances therefore follow from the weights:
 * <ul>
 * <li>{@link #redundant}: base 1, own 0.05: everything within the duplicate cut (one statement repeated);</li>
 * <li>{@link #diverse}: base 1.2, aspect 0.9, own 0.6: aspects about 0.45 apart (one topic, separate
 * sub-topics), passages of one aspect about 0.14 apart (distinct, same sub-topic);</li>
 * <li>{@link #plain}: base 1, own 0.6 (optionally area 0.8): one topic without sub-topics; two plain topics with
 * the same area lie about 0.68 apart (one topic group), without an area 1.0 (independent).</li>
 * </ul>
 */
final class TopicCorpusFixture {

    static final int DIMENSION = 512;

    final List<Passage> passages = new ArrayList<Passage>();
    final List<AnalysisPassage> analysisPassages = new ArrayList<AnalysisPassage>();
    final Map<String, EmbeddingPort.EmbeddingVector> vectors =
            new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
    private final Random jitter = new Random(7L);
    private int nextDirection;
    private int nextPassage;

    int direction() {
        if (nextDirection >= DIMENSION) {
            throw new IllegalStateException("fixture out of directions");
        }
        return nextDirection++;
    }

    /** {@code count} passages repeating one statement, spread over {@code sources} sources. */
    void redundant(String topic, int count, int sources) {
        int base = direction();
        for (int i = 0; i < count; i++) {
            add(topic, i % sources, new int[]{base, direction()}, new double[]{1.0, 0.05});
        }
    }

    /** {@code aspects} sub-topics of {@code perAspect} distinct passages each. */
    void diverse(String topic, int aspects, int perAspect, int sources) {
        int base = direction();
        int index = 0;
        for (int a = 0; a < aspects; a++) {
            int aspect = direction();
            for (int i = 0; i < perAspect; i++) {
                add(topic, index++ % sources, new int[]{base, aspect, direction()}, new double[]{1.2, 0.9, 0.6});
            }
        }
    }

    /** A plain topic; {@code area} &gt;= 0 shares that direction with related topics. Returns the base direction. */
    int plain(String topic, int count, int sources, int area) {
        int base = direction();
        for (int i = 0; i < count; i++) {
            if (area >= 0) {
                add(topic, i % sources, new int[]{area, base, direction()}, new double[]{0.8, 1.0, 0.6});
            } else {
                add(topic, i % sources, new int[]{base, direction()}, new double[]{1.0, 0.6});
            }
        }
        return base;
    }

    /** A query vector along the given directions (a concept node's label path). */
    EmbeddingPort.EmbeddingVector query(int... directions) {
        float[] values = new float[DIMENSION];
        for (int d : directions) {
            values[d] = 1f;
        }
        return new EmbeddingPort.EmbeddingVector("m", "fp", values);
    }

    /** The base direction of the passages of {@code topic} (their first heavy direction). */
    private void add(String topic, int source, int[] directions, double[] weights) {
        float[] values = new float[DIMENSION];
        for (int i = 0; i < directions.length; i++) {
            values[directions[i]] += (float) weights[i];
        }
        for (int d = 0; d < DIMENSION; d++) {
            values[d] += (float) (jitter.nextGaussian() * 1e-4);
        }
        String id = String.format("p%04d", nextPassage++);
        String text = topic + " statement " + id + ". More about " + topic + ".";
        passages.add(new Passage(id, "cap-" + topic, Arrays.asList("s-" + id), "", text, "fp"));
        String sourceId = topic + "-src-" + source;
        analysisPassages.add(new AnalysisPassage(id, sourceId, sourceId + ".example", text));
        vectors.put(id, new EmbeddingPort.EmbeddingVector("m", "fp", values));
    }
}
