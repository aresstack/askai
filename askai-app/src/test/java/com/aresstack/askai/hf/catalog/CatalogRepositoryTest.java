package com.aresstack.askai.hf.catalog;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Live-catalog-fix regression (offline paths only): the bundled fallback loads completely. */
public class CatalogRepositoryTest {

    @Test
    public void fallbackLoadsCompleteBundledSnapshot() {
        // No cache file + no client -> FALLBACK to the bundled resources.
        File noCache = new File("build/tmp/catalog-test-none/models-tags-by-type.json");
        CatalogBundle bundle = new CatalogRepository(noCache).load(null, false);
        assertEquals(CatalogOrigin.FALLBACK, bundle.getOrigin());
        FilterCatalogs catalogs = bundle.getCatalogs();
        // The bundled fallback is a complete snapshot, not the old curated subset.
        assertEquals(52, catalogs.getTasks().size());
        assertEquals(53, catalogs.getLibraries().size());
        assertEquals(4973, catalogs.getLanguages().size());
        assertEquals(82, catalogs.getLicenses().size());
        assertEquals(10, catalogs.getOther().size());
        // Task categories come from the local subType map.
        assertTrue(catalogs.getTasksByCategory().containsKey("Natural Language Processing"));
        assertTrue(catalogs.getTasksByCategory().containsKey("Multimodal"));
    }

    @Test
    public void loadOfflineNeverHitsNetwork() {
        File noCache = new File("build/tmp/catalog-test-none2/models-tags-by-type.json");
        CatalogBundle bundle = new CatalogRepository(noCache).loadOffline();
        assertEquals(CatalogOrigin.FALLBACK, bundle.getOrigin());
        assertTrue(bundle.getCatalogs().getTasks().size() > 0);
    }
}
