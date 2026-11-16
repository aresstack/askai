package com.aresstack.askai.research.scope;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.store.ScopeAnchorVectorIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * The sweep's embedder, BOUND AT CONSTRUCTION to one immutable {@link EmbeddingEndpointDescriptor}
 * — this is how Z3b-3's snapshot invariant is actually guaranteed: the adapter underneath holds the
 * frozen descriptor, so a global embedding-model hot-reload between the 45-115s generation and the
 * embed batch CANNOT switch the vector world mid-run (there is nothing mutable to switch). Serves
 * both roles of one snapshot: the anchor index's {@link ScopeAnchorVectorIndex.AnchorEmbedder}
 * (persistent anchor vectors) and the sweep's {@link ScopeSweepService.SweepEmbedder} (transient
 * mission/probe/control vectors) — one world, by the same object.
 *
 * <p>The scope fence stays RAW (PR 04): its anchors, probe terms and candidate texts are compared only with each
 * other, its thresholds were calibrated on raw vectors, and its fingerprint is the descriptor's raw fingerprint.</p>
 */
public final class EmbeddingSnapshotSweepEmbedder
        implements ScopeSweepService.SweepEmbedder, ScopeAnchorVectorIndex.AnchorEmbedder {

    private final EmbeddingEndpointDescriptor descriptor;
    private final EmbeddingPort adapter;

    public EmbeddingSnapshotSweepEmbedder(EmbeddingEndpointDescriptor descriptor) {
        this(descriptor, descriptor == null ? null : new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport((int) descriptor.timeoutMillis)));
    }

    /** Test seam: the port the snapshot embeds through. */
    EmbeddingSnapshotSweepEmbedder(EmbeddingEndpointDescriptor descriptor, EmbeddingPort port) {
        if (descriptor == null || port == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        this.descriptor = descriptor;
        this.adapter = port;
    }

    @Override
    public String modelFingerprint() {
        return descriptor.embeddingFingerprint();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<float[]>();
        for (EmbeddingPort.EmbeddingVector vector : adapter.embedRaw(texts)) {
            vectors.add(vector.getValues());
        }
        return vectors;
    }
}
