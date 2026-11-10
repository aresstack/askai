package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicDurationField;
import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.service.OllamaService;
import com.aresstack.askai.settings.AskAiPaths;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Paths;

/**
 * Settings → Connections: the Ollama endpoint and the local model storage. Durations are spinners with a
 * unit — no free-text numbers to get wrong. Dictation (speech-to-text) is NOT a connection: it lives under
 * Settings → Speech, next to the speech output.
 */
public final class OllamaConfigPanel extends JPanel {

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final JTextField baseUrlField;
    private final JTextField modelRootField;
    private final ComicDurationField mainModelTimeoutField;
    private final ComicDurationField rerankerTimeoutField;
    private final JTextArea logArea;

    public OllamaConfigPanel(AskAiModel model, OllamaService ollamaService) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.baseUrlField = ComicControls.textField(model.getOllamaBaseUrl(), 30);
        this.modelRootField = ComicControls.textField(model.getModelRoot().toString(), 30);
        this.mainModelTimeoutField = new ComicDurationField(24 * 3600);
        this.mainModelTimeoutField.setSeconds(model.getAiModelSelections().getMainModelTimeoutSeconds());
        this.rerankerTimeoutField = new ComicDurationField(24 * 3600);
        this.rerankerTimeoutField.setSeconds(model.getAiModelSelections().getRerankerTimeoutSeconds());
        this.logArea = ComicControls.textArea(6, 20);
        buildUserInterface();
        append("Config loaded.");
        append("Settings file: " + AskAiPaths.settingsFile());
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout());
        setOpaque(false);

        ComicForm endpoint = ComicForm.titled("Ollama endpoint");
        baseUrlField.setToolTipText("The Ollama HTTP API this AskAI talks to, e.g. http://127.0.0.1:11434.");
        endpoint.row("Base URL", baseUrlField);
        modelRootField.setToolTipText("Where locally created models and downloads are stored.");
        endpoint.row("Local model root", modelRootField);
        mainModelTimeoutField.setToolTipText("How long ONE call of the main (chat) model may take before"
                + " AskAI gives up. Long answers on small local models can take several minutes.");
        endpoint.row("Main model timeout", mainModelTimeoutField);
        rerankerTimeoutField.setToolTipText("How long ONE call of the reranker (the local cross-encoder scoring"
                + " search results and visited pages) may take. On a CPU backend a full page needs well over the"
                + " former 15 s; a call that runs out falls back to keyword ranking.");
        endpoint.row("Reranker timeout", rerankerTimeoutField);
        endpoint.note("How long models stay loaded after a request (keep-alive) is set under Settings > AI models.");

        endpoint.note("Dictation (speech-to-text) is set up under Settings > Speech.");

        JButton saveButton = new ComicButton("Save");
        saveButton.addActionListener(event -> save());
        JButton testButton = new ComicButton("Test connection");
        testButton.addActionListener(event -> testConnection());
        ComicForm actions = new ComicForm();
        actions.actions(saveButton, testButton);

        ComicForm log = ComicForm.titled("Log");
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(100, 140));
        log.component(logScroll);

        add(ComicForm.page(endpoint, actions, log), BorderLayout.CENTER);
    }

    /**
     * Shows the model's current values again, e.g. after the setup wizard saved a new Ollama address, so a later
     * “Save” here never writes a stale address back.
     */
    public void reloadFromModel() {
        baseUrlField.setText(model.getOllamaBaseUrl());
        modelRootField.setText(model.getModelRoot().toString());
        mainModelTimeoutField.setSeconds(model.getAiModelSelections().getMainModelTimeoutSeconds());
        rerankerTimeoutField.setSeconds(model.getAiModelSelections().getRerankerTimeoutSeconds());
    }

    /** Puts the keyboard focus on the Base URL field and selects its text, ready to overwrite. */
    public void focusBaseUrl() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                baseUrlField.requestFocusInWindow();
                baseUrlField.selectAll();
            }
        });
    }

    private void save() {
        model.setOllamaBaseUrl(baseUrlField.getText());
        model.setModelRoot(Paths.get(modelRootField.getText().trim()));
        model.setAiModelSelections(model.getAiModelSelections().withMainModelTimeoutSeconds(
                positive((int) Math.min(Integer.MAX_VALUE, mainModelTimeoutField.getSeconds()),
                        com.aresstack.askai.config.AiModelSelections.DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS))
                .withRerankerTimeoutSeconds(
                        positive((int) Math.min(Integer.MAX_VALUE, rerankerTimeoutField.getSeconds()),
                                com.aresstack.askai.config.AiModelSelections.DEFAULT_RERANKER_TIMEOUT_SECONDS)));
        model.saveSettings();
        append("Saved settings. Ollama: " + model.getOllamaBaseUrl());
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private void testConnection() {
        save();
        append("Testing " + model.getOllamaBaseUrl() + " ...");
        ollamaService.getServerVersion(new OllamaService.ServerVersionListener() {
            @Override
            public void onServerVersion(final String version) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        append("Connection OK: " + (version == null || version.isEmpty() ? "Ollama reachable" : version));
                    }
                });
            }

            @Override
            public void onError(final Exception ex) {
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        append("ERROR: " + ex.getMessage());
                    }
                });
            }
        });
    }

    private void append(String message) {
        UiSupport.appendLog(logArea, message);
    }

    private static void onUi(Runnable runnable) {
        SwingUtilities.invokeLater(runnable);
    }
}
