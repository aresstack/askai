package com.aresstack.askai.browser.search;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngine;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The engine list is a user decision that has to survive a restart — order included. An order that is
 * only honoured while the app runs is not an order, it is a suggestion.
 */
public class BrowserSearchEngineSelectionTest {

    private static BrowserSearchEngineSelection selection(EngineAcquisitionMode mode, String... ids) {
        List<BrowserSearchEngineSelection.Entry> entries =
                new java.util.ArrayList<BrowserSearchEngineSelection.Entry>();
        for (String id : ids) {
            boolean off = id.endsWith("!");
            entries.add(new BrowserSearchEngineSelection.Entry(off ? id.substring(0, id.length() - 1) : id,
                    !off));
        }
        return new BrowserSearchEngineSelection(entries, mode);
    }

    @Test
    public void theArrangedOrderIsWhatIsStoredAndReadBack() {
        BrowserSearchEngineSelection moved = selection(EngineAcquisitionMode.FIRST_USABLE,
                BrowserSearchEngineCatalog.BING, BrowserSearchEngineCatalog.DUCKDUCKGO);

        List<BrowserSearchEngineSelection.Entry> restored =
                BrowserSearchEngineSelection.parseEntries(moved.encodeEntries());

        assertEquals(2, restored.size());
        assertEquals(BrowserSearchEngineCatalog.BING, restored.get(0).getEngineId());
        assertEquals(BrowserSearchEngineCatalog.DUCKDUCKGO, restored.get(1).getEngineId());
        assertTrue(restored.get(0).isEnabled());
    }

    @Test
    public void aDisabledEngineIsNotVisitedAndSaysSoAcrossARestart() {
        BrowserSearchEngineSelection stored = selection(EngineAcquisitionMode.FIRST_USABLE,
                BrowserSearchEngineCatalog.DUCKDUCKGO, BrowserSearchEngineCatalog.BING + "!");

        BrowserSearchEngineSelection restored = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries(stored.encodeEntries()),
                EngineAcquisitionMode.FIRST_USABLE);

        List<BrowserSearchEngine> visited = restored.resolvedEnabledEngines();
        assertEquals(1, visited.size());
        assertEquals(BrowserSearchEngineCatalog.DUCKDUCKGO, visited.get(0).getId());
    }

    /** An id from a newer build is skipped, not fatal — but it must not empty the whole list either. */
    @Test
    public void anUnknownEngineIdIsSkippedRatherThanBreakingTheSearch() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries("mystery:on,duckduckgo:on"),
                EngineAcquisitionMode.FIRST_USABLE);

        List<BrowserSearchEngine> visited = selection.resolvedEnabledEngines();
        assertEquals(1, visited.size());
        assertEquals(BrowserSearchEngineCatalog.DUCKDUCKGO, visited.get(0).getId());
    }

    /** The order also survives the settings codec — the layer that actually writes it to disk. */
    @Test
    public void theOrderSurvivesTheSettingsCodec() {
        LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
        LegacySearchNavigationSettings navigation = new LegacySearchNavigationSettings(
                selection(EngineAcquisitionMode.ALL_ENABLED, BrowserSearchEngineCatalog.BING,
                        BrowserSearchEngineCatalog.DUCKDUCKGO),
                defaults.navigation.maximumEngineAttempts,
                defaults.navigation.navigationCommitTimeoutMillis,
                defaults.navigation.redirectResolutionEnabled,
                defaults.navigation.maximumRedirectUrlLength,
                defaults.navigation.searchResultLimit,
                defaults.navigation.language, defaults.navigation.country);
        LegacyBrowserSearchSettings reordered = new LegacyBrowserSearchSettings(navigation,
                defaults.consent, defaults.captcha, defaults.readiness, defaults.analysis,
                defaults.visualAnalysis, defaults.extraction, defaults.aiLayoutResolver,
                defaults.reranker, defaults.diagnostics, defaults.layoutRepair);

        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(reordered);
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(values);

        assertTrue(decoded.violations.toString(), decoded.violations.isEmpty());
        assertEquals(EngineAcquisitionMode.ALL_ENABLED,
                decoded.settings.navigation.engineSelection.getMode());
        assertEquals(Arrays.asList(BrowserSearchEngineCatalog.BING,
                        BrowserSearchEngineCatalog.DUCKDUCKGO),
                idsOf(decoded.settings.navigation.engineSelection));
    }

    /**
     * The shipped default is Angelo's product decision (issue #22): a fresh installation searches with Bing alone;
     * DuckDuckGo and Google are listed but switched off.
     */
    @Test
    public void theDefaultSearchesWithBingAlone() {
        BrowserSearchEngineSelection shipped =
                LegacyBrowserSearchDefaults.create().navigation.engineSelection;

        assertEquals(Arrays.asList(BrowserSearchEngineCatalog.BING), idsOf(shipped));
        List<String> listed = new java.util.ArrayList<String>();
        for (BrowserSearchEngineSelection.Entry entry : shipped.getEntries()) {
            listed.add(entry.getEngineId() + (entry.isEnabled() ? ":on" : ":off"));
        }
        assertEquals("DuckDuckGo and Google stay available, switched off",
                Arrays.asList(BrowserSearchEngineCatalog.BING + ":on", BrowserSearchEngineCatalog.DUCKDUCKGO + ":off",
                        BrowserSearchEngineCatalog.GOOGLE + ":off"), listed);
    }

    /**
     * Switching the other two on is all it takes to search with all three at once: the default strategy is
     * ALL_ENABLED (each engine in a tab of its own, typed into at the same time) and the endpoint budget reaches all
     * three start pages.
     */
    @Test
    public void switchingTheOtherEnginesOnAsksAllThreeAtOnce() {
        LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();
        List<BrowserSearchEngineSelection.Entry> allOn = new java.util.ArrayList<BrowserSearchEngineSelection.Entry>();
        for (BrowserSearchEngineSelection.Entry entry : defaults.navigation.engineSelection.getEntries()) {
            allOn.add(new BrowserSearchEngineSelection.Entry(entry.getEngineId(), true, entry.getResultPages(),
                    entry.getDelayMillis()));
        }
        BrowserSearchEngineSelection switchedOn =
                new BrowserSearchEngineSelection(allOn, defaults.navigation.engineSelection.getMode());

        assertEquals(EngineAcquisitionMode.ALL_ENABLED, switchedOn.getMode());
        assertEquals(Arrays.asList(BrowserSearchEngineCatalog.BING, BrowserSearchEngineCatalog.DUCKDUCKGO,
                BrowserSearchEngineCatalog.GOOGLE), idsOf(switchedOn));
        assertTrue("the endpoint budget reaches every start page",
                defaults.navigation.maximumEngineAttempts >= 3);
    }

    private static List<String> idsOf(BrowserSearchEngineSelection selection) {
        List<String> ids = new java.util.ArrayList<String>();
        for (BrowserSearchEngine engine : selection.resolvedEnabledEngines()) {
            ids.add(engine.getId());
        }
        return ids;
    }
}
