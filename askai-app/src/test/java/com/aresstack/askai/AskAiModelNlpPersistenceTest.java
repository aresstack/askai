package com.aresstack.askai;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.config.AppConfigurationRepository;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

/** NLP sentence-model selections persist per language independently, a reload restores them, and clearing persists. */
public class AskAiModelNlpPersistenceTest {

    private static File tempConfig() throws IOException {
        File dir = Files.createTempDirectory("askai-nlp-persist").toFile();
        return new File(dir, "askai.properties");
    }

    private static String de(AskAiModel model) {
        return model.getAiModelSelections().getNlp().getModelId(NlpCapability.SENTENCE_DETECTION, "de");
    }

    private static String en(AskAiModel model) {
        return model.getAiModelSelections().getNlp().getModelId(NlpCapability.SENTENCE_DETECTION, "en");
    }

    @Test
    public void persistsDeAndEnIndependentlyAndAReloadRestoresThem() throws IOException {
        File config = tempConfig();
        new AskAiModel(new AppConfigurationRepository(config))
                .persistNlpSentenceModels("apache/sentence-de", "apache/sentence-en");

        AskAiModel reloaded = new AskAiModel(new AppConfigurationRepository(config));
        assertEquals("apache/sentence-de", de(reloaded));
        assertEquals("apache/sentence-en", en(reloaded));
    }

    @Test
    public void settingOnlyGermanLeavesEnglishUnselected() throws IOException {
        File config = tempConfig();
        new AskAiModel(new AppConfigurationRepository(config))
                .persistNlpSentenceModels("apache/sentence-de", "");

        AskAiModel reloaded = new AskAiModel(new AppConfigurationRepository(config));
        assertEquals("apache/sentence-de", de(reloaded));
        assertEquals("", en(reloaded));
    }

    @Test
    public void clearingASelectionPersists() throws IOException {
        File config = tempConfig();
        AppConfigurationRepository repo = new AppConfigurationRepository(config);
        new AskAiModel(repo).persistNlpSentenceModels("apache/sentence-de", "apache/sentence-en");
        new AskAiModel(repo).persistNlpSentenceModels("", "apache/sentence-en"); // clear German

        AskAiModel reloaded = new AskAiModel(new AppConfigurationRepository(config));
        assertEquals("", de(reloaded));
        assertEquals("apache/sentence-en", en(reloaded));
    }
}
