package com.aresstack.askai.browser.search.engine;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The engine table of Search → Engines as stored: priorities, languages, duplicates and the migration. */
public class BrowserSearchEngineTableTest {

    private static BrowserSearchEngineSelection.Entry row(String id, int priority, String... languages) {
        return new BrowserSearchEngineSelection.Entry(id, true, 3, 0, priority, Arrays.asList(languages), false,
                Collections.<String>emptyList());
    }

    private static List<String> ids(BrowserSearchEngineSelection selection) {
        List<String> ids = new java.util.ArrayList<String>();
        for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
            ids.add(entry.getEngineId() + "@" + entry.getPriority());
        }
        return ids;
    }

    @Test
    public void rowsAreSortedByPriorityThenNameThenGivenOrder() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                row("google", 1, "de"), row("startpage", 0, "de"), row("duckduckgo", 0, "en"),
                row("google", 3, "fr", "es"), row("bing", 1, "de")), null);
        assertEquals(Arrays.asList("duckduckgo@0", "startpage@0", "bing@1", "google@1", "google@3"),
                ids(selection));
    }

    @Test
    public void theSameEngineMayHaveSeveralRowsAndIsVisitedOnce() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                row("google", 1, "de"), row("google", 3, "fr", "es")), null);
        assertEquals(2, selection.getEntries().size());
        assertEquals(1, selection.resolvedEnabledEngines().size());
        BrowserSearchEngineSelection restored = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries(selection.encodeEntries()), null);
        assertEquals(Arrays.asList("de"), restored.getEntries().get(0).getLanguages());
        assertEquals(Arrays.asList("fr", "es"), restored.getEntries().get(1).getLanguages());
    }

    @Test
    public void everyColumnSurvivesTheFlatForm() {
        BrowserSearchEngineSelection.Entry google = new BrowserSearchEngineSelection.Entry("google", true, 1, 15_000,
                2, Arrays.asList("de", "zh_cn"), true, Arrays.asList("pl", "UK", "pl", "polish"));
        String encoded = new BrowserSearchEngineSelection(Collections.singletonList(google), null).encodeEntries();
        assertEquals("google:on:1:15:2:de+zh-CN:on:pl+uk", encoded);
        BrowserSearchEngineSelection.Entry back = BrowserSearchEngineSelection.parseEntries(encoded).get(0);
        assertEquals(1, back.getResultPages());
        assertEquals(15_000, back.getDelayMillis());
        assertEquals(2, back.getPriority());
        assertEquals(Arrays.asList("de", "zh-CN"), back.getLanguages());
        assertTrue(back.isFurtherLanguagesEnabled());
        assertEquals(Arrays.asList("pl", "uk"), back.getFurtherLanguages());
        assertEquals(Arrays.asList("de", "zh-CN", "pl", "uk"), back.effectiveLanguages());
    }

    @Test
    public void switchedOffFurtherLanguagesAreKeptButNotSearched() {
        BrowserSearchEngineSelection.Entry entry = new BrowserSearchEngineSelection.Entry("yandex", true, 3, 0, 4,
                Arrays.asList("ru"), false, Arrays.asList("uk", "be"));
        assertEquals(Arrays.asList("uk", "be"), entry.getFurtherLanguages());
        assertEquals(Arrays.asList("ru"), entry.effectiveLanguages());
    }

    @Test
    public void anOldListKeepsItsOrderAsPrioritiesAndGetsTheCatalogLanguages() {
        String migrated = BrowserSearchEngineSelection.migrate("google:on:1:15,duckduckgo:on:3,bing:off:3",
                EngineAcquisitionMode.FIRST_USABLE);
        assertEquals("google:on:1:15:0:de+en+fr+es+it:off:,duckduckgo:on:3:0:1:de+en:off:,"
                + "bing:off:3:0:2:de+en+fr+es:off:", migrated);
        assertEquals("migrating twice changes nothing", migrated,
                BrowserSearchEngineSelection.migrate(migrated, EngineAcquisitionMode.FIRST_USABLE));
    }

    @Test
    public void anOldAllEnabledListBecomesOneGroup() {
        String migrated = BrowserSearchEngineSelection.migrate("duckduckgo:on:3,bing:on:3:1.5",
                EngineAcquisitionMode.ALL_ENABLED);
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(
                BrowserSearchEngineSelection.parseEntries(migrated), EngineAcquisitionMode.ALL_ENABLED);
        assertEquals(Arrays.asList("bing@0", "duckduckgo@0"), ids(selection));
        assertEquals(1500, selection.delayMillisFor("bing"));
    }

    @Test
    public void theCodecMigratesAStoredOldListWithTheStoredStrategy() {
        Map<String, String> stored = new HashMap<String, String>();
        stored.put("navigation.engines", "duckduckgo:off:3,bing:off:3,google:on:3");
        stored.put("navigation.engineAcquisitionMode", "FIRST_USABLE");
        Map<String, String> migrated = LegacyBrowserSearchSettingsCodec.migrateEngineList(stored);
        assertEquals("duckduckgo:off:3:0:0:de+en:off:,bing:off:3:0:1:de+en+fr+es:off:,"
                + "google:on:3:0:2:de+en+fr+es+it:off:", migrated.get("navigation.engines"));
        BrowserSearchEngineSelection decoded = LegacyBrowserSearchSettingsCodec.fromValues(stored)
                .settings.navigation.engineSelection;
        assertEquals(Arrays.asList("duckduckgo@0", "bing@1", "google@2"), ids(decoded));
    }

    @Test
    public void aRowOfAnEngineWithoutDriverIsKeptButNeverVisited() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                row("startpage", 0, "de"), row("duckduckgo", 1, "de")), null);
        assertEquals(2, selection.getEntries().size());
        assertEquals(1, selection.resolvedEnabledEngines().size());
        assertEquals("duckduckgo", selection.resolvedEnabledEngines().get(0).getId());
        assertFalse(SearchEngineCatalog.byId("startpage").isSearchable());
    }

    @Test
    public void theFirstRowOfAnEngineSuppliesItsPagesAndDelay() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Arrays.asList(
                new BrowserSearchEngineSelection.Entry("google", true, 2, 4000, 3, Arrays.asList("fr"), false,
                        Collections.<String>emptyList()),
                new BrowserSearchEngineSelection.Entry("google", true, 1, 15_000, 0, Arrays.asList("de"), false,
                        Collections.<String>emptyList())), null);
        assertEquals(15_000, selection.delayMillisFor("google"));
        assertEquals(1, selection.resultPagesFor("google"));
    }
}
