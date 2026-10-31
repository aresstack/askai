package com.aresstack.askai.browser.playwright;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The AskAI Research add-on as Camoufox gets it: complete, with this start's bridge config, nothing for pages. */
public class ResearchAddonTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installWritesEveryFileAndTheBridgeConfig() throws Exception {
        Path addon = ResearchAddon.install(temporary.getRoot().toPath(), "{\"protocol\":1,\"port\":4711,\"token\":\"t\"}");
        assertEquals(ResearchAddon.DIRECTORY, addon.getFileName().toString());
        for (String file : ResearchAddon.FILES) {
            assertTrue(file, Files.isRegularFile(addon.resolve(file)));
        }
        assertEquals("{\"protocol\":1,\"port\":4711,\"token\":\"t\"}",
                Files.readString(addon.resolve("bridge.json"), StandardCharsets.UTF_8));
    }

    @Test
    public void aRestartReplacesTheOldBridgeConfig() throws Exception {
        Path parent = temporary.getRoot().toPath();
        ResearchAddon.install(parent, "{\"token\":\"old\"}");
        Path addon = ResearchAddon.install(parent, "{\"token\":\"new\"}");
        assertEquals("{\"token\":\"new\"}", Files.readString(addon.resolve("bridge.json")));
        ResearchAddon.install(parent, null);
        assertFalse("an idle add-on has no bridge", Files.exists(addon.resolve("bridge.json")));
    }

    @Test
    public void theManifestExposesNothingToPages() throws Exception {
        Path addon = ResearchAddon.install(temporary.getRoot().toPath(), null);
        JsonObject manifest = JsonParser.parseString(Files.readString(addon.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(ResearchAddon.ID, manifest.getAsJsonObject("browser_specific_settings")
                .getAsJsonObject("gecko").get("id").getAsString());
        // No registered content script, no web-accessible resource: a visited page has nothing of the add-on to
        // observe. Page reads are injected on demand into the isolated content-script world, which needs the tab
        // URLs (tabs) and the host permission for every page (<all_urls>).
        assertFalse(manifest.has("content_scripts"));
        assertFalse(manifest.has("web_accessible_resources"));
        assertEquals("[\"http://127.0.0.1/*\",\"tabs\",\"<all_urls>\"]", manifest.get("permissions").toString());
        for (String file : ResearchAddon.FILES) {
            String text = Files.readString(addon.resolve(file));
            assertFalse(file + " never registers page scripts", text.contains("contentScripts.register")
                    || text.contains("scripting.executeScript") || text.contains("wrappedJSObject")
                    || text.contains("exportFunction"));
            if (!"background.js".equals(file)) {
                assertFalse(file + " does not inject", text.contains("tabs.executeScript"));
            }
        }
    }

    @Test
    public void pageReadsRunOnceInTheIsolatedWorldOfTheMatchingTab() throws Exception {
        Path addon = ResearchAddon.install(temporary.getRoot().toPath(), null);
        String background = Files.readString(addon.resolve("background.js"));
        // Exactly one injection point: the read loop, with AskAI's script as code and nothing for other frames.
        assertEquals(1, background.split("tabs\\.executeScript\\(", -1).length - 1);
        assertTrue(background.contains("code: '(' + read.script + ')()'"));
        assertFalse(background.contains("allFrames"));
        assertTrue(background.contains("tab.url === url"));
    }
}
