package com.aresstack.askai.service;

/**
 * UI descriptor for a future Ollama capability.
 */
public final class FeatureAction {

    private final String id;
    private final String title;
    private final String description;
    private final boolean available;

    public FeatureAction(String id, String title, String description) {
        this(id, title, description, true);
    }

    public FeatureAction(String id, String title, String description, boolean available) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.available = available;
    }

    public String getId() {
        return id;
    }

    /** @return whether this action actually runs today; {@code false} marks it experimental / not wired. */
    public boolean isAvailable() {
        return available;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}
