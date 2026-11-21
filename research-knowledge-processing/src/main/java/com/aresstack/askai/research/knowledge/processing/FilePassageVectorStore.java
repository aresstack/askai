package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The productive {@link PassageVectorStore}: writes the final passage vectors as an EXPLICIT binary artifact
 * {@code vectors.bin} inside the capture's processing generation directory (the same one that holds
 * {@code sentences.properties}/{@code passages.properties}), so a generation carries its vectors canonically and
 * the semantic index is rebuildable without re-embedding. NOT Java serialization — a defined, versioned format.
 *
 * <p>Layout (big-endian, {@link DataOutputStream}):
 * <pre>
 *   int    MAGIC (0x414B5657 "AKVW")
 *   int    formatVersion
 *   UTF    embeddingFingerprint
 *   int    dimension
 *   int    vectorCount
 *   repeat vectorCount:
 *       UTF     passageId
 *       float   value[dimension]
 * </pre>
 * Entries are written in ascending passageId order so the bytes are deterministic (idempotent re-writes) and a
 * reader can rely on explicit ids regardless. The write is atomic (tmp + move); an identical file is not
 * rewritten (no churn, no stray {@code .tmp}).</p>
 */
public final class FilePassageVectorStore implements PassageVectorStore {

    private static final int MAGIC = 0x414B5657;
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_NAME = "vectors.bin";

    private final File projectDirectory;

    public FilePassageVectorStore(File projectDirectory) {
        this.projectDirectory = projectDirectory;
    }

    @Override
    public void store(String captureId, String segmentationPipelineVersion, String embeddingFingerprint,
                      String languageCode, Map<String, EmbeddingPort.EmbeddingVector> passageVectors) {
        if (passageVectors == null || passageVectors.isEmpty()) {
            return; // an empty capture / no passages: nothing to persist (and no generation dir either)
        }
        int dimension = validateUniform(embeddingFingerprint, passageVectors);
        byte[] content = serialize(embeddingFingerprint, dimension, passageVectors);
        atomicWriteIfChanged(vectorsFile(captureId, segmentationPipelineVersion, embeddingFingerprint,
                languageCode), content);
    }

    @Override
    public Map<String, float[]> load(String captureId, String segmentationPipelineVersion,
                                     String embeddingFingerprint, String languageCode) {
        Decoded decoded = read(vectorsFile(captureId, segmentationPipelineVersion, embeddingFingerprint,
                languageCode));
        if (decoded == null) {
            return new LinkedHashMap<String, float[]>();
        }
        if (!embeddingFingerprint.equals(decoded.embeddingFingerprint)) {
            throw new IllegalStateException("vectors.bin for capture " + captureId + " holds fingerprint "
                    + decoded.embeddingFingerprint + ", not the requested " + embeddingFingerprint);
        }
        return decoded.vectors;
    }

    @Override
    public List<String> passageIds(String captureId, String segmentationPipelineVersion,
                                   String embeddingFingerprint, String languageCode) {
        Decoded decoded = read(vectorsFile(captureId, segmentationPipelineVersion, embeddingFingerprint,
                languageCode));
        return decoded == null ? new ArrayList<String>() : new ArrayList<String>(decoded.vectors.keySet());
    }

