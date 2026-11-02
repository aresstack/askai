package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicDurationField;
import com.aresstack.comiccontrols.control.ComicForm;
import javax.swing.JComponent;
import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelCatalog;
import com.aresstack.askai.agent.model.reranker.RerankerModelCatalog;
import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.config.AiModelSelections;
import com.aresstack.askai.service.VirtualOllamaContainerService;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The central "AI models" settings screen: AskAI owns the reranker and embeddings model selections for
 * ALL plugins, so they are chosen here — not in any plugin's own settings. The MAIN (chat/generation)
 * model is set in the chat window and is only shown read-only here. Selections are model NAMES;
 * endpoints are resolved by the host when it hands a descriptor to a plugin.
 */
public final class AiModelsPanel extends JPanel {

    /** Combo entry for "no explicit selection". */
    private static final String NONE = "";

    private final AskAiModel model;
    private final RerankerModelCatalog rerankerCatalog; // nullable: no local runtime → no rerank models
    private final NlpModelCatalog nlpCatalog;            // nullable: lists installed NLP models per language
    private final VirtualOllamaContainerService ollamaService;

    private final JLabel mainModelValue = new JLabel();
    private final JComboBox<String> rerankerCombo = ComicControls.<String>comboBox();
    private final JComboBox<String> embeddingsCombo = ComicControls.<String>comboBox();
    private final JComboBox<String> nlpSentenceDeCombo = ComicControls.<String>comboBox();
    private final JComboBox<String> nlpSentenceEnCombo = ComicControls.<String>comboBox();
    private final JLabel status = new JLabel(" ");
    private final ComicDurationField keepAliveField = new ComicDurationField(7 * 24 * 60);
    private final java.util.List<JComponent> extraSections = new java.util.ArrayList<JComponent>();
    private JPanel pageHost;

    public AiModelsPanel(AskAiModel model, RerankerModelCatalog rerankerCatalog,
                         NlpModelCatalog nlpCatalog, VirtualOllamaContainerService ollamaService) {
        this.model = model;
        this.rerankerCatalog = rerankerCatalog;
        this.nlpCatalog = nlpCatalog;
        this.ollamaService = ollamaService;
        AiModelSelections selections = model.getAiModelSelections();
        // Seed each combo with the persisted selection so nothing is lost before the list loads.
        seedCombo(rerankerCombo, selections.getRerankerModel());
        seedCombo(embeddingsCombo, selections.getEmbeddingsModel());
        seedCombo(nlpSentenceDeCombo, selections.getNlp().getModelId(NlpCapability.SENTENCE_DETECTION, "de"));
        seedCombo(nlpSentenceEnCombo, selections.getNlp().getModelId(NlpCapability.SENTENCE_DETECTION, "en"));
        buildUserInterface();
        refreshMainModelLabel();
        reloadAvailableModels();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout());
        setOpaque(false);

        ComicForm form = ComicForm.titled("AI models (managed centrally for all plugins)");
        form.row("Main model", mainModelValue);
        form.note("The main (chat) model is chosen in the chat window's model picker.");
        keepAliveField.setText(model.getDefaultKeepAlive());
        keepAliveField.setToolTipText("Ollama keep_alive: how long a model stays loaded in memory after a"
                + " request. One global value for every model AskAI calls (chat, embeddings, reranker)."
                + " Longer = faster follow-up answers, memory stays in use.");
        keepAliveField.addChangeListener(event -> {
            model.setDefaultKeepAlive(keepAliveField.getText());
            model.saveSettings();
        });
        form.row("Keep models loaded for", keepAliveField);
        rerankerCombo.setToolTipText("Re-ranks search results and sources for all plugins; empty = none.");
        form.row("Reranker model", rerankerCombo);
        embeddingsCombo.setToolTipText("Embeddings for the knowledge index; empty = none.");
        form.row("Embeddings model", embeddingsCombo);
        form.row("Sentence detection (German)", nlpSentenceDeCombo);
        form.row("Sentence detection (English)", nlpSentenceEnCombo);
        form.note("The NLP sentence models are installed under Models > Setup > NLP; empty = regex fallback.");

        JButton saveButton = new ComicButton("Save");
        saveButton.addActionListener(event -> save());
        JButton refreshButton = new ComicButton("Refresh models");
        refreshButton.addActionListener(event -> reloadAvailableModels());
        ComicForm actions = new ComicForm();
        actions.actions(saveButton, refreshButton);
        actions.component(status);

