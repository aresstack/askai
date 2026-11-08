package com.aresstack.askai.research.store;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * File-backed persistence for the KONZEPTPAPIER document — the JSON sibling of
 * {@link FileResearchBriefStore}, with the same two-level lifecycle but TWO revision notions:
 * the WORKING revision bumps on every successful micro-edit (concurrency + stale-branch
 * detection for the agent tools), the APPROVED revisions stay the user's explicit phase gate
 * ("Konzeptpapier freigeben & weiter"). Layout under the concept directory:
 * <pre>
 *   working.json / working.properties        (workingRevision, contentHash, updatedAt)
 *   revisions/0001.json / 0001.properties    (immutable, never overwritten)
 * </pre>
 * The document envelope is {@code {"title","subtitle","concept":[…]}} — {@code concept} is an
 * ARRAY on purpose: by the tree rule that makes the working surface structural while title and
 * subtitle are ValueLeafs that drop out of every structural view automatically. The envelope is
 * the seed of the future BOOK document: later phases add their own sections ({@code outline},
 * {@code content} = the manuscript, {@code style}, {@code images}, {@code sources}) when their
 * phase needs them — never earlier. Two guardrails for those sections: structural ORDER comes
 * from array position only (JSON property order is cosmetic, never semantic), and every section
 * gets its OWN view + tool contract on the shared JsonTree (the concept's name-chain addressing
 * would be wrong for a manuscript full of {@code paragraph} blocks).
 */
public final class FileConceptStore {

    /** A fresh concept: empty envelope, working revision 0, nothing approved. */
    public static final String EMPTY_DOCUMENT = "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[]}";

    /** Outcome of an approval attempt. */
    public static final class Approval {
        private final boolean approved;
        private final int revisionNumber;

        private Approval(boolean approved, int revisionNumber) {
            this.approved = approved;
            this.revisionNumber = revisionNumber;
        }

        /** {@code false} means ALREADY_CURRENT: the working state equals the last approval. */
        public boolean isNewRevision() {
            return approved;
        }

        public int getRevisionNumber() {
            return revisionNumber;
        }
    }

    private final File dir;

    public FileConceptStore(File conceptDir) {
        this.dir = conceptDir;
    }

    /** The store's canonical content hash (exposed for the identity co-commit's no-op checks). */
    public static String sha256(String content) {
        return StoreIo.sha256(content);
    }

    /** The current working revision; 0 while nothing has ever been committed. */
    public synchronized long workingRevision() {
        return parseLong(workingProps().getProperty("workingRevision"), 0L);
    }

    /** Working copy if present, else the latest approved document, else the empty envelope. */
    public synchronized String effectiveContent() {
        try {
            File working = workingJson();
            if (working.isFile()) {
                return StoreIo.readUtf8(working);
            }
            int latest = latestApprovedNumber();
            if (latest > 0) {
                return StoreIo.readUtf8(revisionJson(latest));
            }
        } catch (IOException unreadable) {
            // fall through to the empty envelope — never fabricate partial content
        }
        return EMPTY_DOCUMENT;
    }

    // ------------------------------------------------------------------ identity co-commit (V3)

    /** The manifest — the confirmed pair's description; {@code epoch == null} on legacy stores. */
    public static final class Manifest {
        public final long revision;
        public final String documentHash;
        public final String identityHash;
        public final String identityCoreHash;
        public final String epoch;

        Manifest(long revision, String documentHash, String identityHash,
                 String identityCoreHash, String epoch) {
            this.revision = revision;
            this.documentHash = documentHash;
            this.identityHash = identityHash;
            this.identityCoreHash = identityCoreHash;
            this.epoch = epoch;
        }
    }

    /**
     * Read the manifest STRICTLY: {@code null} = the file exists but is unreadable or lacks its
     * base keys (corruption — the caller escalates, it never guesses); a {@link Manifest} with
     * {@code revision == 0} and all-null hashes when the file is simply absent (fresh store).
     */
    public synchronized Manifest manifest() {
        return readManifest(workingPropsFile());
    }

