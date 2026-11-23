package com.aresstack.askai.ui.setup;

import com.aresstack.askai.credentials.KeePassAccess;
import com.aresstack.askai.modelport.ModelPortSettings;
import com.aresstack.askai.modelport.ModelProviderKind;
import com.aresstack.askai.modelport.ModelProviderSettings;
import com.aresstack.askai.modelport.ModelProviderSettingsRepository;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The setup wizard's model provider part: Ollama or an OpenAI-compatible API for the research's model slots. It
 * reads and writes the same {@code model-provider.properties} (and encrypted key) as Settings -> AI models -> Model
 * provider, lists the API's models and builds the final check's rows for the API. Calls block; the caller runs them
 * off the EDT.
 */
final class SetupModelProvider {

    static final String RUNTIME_CHECK = "AI runtime";
    static final String RESEARCH_MODEL_CHECK = "Research model (API)";

    private final ModelProviderSettingsRepository repository;
    private final OpenAiCompatibleModelProvider.ConnectionOpener connections;
    private final KeePassAccess keePass;

    SetupModelProvider(ModelProviderSettingsRepository repository,
                       OpenAiCompatibleModelProvider.ConnectionOpener connections) {
        this(repository, connections, KeePassAccess.NONE);
    }

    SetupModelProvider(ModelProviderSettingsRepository repository,
                       OpenAiCompatibleModelProvider.ConnectionOpener connections, KeePassAccess keePass) {
        this.keePass = keePass == null ? KeePassAccess.NONE : keePass;
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        this.repository = repository;
        this.connections = connections == null ? OpenAiCompatibleModelProvider.PLAIN : connections;
    }

    ModelProviderSettingsRepository repository() {
        return repository;
    }

    /** The draft with the provider values as they are stored. */
    SetupDraft.Builder fill(SetupDraft.Builder builder) {
        ModelProviderSettings stored = repository.load();
        return builder.api(stored.usesApi(), stored.getApiBaseUrl(), stored.apiSlotModel(ModelPortSettings.DEFAULT_SLOT))
                .apiKeyStored(stored.hasStoredApiKey()).apiKeePassEntry(stored.getKeePassEntry());
    }

    /** Lists KeePass's entries and the URL of {@code chosen} (or of the first entry); blocks. */
    SetupActions.KeePassStatus keePassEntries(String chosen) {
        try {
            List<String> titles = keePass.titles();
            String entry = chosen != null && !chosen.trim().isEmpty() ? chosen.trim()
                    : titles.isEmpty() ? "" : titles.get(0);
            String url = "";
            if (!entry.isEmpty() && titles.contains(entry)) {
                url = keePass.location(entry);
            }
            return new SetupActions.KeePassStatus(titles, url, "");
        } catch (IOException failed) {
            return new SetupActions.KeePassStatus(null, "", failed.getMessage());
        }
    }

    /** Lists the API's models with {@code typedKey}, or the stored key when it is empty; clears {@code typedKey}. */
    SetupActions.ApiStatus check(SetupDraft draft, char[] typedKey) {
        final char[] typed = typedKey == null ? new char[0] : typedKey;
        try {
            if (draft.apiBaseUrl().isEmpty()) {
                return new SetupActions.ApiStatus(null, "no base URL is set");
            }
            List<String> models = OpenAiCompatibleModelProvider.listModels(draft.apiBaseUrl(),
                    () -> !draft.apiKeePassEntry().isEmpty() ? keePass.apiKey(draft.apiKeePassEntry())
                            : new String(typed).trim().isEmpty() ? repository.apiKey() : typed.clone(),
                    connections);
            return new SetupActions.ApiStatus(models, "");
        } catch (Exception failed) {
            return new SetupActions.ApiStatus(null, failed.getMessage());
        } finally {
            Arrays.fill(typed, '\0');
        }
    }

