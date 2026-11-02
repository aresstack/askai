package io.github.ollama4j.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EmbeddingResult {

    private final List<List<Double>> embeddings;

    public EmbeddingResult(List<List<Double>> embeddings) {
        if (embeddings == null) {
            this.embeddings = Collections.emptyList();
        } else {
            this.embeddings = Collections.unmodifiableList(new ArrayList<List<Double>>(embeddings));
        }
    }

    public List<List<Double>> getEmbeddings() {
        return embeddings;
    }
}