    /** The validated-backup manifest ({@code working.properties.prev}), same strictness. */
    public synchronized Manifest backupManifest() {
        return readManifest(backupPropsFile());
    }

    private static Manifest readManifest(File file) {
        if (!file.isFile()) {
            return new Manifest(0L, null, null, null, null);
        }
        Properties props = new Properties();
        try {
            props.putAll(StoreIo.loadUtf8Properties(file));
        } catch (IOException unreadable) {
            return null;
        }
        String revision = props.getProperty("workingRevision");
        String documentHash = props.getProperty("contentHash");
        if (revision == null || documentHash == null) {
            return null; // present but missing its base keys = corrupt, never a guess
        }
        try {
            return new Manifest(Long.parseLong(revision.trim()), documentHash,
                    props.getProperty("identityHash"), props.getProperty("identityCoreHash"),
                    props.getProperty("epoch"));
        } catch (NumberFormatException corrupt) {
            return null;
        }
    }

    /**
     * The V3 co-commit: document AND stamped identity as one pair. Write order is the ratified
     * protocol — immutable history pair first, working publication second, manifest BACKUP
     * third, atomic manifest swap LAST (only the swap makes the revision exist; anything before
     * a crash stays an invisible orphan). No-op decisions are the SERVICE's job (semantic core
     * comparison) — this method always commits.
     *
     * @return the new working revision
     */
    public synchronized long commitPair(String documentJson, String stampedIdentityJson,
                                        String identityCoreHash, String epoch, long nowMillis) {
        long next = workingRevision() + 1;
        try {
            StoreIo.atomicWrite(identityHistoryJson(next), stampedIdentityJson);
            StoreIo.atomicWrite(historyJson(next), documentJson);
            StoreIo.atomicWrite(workingJson(), documentJson);
            StoreIo.atomicWrite(workingIdentityJson(), stampedIdentityJson);
            backupCurrentManifest();
            StoreIo.atomicWrite(workingPropsFile(), manifestContent(next,
                    StoreIo.sha256(documentJson), StoreIo.sha256(stampedIdentityJson),
                    identityCoreHash, epoch, nowMillis));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist concept: " + ex.getMessage(), ex);
        }
        return next;
    }

