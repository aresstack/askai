package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.service.AskAiService;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;

/**
 * The Setup view's model-search container: two clearly separated sources in tabs — HuggingFace
 * (search, analyze, convert/import) and the Ollama Library (scrape ollama.com, pick a tag, pull it
 * on the remote server). The two result lists are never mixed because their install actions differ.
 * The HuggingFace tab is the unchanged {@link OllamaInstallPanel}.
 */
public final class ModelSearchPanel extends JPanel {

    private final JTabbedPane tabs;
    private final OllamaInstallPanel huggingFacePanel;
    private final SpeechOutputModelsPanel voicesPanel;
    public static final String VOICES_TAB_TITLE = "Voices";
    public static final String DICTATION_TAB_TITLE = "Dictation";

    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this(configurationRepository, askAiService, null, null, null);
    }

    /**
     * @param nlpModelsPanel the NLP model tab (curated OpenNLP sentence models); when non-null it is added as a
     *                       third tab. Prebuilt by the owner so this container stays free of NLP-store details.
     */
    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService,
                            NlpModelsPanel nlpModelsPanel) {
        this(configurationRepository, askAiService, nlpModelsPanel, null, null);
    }

    /**
     * @param voicesPanel      the read-aloud voice installer (Piper); added as the "Voices" tab when non-null
     * @param localModelsPanel the built-in local runtime's models (reranker, embeddings, …); "Local models" tab
     */
    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService,
                            NlpModelsPanel nlpModelsPanel, SpeechOutputModelsPanel voicesPanel,
                            LocalRuntimeModelsPanel localModelsPanel) {
        this(configurationRepository, askAiService, nlpModelsPanel, voicesPanel, null, localModelsPanel);
    }

    /** @param dictationPanel the Whisper dictation-model installer; added as the "Dictation" tab when non-null */
    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService,
                            NlpModelsPanel nlpModelsPanel, SpeechOutputModelsPanel voicesPanel,
                            WhisperModelsPanel dictationPanel, LocalRuntimeModelsPanel localModelsPanel) {
        super(new BorderLayout());
        this.huggingFacePanel = new OllamaInstallPanel(configurationRepository, askAiService);
        final OllamaLibraryPanel libraryPanel = new OllamaLibraryPanel(askAiService);
        this.tabs = ComicControls.tabbedPaneWithTrailingTab();
        tabs.addTab("Hugging Face", huggingFacePanel);
        tabs.addTab("Ollama Library", libraryPanel);
        if (nlpModelsPanel != null) {
            tabs.addTab(NlpModelsPanel.TAB_TITLE, nlpModelsPanel);
        }
        this.voicesPanel = voicesPanel;
        if (voicesPanel != null) {
            tabs.addTab(VOICES_TAB_TITLE, ComicForm.page(voicesPanel));
        }
        if (dictationPanel != null) {
            tabs.addTab(DICTATION_TAB_TITLE, ComicForm.page(dictationPanel));
        }
        if (localModelsPanel != null) {
            tabs.addTab(LocalRuntimeModelsPanel.TAB_TITLE, localModelsPanel);
        }
        // "Technical details": the log of the source tab used last (Hugging Face or Ollama Library),
        // filling the whole area. Always the last tab, painted right-aligned.
        final CardLayout logCards = new CardLayout();
        final JPanel technicalDetails = new JPanel(logCards);
        technicalDetails.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        technicalDetails.add(huggingFacePanel.technicalDetails(), HF_LOG);
        technicalDetails.add(libraryPanel.technicalDetails(), LIBRARY_LOG);
        tabs.addTab(TECHNICAL_DETAILS, technicalDetails);
        tabs.addChangeListener(event -> {
            java.awt.Component selected = tabs.getSelectedComponent();
            if (selected == huggingFacePanel) {
                logCards.show(technicalDetails, HF_LOG);
            } else if (selected == libraryPanel) {
                logCards.show(technicalDetails, LIBRARY_LOG);
            }
        });
        add(tabs, BorderLayout.CENTER);
    }

    public static final String TECHNICAL_DETAILS = "Technical details";
    private static final String HF_LOG = "hf";
    private static final String LIBRARY_LOG = "library";

    /**
     * Wires where a 🔊 speech-output discovery entry lands (voice id → the chat settings' Audio
     * &amp; Dictation section — the entries are a SHORTCUT there, never a GGUF/Ollama import).
     */
    public void setSpeechOutputOpener(java.util.function.Consumer<String> opener) {
        huggingFacePanel.setSpeechOutputOpener(opener);
    }

    /** Selects the Voices tab and marks {@code voiceId}'s row (target of the 🔊 discovery entries). */
    public void openVoices(String voiceId) {
        if (voicesPanel == null) {
            return;
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (VOICES_TAB_TITLE.equals(tabs.getTitleAt(i))) {
                tabs.setSelectedIndex(i);
            }
        }
        voicesPanel.highlightVoice(voiceId);
    }

    /** Selects the Dictation tab (Whisper models), e.g. from "Install models…" in Settings > Speech. */
    public void openDictationModels() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (DICTATION_TAB_TITLE.equals(tabs.getTitleAt(i))) {
                tabs.setSelectedIndex(i);
            }
        }
    }

    /** The tab titles in order (for diagnostics/tests). */
    public java.util.List<String> tabTitles() {
        java.util.List<String> titles = new java.util.ArrayList<String>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            titles.add(tabs.getTitleAt(i));
        }
        return titles;
    }

    /** Selects the Hugging Face tab and runs a search for {@code query} (used to find model add-ons). */
    public void openHuggingFaceSearch(String query) {
        tabs.setSelectedComponent(huggingFacePanel);
        huggingFacePanel.searchFor(query);
    }

    /**
     * Selects the Hugging Face tab and enters add-on mode for {@code existingModelName}: the chosen encoder
     * GGUF is attached to that already-installed model via {@code from}/{@code adapters}, not installed anew.
     */
    public void openHuggingFaceAddOnSearch(String existingModelName, String query) {
        tabs.setSelectedComponent(huggingFacePanel);
        huggingFacePanel.openHuggingFaceAddOnSearch(existingModelName, query);
    }

    /** Selects the Hugging Face tab and enters add-on mode via a local projector-file chooser. */
    public void openLocalProjectorAddOn(String existingModelName) {
        tabs.setSelectedComponent(huggingFacePanel);
        huggingFacePanel.openLocalProjectorAddOn(existingModelName);
    }

    /** Drops any transient add-on target (called when the owning frame leaves the Setup view). */
    public void leaveAddOnMode() {
        huggingFacePanel.leaveAddOnMode();
    }

    /** Wires the callback fired after a verified encoder attach (owner reloads Installed Models). */
    public void setAddOnAttachedListener(Runnable listener) {
        huggingFacePanel.setAddOnAttachedListener(listener);
    }
}
