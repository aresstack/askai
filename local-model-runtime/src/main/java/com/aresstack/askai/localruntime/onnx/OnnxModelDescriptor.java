package com.aresstack.askai.localruntime.onnx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One catalogued ONNX model: where its files live on Hugging Face, which flat local names the installer gives them,
 * and how the runtime turns encoder output into a result (pooling, normalisation, prefixes, token limit). Immutable.
 */
public final class OnnxModelDescriptor {

    /** What the model does. */
    public enum Capability {
        EMBEDDING("embedding"), RERANK("rerank");

        private final String token;

        Capability(String token) {
            this.token = token;
        }

        /** The Ollama-style capability tag the manifest carries. */
        public String token() {
            return token;
        }
    }

    /** How token vectors become one sentence vector. */
    public enum Pooling {
        CLS, MEAN
    }

    /** Whether the catalog can install the model today. */
    public enum Status {
        /** A downloadable ONNX export exists. */
        RUNNABLE,
        /** The repository has no ONNX export yet; see {@link #exportNote()}. */
        NEEDS_EXPORT
    }

    /** One graph variant in the repository (quantized variants are listed before the full-precision graph). */
    public static final class GraphVariant {
        private final String remotePath;
        private final boolean quantized;
        private final String externalDataRemotePath;

        public GraphVariant(String remotePath, boolean quantized, String externalDataRemotePath) {
            this.remotePath = remotePath;
            this.quantized = quantized;
            this.externalDataRemotePath = externalDataRemotePath == null ? "" : externalDataRemotePath;
        }

        public String remotePath() {
            return remotePath;
        }

        public boolean quantized() {
            return quantized;
        }

        /** The {@code *.onnx_data} file the graph references (models over 2 GB), or empty. */
        public String externalDataRemotePath() {
            return externalDataRemotePath;
        }

        /** The flat local file name of the external data; ONNX resolves it next to the graph by this name. */
        public String externalDataLocalName() {
            return fileName(externalDataRemotePath);
        }
    }

    /** The flat local graph file name in every installed model directory. */
    public static final String LOCAL_GRAPH = "model.onnx";
    public static final String LOCAL_TOKENIZER = "tokenizer.json";
    public static final String LOCAL_CONFIG = "config.json";

    private final String repositoryId;
    private final String displayName;
    private final Capability capability;
    private final String architecture;
    private final Pooling pooling;
    private final boolean normalize;
    private final String queryPrefix;
    private final String passagePrefix;
    private final int maxTokens;
    private final String tokenizerRemotePath;
    private final String configRemotePath;
    private final List<GraphVariant> graphs;
    private final Status status;
    private final String exportNote;

    private OnnxModelDescriptor(Builder b) {
        this.repositoryId = b.repositoryId;
        this.displayName = b.displayName;
        this.capability = b.capability;
        this.architecture = b.architecture;
        this.pooling = b.pooling;
        this.normalize = b.normalize;
        this.queryPrefix = b.queryPrefix;
        this.passagePrefix = b.passagePrefix;
        this.maxTokens = b.maxTokens;
        this.tokenizerRemotePath = b.tokenizerRemotePath;
        this.configRemotePath = b.configRemotePath;
        this.graphs = Collections.unmodifiableList(new ArrayList<GraphVariant>(b.graphs));
        this.status = b.status;
        this.exportNote = b.exportNote;
    }

    public static Builder builder(String repositoryId) {
        return new Builder(repositoryId);
    }

    public String repositoryId() {
        return repositoryId;
    }

    public String displayName() {
        return displayName;
    }

    public Capability capability() {
        return capability;
    }

    /** The encoder architecture token, e.g. {@code xlm-roberta} or {@code bert}. */
    public String architecture() {
        return architecture;
    }

    public Pooling pooling() {
        return pooling;
    }

    public boolean normalize() {
        return normalize;
    }

    /** Prefix for search queries ({@code query: } for E5), empty when the model takes raw text. */
    public String queryPrefix() {
        return queryPrefix;
    }

    /** Prefix for stored passages ({@code passage: } for E5), empty when the model takes raw text. */
    public String passagePrefix() {
        return passagePrefix;
    }

