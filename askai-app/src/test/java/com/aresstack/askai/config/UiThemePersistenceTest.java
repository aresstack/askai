package com.aresstack.askai.config;

import com.aresstack.askai.stt.SpeechToTextConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;

/** {@code ui.theme} round-trips through the repository and is never wiped by a save without a theme. */
public class UiThemePersistenceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void themeRoundTrips() throws Exception {
        AppConfigurationRepository repository =
                new AppConfigurationRepository(new File(folder.getRoot(), "askai.properties"));
        assertEquals("", repository.load().getUiTheme());
        repository.save(AppConfiguration.defaults().withUiTheme("classic"));
        assertEquals("classic", repository.load().getUiTheme());
        repository.save(repository.load().withUiTheme("arcane"));
        assertEquals("arcane", repository.load().getUiTheme());
    }

    @Test
    public void aSaveWithoutThemeKeepsTheStoredChoice() throws Exception {
        AppConfigurationRepository repository =
                new AppConfigurationRepository(new File(folder.getRoot(), "askai.properties"));
        repository.save(AppConfiguration.defaults().withUiTheme("classic"));
        AppConfiguration current = repository.load();
        // like ProxyPanel / OllamaInstallPanel: rebuilt through the plain constructor
        repository.save(new AppConfiguration(current.getOllamaBaseUrl(), current.getKeepAlive()));
        assertEquals("classic", repository.load().getUiTheme());
    }

    @Test
    public void otherWithCopiesCarryTheTheme() {
        AppConfiguration configuration = AppConfiguration.defaults().withUiTheme("classic")
                .withChatColors(ChatColorSettings.defaults())
                .withAiModelSelections(AiModelSelections.defaults())
                .withHuggingFaceSearchFilters("")
                .withHuggingFaceSearchSuggestions("x")
                .withSpeechToTextConfiguration(SpeechToTextConfiguration.defaults());
        assertEquals("classic", configuration.getUiTheme());
    }
}
