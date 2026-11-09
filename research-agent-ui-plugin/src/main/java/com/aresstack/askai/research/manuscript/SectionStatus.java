package com.aresstack.askai.research.manuscript;

/** The status of one manuscript section (plan PR 26). */
public enum SectionStatus {
    /** Not written yet (a new outline id, or "Unskip"). */
    PENDING,
    /** Has a current revision. */
    WRITTEN,
    /** The last writer attempt failed; the reason is kept. */
    FAILED,
    /** "Skip section": not written and not assembled. */
    SKIPPED,
    /** Its outline entry, its chapter entry or a cited source changed since it was written (D4). */
    STALE,
    /** Its outline id was deleted; the text is kept and never assembled. */
    ORPHAN;

    public String label() {
        String name = name().toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
