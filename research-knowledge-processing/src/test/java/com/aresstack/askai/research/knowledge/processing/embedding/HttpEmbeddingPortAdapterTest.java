package com.aresstack.askai.research.knowledge.processing.embedding;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Batch /api/embed with strict validation (count, dimension, finiteness) and no fallback. */
public class HttpEmbeddingPortAdapterTest {

    private static EmbeddingEndpointDescriptor descriptor(int dimension) {
        return new EmbeddingEndpointDescriptor("local/e5:latest", "http://127.0.0.1:9", "/api/embed",
                dimension, "none", "rev-1", 1000L);
    }

    private static EmbeddingHttpTransport returns(final String response) {
        return new EmbeddingHttpTransport() {
            public String post(String url, String jsonBody) {
                return response;
            }
        };
    }

    private static HttpEmbeddingPortAdapter adapter(int dimension, String response) {
        return new HttpEmbeddingPortAdapter(descriptor(dimension), returns(response));
    }

    @Test
    public void buildsABatchRawRequestWithEscaping() {
        assertEquals("{\"model\":\"m\",\"input\":[\"a\",\"b\\\"c\"],\"input_type\":\"raw\"}",
                HttpEmbeddingPortAdapter.buildRequest("m", Arrays.asList("a", "b\"c")));
    }

    @Test
    public void parsesANumericMatrixTolerantToWhitespace() {
        List<float[]> m = HttpEmbeddingPortAdapter.parseEmbeddingMatrix(
                "{\"model\":\"x\",\"embeddings\":[[1.0, 2.5],[ -3.0 ,4 ]]}");
        assertEquals(2, m.size());
        assertEquals(1.0f, m.get(0)[0], 1e-6f);
        assertEquals(2.5f, m.get(0)[1], 1e-6f);
        assertEquals(-3.0f, m.get(1)[0], 1e-6f);
        assertEquals(4.0f, m.get(1)[1], 1e-6f);
    }

    @Test
    public void mapsAValidBatchResponseToVectorsTaggedWithTheWorldFingerprint() {
        EmbeddingEndpointDescriptor d = descriptor(3);
        HttpEmbeddingPortAdapter a = new HttpEmbeddingPortAdapter(d,
                returns("{\"model\":\"m\",\"embeddings\":[[0.1,0.2,0.3],[0.4,0.5,0.6]]}"));
        List<EmbeddingPort.EmbeddingVector> vectors = a.embedDocuments(Arrays.asList("one", "two"));
        assertEquals(2, vectors.size());
        assertEquals(3, vectors.get(0).getDimension());
        assertEquals(d.embeddingFingerprint(), vectors.get(0).getModelFingerprint());
        assertEquals("local/e5:latest", vectors.get(0).getModelId());
        assertEquals(0.5f, vectors.get(1).getValues()[1], 1e-6f);
    }

    @Test
    public void aCountMismatchIsRejected() {
        assertFails(adapter(3, "{\"embeddings\":[[0.1,0.2,0.3]]}"), Arrays.asList("a", "b"), "count");
    }

    @Test
    public void aWrongDimensionIsRejected() {
        assertFails(adapter(3, "{\"embeddings\":[[0.1,0.2]]}"), Arrays.asList("a"), "dimension");
    }

    @Test
    public void anEmptyVectorIsRejected() {
        assertFails(adapter(3, "{\"embeddings\":[[]]}"), Arrays.asList("a"), "empty");
    }

    @Test
    public void aNonFiniteValueIsRejected() {
        assertFails(adapter(3, "{\"embeddings\":[[1e999,0.0,0.0]]}"), Arrays.asList("a"), "non-finite");
    }

    @Test
    public void aMissingEmbeddingsFieldIsRejected() {
        assertFails(adapter(3, "{\"model\":\"m\"}"), Arrays.asList("a"), "embeddings");
    }

    @Test
    public void aTransportFailureBecomesAnEmbeddingException() {
        HttpEmbeddingPortAdapter a = new HttpEmbeddingPortAdapter(descriptor(3),
                new EmbeddingHttpTransport() {
                    public String post(String url, String jsonBody) throws IOException {
                        throw new IOException("connection refused");
                    }
                });
        try {
            a.embedDocuments(Arrays.asList("a"));
            fail("transport failure must surface");
        } catch (EmbeddingException e) {
            assertTrue(e.getMessage().contains("connection refused"));
        }
    }

    private static void assertFails(HttpEmbeddingPortAdapter adapter, List<String> inputs, String hint) {
        try {
            adapter.embedDocuments(inputs);
            fail("expected EmbeddingException (" + hint + ")");
        } catch (EmbeddingException expected) {
            // ok
        }
    }
}
