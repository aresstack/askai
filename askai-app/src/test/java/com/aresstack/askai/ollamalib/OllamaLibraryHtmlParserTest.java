package com.aresstack.askai.ollamalib;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OllamaLibraryHtmlParserTest {

    private final OllamaLibraryHtmlParser parser = new OllamaLibraryHtmlParser();

    private String fixture(String name) throws IOException {
        InputStream in = getClass().getResourceAsStream("/ollama/" + name);
        assertNotNull("fixture missing: " + name, in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private OllamaLibraryModel find(List<OllamaLibraryModel> models, String base) {
        for (OllamaLibraryModel model : models) {
            if (model.getBaseName().equals(base)) {
                return model;
            }
        }
        return null;
    }

    private OllamaModelVariant variant(List<OllamaModelVariant> variants, String tag) {
        for (OllamaModelVariant v : variants) {
            if (v.getTag().equals(tag)) {
                return v;
            }
        }
        return null;
    }

    @Test
    public void parsesSearchResults() throws IOException {
        List<OllamaLibraryModel> models = parser.parseSearchResults(fixture("search-devstral.html"));
        assertFalse("expected search results", models.isEmpty());

        OllamaLibraryModel devstral = find(models, "devstral-small-2");
        assertNotNull("devstral-small-2 result missing", devstral);
        assertTrue("description present", devstral.getDescription().length() > 10);
        assertTrue("capability vision", devstral.getCapabilities().contains("vision"));
        assertTrue("capability tools", devstral.getCapabilities().contains("tools"));
        assertTrue("param size 24b", devstral.getParameterSizes().contains("24b"));
        assertTrue("pulls present", devstral.getPullsText().length() > 0);

        // No capability should be a stats label leaking through.
        assertFalse(devstral.getCapabilities().contains("pulls"));
        assertFalse(devstral.getCapabilities().contains("tags"));
        assertFalse(devstral.getCapabilities().contains("updated"));
    }

    private void assertDevstralVariants(List<OllamaModelVariant> variants) {
        // No duplicate variants even though ollama.com ships a mobile + a desktop row per tag.
        assertEquals("expected exactly 3 unique variants", 3, variants.size());

        assertNotNull(variant(variants, "devstral-small-2:latest"));
        OllamaModelVariant b24 = variant(variants, "devstral-small-2:24b");
        assertNotNull("24b variant missing", b24);
        assertEquals("24b", b24.getShortTag());
        assertEquals("15GB", b24.getSize());
        assertEquals("384K context window", b24.getContextWindow());
        assertEquals(Arrays.asList("Text", "Image"), b24.getInputTypes());
        assertEquals("Text, Image", b24.getInputTypesText());
        assertFalse("24b is not cloud", b24.isCloud());
        assertTrue("latest badge only on 24b", b24.isLatest());
        assertFalse("latest badge not on :latest tag itself", variant(variants, "devstral-small-2:latest").isLatest());

        OllamaModelVariant cloud = variant(variants, "devstral-small-2:24b-cloud");
        assertNotNull("cloud variant missing", cloud);
        assertTrue("cloud flagged", cloud.isCloud());
        assertEquals("cloud has no local size", "", cloud.getSize());
        assertEquals("256K context window", cloud.getContextWindow());
        assertEquals(Arrays.asList("Text", "Image"), cloud.getInputTypes());
        assertFalse("cloud is not latest", cloud.isLatest());
    }

    @Test
    public void parsesModelVariantsFromServedHtml() throws IOException {
        // The server-rendered page (no input.command) — parsed via the anchor fallback.
        assertDevstralVariants(parser.parseModelVariants("devstral-small-2",
                fixture("library-devstral-small-2.html")));
    }

    @Test
    public void parsesModelVariantsFromInputCommandHtml() throws IOException {
        // The browser-DOM variant that carries the canonical <input class="command"> pull names.
        assertDevstralVariants(parser.parseModelVariants("devstral-small-2",
                fixture("library-devstral-small-2-tags.html")));
    }

    @Test
    public void changedHtmlThrowsClearError() throws IOException {
        try {
            parser.parseSearchResults(fixture("changed.html"));
            fail("expected a drift error for changed HTML");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("geändert"));
        }
    }
}
