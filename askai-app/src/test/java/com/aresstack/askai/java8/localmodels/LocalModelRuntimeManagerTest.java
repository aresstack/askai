package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.java8.json.SimpleJson;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The manager owns the IN-PROCESS local model runtime: starting it binds a free loopback port that serves
 * the Ollama-compatible API, a second start is a no-op on the same URL, and stop frees everything.
 */
public class LocalModelRuntimeManagerTest {

    @Test
    public void startsTheRuntimeInProcessAndServesTheOllamaApi() throws Exception {
        File modelRoot = Files.createTempDirectory("askai-local-runtime-manager").toFile();
        LocalModelRuntimeManager manager = new LocalModelRuntimeManager(modelRoot);
        assertFalse("an empty model root has no installed models", manager.hasInstalledModels());
        assertTrue("the runtime is part of the application now", manager.isAvailable());
        assertFalse(manager.isRunning());
        assertNull(manager.getBaseUrl());
        try {
            String baseUrl = manager.ensureStarted();
            assertTrue(baseUrl, baseUrl.startsWith("http://127.0.0.1:"));
            assertTrue(manager.isRunning());
            assertEquals(baseUrl, manager.getBaseUrl());
            assertSame("a second start reuses the running runtime", baseUrl, manager.ensureStarted());

            Map<?, ?> version = (Map<?, ?>) SimpleJson.parse(get(baseUrl + "/api/version"));
            assertEquals("askai-local-1", version.get("version"));
            Map<?, ?> tags = (Map<?, ?>) SimpleJson.parse(get(baseUrl + "/api/tags"));
            assertNotNull(tags.get("models"));
        } finally {
            manager.stop();
        }
        assertFalse(manager.isRunning());
        assertNull(manager.getBaseUrl());
        manager.stop(); // idempotent
    }

    private static String get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
