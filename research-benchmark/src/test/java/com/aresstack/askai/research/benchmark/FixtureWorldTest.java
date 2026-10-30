package com.aresstack.askai.research.benchmark;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FixtureWorldTest {

    private final BenchmarkQuestion question = BenchmarkQuestionSet.load().select(
            java.util.Collections.singletonList("q4-distractors")).get(0);

    @Test
    public void everyHostIsItsOwnServerAndServesItsPages() throws Exception {
        try (FixtureWorld world = FixtureWorld.start(question, 8)) {
            java.util.Set<Integer> ports = new java.util.HashSet<Integer>();
            for (BenchmarkQuestion.Page page : question.getPages()) {
                String url = world.urlOf(page);
                ports.add(new URL(url).getPort());
                String html = get(url, 200);
                assertTrue(html.contains(page.getParagraphs().get(0).substring(0, 20)));
                assertEquals(page.getId(), world.pageForUrl(url).getId());
                assertEquals("trailing slash resolves too", page.getId(), world.pageForUrl(url + "/").getId());
            }
            java.util.Set<String> hosts = new java.util.HashSet<String>();
            for (BenchmarkQuestion.Page page : question.getPages()) {
                hosts.add(page.getHost());
            }
            assertEquals("one server per logical host", hosts.size(), ports.size());
            assertNull(world.pageForUrl(world.engineBaseUrl() + "/find?q=x"));
            assertNull(world.pageForUrl("not a url"));
        }
    }

    @Test
    public void theEngineRanksByQueryWordsInTheStructuredSerpLayout() throws Exception {
        try (FixtureWorld world = FixtureWorld.start(question, 8)) {
            List<BenchmarkQuestion.Page> ranked = world.rank("Lindqvist alloy corrosion sea water");
            assertEquals("the datasheet matches every query word", "q4-datasheet", ranked.get(0).getId());
            String serp = get(world.engineBaseUrl() + "/find?q="
                    + URLEncoder.encode("Lindqvist alloy corrosion", StandardCharsets.UTF_8), 200);
            assertTrue(serp.contains("<li><h2><a href='" + world.urlOf(question.page("q4-datasheet")) + "'>"));
            assertTrue(serp.contains("</a></h2><p>"));
            assertEquals("Lindqvist alloy corrosion", world.engineQueries().get(0));
            assertTrue(world.rank("zebra").isEmpty());
        }
        try (FixtureWorld capped = FixtureWorld.start(question, 2)) {
            assertEquals("the result count is a setting", 2, capped.rank("Lindqvist").size());
        }
    }

    @Test
    public void anUnknownPathIsNotFound() throws Exception {
        try (FixtureWorld world = FixtureWorld.start(question, 8)) {
            get(world.urlOf(question.page("q4-band")) + "-missing", 404);
        }
    }

    static String get(String url, int expectedStatus) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            assertEquals(url, expectedStatus, connection.getResponseCode());
            InputStream in = expectedStatus >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
