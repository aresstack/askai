package com.aresstack.askai.hf.convert;

import com.aresstack.askai.hf.HuggingFaceModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What is known about one repository for import-support classification: the file names present, the
 * weight formats detected from them, and (for formats that need it, e.g. Safetensors) the model
 * architecture read from {@code config.json}. Immutable.
 *
 * <p>An analysis is either <em>verified</em> (built by {@link RepositoryAnalyzer} from the real file
 * tree + config.json) or <em>provisional</em> ({@link #provisionalFrom} — guessed from the search
 * row's {@code library_name}/tags, no network), so the UI can distinguish a checked verdict from a
 * fast placeholder.</p>
 */
public final class RepositoryAnalysis {

    private final String modelId;
    private final List<String> files;
    private final Set<ModelFormat> formats;
    private final List<String> architectures;
    private final String modelType;
    private final boolean hasConfigJson;
    private final boolean hasTokenizer;
    private final boolean hasMmproj;
    private final boolean configReadable;
    private final boolean verified;

    private RepositoryAnalysis(Builder builder) {
        this.modelId = builder.modelId == null ? "" : builder.modelId;
        this.files = Collections.unmodifiableList(new ArrayList<String>(builder.files));
        this.formats = Collections.unmodifiableSet(builder.formats.isEmpty()
                ? EnumSet.noneOf(ModelFormat.class) : EnumSet.copyOf(builder.formats));
        this.architectures = Collections.unmodifiableList(new ArrayList<String>(builder.architectures));
        this.modelType = builder.modelType == null ? "" : builder.modelType;
        this.hasConfigJson = builder.hasConfigJson;
        this.hasTokenizer = builder.hasTokenizer;
        this.hasMmproj = builder.hasMmproj;
        this.configReadable = builder.configReadable;
        this.verified = builder.verified;
    }

    public String getModelId() {
        return modelId;
    }

    public List<String> getFiles() {
        return files;
    }

    public Set<ModelFormat> getFormats() {
        return formats;
    }

    public boolean hasFormat(ModelFormat format) {
        return formats.contains(format);
    }

    /** @return the architecture class names from config.json (e.g. "Qwen2ForCausalLM"), or empty. */
    public List<String> getArchitectures() {
        return architectures;
    }

    public String getModelType() {
        return modelType;
    }

    public boolean hasConfigJson() {
        return hasConfigJson;
    }

    public boolean hasTokenizer() {
        return hasTokenizer;
    }

    public boolean hasMmproj() {
        return hasMmproj;
    }

    /** @return whether config.json was actually readable (false when gated/unreadable). */
    public boolean isConfigReadable() {
        return configReadable;
    }

    /** @return true when built from the real tree + config.json, false for a tag-based placeholder. */
    public boolean isVerified() {
        return verified;
    }

    /** @return a short human list of the detected weight formats, or "keine". */
    public String describeFormats() {
        if (formats.isEmpty()) {
            return "keine";
        }
        StringBuilder builder = new StringBuilder();
        for (ModelFormat format : formats) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(format.getDisplayName());
        }
        return builder.toString();
    }

    /**
     * Fast, network-free placeholder from a search row: formats guessed from {@code library_name}
     * and tags only. {@code verified=false}, {@code configReadable=false}.
     */
    public static RepositoryAnalysis provisionalFrom(HuggingFaceModel model) {
        Builder builder = new Builder(model.getId()).verified(false);
        addFormatFromTag(builder, model.getLibraryName());
        List<String> tags = model.getTags();
        for (int i = 0; i < tags.size(); i++) {
            addFormatFromTag(builder, tags.get(i));
        }
        return builder.build();
    }

    private static void addFormatFromTag(Builder builder, String tag) {
        if (tag == null) {
            return;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.equals("gguf")) {
            builder.addFormat(ModelFormat.GGUF);
        } else if (lower.equals("safetensors")) {
            builder.addFormat(ModelFormat.SAFETENSORS);
        } else if (lower.equals("onnx")) {
            builder.addFormat(ModelFormat.ONNX);
        } else if (lower.equals("pytorch")) {
            builder.addFormat(ModelFormat.PYTORCH_BIN);
        } else if (lower.equals("mlx")) {
            builder.addFormat(ModelFormat.MLX);
        } else if (lower.equals("coreml")) {
            builder.addFormat(ModelFormat.COREML);
        } else if (lower.equals("openvino")) {
            builder.addFormat(ModelFormat.OPENVINO);
        } else if (lower.equals("tf") || lower.equals("tf-keras") || lower.equals("keras")) {
            builder.addFormat(ModelFormat.TENSORFLOW);
        }
    }

    public static Builder builder(String modelId) {
        return new Builder(modelId);
    }

    public static final class Builder {
        private final String modelId;
        private final List<String> files = new ArrayList<String>();
        private final Set<ModelFormat> formats = EnumSet.noneOf(ModelFormat.class);
        private final List<String> architectures = new ArrayList<String>();
        private String modelType = "";
        private boolean hasConfigJson;
        private boolean hasTokenizer;
        private boolean hasMmproj;
        private boolean configReadable;
        private boolean verified = true;

        private Builder(String modelId) {
            this.modelId = modelId;
        }

        public Builder addFile(String path) {
            files.add(path);
            return this;
        }

        public Builder addFormat(ModelFormat format) {
            if (format != null) {
                formats.add(format);
            }
            return this;
        }

        public Builder architectures(List<String> value) {
            architectures.clear();
            if (value != null) {
                architectures.addAll(value);
            }
            return this;
        }

        public Builder modelType(String value) {
            this.modelType = value;
            return this;
        }

        public Builder hasConfigJson(boolean value) {
            this.hasConfigJson = value;
            return this;
        }

        public Builder hasTokenizer(boolean value) {
            this.hasTokenizer = value;
            return this;
        }

        public Builder hasMmproj(boolean value) {
            this.hasMmproj = value;
            return this;
        }

        public Builder configReadable(boolean value) {
            this.configReadable = value;
            return this;
        }

        public Builder verified(boolean value) {
            this.verified = value;
            return this;
        }

        public RepositoryAnalysis build() {
            return new RepositoryAnalysis(this);
        }
    }
}
