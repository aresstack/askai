package com.aresstack.askai.research.book;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Phase-1 record (D4): the concept revision approved at {@code SUBMIT_SCOPE}, the working revision it
 * froze (so node ids come from that revision's identity history) and copies of mission, deliverable,
 * working language and blacklist of that moment, because the live scope draft keeps no history.
 */
public final class ConceptPin {

    public static final String PHASE = "concept";

    private final String transitionId;
    private final int approvedRevision;
    private final boolean newRevision;
    private final String contentHash;
    private final long workingRevision;
    private final long approvedAt;
    private final String mission;
    private final String deliverable;
    private final String language;
    private final List<String> blacklist;

    public ConceptPin(String transitionId, int approvedRevision, boolean newRevision, String contentHash,
                      long workingRevision, long approvedAt, String mission, String deliverable,
                      String language, List<String> blacklist) {
        if (transitionId == null || transitionId.isEmpty() || approvedRevision <= 0
                || contentHash == null || contentHash.isEmpty()) {
            throw new IllegalArgumentException("a concept pin needs a transition id, a revision and a hash");
        }
        this.transitionId = transitionId;
        this.approvedRevision = approvedRevision;
        this.newRevision = newRevision;
        this.contentHash = contentHash;
        this.workingRevision = workingRevision;
        this.approvedAt = approvedAt;
        this.mission = mission == null ? "" : mission;
        this.deliverable = deliverable == null ? "" : deliverable;
        this.language = language == null ? "" : language;
        this.blacklist = Collections.unmodifiableList(new ArrayList<String>(
                blacklist == null ? Collections.<String>emptyList() : blacklist));
    }

    public String getTransitionId() {
        return transitionId;
    }

    public int getApprovedRevision() {
        return approvedRevision;
    }

    /** {@code false} when the submit found the concept unchanged and kept the previous revision. */
    public boolean isNewRevision() {
        return newRevision;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getWorkingRevision() {
        return workingRevision;
    }

    public long getApprovedAt() {
        return approvedAt;
    }

    public String getMission() {
        return mission;
    }

    public String getDeliverable() {
        return deliverable;
    }

    public String getLanguage() {
        return language;
    }

    public List<String> getBlacklist() {
        return blacklist;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("phase", PHASE);
        json.addProperty("transitionId", transitionId);
        json.addProperty("approvedRevision", approvedRevision);
        json.addProperty("newRevision", newRevision);
        json.addProperty("contentHash", contentHash);
        json.addProperty("workingRevision", workingRevision);
        json.addProperty("approvedAt", approvedAt);
        json.addProperty("mission", mission);
        json.addProperty("deliverable", deliverable);
        json.addProperty("language", language);
        JsonArray terms = new JsonArray();
        for (String term : blacklist) {
            terms.add(term);
        }
        json.add("blacklist", terms);
        return json;
    }

    static ConceptPin fromJson(JsonObject json) {
        List<String> terms = new ArrayList<String>();
        JsonElement array = json.get("blacklist");
        if (array != null && array.isJsonArray()) {
            for (JsonElement term : array.getAsJsonArray()) {
                terms.add(term.getAsString());
            }
        }
        return new ConceptPin(BookManifest.string(json, "transitionId"),
                json.get("approvedRevision").getAsInt(),
                !json.has("newRevision") || json.get("newRevision").getAsBoolean(),
                BookManifest.string(json, "contentHash"),
                json.get("workingRevision").getAsLong(),
                json.get("approvedAt").getAsLong(),
                BookManifest.string(json, "mission"),
                BookManifest.string(json, "deliverable"),
                BookManifest.string(json, "language"),
                terms);
    }
}
