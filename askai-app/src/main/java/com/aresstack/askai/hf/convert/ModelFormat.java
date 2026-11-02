package com.aresstack.askai.hf.convert;

import java.util.Locale;

/**
 * A model weight/serialization format detectable from a repository's file names. Detection is by
 * filename pattern (the authoritative signal per spec §18 — the actual files, not just tags), with
 * mmproj GGUF encoders recognized separately since they are companions, not standalone models.
 */
public enum ModelFormat {

    GGUF("GGUF"),
    SAFETENSORS("Safetensors"),
    ONNX("ONNX"),
    PYTORCH_BIN("PyTorch (.bin)"),
    MLX("MLX"),
    COREML("Core ML"),
    OPENVINO("OpenVINO"),
    TENSORFLOW("TensorFlow"),
    GGML("GGML"),
    OTHER("Other");

    private final String displayName;

    ModelFormat(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return the format a file name indicates, or {@code null} when the file is not a recognized
     *         weight file (config/tokenizer/readme/mmproj/etc. return {@code null}).
     */
    public static ModelFormat fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        // mmproj is a GGUF encoder companion, not a standalone model format — excluded here so a
        // safetensors repo that also ships an mmproj isn't mistaken for a GGUF model.
        if (lower.startsWith("mmproj") && lower.endsWith(".gguf")) {
            return null;
        }
        if (lower.endsWith(".gguf")) {
            return GGUF;
        }
        if (lower.endsWith(".safetensors")) {
            return SAFETENSORS;
        }
        if (lower.endsWith(".onnx")) {
            return ONNX;
        }
        if (lower.startsWith("pytorch_model") && lower.endsWith(".bin")) {
            return PYTORCH_BIN;
        }
        if (lower.endsWith(".mlmodel") || lower.endsWith(".mlpackage")) {
            return COREML;
        }
        if (lower.startsWith("openvino") && (lower.endsWith(".xml") || lower.endsWith(".bin"))) {
            return OPENVINO;
        }
        if (lower.endsWith(".mlx") || lower.endsWith(".npz")) {
            return MLX;
        }
        if (lower.endsWith(".h5") || lower.startsWith("tf_model")) {
            return TENSORFLOW;
        }
        if (lower.endsWith(".ggml") || lower.endsWith(".bin") && lower.startsWith("ggml")) {
            return GGML;
        }
        return null;
    }
}
