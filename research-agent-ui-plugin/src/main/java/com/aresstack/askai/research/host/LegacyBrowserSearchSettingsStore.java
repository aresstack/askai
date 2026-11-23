package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistence of the GLOBAL legacy-browser-search settings in the host {@link WorkspaceStateStore},
 * using the canonical codec keys under one prefix. Only keys the user actually changed are stored —
 * everything else falls back to {@code LegacyBrowserSearchDefaults} (the single default origin), so
 * a later default improvement reaches users who never touched the field. Every save bumps the
 * monotonic revision and rewrites the search profile of every running session
 * ({@link RunningSearchProfiles}), so their next search uses the saved engine table without a restart.
 */
public final class LegacyBrowserSearchSettingsStore {

    /**
     * GLOBAL routing prefix: search settings are APP-WIDE. Without it the session-scoping store froze
     * the defaults into every chat at first read — the user disabled an engine and every session kept
     * searching with it, because each one had privately frozen "no override" long before.
     */
    static final String PREFIX = WorkspaceStateStore.GLOBAL_KEY_PREFIX + "research.legacy.search.";
    static final String KEY_REVISION = PREFIX + "revision";

    private LegacyBrowserSearchSettingsStore() {
    }

    /** The stored overrides (canonical codec keys, WITHOUT prefix). Missing keys = default applies. */
    public static Map<String, String> loadValues(WorkspaceStateStore store) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String key : LegacyBrowserSearchSettingsCodec
                .toValues(com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create())
                .keySet()) {
            // Overrides are stored as "v:<value>" so an override to the EMPTY string stays
            // distinguishable from "not overridden" (the store has no remove operation).
            String stored = store.get(PREFIX + key, "");
            if (stored.startsWith("v:")) {
                values.put(key, stored.substring(2));
            }
        }
        return values;
    }

    /**
     * Store the given full value set as overrides: values equal to the default are REMOVED (so they
     * keep following default improvements), differing values are written. Bumps the revision and
     * rewrites the running sessions' search profiles from the stored overrides — the same values a
     * session started now would read.
     */
    public static void saveValues(WorkspaceStateStore store, Map<String, String> values) {
        Map<String, String> defaults = LegacyBrowserSearchSettingsCodec
                .toValues(com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create());
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            String key = entry.getKey();
            String value = values.get(key);
            if (value == null || value.equals(entry.getValue())) {
                store.put(PREFIX + key, ""); // tombstone: not overridden, default applies
            } else {
                store.put(PREFIX + key, "v:" + value);
            }
        }
        store.putInt(KEY_REVISION, (int) (revision(store) + 1));
        RunningSearchProfiles.saved(loadValues(store), revision(store));
    }

    /** Monotonic settings revision (0 = never saved). */
    public static long revision(WorkspaceStateStore store) {
        return store.getInt(KEY_REVISION, 0);
    }
}
