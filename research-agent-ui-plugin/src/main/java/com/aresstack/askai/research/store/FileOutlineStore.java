package com.aresstack.askai.research.store;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineJson;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

/**
 * File-backed persistence of the user-owned outline (plan PR 21, D6), on the {@link FileConceptStore} V3 pattern:
 * <pre>
 *   working-history/N.json                   every committed working revision, immutable
 *   working.json                             the published working copy (a convenience copy)
 *   working.properties (+ .prev backup)      the manifest: workingRevision, contentHash, updatedAt
 *   revisions/0001.json / 0001.properties    approved revisions, immutable
 * </pre>
 * Write order: history first, working copy second, manifest backup third, atomic manifest swap last, so only the
 * swap makes a revision exist. The working truth is the history file the manifest names, checked against its
 * hash; a damaged store fails closed ({@link CorruptOutlineException}) and is never overwritten. Every file is
 * UTF-8. Content writes name the working revision they were based on ({@link #commit}); ids are store-owned and
 * live inside the records ({@link Outline#placedAgainst}).
 * <p>
 * The store does not know phases: the write guard of D4 sits in front of it
 * ({@link com.aresstack.askai.research.outline.OutlineWorkspace}).
 */
public final class FileOutlineStore {

    /** The stored outline is damaged; it is kept as it is. */
    public static final class CorruptOutlineException extends IllegalStateException {
        CorruptOutlineException(String message) {
            super(message);
        }
    }

    /** A content write based on an outdated working revision. */
    public static final class RevisionConflictException extends IllegalStateException {
        private final long currentRevision;

        RevisionConflictException(long expected, long current) {
            super("The outline changed (revision " + current + ", your copy is revision " + expected
                    + "). Reload it and apply your change again.");
            this.currentRevision = current;
        }

        public long getCurrentRevision() {
            return currentRevision;
        }
    }

    /** The outcome of a commit. */
    public static final class Commit {
        public final long revision;
        public final Outline outline;
        /** {@code false} when the outline was unchanged and no revision was written. */
        public final boolean changed;

        Commit(long revision, Outline outline, boolean changed) {
            this.revision = revision;
            this.outline = outline;
            this.changed = changed;
        }
    }

    /** The outcome of an approval, together with the working revision it froze. */
    public static final class Approval {
        public final int revisionNumber;
        /** {@code false} when the working copy equals the last approval and that revision was kept. */
        public final boolean newRevision;
        public final long workingRevision;
        public final String contentHash;

        Approval(int revisionNumber, boolean newRevision, long workingRevision, String contentHash) {
            this.revisionNumber = revisionNumber;
            this.newRevision = newRevision;
            this.workingRevision = workingRevision;
            this.contentHash = contentHash;
        }
    }

    private final File dir;
    private final Outline.IdSource ids;

    public FileOutlineStore(File outlineDir) {
        this(outlineDir, Outline.RANDOM_IDS);
    }

    public FileOutlineStore(File outlineDir, Outline.IdSource ids) {
        this.dir = outlineDir;
        this.ids = ids;
    }

    public static String sha256(String content) {
        return StoreIo.sha256(content);
    }

    public File directory() {
        return dir;
    }

    /** The current working revision; 0 while nothing was committed. */
    public synchronized long workingRevision() {
        return manifest().revision;
    }

    /** The working outline ({@link Outline#EMPTY} before the first commit). */
    public synchronized Outline working() {
        return OutlineJson.read(workingContent());
    }

    /** The working outline as stored (canonical JSON). */
    public synchronized String workingContent() {
        Manifest manifest = manifest();
        if (manifest.revision == 0) {
            return OutlineJson.write(Outline.EMPTY);
        }
        String content = readOrNull(historyJson(manifest.revision));
        if (content == null || !StoreIo.sha256(content).equals(manifest.contentHash)) {
            throw corrupt("its working revision " + manifest.revision + " does not match its manifest");
        }
        return content;
    }

    /**
     * Commit a content change based on {@code expectedRevision}. New entries get store-owned ids; an unchanged
     * outline writes nothing.
     * @throws RevisionConflictException when the working revision moved on
     * @throws IllegalArgumentException  for an outline with unknown or duplicate ids or an empty title
     */
    public synchronized Commit commit(Outline draft, long expectedRevision, long nowMillis) {
        long current = workingRevision();
        if (expectedRevision != current) {
            throw new RevisionConflictException(expectedRevision, current);
        }
        Outline stored = working();
        return write(draft.placedAgainst(stored, ids), stored, current, nowMillis);
    }

    /**
     * Set stale reasons on chapters (chapter id -> reason) without an expected revision: the after-apply marks of
     * D4, which the write guard exempts. Unknown chapter ids are ignored; an unchanged outline writes nothing.
     */
    public synchronized Commit markChaptersStale(Map<String, String> reasons, long nowMillis) {
        Outline stored = working();
        long current = workingRevision();
        return write(stored.withChapterStaleReasons(reasons), stored, current, nowMillis);
    }

    private Commit write(Outline next, Outline stored, long current, long nowMillis) {
        if (next.equals(stored)) {
            return new Commit(current, stored, false);
        }
        String content = OutlineJson.write(next);
        long revision = current + 1;
        try {
            StoreIo.atomicWrite(historyJson(revision), content);
            StoreIo.atomicWrite(workingJson(), content);
            if (workingPropsFile().isFile()) {
                StoreIo.atomicWrite(backupPropsFile(), StoreIo.readUtf8(workingPropsFile()));
            }
            StoreIo.atomicWrite(workingPropsFile(), "formatVersion=1"
                    + "\nworkingRevision=" + revision
                    + "\ncontentHash=" + StoreIo.sha256(content)
                    + "\nupdatedAt=" + nowMillis + "\n");
        } catch (IOException failed) {
            throw new IllegalStateException("Could not save the outline: " + failed.getMessage(), failed);
        }
        return new Commit(revision, next, true);
    }