        rebuildPage(form, actions);
    }

    private ComicForm mainForm;
    private ComicForm actionsForm;

    private void rebuildPage(ComicForm form, ComicForm actions) {
        mainForm = form;
        actionsForm = actions;
        removeAll();
        java.util.List<JComponent> sections = new java.util.ArrayList<JComponent>();
        sections.add(form);
        sections.add(actions);
        sections.addAll(extraSections);
        add(ComicForm.page(sections.toArray(new JComponent[0])), BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /** Appends a section (e.g. the read-aloud voice installation) below the model selections. */
    public void addSection(JComponent section) {
        extraSections.add(section);
        rebuildPage(mainForm, actionsForm);
    }

    private void refreshMainModelLabel() {
        String main = model.getAiModelSelections().getMainModel();
        mainModelValue.setText(main == null || main.trim().length() == 0
                ? "— (choose a model in the chat window)" : main);
    }

    /** Loads the installed rerank/embedding model names off the EDT, then repopulates on the EDT. */
    private void reloadAvailableModels() {
        status.setText("Loading installed models ...");
        new Thread(new Runnable() {
            public void run() {
                final List<String> rerankers = loadRerankModelNames();
                final List<String> embeddings = loadEmbeddingModelNames();
                final List<String> nlpDe = loadNlpModelNames("de");
                final List<String> nlpEn = loadNlpModelNames("en");
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        populate(rerankerCombo, rerankers);
                        populate(embeddingsCombo, embeddings);
                        populate(nlpSentenceDeCombo, nlpDe);
                        populate(nlpSentenceEnCombo, nlpEn);
                        status.setText(" ");
                    }
                });
            }
        }, "ai-models-refresh").start();
    }

    /** Only ACTUALLY installed NLP sentence-detection models for the language (never a download suggestion). */
    private List<String> loadNlpModelNames(String language) {
        if (nlpCatalog == null) {
            return Collections.emptyList();
        }
        try {
            return nlpCatalog.listInstalledModels(NlpCapability.SENTENCE_DETECTION, language);
        } catch (RuntimeException failure) {
            return Collections.emptyList();
        }
    }

    private List<String> loadRerankModelNames() {
        if (rerankerCatalog == null) {
            return Collections.emptyList();
        }
        try {
            return rerankerCatalog.listInstalledRerankModels();
        } catch (RuntimeException failure) {
            return Collections.emptyList();
        }
    }

    private List<String> loadEmbeddingModelNames() {
        try {
            return ollamaService.loadEmbeddingModelNamesNow();
        } catch (Exception failure) {
            return Collections.emptyList();
        }
    }

    /** Rebuilds a combo as NONE + the available names, preserving the currently selected value. */
    private void populate(JComboBox<String> combo, List<String> available) {
        String current = (String) combo.getSelectedItem();
        List<String> items = optionsFor(current, available);
        combo.removeAllItems();
        for (String item : items) {
            combo.addItem(item);
        }
        combo.setSelectedItem(current == null ? NONE : current);
    }

    /**
     * The combo items for a selector: {@code NONE} first (so "no selection" — the regex fallback — stays
     * possible), then only the ACTUALLY installed/available names, plus a currently-persisted selection that is
     * not (currently) installed so the user never loses it silently. Pure + testable.
     */
    static List<String> optionsFor(String current, List<String> available) {
        List<String> items = new ArrayList<String>();
        items.add(NONE);
        if (available != null) {
            for (String name : available) {
                if (!items.contains(name)) {
                    items.add(name);
                }
            }
        }
        if (current != null && current.length() > 0 && !items.contains(current)) {
            items.add(current);
        }
        return items;
    }

    private void seedCombo(JComboBox<String> combo, String current) {
        combo.addItem(NONE);
        if (current != null && current.length() > 0) {
            combo.addItem(current);
            combo.setSelectedItem(current);
        } else {
            combo.setSelectedItem(NONE);
        }
    }

    private void save() {
        String reranker = (String) rerankerCombo.getSelectedItem();
        String embeddings = (String) embeddingsCombo.getSelectedItem();
        model.persistRerankerAndEmbeddingsModels(reranker, embeddings);
        // NLP sentence models per language (empty = no selection = later regex fallback); de/en independent.
        model.persistNlpSentenceModels((String) nlpSentenceDeCombo.getSelectedItem(),
                (String) nlpSentenceEnCombo.getSelectedItem());
        status.setText("Saved.");
    }
}
