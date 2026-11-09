package com.aresstack.askai.research.manuscript;

/**
 * The stored state of one manuscript section (plan PR 26): its outline section id, its chapter id, the status
 * with its reason ("" when none), the number of its current revision (0 before the first) and the time of the last
 * change.
 */
public final class ManuscriptSection {

    private final String sectionId;
    private final String chapterId;
    private final SectionStatus status;
    private final String reason;
    private final int revision;
    private final long updatedAt;

    public ManuscriptSection(String sectionId, String chapterId, SectionStatus status, String reason, int revision,
                             long updatedAt) {
        if (sectionId == null || sectionId.trim().isEmpty() || status == null) {
            throw new IllegalArgumentException("a manuscript section needs an id and a status");
        }
        this.sectionId = sectionId.trim();
        this.chapterId = chapterId == null ? "" : chapterId.trim();
        this.status = status;
        this.reason = reason == null ? "" : reason.trim();
        this.revision = revision;
        this.updatedAt = updatedAt;
    }

    public String getSectionId() {
        return sectionId;
    }

    public String getChapterId() {
        return chapterId;
    }

    public SectionStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    /** The current revision number; 0 while the section has no text. */
    public int getRevision() {
        return revision;
    }

    public boolean hasText() {
        return revision > 0;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public ManuscriptSection with(SectionStatus newStatus, String newReason, int newRevision, String newChapterId,
                                  long now) {
        return new ManuscriptSection(sectionId, newChapterId == null ? chapterId : newChapterId, newStatus,
                newReason, newRevision, now);
    }
}
