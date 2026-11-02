package com.aresstack.askai.hf.catalog;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the Apps facet is read from the real HuggingFace source shape: inline
 * {@code {"id","label","type":"apps"}} objects in the {@code /models} page, plus the compact
 * cache-array round-trip.
 */
public class LiveAppCatalogParserTest {

    /** A trimmed excerpt mirroring the models-page inline JSON (apps interleaved with other types). */
    private static final String MODELS_PAGE_EXCERPT =
            "...,\"type\":\"inference_provider\"}],\"apps\":["
                    + "{\"id\":\"llama.cpp\",\"label\":\"llama.cpp\",\"type\":\"apps\"},"
                    + "{\"id\":\"lmstudio\",\"label\":\"LM Studio\",\"type\":\"apps\"},"
                    + "{\"id\":\"ollama\",\"label\":\"Ollama\",\"type\":\"apps\"},"
                    + "{\"id\":\"mlx-lm\",\"label\":\"MLX LM\",\"type\":\"apps\"}"
                    + "],\"other\":[{\"id\":\"moe\",\"label\":\"Mixture of Experts\",\"type\":\"other\"}]...";

    @Test
    public void parsesAppsFromModelsPage() {
        List<CatalogEntry> apps = LiveAppCatalogParser.fromModelsPage(MODELS_PAGE_EXCERPT);
        assertEquals(4, apps.size());
        assertEquals("llama.cpp", apps.get(0).getId());
        assertEquals("LM Studio", apps.get(1).getDisplayName());
        assertEquals("mlx-lm", apps.get(3).getId());
        // Non-app types (other/inference_provider) must not leak in.
        for (CatalogEntry entry : apps) {
            assertTrue(entry.getId(), !entry.getId().equals("moe"));
        }
    }

    @Test
    public void emptyOrGarbageYieldsEmptyForFallback() {
        assertTrue(LiveAppCatalogParser.fromModelsPage("").isEmpty());
        assertTrue(LiveAppCatalogParser.fromModelsPage("<html>no apps here</html>").isEmpty());
        assertTrue(LiveAppCatalogParser.fromModelsPage(null).isEmpty());
    }

    @Test
    public void cacheArrayRoundTrips() {
        List<CatalogEntry> apps = LiveAppCatalogParser.fromModelsPage(MODELS_PAGE_EXCERPT);
        String json = LiveAppCatalogParser.toJsonArray(apps);
        List<CatalogEntry> back = LiveAppCatalogParser.fromJsonArray(json);
        assertEquals(apps.size(), back.size());
        assertEquals("ollama", back.get(2).getId());
        assertEquals("Ollama", back.get(2).getDisplayName());
    }
}
