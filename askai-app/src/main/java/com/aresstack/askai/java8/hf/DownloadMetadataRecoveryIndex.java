package com.aresstack.askai.java8.hf;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small persistent index that remembers Hugging Face downloads whose install sidecar could not be
 * written, so that a later install (after a restart, with no sidecar next to the file) is not silently
 * degraded to a plain manual GGUF import. Entries are keyed by the file's canonical path and validated by
 * size, and carry everything needed to rebuild the {@link HuggingFaceInstallPlan}.
 *
 * <p>Swing-free and self-contained. All mutations are atomic (temp file + rename).</p>
 */
public final class DownloadMetadataRecoveryIndex {

    /** One remembered download whose sidecar write failed. */
    public static final class Entry {
        private final String file;
        private final long size;
        private final String sha256;
        private final String repositoryId;
        private final String requestedRevision;
        private final String resolvedRevisionSha;
        private final String targetModelName;
        private final String modelType;
        private final List<String> declaredCapabilities;
        private final List<String> requiredOllamaCapabilities;
        private final String status;

        Entry(String file, long size, String sha256, HuggingFaceInstallPlan plan) {
            this.file = file;
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256;
            this.repositoryId = plan.getRepositoryId();
            this.requestedRevision = plan.getRevision();
            this.resolvedRevisionSha = plan.getResolvedRevisionSha();
            this.targetModelName = plan.getTargetModelName();
            this.modelType = plan.getModelType();
            this.declaredCapabilities = plan.getDeclaredCapabilities();
            this.requiredOllamaCapabilities = plan.getRequiredOllamaCapabilities();
            this.status = "SIDECAR_WRITE_FAILED";
        }

        private Entry(Map<?, ?> map) {
            this.file = string(map, "file");
            this.size = longOf(map.get("size"));
            this.sha256 = string(map, "sha256");
            this.repositoryId = string(map, "repositoryId");
            this.requestedRevision = string(map, "requestedRevision");
            this.resolvedRevisionSha = string(map, "resolvedRevisionSha");
            this.targetModelName = string(map, "targetModelName");
            this.modelType = string(map, "modelType");
            this.declaredCapabilities = stringList(map.get("declaredCapabilities"));
            this.requiredOllamaCapabilities = stringList(map.get("requiredOllamaCapabilities"));
            this.status = string(map, "status");
        }

        public String getStatus() {
            return status;
        }

        public long getSize() {
            return size;
        }

        /** @return the install plan reconstructed from this entry. */
        public HuggingFaceInstallPlan toPlan() {
            return new HuggingFaceInstallPlan(repositoryId, requestedRevision, resolvedRevisionSha,
                    targetModelName, declaredCapabilities, requiredOllamaCapabilities, modelType);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("file", file);
            map.put("size", size);
            map.put("sha256", sha256);
            map.put("repositoryId", repositoryId);
            map.put("requestedRevision", requestedRevision);
            map.put("resolvedRevisionSha", resolvedRevisionSha);
            map.put("targetModelName", targetModelName);
            map.put("modelType", modelType);
            map.put("declaredCapabilities", new ArrayList<String>(declaredCapabilities));
            map.put("requiredOllamaCapabilities", new ArrayList<String>(requiredOllamaCapabilities));
            map.put("status", status);
            return map;
        }
    }

    private final File indexFile;

    public DownloadMetadataRecoveryIndex(File indexFile) {
        if (indexFile == null) {
            throw new IllegalArgumentException("indexFile is required");
        }
        this.indexFile = indexFile;
    }

    /** Records (or overwrites) a recovery entry for {@code modelFile}. */
    public synchronized void record(File modelFile, String sha256, HuggingFaceInstallPlan plan) throws IOException {
        String key = key(modelFile);
        Map<String, Object> all = load();
        all.put(key, new Entry(key, modelFile.length(), sha256, plan).toMap());
        save(all);
    }

    /**
     * @return the recovery entry for {@code modelFile} when one exists and still matches the file on disk
     *         (same size), or {@code null}. A size mismatch is treated as stale and dropped.
     */
    public synchronized Entry find(File modelFile) {
        String key = key(modelFile);
        Map<String, Object> all = load();
        Object value = all.get(key);
        if (!(value instanceof Map)) {
            return null;
        }
        Entry entry = new Entry((Map<?, ?>) value);
        if (modelFile.isFile() && entry.size > 0 && modelFile.length() != entry.size) {
            return null; // a different file now sits at this path
        }
        return entry;
    }

    /** Removes any recovery entry for {@code modelFile} (no-op when absent). */
    public synchronized void remove(File modelFile) throws IOException {
        String key = key(modelFile);
        Map<String, Object> all = load();
        if (all.remove(key) != null) {
            save(all);
        }
    }

    private static String key(File modelFile) {
        try {
            return modelFile.getCanonicalPath();
        } catch (IOException ex) {
            return modelFile.getAbsolutePath();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        if (!indexFile.isFile()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            byte[] bytes = Files.readAllBytes(indexFile.toPath());
            Object parsed = SimpleJson.parse(new String(bytes, StandardCharsets.UTF_8));
            if (parsed instanceof Map) {
                return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
            }
        } catch (Exception ignored) {
            // A corrupt index is not fatal — treat it as empty rather than blocking installs.
        }
        return new LinkedHashMap<String, Object>();
    }

    private void save(Map<String, Object> all) throws IOException {
        File directory = indexFile.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory for recovery index: " + directory);
        }
        File temp = File.createTempFile("askai-recovery", ".tmp", directory);
        try {
            Files.write(temp.toPath(), SimpleJson.toJson(all).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), indexFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long longOf(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object element : (List<Object>) value) {
                if (element != null) {
                    result.add(String.valueOf(element));
                }
            }
        }
        return result;
    }
}
