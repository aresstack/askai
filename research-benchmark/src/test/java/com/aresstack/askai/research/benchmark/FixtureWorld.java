package com.aresstack.askai.research.benchmark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The local world one question is researched in: one loopback server per logical host of the corpus (the
 * browser's {@code --domain-key-mode=host-port} makes each one its own domain family), a SERP engine that
 * ranks the corpus pages by query-word overlap and answers in the structurally valid {@code li(h2(a),p)}
 * layout.
 */
public final class FixtureWorld implements AutoCloseable, BenchmarkScorer.FixtureWorldIndex {

    private final BenchmarkQuestion question;
    private final int serpResults;
    private final HttpServer engine;
    private final Map<String, HttpServer> hosts = new LinkedHashMap<String, HttpServer>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "benchmark-fixture-world");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> engineQueries = Collections.synchronizedList(new ArrayList<String>());

    private FixtureWorld(BenchmarkQuestion question, int serpResults) throws IOException {
        this.question = question;
        this.serpResults = serpResults;
        this.engine = start();
        for (BenchmarkQuestion.Page page : question.getPages()) {
            if (!hosts.containsKey(page.getHost())) {
                hosts.put(page.getHost(), start());
            }
        }
        engine.createContext("/find", this::serp);
        for (Map.Entry<String, HttpServer> host : hosts.entrySet()) {
            host.getValue().createContext("/", exchange -> page(host.getKey(), exchange));
        }
    }

    /** Start the servers of {@code question}'s corpus. */
    public static FixtureWorld start(BenchmarkQuestion question, int serpResults) throws IOException {
        return new FixtureWorld(question, serpResults);
    }

    /** The engine's search URL template in the browser's {@code --search-url} format. */
    public String searchUrlTemplate() {
        return engineBaseUrl() + "/find?q={query}";
    }

    public String engineBaseUrl() {
        return "http://127.0.0.1:" + engine.getAddress().getPort();
    }

    /** The absolute URL of a corpus page. */
    public String urlOf(BenchmarkQuestion.Page page) {
        return "http://127.0.0.1:" + hosts.get(page.getHost()).getAddress().getPort() + page.getPath();
    }

    /** The corpus page behind a URL the subject visited or accepted, or null for any other URL. */
    @Override
    public BenchmarkQuestion.Page pageForUrl(String url) {
        if (url == null) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        for (BenchmarkQuestion.Page page : question.getPages()) {
            HttpServer server = hosts.get(page.getHost());
            if (uri.getPort() == server.getAddress().getPort() && path.equals(page.getPath())) {
                return page;
            }
        }
        return null;
    }

    /** Every query the engine received, in order (how the subject searched). */
    public List<String> engineQueries() {
        synchronized (engineQueries) {
            return new ArrayList<String>(engineQueries);
        }
    }

    /** The pages the engine returns for {@code query}, best first (also used by the unit test). */
    List<BenchmarkQuestion.Page> rank(String query) {
        Set<String> words = words(query);
        List<BenchmarkQuestion.Page> ranked = new ArrayList<BenchmarkQuestion.Page>();
        Map<BenchmarkQuestion.Page, Integer> scores = new LinkedHashMap<BenchmarkQuestion.Page, Integer>();
        for (BenchmarkQuestion.Page page : question.getPages()) {
            Set<String> pageWords = words(page.text());
            int score = 0;
            for (String word : words) {
                if (pageWords.contains(word)) {
                    score++;
                }
            }
            if (score > 0) {
                scores.put(page, score);
                ranked.add(page);
            }
        }
        // Stable: equal scores keep corpus order, so the SERP is deterministic across runs.
        ranked.sort((a, b) -> Integer.compare(scores.get(b), scores.get(a)));
        return ranked.size() > serpResults ? new ArrayList<BenchmarkQuestion.Page>(ranked.subList(0, serpResults))
                : ranked;
    }

    @Override
    public void close() {
        engine.stop(0);
        for (HttpServer server : hosts.values()) {
            server.stop(0);
        }
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ handlers

    private void serp(HttpExchange exchange) throws IOException {
        String query = parameter(exchange.getRequestURI().getRawQuery(), "q");
        engineQueries.add(query);
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>Find: ")
                .append(escape(query)).append("</title></head><body><main><ul>");
        for (BenchmarkQuestion.Page page : rank(query)) {
            String snippet = page.getParagraphs().get(0);
            if (snippet.length() > 160) {
                snippet = snippet.substring(0, 160) + "...";
            }
            html.append("<li><h2><a href='").append(urlOf(page)).append("'>").append(escape(page.getTitle()))
                    .append("</a></h2><p>").append(escape(snippet)).append("</p></li>");
        }
        html.append("</ul></main></body></html>");
        respond(exchange, 200, "text/html; charset=utf-8", html.toString());
    }

    private void page(String host, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        for (BenchmarkQuestion.Page page : question.getPages()) {
            if (page.getHost().equals(host) && page.getPath().equals(path)) {
                StringBuilder html = new StringBuilder("<!doctype html><html><head><title>")
                        .append(escape(page.getTitle())).append("</title></head><body><article><h1>")
                        .append(escape(page.getTitle())).append("</h1>");
                for (String paragraph : page.getParagraphs()) {
                    html.append("<p>").append(escape(paragraph)).append("</p>");
                }
                for (String link : page.getLinks()) {
                    BenchmarkQuestion.Page target = question.page(link);
                    html.append("<p><a href='").append(urlOf(target)).append("'>")
                            .append(escape(target.getTitle())).append("</a></p>");
                }
                html.append("</article></body></html>");
                respond(exchange, 200, "text/html; charset=utf-8", html.toString());
                return;
            }
        }
        respond(exchange, 404, "text/plain; charset=utf-8", "not found");
    }

    // ------------------------------------------------------------------ helpers

    private HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String parameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return "";
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static Set<String> words(String text) {
        Set<String> out = new HashSet<String>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                out.add(word);
            }
        }
        return out;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }
}
