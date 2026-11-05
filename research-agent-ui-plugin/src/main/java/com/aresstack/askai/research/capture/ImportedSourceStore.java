package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.knowledge.processing.Utf8Properties;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * #39 correction — the DURABLE truth for user imports: the RAW snapshot exactly as the user
 * delivered it, plus the structured extraction record. Both persist BEFORE the acceptance
 * commit; the extracted text in the source record is DERIVED and the source's comment is at
 * most a display convenience — provenance never depends on it. Losing this store's write is
 * an import FAILURE, never a silent success (provenance is part of the evidence chain).
 *
 * <p>Layout under the store directory, keyed by a deterministic IMPORT identity of raw
 * content <em>plus origin</em>: {@code <id>.raw} is the untouched raw content,
 * {@code <id>.properties} the metadata (kind, hashes, origin, extractor, warnings, and the
 * source id once the acceptance bound one). Identical raw bytes from two different origins
 * are two deliveries with two provenance records — a snapshot binds exactly ONE source, so
 * a content-only id would let the second origin's binding overwrite the first's.
 * Re-importing the same content from the same origin rewrites its snapshot idempotently.</p>
 */
public final class ImportedSourceStore {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** The raw user delivery: BYTES untouched (a PDF is a byte sequence), hashed, stamped. */
    public static final class ImportedSourceSnapshot {
        public final String snapshotId;
        public final String kind;
        public final byte[] rawBytes;
        public final String rawSha256;
        public final String originUri;
        public final long importedAtMillis;

        public ImportedSourceSnapshot(String snapshotId, String kind, byte[] rawBytes,
                                      String rawSha256, String originUri, long importedAtMillis) {
            this.snapshotId = snapshotId;
            this.kind = kind;
            this.rawBytes = rawBytes == null ? new byte[0] : rawBytes;
            this.rawSha256 = rawSha256;
            this.originUri = originUri == null ? "" : originUri;
            this.importedAtMillis = importedAtMillis;
        }

        /** Text-shaped payloads read back as UTF-8 — a display convenience, never the truth. */
        public String rawUtf8() {
            return new String(rawBytes, UTF8);
        }
    }

    /** What the mechanical extractor derived from the snapshot (text lives in the record). */
    public static final class ExtractionRecord {
        public final String snapshotId;
        public final String extractorId;
        public final String normalizedSha256;
        public final List<String> warnings;

        public ExtractionRecord(String snapshotId, String extractorId, String normalizedSha256,
                                List<String> warnings) {
            this.snapshotId = snapshotId;
            this.extractorId = extractorId;
            this.normalizedSha256 = normalizedSha256;
            this.warnings = Collections.unmodifiableList(
                    new ArrayList<String>(warnings == null
                            ? Collections.<String>emptyList() : warnings));
        }
    }

    /** One loaded snapshot + extraction + the bound source id (empty until bound). */
    public static final class Loaded {
        public final ImportedSourceSnapshot snapshot;
        public final ExtractionRecord extraction;
        public final String sourceId;

        Loaded(ImportedSourceSnapshot snapshot, ExtractionRecord extraction, String sourceId) {
            this.snapshot = snapshot;
            this.extraction = extraction;
            this.sourceId = sourceId;
        }
    }

    private final File dir;

    public ImportedSourceStore(File dir) {
        this.dir = dir;
    }

    /**
     * Deterministic IMPORT identity: raw content + origin. Same content at the same origin
     * is the same delivery (idempotent); the same content from another origin is its OWN
     * delivery with its own provenance and source binding.
     */
    public static String snapshotIdFor(String rawSha256, String originUri) {
        String origin = originUri == null ? "" : originUri.trim();
        String raw16 = rawSha256.substring(0, Math.min(16, rawSha256.length()));
        if (origin.isEmpty()) {
            return "imp-" + raw16;
        }
        // 64 bits of origin discrimination — 32 was needlessly tight (review note).
        return "imp-" + raw16 + "-"
                + CaptureStore.sha256(origin).substring(0, 16);
    }

