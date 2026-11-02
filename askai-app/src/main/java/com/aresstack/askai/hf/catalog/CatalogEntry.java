package com.aresstack.askai.hf.catalog;

/**
 * One selectable filter value from a local catalog: the technical id sent to the HuggingFace API
 * ({@code id}), a human display name, and the group/category it belongs to (task category, or the
 * "Other" subgroup; empty for flat catalogs like libraries/languages).
 */
public final class CatalogEntry {

    private final String id;
    private final String displayName;
    private final String group;

    public CatalogEntry(String id, String displayName, String group) {
        this.id = id == null ? "" : id;
        this.displayName = displayName == null || displayName.length() == 0 ? this.id : displayName;
        this.group = group == null ? "" : group;
    }

    /** @return the technical filter value sent to the API (e.g. "text-generation", "license:mit"). */
    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** @return the category/subgroup this entry belongs to, or "" for flat catalogs. */
    public String getGroup() {
        return group;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
