package com.aresstack.askai.localruntime.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single generation request handed to a {@link LoadedGenerationHandle}. AskAI-owned. A COMPLETION request
 * carries a raw {@code prompt}; a CHAT request carries {@code messages} the handle renders with the model's
 * chat template. Sampling options are neutral (mapped from Ollama {@code options} by the HTTP layer).
 */
public final class LocalGenerationRequest {

    /** Whether this is a chat turn (messages + template) or a raw completion (prompt). */
    public enum Kind { COMPLETION, CHAT }

    private final Kind kind;
    private final String prompt;
    private final List<LocalGenerationMessage> messages;
    private final int numPredict;
    private final Double temperature;
    private final Double topP;
    private final List<String> stop;
    private final Long seed;

    private LocalGenerationRequest(Builder b) {
        this.kind = b.kind;
        this.prompt = b.prompt == null ? "" : b.prompt;
        this.messages = b.messages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(b.messages));
        this.numPredict = b.numPredict;
        this.temperature = b.temperature;
        this.topP = b.topP;
        this.stop = b.stop == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(b.stop));
        this.seed = b.seed;
    }

    public Kind kind() {
        return kind;
    }

    public String prompt() {
        return prompt;
    }

    public List<LocalGenerationMessage> messages() {
        return messages;
    }

    /** Maximum new tokens to produce; {@code <= 0} means the runtime default. */
    public int numPredict() {
        return numPredict;
    }

    public Double temperature() {
        return temperature;
    }

    public Double topP() {
        return topP;
    }

    public List<String> stop() {
        return stop;
    }

    public Long seed() {
        return seed;
    }

    public static Builder completion(String prompt) {
        return new Builder(Kind.COMPLETION).prompt(prompt);
    }

    public static Builder chat(List<LocalGenerationMessage> messages) {
        return new Builder(Kind.CHAT).messages(messages);
    }

    public static final class Builder {
        private final Kind kind;
        private String prompt;
        private List<LocalGenerationMessage> messages;
        private int numPredict;
        private Double temperature;
        private Double topP;
        private List<String> stop;
        private Long seed;

        private Builder(Kind kind) {
            this.kind = kind;
        }

        public Builder prompt(String value) {
            this.prompt = value;
            return this;
        }

        public Builder messages(List<LocalGenerationMessage> value) {
            this.messages = value;
            return this;
        }

        public Builder numPredict(int value) {
            this.numPredict = value;
            return this;
        }

        public Builder temperature(Double value) {
            this.temperature = value;
            return this;
        }

        public Builder topP(Double value) {
            this.topP = value;
            return this;
        }

        public Builder stop(List<String> value) {
            this.stop = value;
            return this;
        }

        public Builder seed(Long value) {
            this.seed = value;
            return this;
        }

        public LocalGenerationRequest build() {
            return new LocalGenerationRequest(this);
        }
    }
}
