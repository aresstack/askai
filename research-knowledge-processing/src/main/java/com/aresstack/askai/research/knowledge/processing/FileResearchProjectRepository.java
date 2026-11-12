package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.ResearchProjectRepository;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The productive file adapter behind the domain {@link ResearchProjectRepository} port — the SINGLE persistence
 * contract stays whole-aggregate, but the on-disk form is per capture-GENERATION so that reprocessing supersedes
 * cleanly and a capture's derived data is committed ATOMICALLY.
 *
 * <p>A capture's derived knowledge is written as an immutable GENERATION, keyed by its full derivation identity
 * {@code processingKey = sha256(captureId \0 segmentationPipelineVersion \0 embeddingFingerprint)}. Layout under
 * {@code <projectDir>/knowledge/}:
 * <pre>
 *   project.properties                              schema version + project id
 *   captures/&lt;h(captureId)&gt;.properties              immutable SourceCapture + structural blocks
 *   derived/&lt;h(captureId)&gt;/&lt;processingKey&gt;/
 *        manifest.properties                        captureId + segVersion + embeddingFingerprint + counts
 *        sentences.properties                       the generation's sentences
 *        passages.properties                        the generation's passages
 *   active/&lt;h(captureId)&gt;.properties                captureId + the ACTIVE processingKey
 *                                                   + sourceId + capturedAtMillis (one active capture per source)
 *   superseded/&lt;h(captureId)&gt;.properties            captureId + sourceId + the capture that replaced it
 * </pre>
 *
 * <p>Per capture, {@link #save} writes the WHOLE new generation first (sentences + passages + manifest) and only
 * THEN atomically replaces the single {@code active} pointer — the one commit point. A crash before the swap
 * leaves the previous generation active (never new-sentences-with-old-passages); a half-written generation dir is
 * simply ignored because {@code active} still points elsewhere. {@link #load} reconstructs ONLY each capture's
 * ACTIVE generation via the domain record-ops; historical generations stay on disk but are not in the active
 * aggregate. Lucene/vector indexes remain rebuildable projections and are NOT stored here.</p>
 *
 * <p>ONE ACTIVE CAPTURE PER SOURCE: a source that was captured again (a re-read page, a new revision of the same
 * source) keeps exactly one active capture — the NEWEST ({@code capturedAtMillis}, ties broken by capture id).
 * {@link #save} never activates a capture that is older than the source's active one, and removes the active
 * pointer of every older capture of the same source; the replaced capture is noted under {@code superseded/} so
 * its passages can also leave the rebuildable indexes ({@link #supersededCaptureIds()}). {@link #load} enforces
 * the same invariant on projects written before it (newest wins) and never puts a superseded capture into the
 * aggregate, so its passages count nowhere: not in the topic analysis, not in the evidence, not in coverage.
 * Captures without a source id are never grouped. Superseded captures and their generations stay on disk as
 * history, like every older generation.</p>
 */
public final class FileResearchProjectRepository implements ResearchProjectRepository {

    /** Bumped only on an incompatible on-disk format change; an unknown version is rejected, never guessed. */
    static final int SCHEMA_VERSION = 2;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File root;

    public FileResearchProjectRepository(File projectDirectory) {
        this.root = new File(projectDirectory, "knowledge");
    }

    @Override
    public ResearchProject load(String projectId) {
        File meta = new File(root, "project.properties");
        if (meta.isFile()) {
            Properties p = read(meta);
            int version = parseInt(p.getProperty("schemaVersion"), -1);
            if (version != SCHEMA_VERSION) {
                throw new IllegalStateException("incompatible research-knowledge schema version " + version
                        + " (expected " + SCHEMA_VERSION + ") at " + meta.getAbsolutePath());
            }
            String storedId = p.getProperty("projectId", "");
            if (!storedId.isEmpty() && projectId != null && !storedId.equals(projectId)) {
                throw new IllegalStateException("project directory holds project '" + storedId
                        + "', not the requested '" + projectId + "'");
            }
        }
        ResearchProject project = new ResearchProject(projectId, IdSequence.counting());
        // One active capture per source, also for projects written before the invariant: newest wins.
        List<ActivePointer> active = enforceOneActiveCapturePerSource(readActivePointers());
        Map<String, ActivePointer> activeByCapture = new TreeMap<String, ActivePointer>();
        for (ActivePointer pointer : active) {
            activeByCapture.put(pointer.captureId, pointer);
        }
        java.util.Set<String> superseded = supersededCaptureIds();
        // Captures first (sentences/passages require a known capture) — a superseded capture stays history.
        for (File f : sortedPropertyFiles(new File(root, "captures"))) {
            SourceCapture capture = readCapture(read(f));
            if (superseded.contains(capture.getCaptureId())
                    && !activeByCapture.containsKey(capture.getCaptureId())) {
                continue;
            }
            project.recordSourceCapture(capture);
        }
        // Then ONLY each capture's ACTIVE generation.
        for (ActivePointer pointer : active) {
            if (!project.captures().containsKey(pointer.captureId)) {
                continue; // a pointer without its capture file: nothing to attach the generation to
            }
            File genDir = new File(new File(new File(root, "derived"), hash(pointer.captureId)),
                    pointer.processingKey);
            File sentencesFile = new File(genDir, "sentences.properties");
            File passagesFile = new File(genDir, "passages.properties");
            if (sentencesFile.isFile()) {
                project.recordSentences(readSentences(read(sentencesFile)));
            }
            if (passagesFile.isFile()) {
                project.recordPassages(readPassages(read(passagesFile)));
            }
        }
        return project;
    }

    @Override
    public void save(ResearchProject project) {
        writeIfChanged(new File(root, "project.properties"),
                "schemaVersion=" + SCHEMA_VERSION + "\nprojectId=" + escape(project.getProjectId()) + "\n");

        Map<String, List<Sentence>> sentencesByCapture = groupSentencesByCapture(project);
        Map<String, List<Passage>> passagesByCapture = groupPassagesByCapture(project);
        Map<String, ActivePointer> newestActiveBySource = newestBySource(readActivePointers());

        for (SourceCapture capture : project.captures().values()) {
            String captureId = capture.getCaptureId();
            writeIfChanged(new File(new File(root, "captures"), hash(captureId) + ".properties"),
                    serializeCapture(capture));

            List<Passage> passages = passagesByCapture.get(captureId);
            List<Sentence> sentences = sentencesByCapture.get(captureId);
            if ((passages == null || passages.isEmpty()) && (sentences == null || sentences.isEmpty())) {
                continue; // a recorded capture without processed knowledge yet: no generation, no pointer
            }
            ActivePointer newest = newestActiveBySource.get(capture.getSourceId());
            if (newest != null && !newest.captureId.equals(captureId)
                    && newest.isNewerThan(capture.getCapturedAtMillis(), captureId)) {
                // The source already has a NEWER active capture: this older one never becomes active again.
                markSuperseded(captureId, capture.getSourceId(), newest.captureId);
                continue;
            }
            commitGeneration(capture, sentences, passages);
            if (!capture.getSourceId().isEmpty()) {
                newestActiveBySource.put(capture.getSourceId(), new ActivePointer(null, captureId, "",
                        capture.getSourceId(), capture.getCapturedAtMillis()));
            }
        }
        // The newest capture replaces every older active capture of its source.
        enforceOneActiveCapturePerSource(readActivePointers());
    }

    /**
     * The ids of every capture that a newer capture of the same source replaced — their passages are no longer
     * active and must leave the rebuildable indexes too.
     */
    public java.util.Set<String> supersededCaptureIds() {
        java.util.Set<String> ids = new java.util.TreeSet<String>();
        for (File f : sortedPropertyFiles(new File(root, "superseded"))) {
            String captureId = read(f).getProperty("captureId", "");
            if (!captureId.isEmpty()) {
                ids.add(captureId);
            }
        }
        return ids;
    }

    /** The ids of the captures of ONE source that a newer capture of that source replaced. */
    @Override
    public java.util.Set<String> supersededCaptureIds(String sourceId) {
        java.util.Set<String> ids = new java.util.TreeSet<String>();
        if (sourceId == null || sourceId.isEmpty()) {
            return ids;
        }
        for (File f : sortedPropertyFiles(new File(root, "superseded"))) {
            Properties p = read(f);
            String captureId = p.getProperty("captureId", "");
            if (!captureId.isEmpty() && sourceId.equals(p.getProperty("sourceId", ""))) {
                ids.add(captureId);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------ one active capture per source

    /** One {@code active/} pointer with the identity the per-source invariant needs. */
    private static final class ActivePointer {
        final File file;
        final String captureId;
        final String processingKey;
        final String sourceId;
        final long capturedAtMillis;

        ActivePointer(File file, String captureId, String processingKey, String sourceId, long capturedAtMillis) {
            this.file = file;
            this.captureId = captureId;
            this.processingKey = processingKey;
            this.sourceId = sourceId == null ? "" : sourceId;
            this.capturedAtMillis = capturedAtMillis;
        }

        /** Newest wins: the later capture time, and on a tie the greater capture id (deterministic). */
        boolean isNewerThan(long otherCapturedAtMillis, String otherCaptureId) {
            if (capturedAtMillis != otherCapturedAtMillis) {
                return capturedAtMillis > otherCapturedAtMillis;
            }
            return captureId.compareTo(otherCaptureId) > 0;
        }
    }

    /**
     * Every active pointer. Pointers written before the invariant carry only capture id + processing key; their
     * source id and capture time come from the capture file.
     */
    private List<ActivePointer> readActivePointers() {
        List<ActivePointer> pointers = new ArrayList<ActivePointer>();
        for (File f : sortedPropertyFiles(new File(root, "active"))) {
            Properties p = read(f);
            String captureId = p.getProperty("captureId", "");
            String sourceId = p.getProperty("sourceId");
            String capturedAt = p.getProperty("capturedAtMillis");
            if (sourceId == null || capturedAt == null) {
                File captureFile = new File(new File(root, "captures"), hash(captureId) + ".properties");
                Properties capture = captureFile.isFile() ? read(captureFile) : new Properties();
                sourceId = capture.getProperty("sourceId", "");
                capturedAt = capture.getProperty("capturedAtMillis");
            }
            pointers.add(new ActivePointer(f, captureId, p.getProperty("processingKey", ""), sourceId,
                    parseLong(capturedAt)));
        }
        return pointers;
    }

    /** The newest active pointer of every source (pointers without a source id are never grouped). */
    private static Map<String, ActivePointer> newestBySource(List<ActivePointer> pointers) {
        Map<String, ActivePointer> newest = new TreeMap<String, ActivePointer>();
        for (ActivePointer pointer : pointers) {
            if (pointer.sourceId.isEmpty()) {
                continue;
            }
            ActivePointer known = newest.get(pointer.sourceId);
            if (known == null || pointer.isNewerThan(known.capturedAtMillis, known.captureId)) {
                newest.put(pointer.sourceId, pointer);
            }
        }
        return newest;
    }

    /**
     * Keep exactly one active capture per source — the newest — and retire the active pointer of every older one
     * (noted under {@code superseded/}). Returns the pointers that stay active.
     */
    private List<ActivePointer> enforceOneActiveCapturePerSource(List<ActivePointer> pointers) {
        Map<String, ActivePointer> newest = newestBySource(pointers);
        List<ActivePointer> kept = new ArrayList<ActivePointer>();
        for (ActivePointer pointer : pointers) {
            ActivePointer winner = newest.get(pointer.sourceId);
            if (winner == null || winner == pointer) {
                kept.add(pointer);
                continue;
            }
            markSuperseded(pointer.captureId, pointer.sourceId, winner.captureId);
            try {
                Files.deleteIfExists(pointer.file.toPath());
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot retire the active pointer of capture "
                        + pointer.captureId, ex);
            }
            System.err.println("[research-knowledge] capture " + pointer.captureId + " of source "
                    + pointer.sourceId + " superseded by the newer capture " + winner.captureId);
        }
        return kept;
    }

    private void markSuperseded(String captureId, String sourceId, String supersededBy) {
        writeIfChanged(new File(new File(root, "superseded"), hash(captureId) + ".properties"),
                "captureId=" + escape(captureId) + "\nsourceId=" + escape(sourceId)
                        + "\nsupersededBy=" + escape(supersededBy) + "\n");
    }

    /**
     * Write ONE capture's generation (sentences + passages + manifest) fully, THEN atomically swap the active
     * pointer to it — the single commit point that makes the whole capture's derived data active at once.
     */
    private void commitGeneration(SourceCapture capture, List<Sentence> sentences, List<Passage> passages) {
        List<Passage> p = passages == null ? new ArrayList<Passage>() : passages;
        List<Sentence> s = sentences == null ? new ArrayList<Sentence>() : sentences;
        String segVersion = "";
        String fingerprint = "";
        String languageCode = "";
        boolean identitySeen = false;
        for (Passage passage : p) {
            if (!identitySeen) {
                segVersion = passage.getSegmentationPipelineVersion();
                fingerprint = passage.getEmbeddingFingerprint();
                languageCode = passage.getLanguageCode();
                identitySeen = true;
            } else if (!segVersion.equals(passage.getSegmentationPipelineVersion())
                    || !fingerprint.equals(passage.getEmbeddingFingerprint())
                    || !languageCode.equals(passage.getLanguageCode())) {
                throw new IllegalArgumentException("capture " + capture.getCaptureId()
                        + " has passages from more than one derivation identity in a single save");
            }
        }
        String processingKey = processingKey(capture.getCaptureId(), segVersion, fingerprint, languageCode);
        File genDir = new File(new File(new File(root, "derived"), hash(capture.getCaptureId())), processingKey);

        // 1. the whole generation is written first (order does not matter — the pointer is the commit).
        writeIfChanged(new File(genDir, "manifest.properties"),
                "captureId=" + escape(capture.getCaptureId())
                        + "\nsegmentationPipelineVersion=" + escape(segVersion)
                        + "\nembeddingFingerprint=" + escape(fingerprint)
                        + "\nlanguageCode=" + escape(languageCode)
                        + "\nsentenceCount=" + s.size() + "\npassageCount=" + p.size() + "\n");
        writeIfChanged(new File(genDir, "sentences.properties"),
                serializeSentences(capture.getCaptureId(), s));
        writeIfChanged(new File(genDir, "passages.properties"), serializePassages(p));

        // 2. ONLY now: atomically activate this generation (a crash before this keeps the previous one active).
        atomicWrite(new File(new File(root, "active"), hash(capture.getCaptureId()) + ".properties"),
                "captureId=" + escape(capture.getCaptureId()) + "\nprocessingKey=" + processingKey
                        + "\nsourceId=" + escape(capture.getSourceId())
                        + "\ncapturedAtMillis=" + capture.getCapturedAtMillis() + "\n");
        // A capture that becomes active again (it is the source's newest) is no longer superseded.
        try {
            Files.deleteIfExists(new File(new File(root, "superseded"), hash(capture.getCaptureId())
                    + ".properties").toPath());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot clear the superseded note of capture "
                    + capture.getCaptureId(), ex);
        }
    }

    // ------------------------------------------------------------------ serialization (write)

    private static String serializeCapture(SourceCapture c) {
        StringBuilder sb = new StringBuilder();
        line(sb, "captureId", c.getCaptureId());
        line(sb, "sourceId", c.getSourceId());
        line(sb, "canonicalUrl", c.getCanonicalUrl());
        line(sb, "capturedAtMillis", Long.toString(c.getCapturedAtMillis()));
        line(sb, "checksum", c.getChecksum());
        line(sb, "title", c.getTitle());
        line(sb, "author", c.getAuthor());
        line(sb, "blockCount", Integer.toString(c.getBlocks().size()));
        for (int i = 0; i < c.getBlocks().size(); i++) {
            SourceCapture.StructuralBlock b = c.getBlocks().get(i);
            line(sb, "block." + i + ".blockId", b.getBlockId());
            line(sb, "block." + i + ".kind", b.getKind().name());
            line(sb, "block." + i + ".headingPath", b.getHeadingPath());
            line(sb, "block." + i + ".text", b.getText());
        }
        return sb.toString();
    }

    private static String serializeSentences(String captureId, List<Sentence> sentences) {
        StringBuilder sb = new StringBuilder();
        line(sb, "captureId", captureId);
        line(sb, "sentenceCount", Integer.toString(sentences.size()));
        for (int i = 0; i < sentences.size(); i++) {
            Sentence s = sentences.get(i);
            line(sb, "s." + i + ".id", s.getSentenceId());
            line(sb, "s." + i + ".blockId", s.getBlockId());
            line(sb, "s." + i + ".ordinal", Integer.toString(s.getOrdinal()));
            line(sb, "s." + i + ".text", s.getText());
        }
        return sb.toString();
    }

    private static String serializePassages(List<Passage> passages) {
        StringBuilder sb = new StringBuilder();
        line(sb, "passageCount", Integer.toString(passages.size()));
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            line(sb, "p." + i + ".id", p.getPassageId());
            line(sb, "p." + i + ".captureId", p.getCaptureId());
            line(sb, "p." + i + ".headingPath", p.getHeadingPath());
            line(sb, "p." + i + ".embeddingFingerprint", p.getEmbeddingFingerprint());
            line(sb, "p." + i + ".segmentationPipelineVersion", p.getSegmentationPipelineVersion());
            line(sb, "p." + i + ".languageCode", p.getLanguageCode());
            line(sb, "p." + i + ".sentenceIds", join(p.getSentenceIds()));
            line(sb, "p." + i + ".text", p.getText());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ deserialization (read)

    private static SourceCapture readCapture(Properties p) {
        int blockCount = parseInt(p.getProperty("blockCount"), 0);
        List<SourceCapture.StructuralBlock> blocks = new ArrayList<SourceCapture.StructuralBlock>();
        for (int i = 0; i < blockCount; i++) {
            blocks.add(new SourceCapture.StructuralBlock(
                    p.getProperty("block." + i + ".blockId", ""),
                    parseKind(p.getProperty("block." + i + ".kind")),
                    p.getProperty("block." + i + ".headingPath", ""),
                    p.getProperty("block." + i + ".text", "")));
        }
        return new SourceCapture(p.getProperty("captureId", ""), p.getProperty("sourceId", ""),
                p.getProperty("canonicalUrl", ""), parseLong(p.getProperty("capturedAtMillis")),
                p.getProperty("checksum", ""), p.getProperty("title", ""), p.getProperty("author", ""),
                blocks);
    }

    private static List<Sentence> readSentences(Properties p) {
        String captureId = p.getProperty("captureId", "");
        int count = parseInt(p.getProperty("sentenceCount"), 0);
        List<Sentence> sentences = new ArrayList<Sentence>();
        for (int i = 0; i < count; i++) {
            sentences.add(new Sentence(p.getProperty("s." + i + ".id", ""), captureId,
                    p.getProperty("s." + i + ".blockId", ""),
                    parseInt(p.getProperty("s." + i + ".ordinal"), 0), p.getProperty("s." + i + ".text", "")));
        }
        return sentences;
    }

    private static List<Passage> readPassages(Properties p) {
        int count = parseInt(p.getProperty("passageCount"), 0);
        List<Passage> passages = new ArrayList<Passage>();
        for (int i = 0; i < count; i++) {
            passages.add(new Passage(p.getProperty("p." + i + ".id", ""),
                    p.getProperty("p." + i + ".captureId", ""), split(p.getProperty("p." + i + ".sentenceIds")),
                    p.getProperty("p." + i + ".headingPath", ""), p.getProperty("p." + i + ".text", ""),
                    p.getProperty("p." + i + ".embeddingFingerprint", ""),
                    p.getProperty("p." + i + ".segmentationPipelineVersion", ""),
                    p.getProperty("p." + i + ".languageCode", ""))); // "" on legacy files (pre-language)
        }
        return passages;
    }

    // ------------------------------------------------------------------ grouping + identity

    private static Map<String, List<Sentence>> groupSentencesByCapture(ResearchProject project) {
        Map<String, List<Sentence>> byCapture = new TreeMap<String, List<Sentence>>();
        for (Sentence s : project.sentences().values()) {
            List<Sentence> list = byCapture.get(s.getCaptureId());
            if (list == null) {
                list = new ArrayList<Sentence>();
                byCapture.put(s.getCaptureId(), list);
            }
            list.add(s);
        }
        return byCapture;
    }

    private static Map<String, List<Passage>> groupPassagesByCapture(ResearchProject project) {
        Map<String, List<Passage>> byCapture = new TreeMap<String, List<Passage>>();
        for (Passage p : project.passages().values()) {
            List<Passage> list = byCapture.get(p.getCaptureId());
            if (list == null) {
                list = new ArrayList<Passage>();
                byCapture.put(p.getCaptureId(), list);
            }
            list.add(p);
        }
        return byCapture;
    }

    /**
     * The active-generation key: a stable hash of the FULL derivation identity (no filename collisions).
     * The LANGUAGE is part of the identity — the same capture segmented under a different sentence-model
     * language is a DIFFERENT derivation, never a collision on one generation. An empty language (legacy
     * passages written before the language joined the world) normalizes to the documented default "en".
     * This is the ONE key computation; every co-located artifact derives from it.
     */
    private static String processingKey(String captureId, String segmentationVersion, String fingerprint,
                                        String languageCode) {
        return sha256Hex(captureId + " " + segmentationVersion + " " + fingerprint + " "
                + normalizeLanguage(languageCode));
    }

    /** Identity normalization: null/empty → the documented default "en"; otherwise the trimmed code. */
    static String normalizeLanguage(String languageCode) {
        return languageCode == null || languageCode.trim().isEmpty() ? "en" : languageCode.trim();
    }

    private static String hash(String value) {
        return sha256Hex(value == null ? "" : value);
    }

    /**
     * The on-disk generation directory for a capture's derivation identity — the SINGLE source of the layout, so
     * co-located canonical artifacts (e.g. the passage vectors) land in exactly the same generation the active
     * pointer will point at. Package-visible for {@link FilePassageVectorStore}; the key computation stays here
     * so it can never drift from {@link #commitGeneration}.
     */
    static File generationDir(File projectDirectory, String captureId, String segmentationVersion,
                              String fingerprint, String languageCode) {
        File base = new File(projectDirectory, "knowledge");
        return new File(new File(new File(base, "derived"), hash(captureId)),
                processingKey(captureId, segmentationVersion, fingerprint, languageCode));
    }

    // ------------------------------------------------------------------ IO helpers

    private static void writeIfChanged(File target, String content) {
        try {
            if (target.isFile() && content.equals(new String(Files.readAllBytes(target.toPath()), UTF8))) {
                return;
            }
        } catch (IOException ignored) {
            // fall through to a fresh write
        }
        atomicWrite(target, content);
    }

    private static void atomicWrite(File target, String content) {
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            File tmp = new File(parent, target.getName() + ".tmp");
            Files.write(tmp.toPath(), content.getBytes(UTF8));
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot persist " + target.getName(), ex);
        }
    }

    private static Properties read(File f) {
        Properties p = new Properties();
        try {
            p.putAll(Utf8Properties.load(f));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + f.getAbsolutePath(), ex);
        }
        return p;
    }

    private static File[] sortedPropertyFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return new File[0];
        }
        List<File> kept = new ArrayList<File>();
        for (File f : files) {
            if (f.getName().endsWith(".properties")) {
                kept.add(f);
            }
        }
        File[] out = kept.toArray(new File[0]);
        Arrays.sort(out);
        return out;
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(escape(value)).append('\n');
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static SourceCapture.BlockKind parseKind(String v) {
        try {
            return v == null ? SourceCapture.BlockKind.PARAGRAPH : SourceCapture.BlockKind.valueOf(v.trim());
        } catch (IllegalArgumentException ex) {
            return SourceCapture.BlockKind.PARAGRAPH;
        }
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0L : Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
