package com.aresstack.askai.research.knowledge.processing.embedding;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Request goldens of the query/document prompts per model card, and the prompt scheme in the fingerprint. */
public class EmbeddingPromptSchemeTest {

    private static final class Recording implements EmbeddingHttpTransport {
        final List<String> bodies = new ArrayList<String>();

        public String post(String url, String jsonBody) {
            bodies.add(jsonBody);
            return "{\"embeddings\":[[0.1,0.2]]}";
        }
    }

    private static EmbeddingEndpointDescriptor descriptor(String model) {
        return new EmbeddingEndpointDescriptor(model, "http://127.0.0.1:9", "/api/embed", 2, "none", "rev", 1000L);
    }

    private static String body(String model, EmbeddingPort.Role role, String text) {
        Recording transport = new Recording();
        new HttpEmbeddingPortAdapter(descriptor(model), transport).embed(role, Arrays.asList(text));
        return transport.bodies.get(0);
    }

    @Test
    public void nomicQueryAndPassageGetTheirModelCardPrefixes() {
        assertEquals("{\"model\":\"nomic-embed-text:latest\",\"input\":[\"search_query: Kupferpreis 2025\"],"
                        + "\"input_type\":\"raw\",\"truncate\":false}",
                body("nomic-embed-text:latest", EmbeddingPort.Role.QUERY, "Kupferpreis 2025"));
        assertEquals("{\"model\":\"nomic-embed-text:latest\",\"input\":[\"search_document: Der Preis stieg.\"],"
                        + "\"input_type\":\"raw\",\"truncate\":false}",
                body("nomic-embed-text:latest", EmbeddingPort.Role.DOCUMENT, "Der Preis stieg."));
    }

    @Test
    public void rawRoleSendsTheTextUnchangedForEveryModel() {
        assertEquals("{\"model\":\"nomic-embed-text:latest\",\"input\":[\"anchor\"],\"input_type\":\"raw\",\"truncate\":false}",
                body("nomic-embed-text:latest", EmbeddingPort.Role.RAW, "anchor"));
        assertEquals("{\"model\":\"local/intfloat/e5-small-v2:latest\",\"input\":[\"anchor\"],"
                        + "\"input_type\":\"raw\",\"truncate\":false}",
                body("local/intfloat/e5-small-v2:latest", EmbeddingPort.Role.RAW, "anchor"));
    }

    @Test
    public void localE5ModelsGetTheRoleThroughInputType() {
        assertEquals("{\"model\":\"local/intfloat/e5-small-v2:latest\",\"input\":[\"q\"],\"input_type\":\"query\",\"truncate\":false}",
                body("local/intfloat/e5-small-v2:latest", EmbeddingPort.Role.QUERY, "q"));
        assertEquals("{\"model\":\"local/intfloat/e5-small-v2:latest\",\"input\":[\"p\"],"
                        + "\"input_type\":\"passage\",\"truncate\":false}",
                body("local/intfloat/e5-small-v2:latest", EmbeddingPort.Role.DOCUMENT, "p"));
    }

    @Test
    public void aModelWithoutAKnownSchemeIsEmbeddedRaw() {
        assertSame(EmbeddingPromptScheme.RAW, EmbeddingPromptScheme.forModel("bge-m3:latest"));
        assertSame(EmbeddingPromptScheme.RAW, EmbeddingPromptScheme.forModel("some-unknown-embedder"));
        assertSame(EmbeddingPromptScheme.RAW,
                EmbeddingPromptScheme.forModel("local/sentence-transformers/all-MiniLM-L6-v2:latest"));
        assertEquals("{\"model\":\"bge-m3:latest\",\"input\":[\"q\"],\"input_type\":\"raw\",\"truncate\":false}",
                body("bge-m3:latest", EmbeddingPort.Role.QUERY, "q"));
        assertTrue(EmbeddingPromptScheme.forModel("bge-m3").isRaw());
    }

    @Test
    public void otherCandidatesFollowTheirModelCards() {
        assertSame(EmbeddingPromptScheme.NOMIC, EmbeddingPromptScheme.forModel("nomic-embed-text-v2-moe"));
        assertEquals("task: search result | query: x",
                EmbeddingPromptScheme.forModel("embeddinggemma:300m").apply(EmbeddingPort.Role.QUERY, "x"));
        assertEquals("title: none | text: x",
                EmbeddingPromptScheme.forModel("embeddinggemma:300m").apply(EmbeddingPort.Role.DOCUMENT, "x"));
        assertEquals("query: x",
                EmbeddingPromptScheme.forModel("snowflake-arctic-embed2").apply(EmbeddingPort.Role.QUERY, "x"));
        assertEquals("x",
                EmbeddingPromptScheme.forModel("snowflake-arctic-embed2").apply(EmbeddingPort.Role.DOCUMENT, "x"));
    }

    @Test
    public void thePromptSchemeIsPartOfTheFingerprint() {
        HttpEmbeddingPortAdapter nomic = new HttpEmbeddingPortAdapter(descriptor("nomic-embed-text"), new Recording());
        String base = descriptor("nomic-embed-text").embeddingFingerprint();
        // the raw world (scope fence) keeps the calibrated descriptor fingerprint
        assertEquals(base, nomic.fingerprint(EmbeddingPort.Role.RAW));
        // queries and documents share one world, which differs from raw
        assertEquals(nomic.fingerprint(EmbeddingPort.Role.QUERY), nomic.fingerprint(EmbeddingPort.Role.DOCUMENT));
        assertNotEquals(base, nomic.fingerprint(EmbeddingPort.Role.DOCUMENT));
        assertEquals(nomic.fingerprint(EmbeddingPort.Role.DOCUMENT),
                HttpEmbeddingPortAdapter.documentFingerprint(descriptor("nomic-embed-text")));
        // a different scheme on the same base gives a different fingerprint
        assertNotEquals(EmbeddingPromptScheme.NOMIC.fingerprint(base), EmbeddingPromptScheme.ARCTIC.fingerprint(base));
        // the returned vectors carry it
        EmbeddingPort.EmbeddingVector v = nomic.embedDocuments(Arrays.asList("p")).get(0);
        assertEquals(nomic.fingerprint(EmbeddingPort.Role.DOCUMENT), v.getModelFingerprint());
        EmbeddingPort.EmbeddingVector raw = nomic.embedRaw(Arrays.asList("p")).get(0);
        assertEquals(base, raw.getModelFingerprint());
    }

    @Test
    public void aRawSchemeModelKeepsOneFingerprintInAllRoles() {
        HttpEmbeddingPortAdapter bge = new HttpEmbeddingPortAdapter(descriptor("bge-m3"), new Recording());
        assertEquals(bge.fingerprint(EmbeddingPort.Role.RAW), bge.fingerprint(EmbeddingPort.Role.DOCUMENT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aCallWithoutRoleIsRejected() {
        new HttpEmbeddingPortAdapter(descriptor("nomic-embed-text"), new Recording()).embed(null, Arrays.asList("x"));
    }
}
