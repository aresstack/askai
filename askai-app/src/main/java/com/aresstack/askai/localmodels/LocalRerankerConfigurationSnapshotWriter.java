package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationValidationResult;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * A5b: publishes a {@link RerankerConfigurationDocument} as an IMMUTABLE on-disk start snapshot the
 * research agent reads once via {@code ASKAI_RERANKER_CONFIG}. The write is atomic and self-checking:
 * the JSON is written to a sibling {@code .tmp} file, decoded back through the strict codec to prove
 * it is well-formed and valid, and only then moved into place (ATOMIC_MOVE, falling back to a plain
 * replacing move on file systems that reject it). A reader therefore never observes a half-written or
 * structurally-invalid snapshot.
 */
public final class LocalRerankerConfigurationSnapshotWriter {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private LocalRerankerConfigurationSnapshotWriter() {
    }

    /**
     * Atomically write the snapshot to {@code target}.
     *
     * @throws IOException if serialization round-trips to an invalid document (a programming error in
     *                     the descriptor) or the file cannot be written/moved
     */
    public static void write(RerankerConfigurationDocument document, File target) throws IOException {
        String json = RerankerEndpointDescriptorCodec.toJson(document);
        RerankerConfigurationValidationResult check = RerankerEndpointDescriptorCodec.parse(json);
        if (!check.valid) {
            throw new IOException("refusing to publish an invalid reranker snapshot:\n"
                    + check.describe());
        }

        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create snapshot directory: " + parent);
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        OutputStream out = new java.io.FileOutputStream(tmp);
        try {
            out.write(json.getBytes(UTF_8));
            out.flush();
        } finally {
            out.close();
        }

        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UnsupportedOperationException atomicUnsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
