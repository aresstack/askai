package com.aresstack.askai.research.book;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The approved source set (plan PR 14, D4): written by the last record step of {@code APPROVE_EVIDENCE} under its
 * transition id. It names every accepted source of that moment with its content checksum and status, so the set the
 * outline is built from can be read again after a restart and later changes to the repository never alter it.
 */
public final class SourcesPin {

    public static final String PHASE = "sources";

    /** One source of the approved set. */
    public static final class Entry {
        private final String sourceId;
        private final String checksum;
        private final String status;

        public Entry(String sourceId, String checksum, String status) {
            if (sourceId == null || sourceId.isEmpty()) {
                throw new IllegalArgumentException("a sources pin entry needs a source id");
            }
            this.sourceId = sourceId;
            this.checksum = checksum == null ? "" : checksum;
            this.status = status == null ? "" : status;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getChecksum() {
            return checksum;
        }

        public String getStatus() {
            return status;
        }
    }

    private final String transitionId;
    private final long approvedAt;
    private final List<Entry> sources;

    public SourcesPin(String transitionId, long approvedAt, List<Entry> sources) {
        if (transitionId == null || transitionId.isEmpty()) {
            throw new IllegalArgumentException("a sources pin needs a transition id");
        }
        this.transitionId = transitionId;
        this.approvedAt = approvedAt;
        this.sources = Collections.unmodifiableList(new ArrayList<Entry>(
                sources == null ? Collections.<Entry>emptyList() : sources));
    }

    public String getTransitionId() {
        return transitionId;
    }

    public long getApprovedAt() {
        return approvedAt;
    }

    public List<Entry> getSources() {
        return sources;
    }

    public boolean contains(String sourceId) {
        for (Entry entry : sources) {
            if (entry.sourceId.equals(sourceId)) {
                return true;
            }
        }
        return false;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("phase", PHASE);
        json.addProperty("transitionId", transitionId);
        json.addProperty("approvedAt", approvedAt);
        JsonArray list = new JsonArray();
        for (Entry entry : sources) {
            JsonObject item = new JsonObject();
            item.addProperty("id", entry.sourceId);
            item.addProperty("checksum", entry.checksum);
            item.addProperty("status", entry.status);
            list.add(item);
        }
        json.add("sources", list);
        return json;
    }

    static SourcesPin fromJson(JsonObject json) {
        List<Entry> entries = new ArrayList<Entry>();
        JsonElement list = json.get("sources");
        if (list == null || !list.isJsonArray()) {
            throw new IllegalArgumentException("a sources pin without its source list");
        }
        for (JsonElement element : list.getAsJsonArray()) {
            JsonObject item = element.getAsJsonObject();
            entries.add(new Entry(BookManifest.string(item, "id"), BookManifest.string(item, "checksum"),
                    BookManifest.string(item, "status")));
        }
        return new SourcesPin(BookManifest.string(json, "transitionId"), json.get("approvedAt").getAsLong(),
                entries);
    }
}
