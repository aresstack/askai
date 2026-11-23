package com.aresstack.askai.research.knowledge.processing.embedding;

import com.aresstack.askai.agent.model.EndpointAuthorization;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Embeddings on an OpenAI-compatible API ({@code /embeddings}, the wire taken from Space Harvester): one request per
 * text in input order, a strict reader, the key only on the API's own URLs and error messages without any input text.
 */
public class OpenAiEmbeddingOverApiTest {

    private HttpServer server;

    @After
    public void tearDown() {
        EndpointAuthorization.install(null);
        if (server != null) {
            server.stop(0);
        }
    }

    private static EmbeddingEndpointDescriptor api(String baseUrl, int dimension) {
        return new EmbeddingEndpointDescriptor("BAAI/bge-m3", baseUrl, EmbeddingEndpointDescriptor.OPENAI_EMBEDDINGS_PATH,
                dimension, "none", "openai-compatible-api", 5000L);
    }

    @Test
    public void sendsOneRequestPerTextAndKeepsTheOrder() {
        final List<String> bodies = new ArrayList<String>();
        HttpEmbeddingPortAdapter adapter = new HttpEmbeddingPortAdapter(api("https://api.example.org/v1", 2),
                (url, body) -> {
                    assertEquals("https://api.example.org/v1/embeddings", url);
                    bodies.add(body);
                    float first = bodies.size();
                    return "{\"data\":[{\"index\":0,\"embedding\":[" + first + ",0.5]}],\"usage\":{}}";
                });

        List<EmbeddingPort.EmbeddingVector> vectors = adapter.embed(EmbeddingPort.Role.DOCUMENT,
                Arrays.asList("erster \"Text\"", "zweiter"));

        assertEquals(Arrays.asList("{\"model\":\"BAAI/bge-m3\",\"input\":\"erster \\\"Text\\\"\"}",
                "{\"model\":\"BAAI/bge-m3\",\"input\":\"zweiter\"}"), bodies);
        assertEquals(2, vectors.size());
        assertEquals(1.0f, vectors.get(0).getValues()[0], 0.0f);
        assertEquals(2.0f, vectors.get(1).getValues()[0], 0.0f);
        assertEquals("BAAI/bge-m3", vectors.get(0).getModelId());
    }

    @Test
    public void theReaderIsStrict() {
        assertEquals(2, OpenAiEmbeddingWire.parse("{\"data\":[{\"index\":1,\"embedding\":[2]},"
                + "{\"index\":0,\"embedding\":[1]}]}", 2).size());
        assertEquals(1.0f, OpenAiEmbeddingWire.parse("{\"data\":[{\"index\":1,\"embedding\":[2]},"
                + "{\"index\":0,\"embedding\":[1]}]}", 2).get(0)[0], 0.0f);
        for (String broken : Arrays.asList("{\"data\":[{\"embedding\":\"AAAA\"}]}", "{\"data\":[]}",
                "{\"data\":[{\"index\":0,\"embedding\":[1]},{\"embedding\":[2]}]}", "not json", "{}")) {
            try {
                OpenAiEmbeddingWire.parse(broken, broken.contains("},{") ? 2 : 1);
                fail("accepted " + broken);
            } catch (EmbeddingException expected) {
                // strict
            }
        }
        try {
            new HttpEmbeddingPortAdapter(api("https://api.example.org/v1", 3),
                    (url, body) -> "{\"data\":[{\"embedding\":[1,2]}]}").embed(EmbeddingPort.Role.QUERY,
                    Arrays.asList("x"));
            fail("a wrong dimension must fail");
        } catch (EmbeddingException expected) {
            assertTrue(expected.getMessage().contains("dimension"));
        }
    }

    @Test
    public void theKeyGoesOnlyToTheApiAndErrorsNameNoInput() throws Exception {
        final List<String> authorizations = new CopyOnWriteArrayList<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String request = read(exchange.getRequestBody());
            boolean secretInput = request.contains("vertraulich");
            byte[] body = (secretInput
                    ? "{\"error\":{\"message\":\"input vertraulich too long\",\"code\":\"context_length_exceeded\"}}"
                    : "{\"data\":[{\"embedding\":[0.1,0.2]}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(secretInput ? 400 : 200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        EndpointAuthorization.install(url -> url.startsWith(base + "/v1/") ? "sk-test-key".toCharArray() : null);
        UrlConnectionEmbeddingHttpTransport transport = new UrlConnectionEmbeddingHttpTransport(5000);

        HttpEmbeddingPortAdapter adapter = new HttpEmbeddingPortAdapter(api(base + "/v1", 2), transport);
        assertEquals(1, adapter.embed(EmbeddingPort.Role.DOCUMENT, Arrays.asList("ok")).size());
        assertEquals("Bearer sk-test-key", authorizations.get(0));

        try {
            adapter.embed(EmbeddingPort.Role.DOCUMENT, Arrays.asList("vertraulich"));
            fail("HTTP 400 must fail");
        } catch (EmbeddingException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 400 (context_length_exceeded)"));
            assertFalse("no input text in the message", expected.getMessage().contains("vertraulich"));
            assertFalse(expected.getMessage().contains("sk-test-key"));
        }

        transport.post(base + "/api/embed", "{}"); // an Ollama URL of the same host gets no key
        assertEquals("null", authorizations.get(authorizations.size() - 1));
        assertNull(EndpointAuthorization.bearerFor(base + "/api/embed"));
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
