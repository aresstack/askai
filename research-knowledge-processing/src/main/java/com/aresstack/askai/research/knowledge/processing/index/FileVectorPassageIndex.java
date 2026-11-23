package com.aresstack.askai.research.knowledge.processing.index;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.VectorMath;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A persistent BRUTE-FORCE cosine vector index — deliberately simple (no HNSW/ANN/sidecar): for a few hundred to
 * a few thousand passages, an exact linear scan is correct, rebuildable and easy to reason about. One binary file
 * per semantic namespace ({@code embeddingFingerprint}) under {@code <project>/indexes/knowledge/vectors/}, kept
 * strictly separate from the canonical {@code knowledge/} store. Vectors of different fingerprints/dimensions live
 * in different files and are NEVER compared (cosine via {@link VectorMath}, which enforces exactly that and
 * self-normalizes — no assumption of unit vectors, zero-norm treated as invalid).
 *
 * <p>Writes are idempotent upserts keyed by {@code passageId}; {@link #replaceForCapture} swaps a capture's whole
 * passage set (supersession). This is the semantic half of {@code SemanticKnowledgeIndex}; the text half is
 * Lucene.</p>
 */
public final class FileVectorPassageIndex {

    private static final int MAGIC = 0x414B5649; // "AKVI"
    private static final int FORMAT_VERSION = 1;

    private final File vectorsRoot;

    public FileVectorPassageIndex(File projectDirectory) {
        this.vectorsRoot = new File(new File(new File(projectDirectory, "indexes"), "knowledge"), "vectors");
    }

    /** Upsert documents into their own namespaces (grouped by fingerprint), idempotent per passage id. */
    public void upsert(Collection<PassageIndexDocument> documents) {
        for (Map.Entry<String, List<PassageIndexDocument>> byFp : groupByFingerprint(documents).entrySet()) {
            Map<String, Entry> merged = load(byFp.getKey());
            for (PassageIndexDocument d : byFp.getValue()) {
                merged.put(d.getPassageId(), Entry.of(d));
            }
            write(byFp.getKey(), merged);
        }
    }

    /** Replace ALL of a capture's passages in one namespace with {@code documents} (supersession). */
    public void replaceForCapture(String embeddingFingerprint, String captureId,
                                  Collection<PassageIndexDocument> documents) {
        Map<String, Entry> merged = load(embeddingFingerprint);
        List<String> toRemove = new ArrayList<String>();
        for (Entry e : merged.values()) {
            if (e.captureId.equals(captureId == null ? "" : captureId)) {
                toRemove.add(e.passageId);
            }
        }
        if (toRemove.isEmpty() && (documents == null || documents.isEmpty())) {
            return; // nothing of the capture is indexed and nothing comes: the namespace file is not rewritten
        }
        for (String id : toRemove) {
            merged.remove(id);
        }
        for (PassageIndexDocument d : documents == null ? Collections.<PassageIndexDocument>emptyList()
                : documents) {
            if (!embeddingFingerprint.equals(d.getEmbeddingFingerprint())) {
                throw new IllegalArgumentException("document fingerprint " + d.getEmbeddingFingerprint()
                        + " does not match the namespace " + embeddingFingerprint);
            }
            merged.put(d.getPassageId(), Entry.of(d));
        }
        write(embeddingFingerprint, merged);
    }

    /** Cosine search within one namespace; top {@code limit} by descending score (ties: passage id). */
    public List<PassageSearchHit> search(String embeddingFingerprint, float[] queryVector, int limit) {
        Map<String, Entry> namespace = load(embeddingFingerprint);
        EmbeddingPort.EmbeddingVector query =
                new EmbeddingPort.EmbeddingVector("", embeddingFingerprint, queryVector);
        List<PassageSearchHit> hits = new ArrayList<PassageSearchHit>();
        for (Entry e : namespace.values()) {
            EmbeddingPort.EmbeddingVector doc =
                    new EmbeddingPort.EmbeddingVector("", embeddingFingerprint, e.vector);
            // VectorMath enforces same fingerprint + dimension (a cross-world query is a hard error).
            double score = VectorMath.cosine(query, doc);
            hits.add(new PassageSearchHit(e.passageId, e.captureId, e.sourceId, e.text, e.headingPath, score));
        }
        Collections.sort(hits, new Comparator<PassageSearchHit>() {
            public int compare(PassageSearchHit a, PassageSearchHit b) {
                int byScore = Double.compare(b.getScore(), a.getScore());
                return byScore != 0 ? byScore : a.getPassageId().compareTo(b.getPassageId());
            }
        });
        return hits.size() > limit ? new ArrayList<PassageSearchHit>(hits.subList(0, limit)) : hits;
    }

    /** Rebuild the vector index from scratch for the given documents (index loss / schema change recovery). */
    public void rebuild(Collection<PassageIndexDocument> documents) {
        removeAll();
        upsert(documents);
    }

    /** Delete every namespace file (the whole vector index of this project). */
    public void removeAll() {
        File[] files = vectorsRoot.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    f.delete();
                }
            }
        }
    }

    // ------------------------------------------------------------------ namespace file IO

    private File namespaceFile(String fingerprint) {
        return new File(vectorsRoot, FileNaming.sha256Hex(fingerprint) + ".bin");
    }

    private Map<String, Entry> load(String fingerprint) {
        File file = namespaceFile(fingerprint);
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        if (!file.isFile()) {
            return map;
        }
        try {
            DataInputStream in = new DataInputStream(
                    new java.io.BufferedInputStream(new java.io.FileInputStream(file)));
            try {
                if (in.readInt() != MAGIC) {
                    throw new IllegalStateException("not a vector index file: " + file);
                }
                int version = in.readInt();
                if (version != FORMAT_VERSION) {
                    throw new IllegalStateException("unsupported vector index version " + version);
                }
                in.readUTF(); // fingerprint (kept for self-description; the file name already namespaces it)
                int dimension = in.readInt();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    String passageId = in.readUTF();
                    String captureId = in.readUTF();
                    String sourceId = in.readUTF();
                    String headingPath = in.readUTF();
                    String text = in.readUTF();
                    float[] vector = new float[dimension];
                    for (int d = 0; d < dimension; d++) {
                        vector[d] = in.readFloat();
                    }
                    map.put(passageId, new Entry(passageId, captureId, sourceId, headingPath, text, vector));
                }
            } finally {
                in.close();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read vector index " + file, ex);
        }
        return map;
    }

    private void write(String fingerprint, Map<String, Entry> entries) {
        if (entries.isEmpty()) {
            namespaceFile(fingerprint).delete();
            return;
        }
        int dimension = entries.values().iterator().next().vector.length;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(fingerprint);
            out.writeInt(dimension);
            out.writeInt(entries.size());
            for (Entry e : entries.values()) {
                if (e.vector.length != dimension) {
                    throw new IllegalArgumentException("namespace " + fingerprint
                            + " mixes vector dimensions");
                }
                out.writeUTF(e.passageId);
                out.writeUTF(e.captureId);
                out.writeUTF(e.sourceId);
                out.writeUTF(e.headingPath);
                out.writeUTF(e.text);
                for (float v : e.vector) {
                    out.writeFloat(v);
                }
            }
            out.flush();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot serialize vector index", ex);
        }
        FileNaming.atomicWrite(namespaceFile(fingerprint), bytes.toByteArray());
    }

    private static Map<String, List<PassageIndexDocument>> groupByFingerprint(
            Collection<PassageIndexDocument> documents) {
        Map<String, List<PassageIndexDocument>> byFp = new LinkedHashMap<String, List<PassageIndexDocument>>();
        if (documents != null) {
            for (PassageIndexDocument d : documents) {
                List<PassageIndexDocument> list = byFp.get(d.getEmbeddingFingerprint());
                if (list == null) {
                    list = new ArrayList<PassageIndexDocument>();
                    byFp.put(d.getEmbeddingFingerprint(), list);
                }
                list.add(d);
            }
        }
        return byFp;
    }

    /** The fingerprints (namespaces) currently present on disk (diagnostics/tests). */
    public Set<String> namespaceFileNames() {
        Set<String> names = new TreeSet<String>();
        File[] files = vectorsRoot.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".bin")) {
                    names.add(f.getName());
                }
            }
        }
        return names;
    }

    private static final class Entry {
        final String passageId;
        final String captureId;
        final String sourceId;
        final String headingPath;
        final String text;
        final float[] vector;

        Entry(String passageId, String captureId, String sourceId, String headingPath, String text,
              float[] vector) {
            this.passageId = passageId;
            this.captureId = captureId;
            this.sourceId = sourceId;
            this.headingPath = headingPath;
            this.text = text;
            this.vector = vector;
        }

        static Entry of(PassageIndexDocument d) {
            return new Entry(d.getPassageId(), d.getCaptureId(), d.getSourceId(), d.getHeadingPath(),
                    d.getText(), d.getEmbedding());
        }
    }
}
