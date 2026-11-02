package com.aresstack.askai.ui;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelCatalog;
import com.aresstack.askai.localmodels.NlpModelCatalogEntry;
import com.aresstack.askai.localmodels.NlpModelCatalogProvider;
import com.aresstack.askai.localmodels.NlpModelInstaller;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The NLP tab: rows per language, installed state, one-click install off-EDT, refresh on success, error shown. */
public class NlpModelsPanelTest {

    private static final Executor DIRECT = new Executor() {
        public void execute(Runnable command) {
            command.run();
        }
    };

    private static NlpModelCatalogEntry entry(String id, String lang) {
        return new NlpModelCatalogEntry(id, NlpCapability.SENTENCE_DETECTION, lang, "opennlp", "1.5", "1.9.4",
                "https://curated/" + lang + "-sent.bin/download", lang + "-sent.bin", "sha", 1L);
    }

    private static final class FakeCatalog implements NlpModelCatalogProvider {
        public List<NlpModelCatalogEntry> availableModels() {
            return Arrays.asList(entry("apache/de", "de"), entry("apache/en", "en"));
        }
    }

    private static final class FakeInstalled implements NlpModelCatalog {
        final Set<String> ids = new LinkedHashSet<String>();

        public List<String> listInstalledModels(NlpCapability capability, String languageCode) {
            return new ArrayList<String>(ids);
        }
    }

    @Test
    public void showsARowPerLanguageWithInstalledState() {
        FakeInstalled installed = new FakeInstalled();
        installed.ids.add("apache/de");
        NlpModelsPanel panel = new NlpModelsPanel(new FakeCatalog(), installed,
                entry -> NlpModelInstaller.Outcome.INSTALLED, null, DIRECT, DIRECT);

        assertTrue(panel.modelIds().contains("apache/de"));
        assertTrue(panel.modelIds().contains("apache/en"));
        assertEquals("Installed", panel.statusText("apache/de"));
        assertFalse("installed model has no install button", panel.installButton("apache/de").isVisible());
        assertEquals("Not installed", panel.statusText("apache/en"));
        assertTrue(panel.installButton("apache/en").isVisible());
    }

    @Test
    public void installClicksTheInstallerExactlyOnceAndRefreshesOnSuccess() {
        final FakeInstalled installed = new FakeInstalled();
        final AtomicInteger installs = new AtomicInteger();
        final AtomicInteger refreshes = new AtomicInteger();
        NlpModelsPanel.InstallAction action = new NlpModelsPanel.InstallAction() {
            public NlpModelInstaller.Outcome install(NlpModelCatalogEntry entry) {
                installs.incrementAndGet();
                installed.ids.add(entry.getModelId()); // the install makes it installed
                return NlpModelInstaller.Outcome.INSTALLED;
            }
        };
        NlpModelsPanel panel = new NlpModelsPanel(new FakeCatalog(), installed, action,
                new Runnable() { public void run() { refreshes.incrementAndGet(); } }, DIRECT, DIRECT);

        panel.installButton("apache/en").doClick();

        assertEquals("installer invoked exactly once", 1, installs.get());
        assertEquals("global UI refresh triggered", 1, refreshes.get());
        assertEquals("row now shows installed", "Installed", panel.statusText("apache/en"));
        assertFalse(panel.installButton("apache/en").isVisible());
    }

    @Test
    public void anInstallErrorIsShownAndTheButtonStaysAvailable() {
        FakeInstalled installed = new FakeInstalled();
        NlpModelsPanel.InstallAction failing = new NlpModelsPanel.InstallAction() {
            public NlpModelInstaller.Outcome install(NlpModelCatalogEntry entry) throws Exception {
                throw new java.io.IOException("SHA-256 mismatch");
            }
        };
        NlpModelsPanel panel = new NlpModelsPanel(new FakeCatalog(), installed, failing, null, DIRECT, DIRECT);

        panel.installButton("apache/de").doClick();

        assertTrue("the hash error is surfaced, not swallowed",
                panel.errorText().contains("Install failed") && panel.errorText().contains("SHA-256"));
        assertTrue("the button stays available to retry", panel.installButton("apache/de").isVisible());
        assertTrue(panel.installButton("apache/de").isEnabled());
        assertEquals("Not installed", panel.statusText("apache/de"));
    }
}
