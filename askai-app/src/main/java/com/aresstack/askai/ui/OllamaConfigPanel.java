package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicDurationField;
import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.service.OllamaService;
import com.aresstack.askai.settings.AskAiPaths;
import com.aresstack.askai.stt.SpeechToTextConfiguration;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Paths;

/**
 * Settings → Connections: the Ollama endpoint, the local model storage and the speech-to-text service.
 * One {@link ComicForm} grid per group; durations are spinners with a unit, sizes spinners with their
 * unit beside them — no free-text numbers to get wrong.
 */
public final class OllamaConfigPanel extends JPanel {

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final JTextField baseUrlField;
    private final JTextField modelRootField;
    private final ComicDurationField mainModelTimeoutField;
    private final JCheckBox sttEnabledBox;
    private final JComboBox<String> sttBackendCombo;
    private final JTextField sttModelField;
    private final JComboBox<String> sttLanguageCombo;
    private final JTextField sttPromptField;
    private final JSpinner sttMaxFileSizeField;
    private final ComicDurationField sttTimeoutField;
    private final JTextArea logArea;

    /** The languages offered for transcription; "auto" lets the model detect it. Editable for others. */
    private static final String[] STT_LANGUAGES = {"auto", "de", "en", "fr", "es", "it", "nl", "pt"};

    public OllamaConfigPanel(AskAiModel model, OllamaService ollamaService) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.baseUrlField = ComicControls.textField(model.getOllamaBaseUrl(), 30);
        this.modelRootField = ComicControls.textField(model.getModelRoot().toString(), 30);
        this.mainModelTimeoutField = new ComicDurationField(24 * 3600);
        this.mainModelTimeoutField.setSeconds(model.getAiModelSelections().getMainModelTimeoutSeconds());
        SpeechToTextConfiguration stt = model.getSpeechToTextConfiguration();
        this.sttEnabledBox = ComicControls.checkBox("Transcribe microphone recordings with the speech-to-text service",
                stt.isEnabled());
        this.sttBackendCombo = ComicControls.<String>comboBox(new String[]{SpeechToTextConfiguration.Backend.OLLAMA.name()});
        this.sttBackendCombo.setSelectedItem(stt.getBackend().name());
        this.sttModelField = ComicControls.textField(stt.getModelName(), 20);
        this.sttLanguageCombo = ComicControls.<String>comboBox(STT_LANGUAGES);
        this.sttLanguageCombo.setEditable(true);
        this.sttLanguageCombo.setSelectedItem(stt.getLanguage() == null || stt.getLanguage().trim().isEmpty()
                ? "auto" : stt.getLanguage().trim());
        this.sttPromptField = ComicControls.textField(stt.getPrompt(), 30);
        this.sttMaxFileSizeField = ComicControls.integerSpinner(stt.getMaxFileSizeMb(), 1, 100000, 10, 6);
        this.sttTimeoutField = new ComicDurationField(24 * 3600);
        this.sttTimeoutField.setSeconds(stt.getTimeoutSeconds());
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
        endpoint.note("How long models stay loaded after a request (keep-alive) is set under Settings > AI models.");

        ComicForm stt = ComicForm.titled("Speech-to-Text");
        stt.check(sttEnabledBox);
        stt.row("Backend", sttBackendCombo);
        sttModelField.setToolTipText("Empty = the chat model transcribes.");
        stt.row("STT model", sttModelField);
        sttLanguageCombo.setToolTipText("The language spoken in recordings; \"auto\" lets the model detect it."
                + " Type another ISO code if yours is missing.");
        stt.row("Default language", sttLanguageCombo);
        sttPromptField.setToolTipText("Optional context given to the transcription model (names, jargon).");
        stt.row("Context prompt", sttPromptField);
        stt.row("Max file size", sttMaxFileSizeField, new JLabel("MB"));
        stt.row("Timeout", sttTimeoutField);
        stt.note("The STT model is empty by default; then the chat model transcribes.");
        ComicForm.alignLabels(endpoint, stt);

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

        add(ComicForm.page(endpoint, stt, actions, log), BorderLayout.CENTER);
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
        Object language = sttLanguageCombo.getSelectedItem();
        model.setSpeechToTextConfiguration(new SpeechToTextConfiguration(
                sttEnabledBox.isSelected(),
                SpeechToTextConfiguration.parseBackend(String.valueOf(sttBackendCombo.getSelectedItem())),
                sttModelField.getText(),
                language == null ? "auto" : String.valueOf(language).trim(),
                sttPromptField.getText(),
                ((Number) sttMaxFileSizeField.getValue()).intValue(),
                positive((int) Math.min(Integer.MAX_VALUE, sttTimeoutField.getSeconds()),
                        SpeechToTextConfiguration.DEFAULT_TIMEOUT_SECONDS)));
        model.setAiModelSelections(model.getAiModelSelections().withMainModelTimeoutSeconds(
                positive((int) Math.min(Integer.MAX_VALUE, mainModelTimeoutField.getSeconds()),
                        com.aresstack.askai.config.AiModelSelections.DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS)));
        model.saveSettings();
        append("Saved settings. Ollama: " + model.getOllamaBaseUrl());
        append("Speech-to-Text: " + (sttEnabledBox.isSelected() ? "enabled" : "disabled")
                + (sttModelField.getText().trim().length() > 0
                ? ", model " + sttModelField.getText().trim() : ", using the chat model"));
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
