package com.aresstack.askai.research.book;

import com.google.gson.JsonObject;

/**
 * The outline record (D4): the outline revision approved at {@code APPROVE_OUTLINE}, its content hash, the working
 * revision it froze and the approval time, appended under the transition id.
 */
public final class OutlinePin {

    public static final String PHASE = "outline";

    private final String transitionId;
    private final int approvedRevision;
    private final boolean newRevision;
    private final String contentHash;
    private final long workingRevision;
    private final long approvedAt;

    public OutlinePin(String transitionId, int approvedRevision, boolean newRevision, String contentHash,
                      long workingRevision, long approvedAt) {
        if (transitionId == null || transitionId.isEmpty() || approvedRevision <= 0
                || contentHash == null || contentHash.isEmpty()) {
            throw new IllegalArgumentException("an outline pin needs a transition id, a revision and a hash");
        }
        this.transitionId = transitionId;
        this.approvedRevision = approvedRevision;
        this.newRevision = newRevision;
        this.contentHash = contentHash;
        this.workingRevision = workingRevision;
        this.approvedAt = approvedAt;
    }

    public String getTransitionId() {
        return transitionId;
    }

    public int getApprovedRevision() {
        return approvedRevision;
    }

    /** {@code false} when the approval found the outline unchanged and kept the previous revision. */
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

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("phase", PHASE);
        json.addProperty("transitionId", transitionId);
        json.addProperty("approvedRevision", approvedRevision);
        json.addProperty("newRevision", newRevision);
        json.addProperty("contentHash", contentHash);
        json.addProperty("workingRevision", workingRevision);
        json.addProperty("approvedAt", approvedAt);
        return json;
    }

    static OutlinePin fromJson(JsonObject json) {
        return new OutlinePin(BookManifest.string(json, "transitionId"),
                json.get("approvedRevision").getAsInt(),
                !json.has("newRevision") || json.get("newRevision").getAsBoolean(),
                BookManifest.string(json, "contentHash"),
                json.get("workingRevision").getAsLong(),
                json.get("approvedAt").getAsLong());
    }
}