    /**
     * Stores the provider choice, the base URL and the {@code default} slot's model; a typed key (not empty) is
     * stored encrypted, an empty one keeps the stored key. Other tasks' models and the API's other settings stay.
     * Clears {@code typedKey}.
     */
    void save(SetupDraft draft, char[] typedKey) throws IOException {
        try {
            ModelProviderSettings stored = repository.load();
            repository.save(stored.withProvider(draft.useApi() ? ModelProviderKind.OPENAI_COMPATIBLE
                    : ModelProviderKind.OLLAMA).withApi(draft.apiBaseUrl(), draft.apiDefaultModel())
                    .withKeePassEntry(draft.apiKeePassEntry()));
            if (draft.apiKeePassEntry().isEmpty() && typedKey != null && !new String(typedKey).trim().isEmpty()) {
                char[] trimmed = new String(typedKey).trim().toCharArray();
                try {
                    repository.storeApiKey(trimmed);
                } finally {
                    Arrays.fill(trimmed, '\0');
                }
            }
        } finally {
            if (typedKey != null) {
                Arrays.fill(typedKey, '\0');
            }
        }
    }

    /**
     * The final check's rows for the API (with the stored key): the runtime row answers whether the API does, and
     * a missing Ollama is only noted there; then whether the API offers the research model.
     *
     * @param otherRuntimes what else answers ("Ollama 0.9.0", "AskAI local runtime"), "" for nothing
     */
    List<SetupCheck> apiChecks(SetupDraft draft, String otherRuntimes) {
        List<SetupCheck> checks = new ArrayList<SetupCheck>();
        SetupActions.ApiStatus api = check(draft, null);
        String others = otherRuntimes == null || otherRuntimes.trim().isEmpty()
                ? "; Ollama does not answer, which is fine: chat window, embeddings and reranker use the API too"
                : "; " + otherRuntimes.trim();
        if (api.answered()) {
            checks.add(SetupCheck.ok(RUNTIME_CHECK, "OpenAI-compatible API (" + api.models().size() + " models)"
                    + others));
        } else {
            checks.add(SetupCheck.failed(RUNTIME_CHECK, "the OpenAI-compatible API does not answer"
                    + (api.problem().isEmpty() ? "" : ": " + api.problem())));
        }
        String model = draft.apiDefaultModel();
        if (model.isEmpty()) {
            checks.add(SetupCheck.failed(RESEARCH_MODEL_CHECK, "none chosen; every research task needs one"));
        } else if (api.answered() && !api.models().isEmpty() && !api.models().contains(model)) {
            checks.add(SetupCheck.failed(RESEARCH_MODEL_CHECK, model + " is not offered by the API"));
        } else {
            checks.add(SetupCheck.ok(RESEARCH_MODEL_CHECK, model));
        }
        // The chat window, embeddings and the reranker run on the API as well (its roles, AskAI's preference
        // while none is chosen under Settings -> AI models -> Model provider).
        ModelProviderSettings stored = repository.load();
        checks.add(roleCheck(CHAT_CHECK, stored.apiRoleModel(ModelProviderSettings.ROLE_CHAT), api));
        checks.add(roleCheck(EMBEDDING_CHECK, stored.apiRoleModel(ModelProviderSettings.ROLE_EMBEDDING), api));
        checks.add(roleCheck(RERANKER_CHECK, stored.apiRoleModel(ModelProviderSettings.ROLE_RERANK), api));
        return checks;
    }

    static final String CHAT_CHECK = "Chat model (API)";
    static final String EMBEDDING_CHECK = "Embedding model (API)";
    static final String RERANKER_CHECK = "Reranker (API)";

    private static SetupCheck roleCheck(String label, String model, SetupActions.ApiStatus api) {
        if (model.isEmpty()) {
            return SetupCheck.failed(label, "none chosen");
        }
        if (api.answered() && !api.models().isEmpty() && !api.models().contains(model)) {
            return SetupCheck.failed(label, model + " is not offered by the API; choose another under Settings -> "
                    + "AI models -> Model provider");
        }
        return SetupCheck.ok(label, model);
    }
}
