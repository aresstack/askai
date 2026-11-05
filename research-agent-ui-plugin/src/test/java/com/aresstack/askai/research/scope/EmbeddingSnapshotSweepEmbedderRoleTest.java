package com.aresstack.askai.research.scope;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** The scope fence (anchors, probes, gate candidates) embeds raw and keeps the raw fingerprint. */
public class EmbeddingSnapshotSweepEmbedderRoleTest {

    @Test
    public void scopeFenceTextsAreEmbeddedRaw() {
        final List<String> log = new ArrayList<String>();
        EmbeddingPort recording = new EmbeddingPort() {
            public List<EmbeddingVector> embed(Role role, List<String> texts) {
                List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
                for (String text : texts) {
                    log.add(role + ":" + text);
                    out.add(new EmbeddingVector("m", "x", new float[]{1f}));
                }
                return out;
            }
        };
        EmbeddingEndpointDescriptor descriptor = new EmbeddingEndpointDescriptor("nomic-embed-text",
                "http://127.0.0.1:9", "/api/embed", 1, "none", "rev", 1000L);
        EmbeddingSnapshotSweepEmbedder embedder = new EmbeddingSnapshotSweepEmbedder(descriptor, recording);

        embedder.embed(Arrays.asList("anchor text", "probe term"));

        assertEquals(Arrays.asList("RAW:anchor text", "RAW:probe term"), log);
        assertEquals(descriptor.embeddingFingerprint(), embedder.modelFingerprint());
    }
}
