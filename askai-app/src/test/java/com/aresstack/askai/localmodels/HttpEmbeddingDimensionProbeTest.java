package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The probe derives the dimension from the response vector length and rejects any malformed vector. */
public class HttpEmbeddingDimensionProbeTest {

    @Test
    public void dimensionIsTheVectorLength() throws Exception {
        assertEquals(3, HttpEmbeddingDimensionProbe.dimensionOf(
                "{\"model\":\"m\",\"embeddings\":[[0.1,0.2,0.3]]}"));
    }

    @Test
    public void emptyEmbeddingsListIsRejected() {
        assertInvalid("{\"model\":\"m\",\"embeddings\":[]}");
    }

    @Test
    public void moreThanOneEmbeddingForOneInputIsRejected() {
        assertInvalid("{\"model\":\"m\",\"embeddings\":[[0.1],[0.2]]}");
    }

    @Test
    public void anEmptyVectorIsRejected() {
        assertInvalid("{\"model\":\"m\",\"embeddings\":[[]]}");
    }

    @Test
    public void aMissingEmbeddingsFieldIsRejected() {
        assertInvalid("{\"model\":\"m\"}");
    }

    @Test
    public void aNonFiniteValueIsRejected() {
        try {
            HttpEmbeddingDimensionProbe.validateVector(Arrays.asList(0.1, Double.NaN, 0.3));
            fail("NaN must be rejected");
        } catch (EmbeddingConfigurationException e) {
            assertEquals(EmbeddingConfigurationException.Reason.INVALID_PROBE_RESPONSE, e.getReason());
        }
        try {
            HttpEmbeddingDimensionProbe.validateVector(Arrays.asList(0.1, Double.POSITIVE_INFINITY));
            fail("Infinity must be rejected");
        } catch (EmbeddingConfigurationException e) {
            assertEquals(EmbeddingConfigurationException.Reason.INVALID_PROBE_RESPONSE, e.getReason());
        }
    }

    @Test
    public void aFiniteVectorReturnsItsDimension() throws Exception {
        assertEquals(2, HttpEmbeddingDimensionProbe.validateVector(Arrays.asList(-1.5, 2.0)));
    }

    private static void assertInvalid(String json) {
        try {
            HttpEmbeddingDimensionProbe.dimensionOf(json);
            fail("expected INVALID_PROBE_RESPONSE for " + json);
        } catch (EmbeddingConfigurationException e) {
            assertEquals(EmbeddingConfigurationException.Reason.INVALID_PROBE_RESPONSE, e.getReason());
        }
    }
}