    /**
     * Persist raw snapshot + extraction record together, atomically per file. This runs
     * BEFORE the acceptance commit; an {@link IOException} here must fail the import.
     */
    public void save(ImportedSourceSnapshot snapshot, ExtractionRecord extraction)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create import store directory " + dir);
        }
        atomicWriteBytes(rawFile(snapshot.snapshotId), snapshot.rawBytes);
        // Idempotent rewrite of the same content id must never wipe an existing binding.
        Properties existing = readProps(snapshot.snapshotId);
        String boundSourceId = existing == null ? null : existing.getProperty("sourceId");
        Properties props = new Properties();
        if (boundSourceId != null && !boundSourceId.isEmpty()) {
            props.setProperty("sourceId", boundSourceId);
        }
        props.setProperty("snapshotId", snapshot.snapshotId);
        props.setProperty("kind", snapshot.kind);
        props.setProperty("rawSha256", snapshot.rawSha256);
        props.setProperty("originUri", snapshot.originUri);
        props.setProperty("importedAtMillis", Long.toString(snapshot.importedAtMillis));
        props.setProperty("extractorId", extraction.extractorId);
        props.setProperty("normalizedSha256", extraction.normalizedSha256);
        props.setProperty("warningCount", Integer.toString(extraction.warnings.size()));
        for (int index = 0; index < extraction.warnings.size(); index++) {
            props.setProperty("warning." + index, extraction.warnings.get(index));
        }
        writeProps(snapshot.snapshotId, props);
    }

    /** Bind the accepted source id to its snapshot (after the acceptance commit). */
    public void bindSource(String snapshotId, String sourceId) throws IOException {
        Properties props = readProps(snapshotId);
        if (props == null) {
            throw new IOException("no snapshot " + snapshotId + " to bind source " + sourceId);
        }
        props.setProperty("sourceId", sourceId);
        writeProps(snapshotId, props);
    }

    /** @return the full provenance for a snapshot id, or {@code null} when unknown. */
    public Loaded load(String snapshotId) throws IOException {
        Properties props = readProps(snapshotId);
        File raw = rawFile(snapshotId);
        if (props == null || !raw.isFile()) {
            return null;
        }
        byte[] rawBytes = java.nio.file.Files.readAllBytes(raw.toPath());
        List<String> warnings = new ArrayList<String>();
        int count = Integer.parseInt(props.getProperty("warningCount", "0"));
        for (int index = 0; index < count; index++) {
            String warning = props.getProperty("warning." + index);
            if (warning != null) {
                warnings.add(warning);
            }
        }
        return new Loaded(
                new ImportedSourceSnapshot(snapshotId,
                        props.getProperty("kind", ""),
                        rawBytes,
                        props.getProperty("rawSha256", ""),
                        props.getProperty("originUri", ""),
                        Long.parseLong(props.getProperty("importedAtMillis", "0"))),
                new ExtractionRecord(snapshotId,
                        props.getProperty("extractorId", ""),
                        props.getProperty("normalizedSha256", ""),
                        warnings),
                props.getProperty("sourceId", ""));
    }

    /** @return the snapshot id bound to this source, or {@code null} when none is. */
    public String snapshotIdForSource(String sourceId) {
        File[] files = dir.listFiles();
        if (files == null || sourceId == null || sourceId.isEmpty()) {
            return null;
        }
        for (File file : files) {
            if (!file.getName().endsWith(".properties")) {
                continue;
            }
            try {
                Properties props = readPropsFile(file);
                if (sourceId.equals(props.getProperty("sourceId"))) {
                    return props.getProperty("snapshotId");
                }
            } catch (IOException unreadable) {
                // an unreadable sibling never hides the others
            }
        }
        return null;
    }

    // ---- files ----

    private File rawFile(String snapshotId) {
        return new File(dir, snapshotId + ".raw");
    }

    private File propsFile(String snapshotId) {
        return new File(dir, snapshotId + ".properties");
    }

    private Properties readProps(String snapshotId) throws IOException {
        File file = propsFile(snapshotId);
        return file.isFile() ? readPropsFile(file) : null;
    }

    private static Properties readPropsFile(File file) throws IOException {
        return Utf8Properties.load(file);
    }

    private void writeProps(String snapshotId, Properties props) throws IOException {
        java.io.StringWriter buffer = new java.io.StringWriter();
        props.store(buffer, "askai imported source snapshot");
        atomicWrite(propsFile(snapshotId), buffer.toString());
    }

    private static void atomicWrite(File target, String content) throws IOException {
        atomicWriteBytes(target, content.getBytes(UTF8));
    }

    /** The raw payload writes BYTE-IDENTICALLY — no charset round trip, ever. */
    private static void atomicWriteBytes(File target, byte[] content) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        java.io.OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content);
        } finally {
            out.close();
        }
        if (target.isFile() && !target.delete()) {
            throw new IOException("cannot replace " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("cannot move " + tmp + " to " + target);
        }
    }
}
