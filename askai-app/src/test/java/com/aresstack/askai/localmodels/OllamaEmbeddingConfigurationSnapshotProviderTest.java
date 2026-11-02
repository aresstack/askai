package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

/** Resolving an Ollama embedding model: base URL + /api/embed + probed dimension + digest-based fingerprint, no fallback. */
public class OllamaEmbeddingConfigurationSnapshotProviderTest {

    private static final String MODEL = "nomic-embed-text:latest";

    private static OllamaEmbeddingConfigurationSnapshotProvider.OllamaEndpoint endpoint(final String base) {
        return new OllamaEmbeddingConfigurationSnapshotProvider.OllamaEndpoint() {
            public String baseUrl() {
                return base;
            }
        };
    }

    private static final class FakeDigests
            implements OllamaEmbeddingConfigurationSnapshotProvider.ModelDigestLookup {
        String digest = "sha256:abc";
        IOException failure;
        String lastBaseUrl;
        String lastModel;

        public String digestOf(String baseUrl, String modelId) throws IOException {
            lastBaseUrl = baseUrl;
            lastModel = modelId;
            if (failure != null) {
                throw failure;
            }
            return digest; // null would mean "not installed"
        }
    }

    private static final class FakeProbe implements EmbeddingDimensionProbe {
        int dimension = 768;
        String lastBaseUrl;
        String lastModel;

        public int probeDimension(String baseUrl, String virtualModelId) {
            lastBaseUrl = baseUrl;
            lastModel = virtualModelId;
            return dimension;
        }
    }

    private static EmbeddingEndpointDescriptor resolve(String base, FakeDigests digests, FakeProbe probe)
            throws EmbeddingConfigurationException {
        return new OllamaEmbeddingConfigurationSnapshotProvider(endpoint(base), digests, probe, 60_000L)
                .prepareForSession("s", new File("."), MODEL).descriptor;
    }

    @Test
    public void resolvesAnOllamaModelAgainstTheConfiguredEndpointAndApiEmbed() throws Exception {
        FakeDigests digests = new FakeDigests();
        FakeProbe probe = new FakeProbe();
        EmbeddingEndpointDescriptor d = resolve("http://127.0.0.1:11434", digests, probe);

        assertEquals(MODEL, d.modelId);
        assertEquals("http://127.0.0.1:11434", d.baseUrl);      // (3) configured Ollama base URL
        assertEquals("/api/embed", d.embeddingsPath);           // (4) endpoint
        assertEquals(768, d.embeddingDimension);                // (5) real probe
        assertEquals("none", d.normalization);
        assertEquals("the probe used the same endpoint + model", "http://127.0.0.1:11434", probe.lastBaseUrl);
        assertEquals(MODEL, probe.lastModel);
        assertEquals(MODEL, digests.lastModel);                 // (1) resolved as an Ollama model (digest lookup)
    }

    @Test
    public void theDigestIsPartOfTheFingerprint() throws Exception {
        FakeDigests a = new FakeDigests();
        a.digest = "sha256:aaa";
        FakeDigests b = new FakeDigests();
        b.digest = "sha256:bbb";
        // (6)/(8) a different model digest => a different vector world.
        assertNotEquals(resolve("http://h:11434", a, new FakeProbe()).embeddingFingerprint(),
                resolve("http://h:11434", b, new FakeProbe()).embeddingFingerprint());
    }

    @Test
    public void theBaseUrlPortIsNotPartOfTheFingerprint() throws Exception {
        FakeDigests digests = new FakeDigests();
        digests.digest = "sha256:same";
        // (7) same model+digest+dimension+normalization, different port => SAME world.
        assertEquals(resolve("http://127.0.0.1:11434", digests, new FakeProbe()).embeddingFingerprint(),
                resolve("http://127.0.0.1:22222", digests, new FakeProbe()).embeddingFingerprint());
    }

    @Test
    public void anUninstalledModelIsAHardNotFoundNeverALocalFallback() {
        FakeDigests digests = new FakeDigests();
        digests.digest = null; // not installed on this Ollama server
        assertReason(digests, new FakeProbe(), EmbeddingConfigurationException.Reason.MODEL_NOT_FOUND);
    }

    @Test
    public void anUnreachableOllamaIsATypedError() {
        FakeDigests digests = new FakeDigests();
        digests.failure = new IOException("connection refused");
        assertReason(digests, new FakeProbe(), EmbeddingConfigurationException.Reason.RUNTIME_START_FAILED);
    }

    @Test
    public void anEmptySelectionIsNotConfigured() {
        try {
            new OllamaEmbeddingConfigurationSnapshotProvider(endpoint("http://h:11434"), new FakeDigests(),
                    new FakeProbe(), 60_000L).prepareForSession("s", new File("."), "  ");
            fail("empty selection must be MODEL_NOT_CONFIGURED");
        } catch (EmbeddingConfigurationException ex) {
            assertEquals(EmbeddingConfigurationException.Reason.MODEL_NOT_CONFIGURED, ex.getReason());
        }
    }

    private static void assertReason(FakeDigests digests, FakeProbe probe,
                                     EmbeddingConfigurationException.Reason expected) {
        try {
            resolve("http://127.0.0.1:11434", digests, probe);
            fail("expected " + expected);
        } catch (EmbeddingConfigurationException ex) {
            assertEquals(expected, ex.getReason());
        }
    }
}
