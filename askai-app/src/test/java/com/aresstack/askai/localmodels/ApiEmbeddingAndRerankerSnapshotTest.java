package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.modelport.ModelProviderKind;
import com.aresstack.askai.modelport.ModelProviderSettings;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Embeddings and the reranker of a session while the OpenAI-compatible API is the chosen provider. */
public class ApiEmbeddingAndRerankerSnapshotTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static ModelProviderSettings api(String baseUrl) {
        return ModelProviderSettings.defaults().withProvider(ModelProviderKind.OPENAI_COMPATIBLE)
                .withApi(baseUrl, "google/gemma-4-31B-it");
    }

    @Test
    public void theEmbeddingDescriptorPointsAtTheApiWithTheMeasuredDimension() throws Exception {
        ApiEmbeddingConfigurationSnapshotProvider provider = new ApiEmbeddingConfigurationSnapshotProvider(
                () -> api("https://api.example.org/v1/"), (base, model) -> {
            assertEquals("https://api.example.org/v1/", base);
            assertEquals("BAAI/bge-m3", model);
            return 1024;
        }, 60_000L);

        EmbeddingEndpointDescriptor descriptor = provider.prepareForSession("s", folder.getRoot(), "bge-m3")
                .descriptor;

        assertTrue(descriptor.openAiCompatible());
        assertEquals("https://api.example.org/v1/embeddings", descriptor.endpointUrl());
        assertEquals(1024, descriptor.embeddingDimension);
        assertFalse("a vector world of its own: an Ollama stock is embedded again",
                descriptor.sameEmbeddingWorldAs(new EmbeddingEndpointDescriptor("bge-m3", "http://localhost:11434",
                        "/api/embed", 1024, "none", "digest", 60_000L)));
    }

    @Test
    public void theProbeSendsTheKeyAndAFailureNamesTheModel() throws Exception {
        final List<String> authorizations = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ApiEmbeddingConfigurationSnapshotProvider.DimensionProbe probe = ApiEmbeddingConfigurationSnapshotProvider
                    .overHttp(OpenAiCompatibleModelProvider.PLAIN, () -> "sk-probe".toCharArray(), 5000);
            assertEquals(3, probe.probe(base, "BAAI/bge-m3"));
            assertEquals("Bearer sk-probe", authorizations.get(0));
            try {
                new ApiEmbeddingConfigurationSnapshotProvider(() -> api(base),
                        ApiEmbeddingConfigurationSnapshotProvider.overHttp(OpenAiCompatibleModelProvider.PLAIN,
                                () -> null, 5000), 1000L)
                        .prepareForSession("s", folder.getRoot(), "");
                assertEquals(2, authorizations.size());
            } catch (EmbeddingConfigurationException unexpected) {
                fail(unexpected.getMessage());
            }
        } finally {
            server.stop(0);
        }
        try {
            new ApiEmbeddingConfigurationSnapshotProvider(() -> api(""), (b, m) -> 3, 1000L)
                    .prepareForSession("s", folder.getRoot(), "");
            fail("no base URL");
        } catch (EmbeddingConfigurationException expected) {
            assertTrue(expected.getMessage().contains("base URL"));
        }
    }

    @Test
    public void theRerankerRunsOnTheApiWhileItIsChosenElseLocally() throws Exception {
        final ModelProviderSettings[] current = {api("https://api.example.org/v1")};
        ApiAwareRerankerSnapshotProvider provider = new ApiAwareRerankerSnapshotProvider(null, () -> current[0],
                () -> 120_000L);

        RerankerConfigurationSnapshot snapshot = provider.prepareForSession("s", folder.getRoot(), "local/x", "de");

        assertEquals(RerankerProvider.OPENAI_COMPATIBLE, snapshot.getDocument().descriptor.provider);
        assertEquals("nvidia/llama-nemotron-rerank-1b-v2", snapshot.getModelName());
        assertTrue(new String(Files.readAllBytes(snapshot.getSnapshotFile().toPath()), StandardCharsets.UTF_8)
                .contains("OPENAI_COMPATIBLE"));

        current[0] = ModelProviderSettings.defaults();
        try {
            provider.prepareForSession("s", folder.getRoot(), "local/x");
            fail("without the API and without a local runtime there is no reranker");
        } catch (RerankerConfigurationException expected) {
            assertTrue(expected.getMessage().contains("local model runtime"));
        }
    }
}
