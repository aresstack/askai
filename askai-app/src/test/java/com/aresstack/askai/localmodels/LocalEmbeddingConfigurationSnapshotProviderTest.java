package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshot;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The provider orchestrates resolve → start → probe → immutable descriptor, with typed failures, no fallback. */
public class LocalEmbeddingConfigurationSnapshotProviderTest {

    private static EmbeddingModelResolver resolves(final String id, final String revision) {
        return new EmbeddingModelResolver() {
            public ResolvedEmbeddingModel resolve(String virtualModelId) {
                return new ResolvedEmbeddingModel(id, revision);
            }
        };
    }

    private static EmbeddingModelResolver rejects(final EmbeddingConfigurationException.Reason reason) {
        return new EmbeddingModelResolver() {
            public ResolvedEmbeddingModel resolve(String virtualModelId) throws EmbeddingConfigurationException {
                throw new EmbeddingConfigurationException(reason, "rejected");
            }
        };
    }

    private static LocalEmbeddingRuntime runtimeAt(final String baseUrl) {
        return new LocalEmbeddingRuntime() {
            public String ensureStarted(String virtualModelId) {
                return baseUrl;
            }
        };
    }

    private static EmbeddingDimensionProbe probeReturns(final int dim) {
        return new EmbeddingDimensionProbe() {
            public int probeDimension(String baseUrl, String virtualModelId) {
                return dim;
            }
        };
    }

    private static LocalEmbeddingConfigurationSnapshotProvider provider(EmbeddingModelResolver resolver,
                                                                        LocalEmbeddingRuntime runtime,
                                                                        EmbeddingDimensionProbe probe) {
        return new LocalEmbeddingConfigurationSnapshotProvider(resolver, runtime, probe, 5000L);
    }

    private static EmbeddingEndpointDescriptor prepare(LocalEmbeddingConfigurationSnapshotProvider p,
                                                       String selected) throws EmbeddingConfigurationException {
        EmbeddingConfigurationSnapshot snapshot = p.prepareForSession("s1", new File("."), selected);
        return snapshot.descriptor;
    }

    @Test
    public void happyPathBuildsTheDescriptorFromResolveStartAndProbe() throws Exception {
        EmbeddingEndpointDescriptor d = prepare(provider(resolves("local/e5:latest", "rev-1"),
                runtimeAt("http://127.0.0.1:5000"), probeReturns(384)), "local/e5:latest");
        assertEquals("local/e5:latest", d.modelId);
        assertEquals("http://127.0.0.1:5000", d.baseUrl);
        assertEquals("/api/embed", d.embeddingsPath);
        assertEquals(384, d.embeddingDimension);
        assertEquals("none", d.normalization);
        assertEquals("rev-1", d.modelVersionFingerprint);
    }

    @Test
    public void unconfiguredModelIsRejected() {
        assertReason(EmbeddingConfigurationException.Reason.MODEL_NOT_CONFIGURED,
                provider(resolves("x", "r"), runtimeAt("http://h"), probeReturns(1)), "  ");
    }

    @Test
    public void aResolverRejectionIsPropagated() {
        assertReason(EmbeddingConfigurationException.Reason.MODEL_NOT_EMBEDDING_CAPABLE,
                provider(rejects(EmbeddingConfigurationException.Reason.MODEL_NOT_EMBEDDING_CAPABLE),
                        runtimeAt("http://h"), probeReturns(1)), "local/chat:latest");
    }

    @Test
    public void aMissingRevisionIsRejectedNotGuessed() {
        assertReason(EmbeddingConfigurationException.Reason.MISSING_MODEL_REVISION,
                provider(resolves("local/e5:latest", ""), runtimeAt("http://h"), probeReturns(384)),
                "local/e5:latest");
    }

    @Test
    public void aRuntimeStartFailureIsTyped() {
        EmbeddingModelResolver resolver = resolves("local/e5:latest", "rev");
        LocalEmbeddingRuntime dead = new LocalEmbeddingRuntime() {
            public String ensureStarted(String virtualModelId) throws IOException {
                throw new IOException("no runtime");
            }
        };
        assertReason(EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                provider(resolver, dead, probeReturns(384)), "local/e5:latest");
        assertReason(EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED,
                provider(resolver, runtimeAt("  "), probeReturns(384)), "local/e5:latest");
    }

    @Test
    public void aProbeFailureIsPropagated() {
        EmbeddingDimensionProbe failing = new EmbeddingDimensionProbe() {
            public int probeDimension(String baseUrl, String virtualModelId)
                    throws EmbeddingConfigurationException {
                throw new EmbeddingConfigurationException(
                        EmbeddingConfigurationException.Reason.DIMENSION_PROBE_FAILED, "boom");
            }
        };
        assertReason(EmbeddingConfigurationException.Reason.DIMENSION_PROBE_FAILED,
                provider(resolves("local/e5:latest", "rev"), runtimeAt("http://h"), failing),
                "local/e5:latest");
    }

    @Test
    public void sameModelStateOnADifferentBaseUrlIsTheSameEmbeddingWorld() throws Exception {
        EmbeddingEndpointDescriptor a = prepare(provider(resolves("local/e5:latest", "rev-1"),
                runtimeAt("http://127.0.0.1:1111"), probeReturns(384)), "local/e5:latest");
        EmbeddingEndpointDescriptor b = prepare(provider(resolves("local/e5:latest", "rev-1"),
                runtimeAt("http://127.0.0.1:2222"), probeReturns(384)), "local/e5:latest");
        assertTrue("a restart port must not change the vector world", a.sameEmbeddingWorldAs(b));
    }

    @Test
    public void aDifferentRevisionIsADifferentEmbeddingWorld() throws Exception {
        EmbeddingEndpointDescriptor a = prepare(provider(resolves("local/e5:latest", "rev-1"),
                runtimeAt("http://h"), probeReturns(384)), "local/e5:latest");
        EmbeddingEndpointDescriptor b = prepare(provider(resolves("local/e5:latest", "rev-2"),
                runtimeAt("http://h"), probeReturns(384)), "local/e5:latest");
        assertFalse(a.sameEmbeddingWorldAs(b));
    }

    private static void assertReason(EmbeddingConfigurationException.Reason expected,
                                     LocalEmbeddingConfigurationSnapshotProvider p, String selected) {
        try {
            p.prepareForSession("s1", new File("."), selected);
            fail("expected " + expected);
        } catch (EmbeddingConfigurationException e) {
            assertEquals(expected, e.getReason());
        }
    }
}