    /** The model's own token limit including special tokens. */
    public int maxTokens() {
        return maxTokens;
    }

    public String tokenizerRemotePath() {
        return tokenizerRemotePath;
    }

    public String configRemotePath() {
        return configRemotePath;
    }

    /** Graph variants, quantized ones first. */
    public List<GraphVariant> graphs() {
        return graphs;
    }

    public Status status() {
        return status;
    }

    public boolean isRunnable() {
        return status == Status.RUNNABLE && !graphs.isEmpty();
    }

    /** How to produce the ONNX export when {@link #status()} is {@link Status#NEEDS_EXPORT}. */
    public String exportNote() {
        return exportNote;
    }

    /** The AskAI virtual model name: {@code local/<repository>:latest}. */
    public String virtualModelName() {
        return "local/" + repositoryId + ":latest";
    }

    /** The installation directory name under the local model root ({@code owner--name}). */
    public String directoryName() {
        return repositoryId.replace("/", "--");
    }

    /** The graph to install for this preference: the first quantized variant if wanted, else full precision. */
    public GraphVariant preferredGraph(boolean preferQuantized, java.util.function.Predicate<String> present) {
        if (preferQuantized) {
            for (GraphVariant graph : graphs) {
                if (graph.quantized() && present.test(graph.remotePath())) {
                    return graph;
                }
            }
        }
        for (GraphVariant graph : graphs) {
            if (!graph.quantized() && present.test(graph.remotePath())) {
                return graph;
            }
        }
        for (GraphVariant graph : graphs) {
            if (present.test(graph.remotePath())) {
                return graph;
            }
        }
        return null;
    }

    static String fileName(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) {
            return "";
        }
        int slash = remotePath.lastIndexOf('/');
        return slash < 0 ? remotePath : remotePath.substring(slash + 1);
    }

    @Override
    public String toString() {
        return repositoryId + " (" + capability.token() + ", " + architecture + ", "
                + pooling.name().toLowerCase(Locale.ROOT) + ", " + status + ")";
    }

    public static final class Builder {
        private final String repositoryId;
        private String displayName;
        private Capability capability = Capability.EMBEDDING;
        private String architecture = "bert";
        private Pooling pooling = Pooling.MEAN;
        private boolean normalize = true;
        private String queryPrefix = "";
        private String passagePrefix = "";
        private int maxTokens = 512;
        private String tokenizerRemotePath = LOCAL_TOKENIZER;
        private String configRemotePath = LOCAL_CONFIG;
        private final List<GraphVariant> graphs = new ArrayList<GraphVariant>();
        private Status status = Status.RUNNABLE;
        private String exportNote = "";

        private Builder(String repositoryId) {
            this.repositoryId = repositoryId;
            this.displayName = repositoryId;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder capability(Capability value) {
            this.capability = value;
            return this;
        }

        public Builder architecture(String value) {
            this.architecture = value;
            return this;
        }

        public Builder pooling(Pooling value) {
            this.pooling = value;
            return this;
        }

        public Builder normalize(boolean value) {
            this.normalize = value;
            return this;
        }

        public Builder prefixes(String query, String passage) {
            this.queryPrefix = query;
            this.passagePrefix = passage;
            return this;
        }

        public Builder maxTokens(int value) {
            this.maxTokens = value;
            return this;
        }

        public Builder tokenizer(String remotePath) {
            this.tokenizerRemotePath = remotePath;
            return this;
        }

        public Builder config(String remotePath) {
            this.configRemotePath = remotePath;
            return this;
        }

        public Builder graph(String remotePath, boolean quantized) {
            return graph(remotePath, quantized, "");
        }

        public Builder graph(String remotePath, boolean quantized, String externalDataRemotePath) {
            graphs.add(new GraphVariant(remotePath, quantized, externalDataRemotePath));
            return this;
        }

        public Builder needsExport(String note) {
            this.status = Status.NEEDS_EXPORT;
            this.exportNote = note;
            return this;
        }

        public OnnxModelDescriptor build() {
            return new OnnxModelDescriptor(this);
        }
    }
}
