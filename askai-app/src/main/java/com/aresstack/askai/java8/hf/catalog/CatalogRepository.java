package com.aresstack.askai.java8.hf.catalog;

import com.aresstack.askai.java8.hf.HuggingFaceClient;

import java.util.List;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the filter catalogs in the required source order: live HuggingFace data first, then the last
 * successfully cached live data, then the bundled offline resources. A successful live load is cached
 * to disk so the next offline start still has the full data.
 *
 * <p>The single source {@code /api/models-tags-by-type} feeds every group, so the origin is uniform
 * across groups; the returned {@link CatalogBundle} carries that origin plus the per-group counts and
 * a message describing any downgrade.</p>
 */
public final class CatalogRepository {

    private static final String MODELS_TAGS_URL = "https://huggingface.co/api/models-tags-by-type";
    /** The Apps facet is not in the tags API; its list is embedded in this server-rendered page. */
    private static final String MODELS_PAGE_URL = "https://huggingface.co/models";

    private final File cacheFile;

    public CatalogRepository() {
        this(defaultCacheFile());
    }

    public CatalogRepository(File cacheFile) {
        this.cacheFile = cacheFile;
    }

    /**
     * @param client    the configured HuggingFace client (proxy/trust/token) for the live fetch
     * @param forceLive currently informational — the order is always Live → Cache → Fallback; kept so
     *                  a caller can express "the user asked to refresh" for logging
     * @return the best available catalogs with their origin
     */
    public CatalogBundle load(HuggingFaceClient client, boolean forceLive) {
        // Apps live on a separate source (the /models page); resolve them independently so a
        // scrape failure downgrades only the Apps group, not the tag-based catalogs, and vice-versa.
        List<CatalogEntry> apps = client != null ? loadAppsLive(client) : loadAppsOffline();

        String liveError = "";
        if (client != null) {
            try {
                String json = client.fetchText(MODELS_TAGS_URL);
                FilterCatalogs catalogs = LiveCatalogParser.parse(json);
                if (!catalogs.getTasks().isEmpty() && !catalogs.getLibraries().isEmpty()) {
                    writeCache(json);
                    return new CatalogBundle(catalogs.withApps(apps), CatalogOrigin.LIVE, "");
                }
                liveError = "Live-Daten leer";
            } catch (Exception ex) {
                liveError = describe(ex);
            }
        } else {
            liveError = "kein HTTP-Client";
        }

        // Live failed -> last successful cache.
        String cached = readCache();
        if (cached != null) {
            try {
                FilterCatalogs catalogs = LiveCatalogParser.parse(cached);
                if (!catalogs.getTasks().isEmpty()) {
                    return new CatalogBundle(catalogs.withApps(apps), CatalogOrigin.CACHE,
                            "Live nicht erreichbar (" + liveError + ") — zwischengespeicherte Daten.");
                }
            } catch (Exception ignored) {
                // fall through to bundled fallback
            }
        }

        // No usable cache -> bundled offline snapshot.
        return new CatalogBundle(CatalogLoader.load().withApps(apps), CatalogOrigin.FALLBACK,
                "Live und Cache nicht verfügbar (" + liveError + ") — gebündelte Daten.");
    }

    /** @return the newest offline-available bundle without any network attempt (cache, else bundled). */
    public CatalogBundle loadOffline() {
        List<CatalogEntry> apps = loadAppsOffline();
        String cached = readCache();
        if (cached != null) {
            try {
                FilterCatalogs catalogs = LiveCatalogParser.parse(cached);
                if (!catalogs.getTasks().isEmpty()) {
                    return new CatalogBundle(catalogs.withApps(apps), CatalogOrigin.CACHE, "zwischengespeicherte Daten");
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return new CatalogBundle(CatalogLoader.load().withApps(apps), CatalogOrigin.FALLBACK, "gebündelte Daten");
    }

    /**
     * Resolves the Apps facet, live first: scrape the {@code /models} page, and on success cache the
     * extracted list. An empty scrape or any error downgrades to the last cached apps, then the bundled
     * snapshot — the app list changes rarely, so a stale fallback is fine.
     */
    private List<CatalogEntry> loadAppsLive(HuggingFaceClient client) {
        try {
            List<CatalogEntry> apps = LiveAppCatalogParser.fromModelsPage(client.fetchText(MODELS_PAGE_URL));
            if (!apps.isEmpty()) {
                writeAppsCache(LiveAppCatalogParser.toJsonArray(apps));
                return apps;
            }
        } catch (Exception ignored) {
            // fall through to cache / bundled
        }
        return loadAppsOffline();
    }

    /** @return the cached apps if present and non-empty, else the bundled apps.txt snapshot. */
    private List<CatalogEntry> loadAppsOffline() {
        String cached = readText(appsCacheFile());
        if (cached != null) {
            List<CatalogEntry> apps = LiveAppCatalogParser.fromJsonArray(cached);
            if (!apps.isEmpty()) {
                return apps;
            }
        }
        return CatalogLoader.load().getApps();
    }

    private File appsCacheFile() {
        return new File(cacheFile.getParentFile(), "apps.json");
    }

    private void writeAppsCache(String json) {
        writeFile(appsCacheFile(), json);
    }

    private void writeCache(String json) {
        writeFile(cacheFile, json);
    }

    private String readCache() {
        return readText(cacheFile);
    }

    private static void writeFile(File file, String content) {
        File directory = file.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A failed cache write is non-fatal; the live data is still returned.
        } finally {
            closeQuietly(out);
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            StringBuilder builder = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return builder.toString();
        } catch (IOException ex) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static File defaultCacheFile() {
        File base = com.aresstack.askai.java8.settings.AskAiPaths.appDirectory().toFile();
        return new File(new File(base, "catalog-cache"), "models-tags-by-type.json");
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage();
        return message != null && message.trim().length() > 0 ? message : ex.getClass().getSimpleName();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
