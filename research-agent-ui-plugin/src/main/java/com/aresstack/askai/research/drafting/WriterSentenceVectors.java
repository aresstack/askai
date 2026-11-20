package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.runtime.writer.SentenceVectors;

import java.util.ArrayList;
import java.util.List;

/**
 * The sentence-embedding slot as the writer checks use it: both sides embedded as documents, so they are comparable.
 * The app's writer and the writer A/B take their sentence vectors from here.
 */
public final class WriterSentenceVectors {

    private WriterSentenceVectors() {
    }

    /** The writer's sentence vectors on {@code port}; {@code null} without a port. */
    public static SentenceVectors of(final EmbeddingPort port) {
        if (port == null) {
            return null;
        }
        return new SentenceVectors() {
            public List<float[]> embed(List<String> sentences) {
                List<float[]> out = new ArrayList<float[]>(sentences.size());
                for (EmbeddingPort.EmbeddingVector vector : port.embed(EmbeddingPort.Role.DOCUMENT, sentences)) {
                    out.add(vector.getValues());
                }
                return out;
            }
        };
    }
}
