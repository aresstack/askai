package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationException;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshot;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;

import java.io.File;
import java.io.IOException;

/**
 * The productive host {@link NlpConfigurationSnapshotProvider}: resolves the EXPLICITLY selected
 * ({@code ai.nlpSentenceModel.<lang>}) installed NLP model into an immutable snapshot. It validates the selection
 * against the {@link LocalNlpModelStore}, that the artifact exists, and — when the descriptor carries one — that
 * its SHA-256 matches (a tampered/broken artifact is a hard error, never a silent regex fallback). It NEVER
 * downloads at resolve time.
 *
 * <p>Typed reasons: {@code MODEL_NOT_CONFIGURED} (nothing selected) and {@code MODEL_NOT_INSTALLED} (selection not
 * in the store) let the caller degrade to the regex segmenter; {@code ARTIFACT_MISSING}/{@code CHECKSUM_MISMATCH}
 * surface.</p>
 */
public final class LocalNlpConfigurationSnapshotProvider implements NlpConfigurationSnapshotProvider {

    /** The central NLP selection seam — read the authoritative {@code ai.nlpSentenceModel.<lang>} model id. */
    interface NlpSelectionStore {
        String selectedModelId(NlpCapability capability, String languageCode);
    }

    private final LocalNlpModelStore store;
    private final NlpSelectionStore selection;

    public LocalNlpConfigurationSnapshotProvider(LocalNlpModelStore store,
                                                 AppConfigurationRepository centralConfig) {
        this(store, adapt(centralConfig));
    }

    LocalNlpConfigurationSnapshotProvider(LocalNlpModelStore store, NlpSelectionStore selection) {
        this.store = store;
        this.selection = selection == null ? NONE : selection;
    }

    @Override
    public NlpConfigurationSnapshot resolve(NlpCapability capability, String languageCode)
            throws NlpConfigurationException {
        String selectedModel = selection.selectedModelId(capability, languageCode);
        if (selectedModel == null || selectedModel.trim().isEmpty()) {
            throw new NlpConfigurationException(NlpConfigurationException.Reason.MODEL_NOT_CONFIGURED,
                    "no NLP model selected for " + capability.getTag() + " / " + languageCode);
        }
        NlpModelDescriptor descriptor = store.find(selectedModel.trim());
        if (descriptor == null || descriptor.getCapability() != capability
                || !descriptor.getLanguageCode().equals(languageCode == null ? "" : languageCode.trim().toLowerCase())) {
            throw new NlpConfigurationException(NlpConfigurationException.Reason.MODEL_NOT_INSTALLED,
                    "selected NLP model '" + selectedModel + "' is not installed for " + capability.getTag()
                            + " / " + languageCode);
        }
        File artifact = new File(descriptor.getArtifactPath());
        if (!artifact.isFile()) {
            throw new NlpConfigurationException(NlpConfigurationException.Reason.ARTIFACT_MISSING,
                    "NLP model artifact is missing: " + descriptor.getArtifactPath());
        }
        if (!descriptor.getSha256().isEmpty()) {
            String actual;
            try {
                actual = LocalNlpModelStore.sha256Of(artifact);
            } catch (IOException ex) {
                throw new NlpConfigurationException(NlpConfigurationException.Reason.ARTIFACT_MISSING,
                        "NLP model artifact could not be read for checksum: " + ex.getMessage(), ex);
            }
            if (!actual.equalsIgnoreCase(descriptor.getSha256())) {
                throw new NlpConfigurationException(NlpConfigurationException.Reason.CHECKSUM_MISMATCH,
                        "NLP model artifact SHA-256 mismatch for '" + selectedModel + "' (expected "
                                + descriptor.getSha256() + ", got " + actual + ")");
            }
        }
        return new NlpConfigurationSnapshot(descriptor);
    }

    private static final NlpSelectionStore NONE = new NlpSelectionStore() {
        public String selectedModelId(NlpCapability capability, String languageCode) {
            return "";
        }
    };

    private static NlpSelectionStore adapt(final AppConfigurationRepository repository) {
        if (repository == null) {
            return NONE;
        }
        return new NlpSelectionStore() {
            public String selectedModelId(NlpCapability capability, String languageCode) {
                AppConfiguration configuration = repository.load();
                return configuration.getAiModelSelections().getNlp().getModelId(capability, languageCode);
            }
        };
    }
}
