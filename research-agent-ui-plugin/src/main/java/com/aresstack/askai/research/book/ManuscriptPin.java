package com.aresstack.askai.research.book;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The manuscript record (plan PR 26, D4): written by the last {@code beforeApply} step of {@code APPROVE_FINAL}
 * under its transition id. It names the approved outline revision and, in outline order, every section with its
 * status, current revision, text hash and the model label that wrote it. PR 29 adds the report and the cited
 * sources with their checksums.
 */
public final class ManuscriptPin {

    public static final String PHASE = "manuscript";

    /** One section of the pinned manuscript. */
    public static final class Entry {
        private final String sectionId;
        private final String status;
        private final int revision;
        private final String textHash;
        private final String modelLabel;

        public Entry(String sectionId, String status, int revision, String textHash, String modelLabel) {
            if (sectionId == null || sectionId.isEmpty()) {
                throw new IllegalArgumentException("a manuscript pin entry needs a section id");
            }
            this.sectionId = sectionId;
            this.status = status == null ? "" : status;
            this.revision = revision;
            this.textHash = textHash == null ? "" : textHash;
            this.modelLabel = modelLabel == null ? "" : modelLabel;
        }

        public String getSectionId() {
            return sectionId;
        }

        public String getStatus() {
            return status;
        }

        public int getRevision() {
            return revision;
        }

        public String getTextHash() {
            return textHash;
        }

        public String getModelLabel() {
            return modelLabel;
        }
    }

    private final String transitionId;
    private final int outlineRevision;
    private final long approvedAt;
    private final List<Entry> sections;
    private String bookStatus = "";
    private String qualityReport = "";
    private final java.util.Map<String, String> citedSources = new java.util.LinkedHashMap<String, String>();

    public ManuscriptPin(String transitionId, int outlineRevision, long approvedAt, List<Entry> sections) {
        if (transitionId == null || transitionId.isEmpty()) {
            throw new IllegalArgumentException("a manuscript pin needs a transition id");
        }
        this.transitionId = transitionId;
        this.outlineRevision = outlineRevision;
        this.approvedAt = approvedAt;
        this.sections = Collections.unmodifiableList(new ArrayList<Entry>(
                sections == null ? Collections.<Entry>emptyList() : sections));
    }

    public String getTransitionId() {
        return transitionId;
    }

    public int getOutlineRevision() {
        return outlineRevision;
    }

    public long getApprovedAt() {
        return approvedAt;
    }

    public List<Entry> getSections() {
        return sections;
    }

    /**
     * Plan PR 30: the same pin with the quality report of the final assembly and every cited source with the
     * checksum the book cites it by (reference order).
     */
    public ManuscriptPin withBook(String status, String report, java.util.Map<String, String> cited) {
        ManuscriptPin pin = new ManuscriptPin(transitionId, outlineRevision, approvedAt, sections);
        pin.bookStatus = status == null ? "" : status;
        pin.qualityReport = report == null ? "" : report;
        if (cited != null) {
            pin.citedSources.putAll(cited);
        }
        return pin;
    }

    public String getBookStatus() {
        return bookStatus;
    }

    public String getQualityReport() {
        return qualityReport;
    }

    /** Source id to checksum of every cited source, in reference order. */
    public java.util.Map<String, String> getCitedSources() {
        return Collections.unmodifiableMap(citedSources);
    }

    /** The distinct model labels of the pinned revisions, in first-use order. */
    public List<String> modelLabels() {
        List<String> labels = new ArrayList<String>();
        for (Entry entry : sections) {
            if (!entry.modelLabel.isEmpty() && !labels.contains(entry.modelLabel)) {
                labels.add(entry.modelLabel);
            }
        }
        return labels;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("phase", PHASE);
        json.addProperty("transitionId", transitionId);
        json.addProperty("outlineRevision", outlineRevision);
        json.addProperty("approvedAt", approvedAt);
        JsonArray array = new JsonArray();
        for (Entry entry : sections) {
            JsonObject e = new JsonObject();
            e.addProperty("sectionId", entry.sectionId);
            e.addProperty("status", entry.status);
            e.addProperty("revision", entry.revision);
            e.addProperty("textHash", entry.textHash);
            e.addProperty("modelLabel", entry.modelLabel);
            array.add(e);
        }
        json.add("sections", array);
        JsonArray labels = new JsonArray();
        for (String label : modelLabels()) {
            labels.add(label);
        }
        json.add("modelLabels", labels);
        json.addProperty("bookStatus", bookStatus);
        json.addProperty("qualityReport", qualityReport);
        JsonArray cited = new JsonArray();
        for (java.util.Map.Entry<String, String> source : citedSources.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("sourceId", source.getKey());
            c.addProperty("checksum", source.getValue());
            cited.add(c);
        }
        json.add("citedSources", cited);
        return json;
    }

    static ManuscriptPin fromJson(JsonObject json) {
        List<Entry> entries = new ArrayList<Entry>();
        for (JsonElement element : json.getAsJsonArray("sections")) {
            JsonObject e = element.getAsJsonObject();
            entries.add(new Entry(BookManifest.string(e, "sectionId"), BookManifest.string(e, "status"),
                    e.get("revision").getAsInt(), BookManifest.string(e, "textHash"),
                    BookManifest.string(e, "modelLabel")));
        }
        ManuscriptPin pin = new ManuscriptPin(BookManifest.string(json, "transitionId"),
                json.get("outlineRevision").getAsInt(), json.get("approvedAt").getAsLong(), entries);
        java.util.Map<String, String> cited = new java.util.LinkedHashMap<String, String>();
        if (json.has("citedSources")) {
            for (JsonElement element : json.getAsJsonArray("citedSources")) {
                JsonObject c = element.getAsJsonObject();
                cited.put(BookManifest.string(c, "sourceId"), BookManifest.string(c, "checksum"));
            }
        }
        return pin.withBook(json.has("bookStatus") ? BookManifest.string(json, "bookStatus") : "",
                json.has("qualityReport") ? BookManifest.string(json, "qualityReport") : "", cited);
    }
}
