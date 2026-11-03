package com.aresstack.askai.ui.setup;

import java.awt.Component;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The setup wizard's integration port: everything it needs from AskAI. The wizard UI knows neither files, property
 * keys nor services; the production adapter is {@link AskAiSetupActions}. Calls come from the EDT, results are
 * delivered on the EDT. Results of the form {@code (message, error)} carry exactly one non-{@code null} value.
 *
 * <p>Ported from the Enterprise AI Client ({@code exedra.ui.setup.SetupActions}); its KeePass, Confluence and
 * manual-key parts are gone, AskAI's model roles (chat, embeddings, reranker, OpenNLP, voices) are new.</p>
 */
public interface SetupActions {

    // ------------------------------------------------------------------ network & security

    /** A real HTTPS request through the draft's proxy and certificate settings. */
    void testNetwork(SetupDraft draft, BiConsumer<String, String> done);

    /**
     * AskAI's full network settings (proxy discovery, TLS trust, HTTP client) in a modal dialog; blocks on the EDT.
     *
     * @return the draft with the network values as they are stored now, or {@code null} when nothing changed
     */
    SetupDraft editNetwork(Component parent, SetupDraft draft);

    // ------------------------------------------------------------------ AI runtime

    /** Asks the Ollama server of the draft for its version and checks AskAI's own local model runtime. */
    void checkRuntime(SetupDraft draft, Consumer<RuntimeStatus> done);

    /** Opens the download page of a runtime (Ollama) in the browser. */
    void openRuntimeDownload();

    // ------------------------------------------------------------------ models

    /** The installed models per role, from Ollama and AskAI's local runtime together. */
    void loadModels(SetupDraft draft, Consumer<SetupModels> done);

    /** A real embedding call with the draft's embedding model; result is the vector length. */
    void embeddingDimension(SetupDraft draft, BiConsumer<Integer, String> done);

    /**
     * Installs the curated OpenNLP sentence models that are still missing (German and English); {@code progress}
     * gets one line per model.
     */
    void installSentenceModels(Consumer<String> progress, BiConsumer<String, String> done);

    // ------------------------------------------------------------------ voice

    /** The read-aloud voices AskAI offers (installed ones marked). */
    void loadVoices(Consumer<List<VoiceOffer>> done);

    /** Downloads and installs a voice (engine first, if missing); {@code progress} gets short status lines. */
    void installVoice(String voiceId, Consumer<String> progress, BiConsumer<String, String> done);

    /** Stores the draft's voice choice per language; {@code done} gets {@code null} or the error. */
    void saveVoices(SetupDraft draft, Consumer<String> done);

    // ------------------------------------------------------------------ finish

    /**
     * Writes the draft into AskAI's settings and then checks every role for real; delivers each item of the final
     * check. Optional roles left empty are reported as fine.
     */
    void finish(SetupDraft draft, Consumer<List<SetupCheck>> done);

    /**
     * The wizard was cancelled: undo what the actions applied for testing only (e.g. the Ollama address the model
     * lists were read from). Saved settings are never touched.
     */
    default void cancelled() {
    }

    // ------------------------------------------------------------------ Witch

    /**
     * A free question to Witch: one call of the draft's chat model (frame {@code system}, question
     * {@code question}), without tools and history. Result is the answer or ({@code null}, message).
     */
    default void ask(SetupDraft draft, String system, String question, BiConsumer<String, String> done) {
        done.accept(null, "Free questions are not available here.");
    }

    /** Witch's own voice, or {@code null} for speech bubbles only. */
    default WizardVoice witchVoice() {
        return null;
    }

    /** The voices of the hand-over after the first setup; without speech output only bubbles. */
    default void voices(SetupDraft draft, Consumer<SetupVoices> done) {
        done.accept(SetupVoices.silent(null));
    }

    // ------------------------------------------------------------------ values

    /** What the runtime check found. */
    final class RuntimeStatus {
        private final String ollamaVersion;
        private final String ollamaProblem;
        private final boolean localRuntime;

        public RuntimeStatus(String ollamaVersion, String ollamaProblem, boolean localRuntime) {
            this.ollamaVersion = ollamaVersion == null || ollamaVersion.trim().isEmpty() ? null : ollamaVersion;
            this.ollamaProblem = ollamaProblem == null ? "" : ollamaProblem;
            this.localRuntime = localRuntime;
        }

        /** The Ollama server's version, {@code null} when it did not answer. */
        public String ollamaVersion() {
            return ollamaVersion;
        }

        /** Why Ollama did not answer (empty when it did). */
        public String ollamaProblem() {
            return ollamaProblem;
        }

        /** Whether AskAI's own local model runtime has models to run. */
        public boolean localRuntime() {
            return localRuntime;
        }

        /** At least one runtime can serve models. */
        public boolean reachable() {
            return ollamaVersion != null || localRuntime;
        }
    }

    /** The installed models per role. */
    final class SetupModels {
        private final List<String> chat;
        private final List<String> embeddings;
        private final List<String> rerankers;
        private final List<String> sentenceGerman;
        private final List<String> sentenceEnglish;
        private final String problem;

        public SetupModels(List<String> chat, List<String> embeddings, List<String> rerankers,
                           List<String> sentenceGerman, List<String> sentenceEnglish, String problem) {
            this.chat = copy(chat);
            this.embeddings = copy(embeddings);
            this.rerankers = copy(rerankers);
            this.sentenceGerman = copy(sentenceGerman);
            this.sentenceEnglish = copy(sentenceEnglish);
            this.problem = problem == null ? "" : problem;
        }

        private static List<String> copy(List<String> values) {
            return values == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<String>(values));
        }

        public List<String> chat() {
            return chat;
        }

        public List<String> embeddings() {
            return embeddings;
        }

        public List<String> rerankers() {
            return rerankers;
        }

        public List<String> sentenceGerman() {
            return sentenceGerman;
        }

        public List<String> sentenceEnglish() {
            return sentenceEnglish;
        }

        /** Why a list could not be read (empty when all were read). */
        public String problem() {
            return problem;
        }
    }

    /** One read-aloud voice offered by AskAI. */
    final class VoiceOffer {
        private final String id;
        private final String label;
        private final String languageCode;
        private final boolean installed;

        public VoiceOffer(String id, String label, String languageCode, boolean installed) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("id must not be empty");
            }
            this.id = id;
            this.label = label == null || label.trim().isEmpty() ? id : label;
            this.languageCode = languageCode == null ? "" : languageCode;
            this.installed = installed;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** {@code de} or {@code en}. */
        public String languageCode() {
            return languageCode;
        }

        public boolean isInstalled() {
            return installed;
        }

        @Override
        public String toString() {
            return label + (installed ? " (installed)" : "");
        }
    }
}
