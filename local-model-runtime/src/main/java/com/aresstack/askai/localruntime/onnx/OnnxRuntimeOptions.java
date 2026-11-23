package com.aresstack.askai.localruntime.onnx;

import java.util.Locale;

/**
 * Immutable settings of the ONNX Runtime backend. Every value is a user setting with a default (see
 * {@link #defaults()}); {@code 0} threads lets ONNX Runtime choose.
 */
public final class OnnxRuntimeOptions {

    /** Default intra-op threads: 0 = ONNX Runtime picks (all physical cores). */
    public static final int DEFAULT_INTRA_OP_THREADS = 0;
    /** Default inter-op threads: 0 = ONNX Runtime picks. */
    public static final int DEFAULT_INTER_OP_THREADS = 0;
    /** Default: install and load the int8-quantized graph when the repository offers one. */
    public static final boolean DEFAULT_PREFER_QUANTIZED = true;
    /** Default texts per inference call. */
    public static final int DEFAULT_BATCH_SIZE = 16;
    /** Default token cap per text; 0 = the model's own limit from the catalog. */
    public static final int DEFAULT_MAX_TOKENS = 0;
    /** Default execution provider: the CPU, which never needs a GPU. */
    public static final ExecutionProvider DEFAULT_EXECUTION_PROVIDER = ExecutionProvider.CPU;

    /** Where ONNX Runtime executes the graph. */
    public enum ExecutionProvider {
        /** Always available. */
        CPU,
        /**
         * DirectML through ONNX Runtime itself (Windows GPU). Only works with an ONNX Runtime build that ships the
         * DirectML provider; otherwise the session falls back to the CPU and logs it.
         */
        DIRECTML;

        public static ExecutionProvider parse(String token) {
            String value = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
            for (ExecutionProvider provider : values()) {
                if (provider.name().equals(value)) {
                    return provider;
                }
            }
            return DEFAULT_EXECUTION_PROVIDER;
        }

        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final int intraOpThreads;
    private final int interOpThreads;
    private final boolean preferQuantized;
    private final int batchSize;
    private final int maxTokens;
    private final ExecutionProvider executionProvider;

    private OnnxRuntimeOptions(Builder builder) {
        this.intraOpThreads = Math.max(0, builder.intraOpThreads);
        this.interOpThreads = Math.max(0, builder.interOpThreads);
        this.preferQuantized = builder.preferQuantized;
        this.batchSize = builder.batchSize > 0 ? builder.batchSize : DEFAULT_BATCH_SIZE;
        this.maxTokens = Math.max(0, builder.maxTokens);
        this.executionProvider = builder.executionProvider == null ? DEFAULT_EXECUTION_PROVIDER
                : builder.executionProvider;
    }

    public static OnnxRuntimeOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int intraOpThreads() {
        return intraOpThreads;
    }

    public int interOpThreads() {
        return interOpThreads;
    }

    public boolean preferQuantized() {
        return preferQuantized;
    }

    public int batchSize() {
        return batchSize;
    }

    /** The configured token cap, or 0 for the model's own limit. */
    public int maxTokens() {
        return maxTokens;
    }

    public ExecutionProvider executionProvider() {
        return executionProvider;
    }

    /** The effective token cap for a model whose own limit is {@code modelLimit}. */
    int effectiveMaxTokens(int modelLimit) {
        return maxTokens > 0 && maxTokens < modelLimit ? maxTokens : modelLimit;
    }

    @Override
    public String toString() {
        return "onnx(provider=" + executionProvider.token() + ", intraOp=" + intraOpThreads + ", interOp="
                + interOpThreads + ", quantized=" + preferQuantized + ", batch=" + batchSize + ", maxTokens="
                + maxTokens + ")";
    }

    public static final class Builder {
        private int intraOpThreads = DEFAULT_INTRA_OP_THREADS;
        private int interOpThreads = DEFAULT_INTER_OP_THREADS;
        private boolean preferQuantized = DEFAULT_PREFER_QUANTIZED;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private ExecutionProvider executionProvider = DEFAULT_EXECUTION_PROVIDER;

        private Builder() {
        }

        public Builder intraOpThreads(int value) {
            this.intraOpThreads = value;
            return this;
        }

        public Builder interOpThreads(int value) {
            this.interOpThreads = value;
            return this;
        }

        public Builder preferQuantized(boolean value) {
            this.preferQuantized = value;
            return this;
        }

        public Builder batchSize(int value) {
            this.batchSize = value;
            return this;
        }

        public Builder maxTokens(int value) {
            this.maxTokens = value;
            return this;
        }

        public Builder executionProvider(ExecutionProvider value) {
            this.executionProvider = value;
            return this;
        }

        public OnnxRuntimeOptions build() {
            return new OnnxRuntimeOptions(this);
        }
    }
}
