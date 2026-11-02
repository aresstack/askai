package com.aresstack.askai.hf.meta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed, immutable set of metadata AskAI wants Ollama to record for an imported model. It is the
 * single place that decides which values are trusted enough to become functional {@code /api/create}
 * fields: only non-empty, sufficiently trusted values are emitted, so an uncertain guess can never
 * overwrite Ollama's own GGUF-derived detection.
 *
 * <p>Every field — including {@code license} and each {@code parameters} entry — carries its source and
 * confidence via {@link MetadataValue}, so nothing bypasses the trust gate.</p>
 *
 * <p>Built through {@link Builder}. Callers that only have a capability list use
 * {@link #ofCapabilities(List)} and get byte-identical wire output to the previous capability-only path.</p>
 */
public final class OllamaCreateMetadata {

    private final List<String> capabilities;
    private final MetadataValue<String> modelFamily;
    private final MetadataValue<String> baseName;
    private final MetadataValue<String> quantizationLevel;
    private final MetadataValue<String> parameterSize;
    private final MetadataValue<Integer> contextLength;
    private final MetadataValue<Integer> embeddingLength;
    private final MetadataValue<List<String>> license;
    private final Map<String, MetadataValue<Object>> parameters;
    // Top-level B3 fields (never under info). Only tested/safe values are ever set.
    private final MetadataValue<String> template;
    private final MetadataValue<String> renderer;
    private final MetadataValue<String> parser;
    private final MetadataValue<String> requires;
    private final MetadataValue<String> system;
    private final MetadataValue<List<OllamaCreateMessage>> messages;

    private OllamaCreateMetadata(Builder builder) {
        this.capabilities = immutable(builder.capabilities);
        this.modelFamily = builder.modelFamily;
        this.baseName = builder.baseName;
        this.quantizationLevel = builder.quantizationLevel;
        this.parameterSize = builder.parameterSize;
        this.contextLength = builder.contextLength;
        this.embeddingLength = builder.embeddingLength;
        this.license = builder.license;
        this.parameters = builder.parameters == null
                ? Collections.<String, MetadataValue<Object>>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, MetadataValue<Object>>(builder.parameters));
        this.template = builder.template;
        this.renderer = builder.renderer;
        this.parser = builder.parser;
        this.requires = builder.requires;
        this.system = builder.system;
        this.messages = builder.messages;
    }

    /** @return metadata carrying only the given (already normalized) capability tags. */
    public static OllamaCreateMetadata ofCapabilities(List<String> capabilities) {
        return new Builder().capabilities(capabilities).build();
    }

    /** @return metadata with nothing set — a manual GGUF import. */
    public static OllamaCreateMetadata empty() {
        return new Builder().build();
    }

    public List<String> capabilities() {
        return capabilities;
    }

    /**
     * @return a copy of this metadata with its capability list replaced (every other field unchanged). Used
     *         to intersect the declared capabilities with what the installed runtime can actually honour.
     */
    public OllamaCreateMetadata withCapabilities(List<String> newCapabilities) {
        Builder builder = new Builder()
                .capabilities(newCapabilities)
                .modelFamily(modelFamily)
                .baseName(baseName)
                .quantizationLevel(quantizationLevel)
                .parameterSize(parameterSize)
                .contextLength(contextLength)
                .embeddingLength(embeddingLength)
                .license(license)
                .template(template)
                .renderer(renderer)
                .parser(parser)
                .requires(requires)
                .system(system)
                .messages(messages);
        if (!parameters.isEmpty()) {
            builder.parameters(parameters);
        }
        return builder.build();
    }

    public MetadataValue<String> modelFamily() {
        return modelFamily;
    }

    public MetadataValue<String> quantizationLevel() {
        return quantizationLevel;
    }

    /**
     * @return the {@code info} object for {@code /api/create}: capabilities plus every trusted, non-empty
     *         metadata field. Empty when nothing is known (the caller then omits {@code info}).
     */
    public Map<String, Object> toInfoMap() {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        if (!capabilities.isEmpty()) {
            info.put("capabilities", new ArrayList<String>(capabilities));
        }
        putTrustedText(info, "model_family", modelFamily);
        putTrustedText(info, "base_name", baseName);
        putTrustedText(info, "quantization_level", quantizationLevel);
        putTrustedText(info, "parameter_size", parameterSize);
        putTrustedPositiveInt(info, "context_length", contextLength);
        putTrustedPositiveInt(info, "embedding_length", embeddingLength);
        return info;
    }

    /** @return the trusted, non-empty license lines to send as top-level {@code license}, else empty. */
    public List<String> licenses() {
        if (license == null || !license.isTrusted(false) || license.value() == null) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<String>();
        for (String line : license.value()) {
            if (line != null && line.trim().length() > 0) {
                cleaned.add(line);
            }
        }
        return Collections.unmodifiableList(cleaned);
    }

    /** @return the trusted {@code parameters} entries (unwrapped) to send as top-level {@code parameters}. */
    public Map<String, Object> parameters() {
        Map<String, Object> trusted = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, MetadataValue<Object>> entry : parameters.entrySet()) {
            MetadataValue<Object> value = entry.getValue();
            if (value != null && value.isTrusted(false) && value.value() != null) {
                trusted.put(entry.getKey(), value.value());
            }
        }
        return trusted;
    }

    /** @return the trusted top-level {@code template}, or "" to omit it. */
    public String template() {
        return trustedText(template);
    }

    /** @return the trusted top-level {@code renderer}, or "" to omit it. */
    public String renderer() {
        return trustedText(renderer);
    }

    /** @return the trusted top-level {@code parser}, or "" to omit it. */
    public String parser() {
        return trustedText(parser);
    }

    /** @return the trusted top-level {@code requires}, or "" to omit it. */
    public String requires() {
        return trustedText(requires);
    }

    /** @return the trusted top-level {@code system} prompt, or "" to omit it. */
    public String system() {
        return trustedText(system);
    }

    /** @return the trusted top-level {@code messages}, or empty to omit them. */
    public List<OllamaCreateMessage> messages() {
        if (messages == null || !messages.isTrusted(false) || messages.value() == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<OllamaCreateMessage>(messages.value()));
    }

    /** @return true when nothing at all would be written — a plain manual import. */
    public boolean isEmpty() {
        return toInfoMap().isEmpty() && licenses().isEmpty() && parameters().isEmpty()
                && template().isEmpty() && renderer().isEmpty() && parser().isEmpty()
                && requires().isEmpty() && system().isEmpty() && messages().isEmpty();
    }

    private static String trustedText(MetadataValue<String> value) {
        if (value == null || !value.isTrusted(false) || value.value() == null) {
            return "";
        }
        return value.value().trim();
    }

    private static void putTrustedText(Map<String, Object> info, String key, MetadataValue<String> value) {
        if (value != null && value.isTrusted(false) && value.value() != null && value.value().trim().length() > 0) {
            info.put(key, value.value().trim());
        }
    }

    private static void putTrustedPositiveInt(Map<String, Object> info, String key,
                                              MetadataValue<Integer> value) {
        if (value != null && value.isTrusted(false) && value.value() != null && value.value() > 0) {
            info.put(key, value.value());
        }
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private List<String> capabilities = Collections.emptyList();
        private MetadataValue<String> modelFamily;
        private MetadataValue<String> baseName;
        private MetadataValue<String> quantizationLevel;
        private MetadataValue<String> parameterSize;
        private MetadataValue<Integer> contextLength;
        private MetadataValue<Integer> embeddingLength;
        private MetadataValue<List<String>> license;
        private Map<String, MetadataValue<Object>> parameters;
        private MetadataValue<String> template;
        private MetadataValue<String> renderer;
        private MetadataValue<String> parser;
        private MetadataValue<String> requires;
        private MetadataValue<String> system;
        private MetadataValue<List<OllamaCreateMessage>> messages;

        public Builder capabilities(List<String> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder modelFamily(MetadataValue<String> value) {
            this.modelFamily = value;
            return this;
        }

        public Builder baseName(MetadataValue<String> value) {
            this.baseName = value;
            return this;
        }

        public Builder quantizationLevel(MetadataValue<String> value) {
            this.quantizationLevel = value;
            return this;
        }

        public Builder parameterSize(MetadataValue<String> value) {
            this.parameterSize = value;
            return this;
        }

        public Builder contextLength(MetadataValue<Integer> value) {
            this.contextLength = value;
            return this;
        }

        public Builder embeddingLength(MetadataValue<Integer> value) {
            this.embeddingLength = value;
            return this;
        }

        public Builder license(MetadataValue<List<String>> value) {
            this.license = value;
            return this;
        }

        public Builder parameters(Map<String, MetadataValue<Object>> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder template(MetadataValue<String> value) {
            this.template = value;
            return this;
        }

        public Builder renderer(MetadataValue<String> value) {
            this.renderer = value;
            return this;
        }

        public Builder parser(MetadataValue<String> value) {
            this.parser = value;
            return this;
        }

        public Builder requires(MetadataValue<String> value) {
            this.requires = value;
            return this;
        }

        public Builder system(MetadataValue<String> value) {
            this.system = value;
            return this;
        }

        public Builder messages(MetadataValue<List<OllamaCreateMessage>> value) {
            this.messages = value;
            return this;
        }

        public OllamaCreateMetadata build() {
            return new OllamaCreateMetadata(this);
        }
    }
}
