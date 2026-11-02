package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A5e/A5g: the productive host implementation of {@link RerankerConfigurationSnapshotProvider}. It
 * validates the EXPLICITLY selected rerank-capable local model (persisted research runtime setting),
 * ensures its runtime is started, builds the neutral descriptor and writes an atomic per-session
 * snapshot — then hands the agent only the file path.
 *
 * <p>There is NO silent "first found" fallback and no guessing: the selected virtual model id must
 * resolve to an installed manifest that declares the {@code RERANK} capability and a usable state. A
 * missing, removed or incompatible selection is a visible {@link RerankerConfigurationException} that
 * fails the productive session start.
 */
public final class LocalRerankerConfigurationSnapshotProvider
        implements RerankerConfigurationSnapshotProvider {

    private static final String SNAPSHOT_FILE_NAME = "reranker-config.json";

    private final LocalModelRuntimeManager manager;
    private final LocalRerankerModelCatalog catalog;
    private final CentralRerankerSelectionStore central;

    /**
     * The central reranker selection seam: read the authoritative {@code ai.rerankerModel} and migrate a
     * legacy selection into it. Kept minimal so the resolver is unit-testable without the real config file.
     */
    interface CentralRerankerSelectionStore {
        /** The trimmed central selection, or "" when none is set. */
        String currentSelection();

        /** Persists {@code model} as the central reranker selection (one-time migration). */
        void migrateSelection(String model);

        /** Legacy behaviour: no central store, so the plugin-passed selection is authoritative. */
        CentralRerankerSelectionStore NONE = new CentralRerankerSelectionStore() {
            public String currentSelection() {
                return "";
            }

            public void migrateSelection(String model) {
            }
        };
    }

    public LocalRerankerConfigurationSnapshotProvider(LocalModelRuntimeManager manager) {
        this(manager, (AppConfigurationRepository) null);
    }

    /**
     * @param centralConfig the central AskAI configuration store. When present the reranker selection is
     *                      taken from {@code ai.rerankerModel} (chosen in AskAI → Configuration → AI models);
     *                      the plugin-passed selection is only a transitional fallback that is migrated into
     *                      the central store the first time it is used. Null preserves the legacy behaviour
     *                      of trusting the passed selection.
     */
    public LocalRerankerConfigurationSnapshotProvider(LocalModelRuntimeManager manager,
                                                      AppConfigurationRepository centralConfig) {
        this(manager, adapt(centralConfig));
    }

    LocalRerankerConfigurationSnapshotProvider(LocalModelRuntimeManager manager,
                                               CentralRerankerSelectionStore central) {
        this.manager = manager;
        this.catalog = new LocalRerankerModelCatalog(manager);
        this.central = central == null ? CentralRerankerSelectionStore.NONE : central;
    }

    private static CentralRerankerSelectionStore adapt(final AppConfigurationRepository repository) {
        if (repository == null) {
            return CentralRerankerSelectionStore.NONE;
        }
        return new CentralRerankerSelectionStore() {
            public String currentSelection() {
                String selection = repository.load().getAiModelSelections().getRerankerModel();
                return selection == null ? "" : selection.trim();
            }

            public void migrateSelection(String model) {
                AppConfiguration current = repository.load();
                repository.save(current.withAiModelSelections(
                        current.getAiModelSelections().withRerankerModel(model)));
            }
        };
    }

    /**
     * Language-aware resolution seam. DELIBERATE initial configuration: the one explicitly selected reranker
     * serves BOTH research languages ("en"/"de") — the seam exists so a per-language selection (e.g. a
     * multilingual/German cross-encoder for "de" next to the English MS-MARCO model) can slot in HERE without
     * touching the language selector or the session-start chain. The language is logged so a German session
     * running on an English-trained model is visible, never silent.
     */
    @Override
    public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                           String selectedModel, String languageCode)
            throws RerankerConfigurationException {
        String language = languageCode == null || languageCode.trim().isEmpty() ? "en" : languageCode.trim();
        RerankerConfigurationSnapshot snapshot = prepareForSession(sessionId, sessionDirectory, selectedModel);
        System.err.println("[reranker] session=" + sessionId + " language=" + language
                + " model=" + snapshot.getModelName()
                + ("de".equals(language) ? " (note: language-independent selection — the configured model"
                        + " also serves German until a per-language selection exists)" : ""));
        return snapshot;
    }

    @Override
    public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                           String selectedModel)
            throws RerankerConfigurationException {
        String model = resolveSelectedModel(selectedModel);

        String baseUrl;
        try {
            baseUrl = manager.ensureStarted();
        } catch (IOException ex) {
            throw new RerankerConfigurationException(
                    "The local model runtime for the reranker could not be started: "
                            + ex.getMessage(), ex);
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new RerankerConfigurationException(
                    "The local model runtime reported no usable base URL for the reranker.");
        }

        RerankerConfigurationDocument document;
        try {
            document = LocalRerankerEndpointDescriptorFactory.forLocalReranker(manager, model, 0L);
        } catch (IOException ex) {
            throw new RerankerConfigurationException(
                    "The reranker endpoint descriptor could not be built: " + ex.getMessage(), ex);
        }

        File target = new File(sessionDirectory, SNAPSHOT_FILE_NAME);
        try {
            LocalRerankerConfigurationSnapshotWriter.write(document, target);
        } catch (IOException ex) {
            throw new RerankerConfigurationException(
                    "The reranker session snapshot could not be written to " + target + ": "
                            + ex.getMessage(), ex);
        }
        return new RerankerConfigurationSnapshot(target.getAbsoluteFile(), document);
    }

    /**
     * The validated virtual model id of the EXPLICIT selection. The central AskAI selection
     * ({@code ai.rerankerModel}) is authoritative; the plugin-passed selection is only a transitional
     * fallback used when nothing is centrally selected yet (and then migrated into the central store).
     * Fails visibly when neither yields a selection, or when the resolved model is no longer installed,
     * lost its {@code RERANK} capability or is not in a usable state — never a first-match guess.
     */
    String resolveSelectedModel(String selectedModel) throws RerankerConfigurationException {
        List<String> installed = catalog.listInstalledRerankModels();
        String centralSelection = central.currentSelection();
        String effective = !centralSelection.isEmpty()
                ? centralSelection
                : (selectedModel == null ? "" : selectedModel.trim());
        if (effective.isEmpty()) {
            throw new RerankerConfigurationException(noSelectionMessage(installed));
        }
        if (!installed.contains(effective)) {
            throw new RerankerConfigurationException(
                    "The selected reranker model \"" + effective + "\" is not an installed, usable "
                            + "rerank-capable local model (installed: " + installed + "). Update the "
                            + "selection in AskAI → Configuration → AI models.");
        }
        // One-time self-healing migration: a valid legacy (plugin-side) selection is copied into the
        // central store the first time it is used, so removing the plugin picker never strands it.
        if (centralSelection.isEmpty()) {
            central.migrateSelection(effective);
        }
        return effective;
    }

    /**
     * The user-facing hint for a missing reranker selection. When exactly one rerank-capable model is
     * installed it is suggested BY NAME (still no silent auto-selection — the user confirms it); several are
     * listed; none points to the local install. In every case the Ollama limitation is named, so it is clear
     * WHY the reranker must run locally.
     */
    private static String noSelectionMessage(List<String> installed) {
        String ollamaNote = " Note: Ollama has no native reranker yet, so the reranker runs locally in AskAI.";
        if (installed.isEmpty()) {
            return "No reranker model is selected and no rerank-capable local model is installed. Install a "
                    + "reranker under \"Install locally in AskAI\" — e.g. cross-encoder/ms-marco-MiniLM-L6-v2 "
                    + "— and select it in AskAI → Configuration → AI models before starting a research "
                    + "session." + ollamaNote;
        }
        if (installed.size() == 1) {
            return "No reranker model is selected yet. Select the installed model \"" + installed.get(0)
                    + "\" in AskAI → Configuration → AI models to start a research session." + ollamaNote;
        }
        return "No reranker model is selected. Choose one of the installed rerank models " + installed
                + " in AskAI → Configuration → AI models (there is no silent first-match selection)."
                + ollamaNote;
    }
}
