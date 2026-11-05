package com.aresstack.askai.research.store;

import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactContent;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactWriteResult;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

/**
 * File-backed {@link AgentArtifactStore}: each artifact is a human-readable Markdown file under
 * {@code artifacts/&lt;id&gt;.md}, with a sibling {@code &lt;id&gt;.md.meta} holding the revision and checksum.
 * Writes are atomic (temp + rename) and guarded by optimistic locking on the revision. Content survives a
 * restart; a corrupt meta file is isolated (revision falls back to 1) and never deletes the Markdown.
 */
public final class FileArtifactStore implements AgentArtifactStore {

    private final File dir;

    public FileArtifactStore(File artifactsDir) {
        this.dir = artifactsDir;
    }

    private File md(String id) {
        return new File(dir, safe(id) + ".md");
    }

    private File meta(String id) {
        return new File(dir, safe(id) + ".md.meta");
    }

    private static String safe(String id) {
        return id == null ? "_" : id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    @Override
    public ArtifactContent read(String artifactId) {
        File md = md(artifactId);
        if (!md.isFile()) {
            return new ArtifactContent("", 0L);
        }
        try {
            String content = StoreIo.readUtf8(md);
            return new ArtifactContent(content, readRevision(artifactId));
        } catch (IOException ex) {
            return new ArtifactContent("", 0L);
        }
    }

    @Override
    public ArtifactWriteResult replace(String artifactId, long expectedRevision, String markdown) {
        long current = read(artifactId).getRevision();
        if (expectedRevision != current) {
            return ArtifactWriteResult.conflict(read(artifactId).getMarkdown(), current);
        }
        long next = current + 1L;
        String content = markdown == null ? "" : markdown;
        try {
            StoreIo.atomicWrite(md(artifactId), content);
            writeMeta(artifactId, next, StoreIo.sha256(content));
            return ArtifactWriteResult.ok(next);
        } catch (IOException ex) {
            return ArtifactWriteResult.error("Could not write artifact: " + ex.getMessage());
        }
    }

    private long readRevision(String artifactId) {
        File meta = meta(artifactId);
        if (!meta.isFile()) {
            return 1L; // Markdown exists without meta (e.g. hand-edited): treat as revision 1.
        }
        try {
            Properties props = new Properties();
            props.putAll(StoreIo.loadUtf8Properties(meta));
            return Long.parseLong(props.getProperty("revision", "1"));
        } catch (Exception corruptOrMissing) {
            return 1L; // isolate a corrupt meta; never lose the Markdown
        }
    }

    private void writeMeta(String artifactId, long revision, String checksum) throws IOException {
        StoreIo.atomicWrite(meta(artifactId),
                "revision=" + revision + "\nchecksum=" + checksum + "\n");
    }
}
