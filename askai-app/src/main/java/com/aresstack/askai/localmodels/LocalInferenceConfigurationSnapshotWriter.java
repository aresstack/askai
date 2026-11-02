package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationCodec;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationValidationResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.File;

/**
 * Writes an {@link InferenceConfigurationDocument} to {@code inference-config.json} atomically: serialize →
 * re-parse to self-check → write a sibling {@code .tmp} → ATOMIC_MOVE onto the target (plain replace
 * fallback). A document that would not decode back is refused rather than published. Mirrors the reranker
 * snapshot writer.
 */
public final class LocalInferenceConfigurationSnapshotWriter {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private LocalInferenceConfigurationSnapshotWriter() {
    }

    public static void write(InferenceConfigurationDocument document, File target) throws IOException {
        String json = InferenceConfigurationCodec.toJson(document);
        InferenceConfigurationValidationResult check = InferenceConfigurationCodec.parse(json);
        if (!check.valid) {
            throw new IOException("refusing to publish an invalid inference snapshot:\n" + check.describe());
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
