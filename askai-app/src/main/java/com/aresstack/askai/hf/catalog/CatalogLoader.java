package com.aresstack.askai.hf.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the local filter catalogs from classpath resources under {@code /catalogs/}. The loader is
 * deliberately separated from the catalog source: today the data is baked into bundled resource
 * files (independently updatable by replacing a file), but a later remote refresh only has to
 * produce the same line format for this loader to consume it unchanged.
 *
 * <p>Line format: {@code id|Display Name} or {@code id|Display Name|Group}; blank lines and lines
 * starting with {@code #} are ignored; malformed lines (no id) are logged to stderr and skipped so
 * one bad line never aborts the whole catalog.</p>
 */
public final class CatalogLoader {

    private static volatile FilterCatalogs cached;

    private CatalogLoader() {
    }

    /** @return the catalogs, loaded once and cached (they are constant for a run). */
    public static FilterCatalogs load() {
        FilterCatalogs local = cached;
        if (local == null) {
            synchronized (CatalogLoader.class) {
                if (cached == null) {
                    cached = new FilterCatalogs(
                            read("/catalogs/tasks.txt"),
                            read("/catalogs/libraries.txt"),
                            read("/catalogs/languages.txt"),
                            read("/catalogs/licenses.txt"),
                            read("/catalogs/other.txt"),
                            read("/catalogs/apps.txt"));
                }
                local = cached;
            }
        }
        return local;
    }

    private static List<CatalogEntry> read(String resource) {
        List<CatalogEntry> entries = new ArrayList<CatalogEntry>();
        InputStream stream = CatalogLoader.class.getResourceAsStream(resource);
        if (stream == null) {
            System.err.println("Catalog resource not found on classpath: " + resource);
            return entries;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.charAt(0) == '#') {
                    continue;
                }
                String[] parts = trimmed.split("\\|", -1);
                String id = parts[0].trim();
                if (id.length() == 0) {
                    System.err.println("Skipping malformed catalog line " + resource + ":" + lineNumber + " -> " + line);
                    continue;
                }
                String display = parts.length > 1 ? parts[1].trim() : id;
                String group = parts.length > 2 ? parts[2].trim() : "";
                entries.add(new CatalogEntry(id, display, group));
            }
        } catch (IOException ex) {
            System.err.println("Failed to read catalog " + resource + ": " + ex.getMessage());
        } finally {
            closeQuietly(reader);
        }
        return entries;
    }

    private static void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
    }
}
