package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.playwright.PlaywrightBrowserRuntime;
import com.aresstack.askai.browser.playwright.PlaywrightUserContextPool;
import com.aresstack.askai.browser.playwright.WorkerBrowserLimits;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.ResearchSearchIndex;
import com.aresstack.askai.research.capture.SourceAcceptanceService;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextLease;
import com.aresstack.askai.research.runtime.browser.BrowserUserContextRequest;
import com.aresstack.askai.research.runtime.browser.BrowsingContextLease;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * The research session's worker user contexts end to end over a REAL browser: workers acquire leases through
 * the runtime's neutral {@code BrowserUserContextPool}, browse in parallel in isolated user contexts, and every
 * page they read lands in the session's ONE capture store with a {@code capture_id} — accepted through the
 * session's ONE {@link SourceAcceptanceService}, which recognizes the page two workers found independently
 * as the same source. ENVIRONMENT-GATED on a Chromium executable (as the browser module's live tests).
 */
public class ChatBrowserUserContextsLiveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HttpServer server;
    private String base;
    private PlaywrightUserContextPool pool;
    private final ExecutorService workers = Executors.newCachedThreadPool();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if (path.equals("/login")) {
                exchange.getResponseHeaders().add("Set-Cookie", "session=" + query.substring(5) + "; Path=/");
            }
            try {
                Thread.sleep(path.startsWith("/article") ? 800L : 0L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, "<!doctype html><html><head><title>" + path + "</title></head><body><div id='c'>"
                    + "</div><script>document.getElementById('c').textContent='Article text of " + path
                    + " cookie=[' + document.cookie + ']';</script></body></html>");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        workers.shutdownNow();
        if (pool != null) {
            pool.close();
        }
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty(PlaywrightUserContextPool.WORKER_EXECUTABLE_PROPERTY);
    }

    private static void respond(HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Path chromium() {
        String configured = System.getProperty("askai.test.workerBrowser", System.getenv("ASKAI_TEST_WORKER_BROWSER"));
        if (configured != null && !configured.trim().isEmpty()) {
            Path path = Paths.get(configured.trim());
            return Files.isExecutable(path) ? path : null;
        }
        Path root = Paths.get("/opt/pw-browsers");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (java.util.stream.Stream<Path> found = Files.walk(root, 3)) {
            return found.filter(p -> p.getFileName().toString().equals("chrome") && Files.isExecutable(p)
                    && p.toString().contains("chromium-")).findFirst().orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static Map<String, Object> url(String url) {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("url", url);
        return args;
    }

    private static String captureId(String result) {
        Matcher m = Pattern.compile("capture_id=(\\S+)").matcher(result);
        assertTrue("the bridge contract line must carry a capture id: " + result, m.find());
        return m.group(1);
    }

    @Test
    public void parallelWorkersFeedTheSessionsOneSourceProcess() throws Exception {
        Path executable = chromium();
        assumeTrue("SKIPPED (environment-gated live test): no Chromium executable found", executable != null);
        System.setProperty(PlaywrightUserContextPool.WORKER_EXECUTABLE_PROPERTY, executable.toString());
        pool = new PlaywrightUserContextPool(new WorkerBrowserLimits(2, 2, 2, 4, 60_000L, 60_000L, 0L));
        PlaywrightBrowserRuntime.Options options = new PlaywrightBrowserRuntime.Options("chrome", true, true,
                null, null, true);
        final CaptureStore captures = new CaptureStore(100);
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        SourceAcceptanceService acceptance = new SourceAcceptanceService(captures, repo,
                new SourceAcceptanceService.SourceCreator() {
                    public void create(ResearchSourceRecord record) {
                        repo.put(record);
                    }
                }, new ResearchSearchIndex.InMemory());
        Path stateRoot = tmp.newFolder("worker-state").toPath();
        ChatBrowserUserContexts contexts = new ChatBrowserUserContexts(pool.openChat("chat-a", options), captures,
                new WorkerUserContextStateStore(stateRoot, "chat-a"), "chat-a");
        assertEquals(4, contexts.capacity());

        // Two workers search in parallel; both run into the same article, each also reads its own.
        final List<BrowserUserContextLease> leases = new ArrayList<BrowserUserContextLease>();
        leases.add(contexts.acquire(BrowserUserContextRequest.of("worker-1", "task-1")));
        leases.add(contexts.acquire(BrowserUserContextRequest.of("worker-2", "task-2")));
        assertNotEquals(leases.get(0).userContextId(), leases.get(1).userContextId());
        List<Future<String[]>> visits = new ArrayList<Future<String[]>>();
        long start = System.nanoTime();
        for (int i = 0; i < 2; i++) {
            final ToolInvoker browser = leases.get(i).browser();
            final int n = i + 1;
            visits.add(workers.submit(new Callable<String[]>() {
                public String[] call() throws Exception {
                    String shared = browser.call("web_open", url(base + "/article-shared"));
                    String own = browser.call("web_open", url(base + "/article-" + n));
                    return new String[]{shared, own};
                }
            }));
        }
        List<String> captureIds = new ArrayList<String>();
        for (Future<String[]> visit : visits) {
            for (String result : visit.get(60, TimeUnit.SECONDS)) {
                assertTrue(result, result.contains("Article text of /article"));
                captureIds.add(captureId(result));
            }
        }
        long wall = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertEquals("all four visits are in the session's one capture store", 4, captures.size());
        assertTrue("two workers x two 800 ms pages in parallel, took " + wall + " ms", wall < 3_000L);

        // The session's ONE acceptance: four visits, three distinct pages, three sources.
        int accepted = 0;
        int alreadyAccepted = 0;
        for (String id : captureIds) {
            SourceAcceptanceService.Result result = acceptance.accept(id);
            if (result.status == SourceAcceptanceService.Status.ACCEPTED) {
                accepted++;
            } else if (result.status == SourceAcceptanceService.Status.ALREADY_ACCEPTED) {
                alreadyAccepted++;
            }
        }
        assertEquals(3, accepted);
        assertEquals("the page both workers found is one source", 1, alreadyAccepted);
        assertEquals(3, repo.find(com.aresstack.askai.research.sources.SourceQuery.all()).size());

        // A second tab of worker 1; its pages enter the same capture store.
        BrowsingContextLease tab = leases.get(0).openBrowsingContext();
        captureId(tab.tools().call("web_open", url(base + "/article-tab")));
        assertEquals(5, captures.size());
        tab.close();

        // Closing a lease ends the worker's endpoint, never another worker's.
        leases.get(0).close();
        try {
            leases.get(0).browser().call("web_read", new HashMap<String, Object>());
            fail("a closed lease must not answer");
        } catch (ToolInvoker.EndpointUnavailable expected) {
            // the endpoint is gone for this worker
        }
        assertTrue(leases.get(1).browser().call("web_read", new HashMap<String, Object>()).contains("capture_id="));
        leases.get(1).close();
        assertEquals(0, contexts.openLeases());

        // A worker that keeps its sign-in: saved on close (outside the project folder), restored on the next lease.
        BrowserUserContextLease signIn = contexts.acquire(BrowserUserContextRequest.of("worker-login", "t")
                .withStateKey("account-alice"));
        signIn.browser().call("web_open", url(base + "/login?user=alice"));
        signIn.close();
        assertTrue(Files.isRegularFile(stateRoot.resolve(new WorkerUserContextStateStore(stateRoot, "chat-a")
                .chatDirectory().getFileName()).resolve("account-alice.json")));
        BrowserUserContextLease resumed = contexts.acquire(BrowserUserContextRequest.of("worker-login", "t2")
                .withStateKey("account-alice"));
        BrowserUserContextLease fresh = contexts.acquire(BrowserUserContextRequest.of("worker-other", "t3"));
        assertTrue(resumed.browser().call("web_open", url(base + "/whoami")).contains("cookie=[session=alice]"));
        assertTrue(fresh.browser().call("web_open", url(base + "/whoami")).contains("cookie=[]"));

        // Session end: every lease and worker browser of the chat ends.
        contexts.close();
        try {
            fresh.browser().call("web_read", new HashMap<String, Object>());
            fail("leases end with the session");
        } catch (ToolInvoker.EndpointUnavailable expected) {
            // closed with the session
        }
        long deadline = System.currentTimeMillis() + 15_000L;
        while (pool.processCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(0, pool.processCount());
    }
}