    /**
     * Adopt an identity for the CURRENT revision without bumping it — the migration / epoch-mint
     * path (V3 §7 stage 2): the document is already manifest-confirmed, only the identity side
     * is created. Follows the same order rules (history first, manifest swap last).
     */
    public synchronized void adoptIdentityForCurrentRevision(String stampedIdentityJson,
                                                             String identityCoreHash,
                                                             String epoch, long nowMillis) {
        long revision = workingRevision();
        String documentHash = workingProps().getProperty("contentHash");
        try {
            if (revision > 0) {
                StoreIo.atomicWrite(identityHistoryJson(revision), stampedIdentityJson);
            }
            StoreIo.atomicWrite(workingIdentityJson(), stampedIdentityJson);
            backupCurrentManifest();
            StoreIo.atomicWrite(workingPropsFile(), manifestContent(revision,
                    documentHash == null ? StoreIo.sha256(effectiveContent()) : documentHash,
                    StoreIo.sha256(stampedIdentityJson), identityCoreHash, epoch, nowMillis));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not adopt concept identity: "
                    + ex.getMessage(), ex);
        }
    }

    /** Restore the manifest from its validated backup (recovery stage 3). */
    public synchronized void restoreManifestFromBackup() {
        try {
            StoreIo.atomicWrite(workingPropsFile(), StoreIo.readUtf8(backupPropsFile()));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not restore manifest from backup: "
                    + ex.getMessage(), ex);
        }
    }

    /** Re-publish working files from confirmed content (self-heal, recovery stage 1). */
    public synchronized void publishWorking(String documentJson, String identityJsonOrNull) {
        try {
            StoreIo.atomicWrite(workingJson(), documentJson);
            if (identityJsonOrNull != null) {
                StoreIo.atomicWrite(workingIdentityJson(), identityJsonOrNull);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not self-heal working files: "
                    + ex.getMessage(), ex);
        }
    }

    /** The raw published working document, or {@code null} — NO fallback (recovery reads). */
    public synchronized String rawWorkingContent() {
        try {
            return workingJson().isFile() ? StoreIo.readUtf8(workingJson()) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The published working identity sidecar, or {@code null}. */
    public synchronized String workingIdentityContent() {
        try {
            return workingIdentityJson().isFile()
                    ? StoreIo.readUtf8(workingIdentityJson()) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The identity sidecar as committed for {@code revision}, or {@code null}. */
    public synchronized String identityHistoryContent(long revision) {
        File file = identityHistoryJson(revision);
        if (!file.isFile()) {
            return null;
        }
        try {
            return StoreIo.readUtf8(file);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** History revisions ABOVE the confirmed one: uncommitted orphans, never adopted. */
    public synchronized java.util.List<Long> orphanHistoryRevisions(long confirmedRevision) {
        java.util.List<Long> orphans = new java.util.ArrayList<Long>();
        File[] files = new File(dir, "working-history").listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".json")) {
                    try {
                        long revision = Long.parseLong(
                                name.substring(0, name.length() - ".json".length()));
                        if (revision > confirmedRevision) {
                            orphans.add(revision);
                        }
                    } catch (NumberFormatException foreign) {
                        // not a history file
                    }
                }
            }
        }
        java.util.Collections.sort(orphans);
        return orphans;
    }

    private void backupCurrentManifest() throws IOException {
        if (workingPropsFile().isFile()) {
            StoreIo.atomicWrite(backupPropsFile(), StoreIo.readUtf8(workingPropsFile()));
        }
    }

    private static String manifestContent(long revision, String documentHash,
                                          String identityHash, String identityCoreHash,
                                          String epoch, long nowMillis) {
        return "formatVersion=1"
                + "\nworkingRevision=" + revision
                + "\ncontentHash=" + documentHash
                + "\nidentityHash=" + identityHash
                + "\nidentityCoreHash=" + identityCoreHash
                + "\nepoch=" + epoch
                + "\nupdatedAt=" + nowMillis + "\n";
    }

    private File workingIdentityJson() {
        return new File(dir, "working-identity.json");
    }

    private File backupPropsFile() {
        return new File(dir, "working.properties.prev");
    }

    private File identityHistoryJson(long workingRevision) {
        return new File(new File(dir, "identity-history"), workingRevision + ".json");
    }

    /**
     * Commit a new working document. Content-hash dedupe: an identical document does NOT bump
     * the revision (a no-op edit must not invalidate everyone else's branch handles).
     *
     * <p>LEGACY, pre-identity path: kept only for old callers/tests — the productive service
     * commits exclusively through {@link #commitPair}; a store advanced this way is re-adopted
     * (new epoch) by the service's staged recovery on the next load.</p>
     *
     * @return the working revision after the commit (unchanged when deduped)
     */
    public synchronized long commitWorking(String documentJson, long nowMillis) {
        String hash = StoreIo.sha256(documentJson);
        Properties props = workingProps();
        long revision = parseLong(props.getProperty("workingRevision"), 0L);
        if (hash.equals(props.getProperty("contentHash")) && workingJson().isFile()) {
            return revision;
        }
        long next = revision + 1;
        try {
            StoreIo.atomicWrite(workingJson(), documentJson);
            // Every committed working state stays readable under its revision number — the
            // user's manual rollback in the concept editor steps through THESE; the approved
            // revisions/ dir remains the coarser phase gate. Never pruned (the no-delete rule).
            StoreIo.atomicWrite(historyJson(next), documentJson);
            StoreIo.atomicWrite(workingPropsFile(),
                    "workingRevision=" + next
                            + "\ncontentHash=" + hash
                            + "\nupdatedAt=" + nowMillis + "\n");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist concept: " + ex.getMessage(), ex);
        }
        return next;
    }

    /**
     * The document as it stood at WORKING revision {@code revision}, or {@code null} when no
     * history file exists (revisions committed before the history feature, or never existed).
     */
    public synchronized String workingHistoryContent(long revision) {
        File file = historyJson(revision);
        if (!file.isFile()) {
            return null;
        }
        try {
            return StoreIo.readUtf8(file);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** An approval together with the working revision it froze, read under one lock. */
    public static final class FrozenApproval {
        public final Approval approval;
        public final long workingRevision;

        FrozenApproval(Approval approval, long workingRevision) {
            this.approval = approval;
            this.workingRevision = workingRevision;
        }
    }

    /** Approve and read the working revision atomically, so no commit can slip in between (PR 07). */
    public synchronized FrozenApproval approveCurrentFrozen(long nowMillis) {
        return new FrozenApproval(approveCurrent(nowMillis), workingRevision());
    }

    /** Freeze the current effective document as the next immutable approved revision. */
    public synchronized Approval approveCurrent(long nowMillis) {
        String content = effectiveContent();
        String hash = StoreIo.sha256(content);
        int latest = latestApprovedNumber();
        if (latest > 0 && hash.equals(revisionProps(latest).getProperty("contentHash"))) {
            return new Approval(false, latest);
        }
        int next = latest + 1;
        try {
            StoreIo.atomicWrite(revisionJson(next), content);
            StoreIo.atomicWrite(revisionPropsFile(next),
                    "revisionNumber=" + next
                            + "\ncontentHash=" + hash
                            + "\napprovedAt=" + nowMillis + "\n");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not approve concept: " + ex.getMessage(), ex);
        }
        return new Approval(true, next);
    }

    /** The latest approved document, or {@code null} when nothing was approved yet. */
    public synchronized String latestApprovedContent() {
        int latest = latestApprovedNumber();
        if (latest <= 0) {
            return null;
        }
        try {
            return StoreIo.readUtf8(revisionJson(latest));
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The approved document of {@code number}, or {@code null} when there is none. */
    public synchronized String approvedContent(int number) {
        if (number <= 0 || !revisionJson(number).isFile()) {
            return null;
        }
        try {
            return StoreIo.readUtf8(revisionJson(number));
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The content hash recorded with approved revision {@code number} ("" when unknown). */
    public synchronized String approvedHash(int number) {
        return revisionProps(number).getProperty("contentHash", "");
    }

    public synchronized int latestApprovedNumber() {
        File revDir = new File(dir, "revisions");
        File[] files = revDir.listFiles();
        int latest = 0;
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".json")) {
                    try {
                        latest = Math.max(latest,
                                Integer.parseInt(name.substring(0, name.length() - ".json".length())));
                    } catch (NumberFormatException notANumber) {
                        // foreign file — ignore
                    }
                }
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------ paths & props

    private File workingJson() {
        return new File(dir, "working.json");
    }

    private File workingPropsFile() {
        return new File(dir, "working.properties");
    }

    private File revisionJson(int number) {
        return new File(new File(dir, "revisions"), pad(number) + ".json");
    }

    private File historyJson(long workingRevision) {
        return new File(new File(dir, "working-history"), workingRevision + ".json");
    }

    private File revisionPropsFile(int number) {
        return new File(new File(dir, "revisions"), pad(number) + ".properties");
    }

    private Properties workingProps() {
        return readProps(workingPropsFile());
    }

    private Properties revisionProps(int number) {
        return readProps(revisionPropsFile(number));
    }

    private static String pad(int number) {
        return String.format("%04d", number);
    }

    private static Properties readProps(File file) {
        Properties props = new Properties();
        if (!file.isFile()) {
            return props;
        }
        try {
            props.putAll(StoreIo.loadUtf8Properties(file));
        } catch (IOException corrupt) {
            // best-effort: defaults fill the rest
        }
        return props;
    }

    private static long parseLong(String text, long fallback) {
        try {
            return text == null ? fallback : Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
