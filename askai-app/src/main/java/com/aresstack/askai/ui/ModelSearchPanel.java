package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.service.AskAiService;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;

/**
 * The Setup view's model-search container: two clearly separated sources in tabs — HuggingFace
 * (search, analyze, convert/import) and the Ollama Library (scrape ollama.com, pick a tag, pull it
 * on the remote server). The two result lists are never mixed because their install actions differ.
 * The HuggingFace tab is the unchanged {@link OllamaInstallPanel}.
 */
public final class ModelSearchPanel extends JPanel {

    private final JTabbedPane tabs;
    private final OllamaInstallPanel huggingFacePanel;

    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService) {
        this(configurationRepository, askAiService, null);
    }

    /**
     * @param nlpModelsPanel the NLP model tab (curated OpenNLP sentence models); when non-null it is added as a
     *                       third tab. Prebuilt by the owner so this container stays free of NLP-store details.
     */
    public ModelSearchPanel(AppConfigurationRepository configurationRepository, AskAiService askAiService,
                            NlpModelsPanel nlpModelsPanel) {
        super(new BorderLayout());
        this.huggingFacePanel = new OllamaInstallPanel(configurationRepository, askAiService);
        this.tabs = ComicControls.tabbedPane();
        tabs.addTab("Hugging Face", huggingFacePanel);
        tabs.addTab("Ollama Library", new OllamaLibraryPanel(askAiService));
        if (nlpModelsPanel != null) {
            tabs.addTab(NlpModelsPanel.TAB_TITLE, nlpModelsPanel);
        }
        add(tabs, BorderLayout.CENTER);
    }

    /**
     * Wires where a 🔊 speech-output discovery entry lands (voice id → the chat settings' Audio
     * &amp; Dictation section — the entries are a SHORTCUT there, never a GGUF/Ollama import).
     */
    public void setSpeechOutputOpener(java.util.function.Consumer<String> opener) {
        huggingFacePanel.setSpeechOutputOpener(opener);
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