    /**
     * A generation folder of the capture that holds nothing but vectors ({@code vectors.bin}, maybe its temp file)
     * is a leftover: the repository writes sentences, passages and manifest into every generation it commits, and no
     * active pointer can name a folder without them. Such folders are deleted, the capture's other generations stay.
     */
    @Override
    public int removeOrphans(String captureId) {
        File[] generations = FileResearchProjectRepository.derivedDir(projectDirectory, captureId).listFiles();
        if (generations == null) {
            return 0;
        }
        int removed = 0;
        for (File generation : generations) {
            File[] files = generation.listFiles();
            if (files == null || files.length == 0) {
                continue;
            }
            boolean vectorsOnly = true;
            for (File file : files) {
                String name = file.getName();
                if (!file.isFile() || !(name.equals(FILE_NAME) || name.equals(FILE_NAME + ".tmp"))) {
                    vectorsOnly = false;
                    break;
                }
            }
            if (!vectorsOnly) {
                continue;
            }
            try {
                for (File file : files) {
                    Files.deleteIfExists(file.toPath());
                }
                Files.deleteIfExists(generation.toPath());
                removed++;
            } catch (IOException ex) {
                throw new IllegalStateException("cannot remove the leftover vectors " + generation, ex);
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ identity / paths

    private File vectorsFile(String captureId, String segVersion, String fingerprint, String languageCode) {
        return new File(FileResearchProjectRepository.generationDir(projectDirectory, captureId, segVersion,
                fingerprint, languageCode), FILE_NAME);
    }

    private static int validateUniform(String fingerprint,
                                       Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        int dimension = -1;
        for (EmbeddingPort.EmbeddingVector v : vectors.values()) {
            if (!fingerprint.equals(v.getModelFingerprint())) {
                throw new IllegalArgumentException("vector fingerprint " + v.getModelFingerprint()
                        + " does not match the generation fingerprint " + fingerprint);
            }
            if (dimension < 0) {
                dimension = v.getDimension();
            } else if (dimension != v.getDimension()) {
                throw new IllegalArgumentException("passage vectors of a generation must share one dimension");
            }
        }
        return dimension;
    }

    // ------------------------------------------------------------------ binary codec

    private static byte[] serialize(String fingerprint, int dimension,
                                    Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        // Ascending passageId order → deterministic bytes.
        Map<String, EmbeddingPort.EmbeddingVector> sorted =
                new TreeMap<String, EmbeddingPort.EmbeddingVector>(vectors);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeUTF(fingerprint == null ? "" : fingerprint);
            out.writeInt(dimension);
            out.writeInt(sorted.size());
            for (Map.Entry<String, EmbeddingPort.EmbeddingVector> e : sorted.entrySet()) {
                out.writeUTF(e.getKey());
                float[] values = e.getValue().getValues();
                if (values.length != dimension) {
                    throw new IllegalArgumentException("vector for " + e.getKey() + " has dimension "
                            + values.length + ", expected " + dimension);
                }
                for (float value : values) {
                    out.writeFloat(value);
                }
            }
            out.flush();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot serialize passage vectors", ex);
        }
        return bytes.toByteArray();
    }

    private static Decoded read(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            DataInputStream in = new DataInputStream(openBuffered(file));
            try {
                if (in.readInt() != MAGIC) {
                    throw new IllegalStateException("not a passage vector file: " + file);
                }
                int version = in.readInt();
                if (version != FORMAT_VERSION) {
                    throw new IllegalStateException("unsupported vectors.bin format version " + version
                            + " at " + file);
                }
                String fingerprint = in.readUTF();
                int dimension = in.readInt();
                int count = in.readInt();
                Map<String, float[]> vectors = new LinkedHashMap<String, float[]>();
                for (int i = 0; i < count; i++) {
                    String passageId = in.readUTF();
                    float[] values = new float[dimension];
                    for (int d = 0; d < dimension; d++) {
                        values[d] = in.readFloat();
                    }
                    vectors.put(passageId, values);
                }
                return new Decoded(fingerprint, dimension, vectors);
            } finally {
                in.close();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read passage vectors at " + file, ex);
        }
    }

    private static InputStream openBuffered(File file) throws IOException {
        return new java.io.BufferedInputStream(new java.io.FileInputStream(file));
    }

    private static void atomicWriteIfChanged(File target, byte[] content) {
        try {
            if (target.isFile() && Arrays.equals(content, Files.readAllBytes(target.toPath()))) {
                return; // identical → no churn, no rewrite
            }
            File parent = target.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            File tmp = new File(parent, target.getName() + ".tmp");
            Files.write(tmp.toPath(), content);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot persist passage vectors " + target.getName(), ex);
        }
    }

    private static final class Decoded {
        final String embeddingFingerprint;
        final int dimension;
        final Map<String, float[]> vectors;

        Decoded(String embeddingFingerprint, int dimension, Map<String, float[]> vectors) {
            this.embeddingFingerprint = embeddingFingerprint;
            this.dimension = dimension;
            this.vectors = vectors;
        }
    }
}
