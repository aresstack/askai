package com.aresstack.askai.hf.convert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The allowlist of config.json {@code architectures} class names Ollama's safetensors import can
 * convert, loaded from the bundled, independently-updatable resource
 * {@code /catalogs/ollama-architectures.txt} (line format {@code id|Display Name}). Loaded once and
 * cached. Matching is case-insensitive.
 */
public final class ArchitectureCatalog {

    private static volatile Set<String> cached;

    private ArchitectureCatalog() {
    }

    /** @return the set of supported architecture ids (lower-cased), loaded once. */
    public static Set<String> supported() {
        Set<String> local = cached;
        if (local == null) {
            synchronized (ArchitectureCatalog.class) {
                if (cached == null) {
                    cached = load();
                }
                local = cached;
            }
        }
        return local;
    }

    /** @return true when any of the given architecture class names is Ollama-importable. */
    public static boolean isSupported(Iterable<String> architectures) {
        if (architectures == null) {
            return false;
        }
        Set<String> allowed = supported();
        for (String architecture : architectures) {
            if (architecture != null && allowed.contains(architecture.trim().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> load() {
        Set<String> ids = new LinkedHashSet<String>();
        InputStream stream = ArchitectureCatalog.class.getResourceAsStream("/catalogs/ollama-architectures.txt");
        if (stream == null) {
            System.err.println("Architecture catalog resource not found on classpath");
            return Collections.unmodifiableSet(ids);
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.charAt(0) == '#') {
                    continue;
                }
                int bar = trimmed.indexOf('|');
                String id = (bar >= 0 ? trimmed.substring(0, bar) : trimmed).trim();
                if (id.length() > 0) {
                    ids.add(id.toLowerCase(java.util.Locale.ROOT));
                }
            }
        } catch (IOException ex) {
            System.err.println("Failed to read architecture catalog: " + ex.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return Collections.unmodifiableSet(ids);
    }
}