    /**
     * Make working revision {@code revision} the head again (plan PR 23): its entries were placed by this store,
     * so the historical ids return unchanged, even ids the current head no longer has.
     * @throws RevisionConflictException when the working revision moved on
     * @throws IllegalArgumentException  when there is no such revision
     */
    public synchronized Commit restore(long revision, long expectedRevision, long nowMillis) {
        long current = workingRevision();
        if (expectedRevision != current) {
            throw new RevisionConflictException(expectedRevision, current);
        }
        Outline historical = revision <= 0 || revision > current ? null : workingHistory(revision);
        if (historical == null) {
            throw new IllegalArgumentException("There is no outline revision " + revision + ".");
        }
        return write(historical, working(), current, nowMillis);
    }

    /** The outline at working revision {@code revision}, or {@code null} when there is none. */
    public synchronized Outline workingHistory(long revision) {
        String content = readOrNull(historyJson(revision));
        return content == null ? null : OutlineJson.read(content);
    }

    /**
     * Freeze the working outline as the next approved revision and report the working revision it froze, under
     * one lock. An outline equal to the last approval keeps that revision.
     */
    public synchronized Approval approveCurrent(long nowMillis) {
        String content = workingContent();
        long working = workingRevision();
        String hash = StoreIo.sha256(content);
        int latest = latestApprovedNumber();
        if (latest > 0 && hash.equals(approvedHash(latest))) {
            String approved = approvedContent(latest);
            if (approved == null || !hash.equals(StoreIo.sha256(approved))) {
                throw corrupt("its approved revision " + latest + " does not match its recorded hash");
            }
            return new Approval(latest, false, working, hash);
        }
        int next = latest + 1;
        try {
            StoreIo.atomicWrite(revisionJson(next), content);
            StoreIo.atomicWrite(revisionPropsFile(next), "revisionNumber=" + next
                    + "\ncontentHash=" + hash
                    + "\nworkingRevision=" + working
                    + "\napprovedAt=" + nowMillis + "\n");
        } catch (IOException failed) {
            throw new IllegalStateException("Could not approve the outline: " + failed.getMessage(), failed);
        }
        return new Approval(next, true, working, hash);
    }

    /** The approved outline text of {@code number}, or {@code null}. */
    public synchronized String approvedContent(int number) {
        return number <= 0 ? null : readOrNull(revisionJson(number));
    }

    /** The approved outline of {@code number}, or {@code null}. */
    public synchronized Outline approved(int number) {
        String content = approvedContent(number);
        return content == null ? null : OutlineJson.read(content);
    }

    /** The content hash recorded with approved revision {@code number} ("" when unknown). */
    public synchronized String approvedHash(int number) {
        return readProps(revisionPropsFile(number)).getProperty("contentHash", "");
    }

    public synchronized int latestApprovedNumber() {
        File[] files = new File(dir, "revisions").listFiles();
        int latest = 0;
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".json")) {
                    try {
                        latest = Math.max(latest, Integer.parseInt(name.substring(0, name.length() - 5)));
                    } catch (NumberFormatException foreign) {
                        // not a revision file
                    }
                }
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ manifest & paths

    private static final class Manifest {
        final long revision;
        final String contentHash;

        Manifest(long revision, String contentHash) {
            this.revision = revision;
            this.contentHash = contentHash;
        }
    }

    private Manifest manifest() {
        File file = workingPropsFile();
        if (!file.isFile()) {
            File[] history = new File(dir, "working-history").listFiles();
            if (file.exists() || (history != null && history.length > 0)) {
                throw corrupt("its manifest is missing although working revisions exist");
            }
            return new Manifest(0L, null);
        }
        Properties props;
        try {
            props = StoreIo.loadUtf8Properties(file);
        } catch (IOException unreadable) {
            throw corrupt("its manifest cannot be read (" + unreadable.getMessage() + ")");
        }
        String revision = props.getProperty("workingRevision");
        String hash = props.getProperty("contentHash");
        if (revision == null || hash == null) {
            throw corrupt("its manifest lacks the working revision or the content hash");
        }
        try {
            return new Manifest(Long.parseLong(revision.trim()), hash.trim());
        } catch (NumberFormatException broken) {
            throw corrupt("its manifest names no valid working revision");
        }
    }

    private CorruptOutlineException corrupt(String what) {
        return new CorruptOutlineException("The outline store " + dir.getPath() + " is damaged: " + what
                + ". It was kept as it is; repair or remove it before you continue.");
    }

    private static String readOrNull(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return StoreIo.readUtf8(file);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static Properties readProps(File file) {
        if (!file.isFile()) {
            return new Properties();
        }
        try {
            return StoreIo.loadUtf8Properties(file);
        } catch (IOException unreadable) {
            return new Properties();
        }
    }

    private File workingJson() {
        return new File(dir, "working.json");
    }

    private File workingPropsFile() {
        return new File(dir, "working.properties");
    }

    private File backupPropsFile() {
        return new File(dir, "working.properties.prev");
    }

    private File historyJson(long revision) {
        return new File(new File(dir, "working-history"), revision + ".json");
    }

    private File revisionJson(int number) {
        return new File(new File(dir, "revisions"), String.format("%04d", number) + ".json");
    }

    private File revisionPropsFile(int number) {
        return new File(new File(dir, "revisions"), String.format("%04d", number) + ".properties");
    }
}
