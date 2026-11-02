package com.aresstack.askai.plugin.host;

import com.aresstack.askai.state.ApplicationStateService;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

/**
 * A {@link WorkspaceStateStore} backed by the shared {@link ApplicationStateService}, namespaced by a key
 * prefix. Used for host-level chat/workspace preferences (e.g. the selected mode) in application-state.json.
 */
public final class ApplicationStateWorkspaceStateStore implements WorkspaceStateStore {

    private final ApplicationStateService state;
    private final String prefix;

    public ApplicationStateWorkspaceStateStore(ApplicationStateService state, String prefix) {
        this.state = state;
        this.prefix = prefix == null ? "" : prefix;
    }

    private String key(String key) {
        return prefix + key;
    }

    @Override
    public String get(String key, String defaultValue) {
        return state.get(key(key), defaultValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return state.getBoolean(key(key), defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String value = state.get(key(key), null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @Override
    public void put(String key, String value) {
        state.putAndSave(key(key), value);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        state.putAndSave(key(key), Boolean.toString(value));
    }

    @Override
    public void putInt(String key, int value) {
        state.putAndSave(key(key), Integer.toString(value));
    }
}
