package com.aresstack.askai.java8.hf.meta;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The full audit trail of one Hugging Face → Ollama import, persisted as a <em>separate</em> sidecar
 * ({@code <model>.askai-provenance.json}, schema version 4) purely for repeatability, diagnosis and
 * traceability. It records the repository provenance, a per-field source/confidence ledger and a copy of
 * exactly what was sent to {@code /api/create}.
 *
 * <p>This file must never feed the installed-model display — Ollama's {@code /api/tags} and
 * {@code /api/show} remain the only source of truth for installed models.</p>
 */
public final class HuggingFaceImportProvenance {

    private static final String SUFFIX = ".askai-provenance.json";
    private static final int SCHEMA_VERSION = 4;

    private final Map<String, Object> document;

    public HuggingFaceImportProvenance(Map<String, Object> document) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        copy.put("schemaVersion", SCHEMA_VERSION);
        if (document != null) {
            copy.putAll(document);
        }
        this.document = copy;
    }

    public Map<String, Object> asMap() {
        return new LinkedHashMap<String, Object>(document);
    }

    public static File sidecarFile(File modelFile) {
        return new File(modelFile.getParentFile(), modelFile.getName() + SUFFIX);
    }

    /** Writes the provenance sidecar next to {@code modelFile}, atomically. */
    public void writeSidecar(File modelFile) throws IOException {
        File target = sidecarFile(modelFile);
        File directory = target.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create directory for provenance sidecar: " + directory);
        }
        File temp = File.createTempFile(modelFile.getName() + ".", ".askai-provenance.tmp", directory);
        try {
            Files.write(temp.toPath(), SimpleJson.toJson(document).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }
}
