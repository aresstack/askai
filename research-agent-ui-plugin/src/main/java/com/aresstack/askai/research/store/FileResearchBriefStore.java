package com.aresstack.askai.research.store;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * File-backed persistence for the {@link ResearchBriefArtifact}: the mutable working copy and the append-only
 * immutable approved revisions, so both survive a restart (RA-P6 §10). Reuses the store's atomic write + SHA
 * helpers; approved revision files are never overwritten once written, and the transient working copy is the
 * only thing that is rewritten or cleared. Layout under the brief directory:
 * <pre>
 *   working.md / working.properties          (present only while a working copy exists)
 *   revisions/0001.md / revisions/0001.properties
 *   revisions/0002.md / revisions/0002.properties  ...
 * </pre>
 */
public final class FileResearchBriefStore {

    private final File dir;

    public FileResearchBriefStore(File briefDir) {
        this.dir = briefDir;
    }

    /** Reconstruct the artifact from disk (empty when nothing has been written yet). */
    public ResearchBriefArtifact load() {
        List<ResearchBriefRevision> revisions = loadRevisions();
        ResearchBriefWorkingCopy working = loadWorkingCopy();
        return new ResearchBriefArtifact(ResearchBriefArtifact.ARTIFACT_ID, working, revisions);
    }

    /**
     * Fold a new brief markdown into the working copy and persist ONLY if it actually changed. Returns true
     * when something was written, false when the content was identical to the current effective content.
     */
    public boolean updateWorkingCopy(String markdown, long nowMillis) {
        ResearchBriefArtifact.Update update = load().withWorkingCopyUpdatedTo(markdown, nowMillis);
        if (update.isChanged()) {
            save(update.getArtifact());
        }
        return update.isChanged();
    }

    /** Approve the current working copy into a new immutable revision (or report ALREADY_CURRENT). */
    public ResearchBriefArtifact.Approval approveCurrent(long nowMillis) {
        ResearchBriefArtifact.Approval approval = load().approve(nowMillis);
        save(approval.getArtifact());
        return approval;
    }

    /** The latest approved revision content (what OTHER phases read), or empty when none is approved. */
    public String latestApprovedContent() {
        ResearchBriefRevision latest = load().latestApprovedRevision();
        return latest == null ? "" : latest.getContent();
    }

    /** What the scoping assistant is working with: the working copy, else the latest approved, else empty. */
    public String effectiveContent() {
        return load().effectiveContent();
    }

    /** Persist the artifact: append any new revision files (never overwriting), then sync the working copy. */
    public void save(ResearchBriefArtifact artifact) {
        try {
            for (ResearchBriefRevision revision : artifact.getApprovedRevisions()) {
                File md = revisionMd(revision.getRevisionNumber());
                if (!md.isFile()) {
                    StoreIo.atomicWrite(md, revision.getContent());
                    StoreIo.atomicWrite(revisionProps(revision.getRevisionNumber()),
                            "revisionNumber=" + revision.getRevisionNumber()
                                    + "\ncontentHash=" + revision.getContentHash()
                                    + "\npreviousRevision=" + revision.getPreviousRevision()
                                    + "\napprovedAt=" + revision.getApprovedAtMillis() + "\n");
                }
            }
            if (artifact.hasWorkingCopy()) {
                ResearchBriefWorkingCopy working = artifact.getWorkingCopy();
                StoreIo.atomicWrite(workingMd(), working.getContent());
                StoreIo.atomicWrite(workingProps(),
                        "contentHash=" + working.getContentHash()
                                + "\nbaseApprovedRevision=" + working.getBaseApprovedRevision()
                                + "\nupdatedAt=" + working.getUpdatedAtMillis() + "\n");
            } else {
                deleteQuietly(workingMd());
                deleteQuietly(workingProps());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not persist research brief: " + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------------ load internals

    private List<ResearchBriefRevision> loadRevisions() {
        File revDir = new File(dir, "revisions");
        File[] files = revDir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<Integer> numbers = new ArrayList<Integer>();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".md")) {
                Integer number = parseNumber(name.substring(0, name.length() - ".md".length()));
                if (number != null) {
                    numbers.add(number);
                }
            }
        }
        Collections.sort(numbers);
        List<ResearchBriefRevision> revisions = new ArrayList<ResearchBriefRevision>();
        for (Integer number : numbers) {
            ResearchBriefRevision revision = loadRevision(number);
            if (revision != null) {
                revisions.add(revision);
            }
        }
        return revisions;
    }

    private ResearchBriefRevision loadRevision(int number) {
        try {
            String content = StoreIo.readUtf8(revisionMd(number));
            Properties props = readProps(revisionProps(number));
            return new ResearchBriefRevision(number, content,
                    props.getProperty("contentHash", StoreIo.sha256(content)),
                    parseInt(props.getProperty("previousRevision"), number - 1),
                    parseLong(props.getProperty("approvedAt"), 0L));
        } catch (IOException corruptOrMissing) {
            return null; // isolate a corrupt revision; never fabricate one
        }
    }

    private ResearchBriefWorkingCopy loadWorkingCopy() {
        File md = workingMd();
        if (!md.isFile()) {
            return null;
        }
        try {
            String content = StoreIo.readUtf8(md);
            Properties props = readProps(workingProps());
            return new ResearchBriefWorkingCopy(content,
                    props.getProperty("contentHash", StoreIo.sha256(ResearchBriefArtifact.normalize(content))),
                    parseInt(props.getProperty("baseApprovedRevision"), 0),
                    parseLong(props.getProperty("updatedAt"), 0L));
        } catch (IOException corruptOrMissing) {
            return null;
        }
    }

    // ------------------------------------------------------------------ paths & parsing

    private File workingMd() {
        return new File(dir, "working.md");
    }

    private File workingProps() {
        return new File(dir, "working.properties");
    }

    private File revisionMd(int number) {
        return new File(new File(dir, "revisions"), pad(number) + ".md");
    }

    private File revisionProps(int number) {
        return new File(new File(dir, "revisions"), pad(number) + ".properties");
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
            // best-effort: return whatever loaded, sensible defaults fill the rest
        }
        return props;
    }

    private static Integer parseNumber(String text) {
        try {
            return Integer.valueOf(Integer.parseInt(text));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static int parseInt(String text, int fallback) {
        try {
            return text == null ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return text == null ? fallback : Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static void deleteQuietly(File file) {
        if (file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
