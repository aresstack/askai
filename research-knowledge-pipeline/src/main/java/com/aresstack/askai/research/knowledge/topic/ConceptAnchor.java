package com.aresstack.askai.research.knowledge.topic;

import com.aresstack.askai.research.knowledge.EmbeddingPort;

/**
 * Phase 3: one node of the approved concept as the topic analysis compares evidence with it: id, label, the full
 * label path that was embedded as a QUERY (book title, parent labels, label), its depth below the book frame (1 =
 * main theme) and its parent's id ("" for a main theme). Immutable.
 */
public final class ConceptAnchor {

    private final String id;
    private final String label;
    private final String path;
    private final int depth;
    private final String parentId;
    private final EmbeddingPort.EmbeddingVector vector;

    public ConceptAnchor(String id, String label, String path, int depth, String parentId,
                         EmbeddingPort.EmbeddingVector vector) {
        if (vector == null) {
            throw new IllegalArgumentException("a concept anchor needs its query vector");
        }
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label.trim();
        this.path = path == null ? "" : path.trim();
        this.depth = Math.max(1, depth);
        this.parentId = parentId == null ? "" : parentId;
        this.vector = vector;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getPath() {
        return path;
    }

    public int getDepth() {
        return depth;
    }

    public String getParentId() {
        return parentId;
    }

    public EmbeddingPort.EmbeddingVector getVector() {
        return vector;
    }
}
