package com.aresstack.askai.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * The centrally-managed AI model selections value object plus its threading through
 * {@link AppConfiguration}: with-copies, normalization, defaults, and carry-over across the other
 * config withers (so a save site that rebuilds an AppConfiguration never silently drops the models).
 */
public class AiModelSelectionsTest {

    @Test
    public void defaultsAreEmpty() {
        AiModelSelections defaults = AiModelSelections.defaults();
        assertEquals("", defaults.getMainModel());
        assertEquals("", defaults.getRerankerModel());
        assertEquals("", defaults.getEmbeddingsModel());
    }

    @Test
    public void constructorNormalizesNullAndTrims() {
        AiModelSelections selections = new AiModelSelections(null, "  qwen3-reranker  ", "\tnomic-embed\t");
        assertEquals("", selections.getMainModel());
        assertEquals("qwen3-reranker", selections.getRerankerModel());
        assertEquals("nomic-embed", selections.getEmbeddingsModel());
    }

    @Test
    public void withersReplaceOnlyTheTargetedField() {
        AiModelSelections base = AiModelSelections.defaults()
                .withMainModel("gpt-oss-20b")
                .withRerankerModel("reranker-1")
                .withEmbeddingsModel("embed-1");
        assertEquals("gpt-oss-20b", base.getMainModel());
        assertEquals("reranker-1", base.getRerankerModel());
        assertEquals("embed-1", base.getEmbeddingsModel());

        AiModelSelections changed = base.withMainModel("llama-3.1-8b");
        assertEquals("llama-3.1-8b", changed.getMainModel());
        assertEquals("reranker-1", changed.getRerankerModel());
        assertEquals("embed-1", changed.getEmbeddingsModel());
    }

    @Test
    public void valueEquality() {
        assertEquals(new AiModelSelections("a", "b", "c"), new AiModelSelections("a", "b", "c"));
        assertNotEquals(new AiModelSelections("a", "b", "c"), new AiModelSelections("a", "b", "x"));
    }

    @Test
    public void appConfigurationDefaultsCarryEmptySelections() {
        assertEquals(AiModelSelections.defaults(), AppConfiguration.defaults().getAiModelSelections());
    }

    @Test
    public void rerankerAndEmbeddingsPersistComposesWithoutTouchingMainModel() {
        // Mirrors AskAiModel.persistRerankerAndEmbeddingsModels: chaining the two withers must set both
        // and leave the chat-window main model untouched.
        AiModelSelections start = AiModelSelections.defaults().withMainModel("gpt-oss-20b");
        AiModelSelections after = start.withRerankerModel("rr").withEmbeddingsModel("emb");
        assertEquals("gpt-oss-20b", after.getMainModel());
        assertEquals("rr", after.getRerankerModel());
        assertEquals("emb", after.getEmbeddingsModel());
    }

    @Test
    public void appConfigurationCarriesSelectionsAcrossOtherWithers() {
        AiModelSelections models = new AiModelSelections("main-x", "rerank-x", "embed-x");
        AppConfiguration configuration = AppConfiguration.defaults()
                .withAiModelSelections(models)
                // any subsequent unrelated wither must preserve the selections
                .withChatColors(ChatColorSettings.defaults())
                .withHuggingFaceSearchFilters("something");
        assertEquals(models, configuration.getAiModelSelections());
    }

    /**
     * The main-model read timeout is a SETTING (the user's rule: no magic numbers outside the
     * settings): it defaults visibly, survives the property round-trip, withers preserve it, and a
     * nonsense value falls back to the default instead of becoming a limit.
     */
    @Test
    public void theMainModelTimeoutIsASettingWithAnHonestDefault() throws Exception {
        assertEquals(AiModelSelections.DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS,
                AiModelSelections.defaults().getMainModelTimeoutSeconds());
        AiModelSelections tuned = AiModelSelections.defaults().withMainModelTimeoutSeconds(600);
        assertEquals(600, tuned.getMainModelTimeoutSeconds());
        assertEquals("withers preserve the timeout", 600,
                tuned.withMainModel("m").withRerankerModel("r").getMainModelTimeoutSeconds());
        assertEquals("nonsense falls back to the default",
                AiModelSelections.DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS,
                AiModelSelections.defaults().withMainModelTimeoutSeconds(0)
                        .getMainModelTimeoutSeconds());

        java.io.File file = java.io.File.createTempFile("askai-config", ".properties");
        try {
            AppConfigurationRepository repository = new AppConfigurationRepository(file);
            repository.save(AppConfiguration.defaults().withAiModelSelections(tuned));
            assertEquals("the timeout survives the persisted round-trip", 600,
                    repository.load().getAiModelSelections().getMainModelTimeoutSeconds());
        } finally {
            file.delete();
        }
    }
}
