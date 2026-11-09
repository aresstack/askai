package com.aresstack.askai.research.manuscript;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.store.FileManuscriptStore;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one way content reaches the manuscript (plan PR 26, D4): the write guard allows content writes (section
 * text, restore, skip and unskip) only while the workflow is in {@code draft/running}, so the manuscript is
 * read-only after {@code finish-document} until "Request changes". The stale and orphan marks of the transition
 * hooks are exempt ({@link #reconcileOutline}, {@link #markCitingOutside}). The listener is told after every
 * change so {@code document.md} and open views follow.
 */
public final class ManuscriptWorkspace {

    /** Answers why manuscript content cannot change now, or {@code null} while it can. */
    public interface WriteGuard {
        String refusal();
    }

    /** Told after every change of the stored manuscript. */
    public interface ChangeListener {
        void manuscriptChanged();
    }

    /** A content write the guard refused. */
    public static final class WriteRefusedException extends IllegalStateException {
        WriteRefusedException(String message) {
            super(message);
        }
    }

    private static final WriteGuard OPEN = new WriteGuard() {
        public String refusal() {
            return null;
        }
    };
    private static final ChangeListener NOBODY = new ChangeListener() {
        public void manuscriptChanged() {
        }
    };

    private final FileManuscriptStore store;
    private volatile WriteGuard guard = OPEN;
    private volatile ChangeListener listener = NOBODY;

    public ManuscriptWorkspace(FileManuscriptStore store) {
        this.store = store;
    }

    public FileManuscriptStore store() {
        return store;
    }

    public void setWriteGuard(WriteGuard guard) {
        this.guard = guard == null ? OPEN : guard;
    }

    public void setChangeListener(ChangeListener listener) {
        this.listener = listener == null ? NOBODY : listener;
    }

    /** {@code null} while the manuscript may change, otherwise the reason it may not. */
    public String writeRefusal() {
        return guard.refusal();
    }

    /**
     * Save a section revision based on {@code expectedRevision}.
     * @throws WriteRefusedException                          outside {@code draft/running}
     * @throws FileManuscriptStore.SectionConflictException   when the section moved on meanwhile
     */
    public FileManuscriptStore.Write save(String sectionId, String chapterId, int expectedRevision,
                                          SectionRevision.Builder content, long nowMillis) {
        requireWritable();
        FileManuscriptStore.Write write = store.save(sectionId, chapterId, expectedRevision, content, nowMillis);
        listener.manuscriptChanged();
        return write;
    }

    /** Restore an older revision as the newest one. */
    public FileManuscriptStore.Write restore(String sectionId, int number, int expectedRevision, long nowMillis) {
        requireWritable();
        FileManuscriptStore.Write write = store.restore(sectionId, number, expectedRevision, nowMillis);
        listener.manuscriptChanged();
        return write;
    }

    /** "Skip section": the section is neither written nor assembled. */
    public ManuscriptSection skip(String sectionId, String chapterId, long nowMillis) {
        requireWritable();
        ManuscriptSection state = store.mark(sectionId, chapterId, SectionStatus.SKIPPED, "Skipped by you",
                nowMillis);
        listener.manuscriptChanged();
        return state;
    }

    /** "Unskip": the section is pending again. */
    public ManuscriptSection unskip(String sectionId, long nowMillis) {
        requireWritable();
        ManuscriptSection state = store.section(sectionId);
        if (state == null || state.getStatus() != SectionStatus.SKIPPED) {
            return state;
        }
        ManuscriptSection next = store.mark(sectionId, null, SectionStatus.PENDING, "", nowMillis);
        listener.manuscriptChanged();
        return next;
    }

    /** A failed writer attempt (PR 28 uses it); a content write, so it follows the guard. */
    public ManuscriptSection markFailed(String sectionId, String chapterId, String reason, long nowMillis) {
        requireWritable();
        ManuscriptSection state = store.mark(sectionId, chapterId, SectionStatus.FAILED, reason, nowMillis);
        listener.manuscriptChanged();
        return state;
    }

    /**
     * The after-apply marks of {@code APPROVE_OUTLINE} (D4), exempt from the guard: a section whose own outline
     * entry or chapter entry changed since its current revision was written becomes stale, a new section id is
     * pending, a deleted id is kept as an orphan, and an orphan whose id came back takes its text again. Nothing
     * is rewritten. Idempotent: the same outline marks nothing new.
     * @return section id -> the reason of each new stale or orphan mark
     */
    public Map<String, String> reconcileOutline(Outline approved, int outlineRevision, long nowMillis) {
        Map<String, String> marked = new LinkedHashMap<String, String>();
        Map<String, ManuscriptSection> stored = new HashMap<String, ManuscriptSection>();
        for (ManuscriptSection section : store.sections()) {
            stored.put(section.getSectionId(), section);
        }
        String suffix = " (outline revision " + outlineRevision + ")";
        for (OutlineChapter chapter : approved.getChapters()) {
            String chapterPrint = OutlineFingerprint.of(chapter);
            for (OutlineSection section : chapter.getSections()) {
                String id = section.getId();
                if (!FileManuscriptStore.isValidSectionId(id)) {
                    continue;
                }
                ManuscriptSection state = stored.remove(id);
                if (state == null) {
                    store.mark(id, chapter.getId(), SectionStatus.PENDING, "", nowMillis);
                    continue;
                }
                SectionRevision current = state.hasText() ? store.revision(id, state.getRevision()) : null;
                SectionStatus status = state.getStatus();
                String reason = state.getReason();
                if (status == SectionStatus.ORPHAN) {
                    status = current == null ? SectionStatus.PENDING : SectionStatus.WRITTEN;
                    reason = "";
                }
                if (current != null && status != SectionStatus.SKIPPED) {
                    if (!current.getSectionFingerprint().equals(OutlineFingerprint.of(section))) {
                        status = SectionStatus.STALE;
                        reason = "Section \"" + section.getTitle() + "\" changed in the outline" + suffix;
                    } else if (!current.getChapterFingerprint().equals(chapterPrint)) {
                        status = SectionStatus.STALE;
                        reason = "Chapter \"" + chapter.getTitle() + "\" changed in the outline" + suffix;
                    }
                }
                if (status != state.getStatus() || !reason.equals(state.getReason())
                        || !chapter.getId().equals(state.getChapterId())) {
                    store.mark(id, chapter.getId(), status, reason, nowMillis);
                    if (status == SectionStatus.STALE && state.getStatus() != SectionStatus.STALE) {
                        marked.put(id, reason);
                    }
                }
            }
        }
        for (ManuscriptSection gone : stored.values()) {
            if (gone.getStatus() != SectionStatus.ORPHAN) {
                String reason = "Removed from the outline" + suffix;
                store.mark(gone.getSectionId(), null, SectionStatus.ORPHAN, reason, nowMillis);
                marked.put(gone.getSectionId(), reason);
            }
        }
        listener.manuscriptChanged();
        return marked;
    }

    /**
     * The after-apply marks of {@code APPROVE_EVIDENCE} (D4), exempt from the guard: a written section whose
     * current revision cites a source outside {@code approvedSourceIds} becomes stale.
     */
    public Map<String, String> markCitingOutside(Set<String> approvedSourceIds, long nowMillis) {
        Map<String, String> marked = new LinkedHashMap<String, String>();
        for (ManuscriptSection state : store.sections()) {
            if (!state.hasText() || state.getStatus() == SectionStatus.ORPHAN
                    || state.getStatus() == SectionStatus.SKIPPED || state.getStatus() == SectionStatus.STALE) {
                continue;
            }
            SectionRevision current = store.revision(state.getSectionId(), state.getRevision());
            for (ManuscriptCitation citation : current.getCitations()) {
                if (!approvedSourceIds.contains(citation.getSourceId())) {
                    String reason = "Cites source " + citation.getSourceId()
                            + ", which is no longer in the approved sources";
                    store.mark(state.getSectionId(), null, SectionStatus.STALE, reason, nowMillis);
                    marked.put(state.getSectionId(), reason);
                    break;
                }
            }
        }
        if (!marked.isEmpty()) {
            listener.manuscriptChanged();
        }
        return marked;
    }

    /** The current revisions of the sections in outline order, for {@code document.md} and the pin. */
    public List<ManuscriptSection> sections() {
        return store.sections();
    }

    private void requireWritable() {
        String refusal = guard.refusal();
        if (refusal != null) {
            throw new WriteRefusedException(refusal);
        }
    }
}
