package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.drafting.EvidenceAssignments;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pipeline variant of the writer A/B: a name and the writer settings it changes against the app's defaults. The
 * targets come from {@link ResearchRuntimeSettings#loadWriterTargets}, the method the app reads its settings with,
 * over a store that holds only these values, so a variant is exactly a set of settings a user could make.
 *
 * <p>{@code -Daskai.benchmark.writerAb.variants=app,part-context} names the variants of a run; a name of the form
 * {@code name:key=value;key=value} defines one inline, and {@code -Daskai.benchmark.writerAb.variant.<name>=key=value;...}
 * defines or overrides one by name.</p>
 */
public final class WriterAbVariant {

    /** The app's defaults, unchanged. */
    public static final String APP = "app";

    private final String name;
    private final Map<String, String> settings;

    public WriterAbVariant(String name, Map<String, String> settings) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("a variant needs a name");
        }
        this.name = name.trim();
        this.settings = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                settings == null ? Collections.<String, String>emptyMap() : settings));
    }

    /** The built-in variants by name. */
    static Map<String, String> builtIn(String name) {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        if (APP.equals(name)) {
            return settings;
        }
        return null;
    }

    /**
     * The variants of a run.
     *
     * @param spec      comma-separated names or {@code name:key=value;key=value} entries; empty = {@link #APP}
     * @param overrides the {@code askai.benchmark.writerAb.variant.<name>} properties (name to {@code key=value;...})
     */
    public static List<WriterAbVariant> parse(String spec, Map<String, String> overrides) {
        List<WriterAbVariant> variants = new ArrayList<WriterAbVariant>();
        String all = spec == null || spec.trim().isEmpty() ? APP : spec;
        for (String entry : all.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            String name = colon < 0 ? trimmed : trimmed.substring(0, colon).trim();
            Map<String, String> settings = colon < 0 ? builtIn(name) : settings(trimmed.substring(colon + 1));
            String override = overrides == null ? null : overrides.get(name);
            if (override != null) {
                settings = settings(override);
            }
            if (settings == null) {
                throw new IllegalArgumentException("unknown writer variant '" + name + "'; define it as "
                        + name + ":key=value;key=value or with -Daskai.benchmark.writerAb.variant." + name);
            }
            variants.add(new WriterAbVariant(name, settings));
        }
        return Collections.unmodifiableList(variants);
    }

    static Map<String, String> settings(String spec) {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        for (String pair : spec.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                settings.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return settings;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getSettings() {
        return settings;
    }

    /**
     * The writer targets of this variant for an arm, read by the app's settings loader.
     *
     * @param wordsPerCall the arm's words per call; 0 keeps the setting (the app's default 450 unless the variant
     *                     sets {@code research.writer.wordsPerCall})
     */
    public EvidenceAssignments.Targets targets(int wordsPerCall) {
        MapStore store = new MapStore(settings);
        if (wordsPerCall > 0) {
            store.put(ResearchRuntimeSettings.KEY_WRITER_WORDS_PER_CALL, String.valueOf(wordsPerCall));
        }
        return ResearchRuntimeSettings.loadWriterTargets(store);
    }

    /** A settings store holding only the given values. */
    static final class MapStore implements WorkspaceStateStore {
        private final Map<String, String> values;

        MapStore(Map<String, String> values) {
            this.values = new LinkedHashMap<String, String>(values);
        }

        public String get(String key, String defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : value;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
        }

        public int getInt(String key, int defaultValue) {
            String value = values.get(key);
            try {
                return value == null ? defaultValue : Integer.parseInt(value.trim());
            } catch (NumberFormatException bad) {
                return defaultValue;
            }
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }
}
