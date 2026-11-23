package com.aresstack.askai.service;

import com.aresstack.askai.client.OllamaChatTurn;
import com.aresstack.askai.client.OllamaModelInfoView;
import com.aresstack.askai.modelport.CentralModelDirectories;
import com.aresstack.askai.modelport.ModelProviderKind;
import com.aresstack.askai.modelport.ModelProviderSettingsRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The chat window on the OpenAI-compatible API: its model list (chat models only, the chat role first), details
 * without an Ollama call, a streamed answer with the key, and Ollama untouched while it is the chosen provider.
 */
public class ApiAwareChatServiceTest {

    private static final Executor DIRECT = Runnable::run;
    private static final List<String> API_MODELS = Arrays.asList("mistralai/Mistral-Small-3.2-24B-Instruct-2506",
            "google/gemma-4-31B-it", "nvidia/llama-nemotron-rerank-1b-v2", "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice",
            "danielheinz/e5-base-sts-en-de", "Qwen/Qwen3-Embedding-8B", "mixedbread-ai/mxbai-embed-large-v1",
            "orpheus-tts-3b-de", "google/vit-base-patch16-224-in21k", "ellamind/sui-1-24b", "BAAI/bge-m3",
            "ibm-granite/granite-docling-258M", "openai/gpt-oss-120b", "opendatalab/MinerU2.5-Pro-2604-1.2B");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private String base;
    private ModelProviderSettingsRepository providers;
    private final List<String> requests = new CopyOnWriteArrayList<String>();
    private final List<String> ollamaCalls = new CopyOnWriteArrayList<String>();

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(path + " " + exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + read(exchange.getRequestBody()));
            byte[] body;
            if (path.endsWith("/models")) {
                body = "{\"data\":[{\"id\":\"BAAI/bge-m3\"},{\"id\":\"openai/gpt-oss-120b\"},{\"id\":\"google/gemma-4-31B-it\"},{\"id\":\"orpheus-tts-3b-de\"}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            } else {
                body = ("data: {\"choices\":[{\"delta\":{\"content\":\"Hal\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            }
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        providers = new ModelProviderSettingsRepository(new File(folder.getRoot(), "model-provider.properties"));
        providers.save(providers.load().withProvider(ModelProviderKind.OPENAI_COMPATIBLE).withApi(base, "x"));
        providers.storeApiKey("sk-chat".toCharArray());
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private ApiAwareChatService service() {
        OllamaService ollama = (OllamaService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{OllamaService.class}, (proxy, method, args) -> {
                    ollamaCalls.add(method.getName());
                    return null;
                });
        return new ApiAwareChatService(ollama, providers, CentralModelDirectories.apiProvider(providers, null),
                DIRECT);
    }

    @Test
    public void theModelListHoldsOnlyChatModelsWithTheChatRoleFirst() {
        final List<String> names = new ArrayList<String>();
        service().listChatModelNames(new OllamaService.ModelNamesListener() {
            public void onModelNames(List<String> listed) {
                names.addAll(listed);
            }

            public void onError(Exception ex) {
                throw new AssertionError(ex);
            }
        });
        assertEquals(Arrays.asList("google/gemma-4-31B-it", "openai/gpt-oss-120b"), names);
        assertTrue(ollamaCalls.isEmpty());
        assertEquals(Arrays.asList("mistralai/Mistral-Small-3.2-24B-Instruct-2506", "google/gemma-4-31B-it",
                        "ellamind/sui-1-24b", "openai/gpt-oss-120b"),
                ApiAwareChatService.chatModels(API_MODELS,
                        ""));
    }

    @Test
    public void anAnswerStreamsFromTheApiWithTheKey() {
        final StringBuilder content = new StringBuilder();
        final List<OllamaService.ChatResult> done = new ArrayList<OllamaService.ChatResult>();
        service().streamChat(new OllamaService.ChatRequest("google/gemma-4-31B-it", "5m", Arrays.asList(
                OllamaChatTurn.system("Sei kurz."), OllamaChatTurn.user("Hallo?"))), new OllamaService.ChatListener() {
            public void onContent(String delta) {
                content.append(delta);
            }

            public void onStatus(String status) {
            }

            public void onComplete(OllamaService.ChatResult result) {
                done.add(result);
            }

            public void onError(Exception ex) {
                throw new AssertionError(ex);
            }
        });
        assertEquals("Hallo", content.toString());
        assertEquals(1, done.size());
        String request = requests.get(0);
        assertTrue(request, request.startsWith("/v1/chat/completions Bearer sk-chat "));
        assertTrue(request.contains("\"model\":\"google/gemma-4-31B-it\""));
        assertTrue(request.contains("Sei kurz."));
        assertTrue(ollamaCalls.isEmpty());

        final List<String> capabilities = new ArrayList<String>();
        service().getModelInfo("google/gemma-4-31B-it", new OllamaService.ModelInfoListener() {
            public void onModelInfo(OllamaModelInfoView info) {
                capabilities.addAll(info.getCapabilities());
            }

            public void onError(Exception ex) {
                throw new AssertionError(ex);
            }
        });
        assertTrue("gemma-4-31B-it is also the vision model", capabilities.contains("vision"));
    }

    @Test
    public void withOllamaChosenEverythingGoesToOllama() throws Exception {
        providers.save(providers.load().withProvider(ModelProviderKind.OLLAMA));
        ApiAwareChatService service = service();
        service.listChatModelNames(null);
        service.streamChat(null, null);
        service.loadModel("m", "5m", null);
        assertEquals(Arrays.asList("listChatModelNames", "streamChat", "loadModel"), ollamaCalls);
        assertFalse(requests.stream().anyMatch(r -> r.contains("chat/completions")));
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
