package com.aresstack.askai.research.outline;

import com.aresstack.askai.research.store.FileOutlineStore;

import java.util.Map;

/**
 * The one way content reaches the outline store (D4): the write guard allows content writes only while the
 * workflow is in {@code outline/running}, whichever edge led there, and the listener re-renders whatever depends
 * on the stored outline (the {@code outline} artifact, open views). The after-apply stale marks are exempt from
 * the guard ({@link #markChaptersStale}).
 */
public final class OutlineWorkspace {

    /** Answers why outline content cannot change now, or {@code null} while it can. */
    public interface WriteGuard {
        String refusal();
    }

    /** Told after every change of the stored outline. */
    public interface ChangeListener {
        void outlineChanged(Outline outline, long workingRevision);
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
        public void outlineChanged(Outline outline, long workingRevision) {
        }
    };

    private final FileOutlineStore store;
    private volatile WriteGuard guard = OPEN;
    private volatile ChangeListener listener = NOBODY;

    public OutlineWorkspace(FileOutlineStore store) {
        this.store = store;
    }

    public FileOutlineStore store() {
        return store;
    }

    public void setWriteGuard(WriteGuard guard) {
        this.guard = guard == null ? OPEN : guard;
    }

    public void setChangeListener(ChangeListener listener) {
        this.listener = listener == null ? NOBODY : listener;
    }

    /** {@code null} while the outline may change, otherwise the reason it may not. */
    public String writeRefusal() {
        return guard.refusal();
    }

    public Outline current() {
        return store.working();
    }

    public long workingRevision() {
        return store.workingRevision();
    }

    /**
     * Save a content change based on {@code expectedRevision}.
     * @throws WriteRefusedException                       outside {@code outline/running}
     * @throws FileOutlineStore.RevisionConflictException  when the outline moved on meanwhile
     * @throws IllegalArgumentException                    for unknown or duplicate ids or an empty title
     */
    public FileOutlineStore.Commit save(Outline draft, long expectedRevision, long nowMillis) {
        String refusal = guard.refusal();
        if (refusal != null) {
            throw new WriteRefusedException(refusal);
        }
        FileOutlineStore.Commit commit = store.commit(draft, expectedRevision, nowMillis);
        if (commit.changed) {
            listener.outlineChanged(commit.outline, commit.revision);
        }
        return commit;
    }

    /**
     * Save an editor draft (plan PR 23, tree gestures and raw JSON alike): duplicate ids and empty titles are
     * refused with a reason, known ids keep their provenance, edited and new entries get the user-edited flag.
     */
    public FileOutlineStore.Commit saveEdits(Outline draft, long expectedRevision, long nowMillis) {
        String refusal = guard.refusal();
        if (refusal != null) {
            throw new WriteRefusedException(refusal);
        }
        OutlineEdits.validate(draft);
        return save(OutlineEdits.markEdits(draft, store.working()), expectedRevision, nowMillis);
    }

    /** Make working revision {@code revision} the head again (the editor's restore); guarded like a save. */
    public FileOutlineStore.Commit restore(long revision, long expectedRevision, long nowMillis) {
        String refusal = guard.refusal();
        if (refusal != null) {
            throw new WriteRefusedException(refusal);
        }
        FileOutlineStore.Commit commit = store.restore(revision, expectedRevision, nowMillis);
        if (commit.changed) {
            listener.outlineChanged(commit.outline, commit.revision);
        }
        return commit;
    }

    /** The after-apply stale marks (chapter id -> reason); exempt from the write guard. */
    public FileOutlineStore.Commit markChaptersStale(Map<String, String> reasons, long nowMillis) {
        FileOutlineStore.Commit commit = store.markChaptersStale(reasons, nowMillis);
        if (commit.changed) {
            listener.outlineChanged(commit.outline, commit.revision);
        }
        return commit;
    }
}
