package com.aresstack.askai.hf.catalog;

/**
 * Where a loaded {@link FilterCatalogs} came from, in preference order: fresh from the HuggingFace
 * API, the last successful on-disk cache, or the bundled offline resource snapshot.
 */
public enum CatalogOrigin {

    LIVE("Live"),
    CACHE("Cache"),
    FALLBACK("Fallback");

    private final String displayName;

    CatalogOrigin(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
