package com.aresstack.askai.research.acceptance;

import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Plan PR 32: the opt-in live gate of the first-book acceptance run. It skips unless an Ollama endpoint is
 * configured ({@code ASKAI_ACCEPTANCE_OLLAMA}, e.g. {@code http://localhost:11434}). With the endpoint it checks that
 * the models of the run are installed ({@code ASKAI_ACCEPTANCE_MODELS}, default {@code gemma4:e2b,bge-m3}); with
 * {@code ASKAI_ACCEPTANCE_PROJECT} pointing at the finished book project it runs the data-integrity level and
 * writes its Markdown to {@code build/first-book-acceptance/integrity.md} for {@code docs/first-book-acceptance.md}.
 * It never starts, changes or downloads a model.
 */
public class FirstBookAcceptanceGateTest {

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    @Test
    public void firstBookAcceptanceGate() throws Exception {
        String endpoint = env("ASKAI_ACCEPTANCE_OLLAMA");
        assumeTrue("set ASKAI_ACCEPTANCE_OLLAMA to run the first-book acceptance gate", !endpoint.isEmpty());

        String tags = get(endpoint.replaceAll("/+$", "") + "/api/tags");
        String models = env("ASKAI_ACCEPTANCE_MODELS").isEmpty() ? "gemma4:e2b,bge-m3"
                : env("ASKAI_ACCEPTANCE_MODELS");
        for (String model : models.split(",")) {
            String name = model.trim();
            if (!name.isEmpty()) {
                assertTrue("model " + name + " is not installed at " + endpoint,
                        tags.contains("\"" + name + "\"") || tags.contains("\"" + name + ":latest\""));
            }
        }

        String project = env("ASKAI_ACCEPTANCE_PROJECT");
        if (project.isEmpty()) {
            return;
        }
        File projectDirectory = new File(project);
        assertTrue("no book project at ASKAI_ACCEPTANCE_PROJECT", projectDirectory.isDirectory());
        BookIntegrityReport report = BookIntegrityCheck.forProject(projectDirectory).run();
        File out = new File("build/first-book-acceptance");
        out.mkdirs();
        Files.write(new File(out, "integrity.md").toPath(), report.markdown().getBytes(Charset.forName("UTF-8")));
        assertTrue(report.findings().toString(), report.passed());
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        assertEquals("Ollama at " + url, 200, connection.getResponseCode());
        InputStream in = connection.getInputStream();
        try {
            Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } finally {
            in.close();
        }
    }
}
