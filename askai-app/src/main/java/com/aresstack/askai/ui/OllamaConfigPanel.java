package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.service.OllamaService;
import com.aresstack.askai.settings.AskAiPaths;
import com.aresstack.askai.stt.SpeechToTextConfiguration;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Paths;

/**
 * Edits Ollama endpoint and local model storage settings.
 */
public final class OllamaConfigPanel extends JPanel {

    private final AskAiModel model;
    private final OllamaService ollamaService;
    private final JTextField baseUrlField;
    private final JTextField modelRootField;
    private final JTextField quantizationField;
    private final JTextField keepAliveField;
    private final JCheckBox sttEnabledBox;
    private final JComboBox<String> sttBackendCombo;
    private final JTextField sttModelField;
    private final JTextField sttLanguageField;
    private final JTextField sttPromptField;
    private final JTextField sttMaxFileSizeField;
    private final JTextField sttTimeoutField;
    /** Per-call read timeout of the MAIN model (seconds) — a setting, never a code constant. */
    private final JTextField mainModelTimeoutField;
    private final JTextArea logArea;

    public OllamaConfigPanel(AskAiModel model, OllamaService ollamaService) {
        this.model = model;
        this.ollamaService = ollamaService;
        this.baseUrlField = ComicControls.textField(model.getOllamaBaseUrl(), 42);
        this.modelRootField = ComicControls.textField(model.getModelRoot().toString(), 42);
        this.quantizationField = ComicControls.textField(model.getDefaultQuantization(), 20);
        this.keepAliveField = ComicControls.textField(model.getDefaultKeepAlive(), 20);
        SpeechToTextConfiguration stt = model.getSpeechToTextConfiguration();
        this.sttEnabledBox = ComicControls.checkBox("Enabled", stt.isEnabled());
        this.sttBackendCombo = ComicControls.<String>comboBox(new String[]{SpeechToTextConfiguration.Backend.OLLAMA.name()});
        this.sttBackendCombo.setSelectedItem(stt.getBackend().name());
        this.sttModelField = ComicControls.textField(stt.getModelName(), 30);
        this.sttLanguageField = ComicControls.textField(stt.getLanguage(), 8);
        this.sttPromptField = ComicControls.textField(stt.getPrompt(), 42);
        this.sttMaxFileSizeField = ComicControls.textField(String.valueOf(stt.getMaxFileSizeMb()), 8);
        this.sttTimeoutField = ComicControls.textField(String.valueOf(stt.getTimeoutSeconds()), 8);
        this.mainModelTimeoutField = ComicControls.textField(
                String.valueOf(model.getAiModelSelections().getMainModelTimeoutSeconds()), 8);
        this.logArea = ComicControls.textArea(16, 70);
        buildUserInterface();
        append("Config loaded.");
        append("Settings file: " + AskAiPaths.settingsFile());
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(ComicControls.titledBorder("Ollama endpoint"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addRow(form, constraints, 0, "Base URL", baseUrlField);
        addRow(form, constraints, 1, "Local model root", modelRootField);
        addRow(form, constraints, 2, "Create quantization", quantizationField);
        addRow(form, constraints, 3, "Chat keep_alive", keepAliveField);
        mainModelTimeoutField.setToolTipText("Lese-Timeout pro Modell-Aufruf des Hauptmodells. Lange "
                + "Antworten (z. B. Quellen-Auswertungen mit großem Antwortbudget) brauchen auf kleinen "
                + "lokalen Modellen mehrere Minuten.");
        addRow(form, constraints, 4, "Main model timeout (seconds)", mainModelTimeoutField);

        JPanel sttForm = new JPanel(new GridBagLayout());
        sttForm.setBorder(ComicControls.titledBorder("Speech-to-Text"));
        GridBagConstraints sttConstraints = new GridBagConstraints();
        sttConstraints.insets = new Insets(4, 4, 4, 4);
        sttConstraints.anchor = GridBagConstraints.WEST;
        sttConstraints.fill = GridBagConstraints.HORIZONTAL;
        addRow(sttForm, sttConstraints, 0, "Speech-to-Text", sttEnabledBox);
        addRow(sttForm, sttConstraints, 1, "Backend", sttBackendCombo);
        addRow(sttForm, sttConstraints, 2, "STT model (empty = chat model)", sttModelField);
        addRow(sttForm, sttConstraints, 3, "Default language (auto/de/en/...)", sttLanguageField);
        addRow(sttForm, sttConstraints, 4, "Context prompt (optional)", sttPromptField);
        addRow(sttForm, sttConstraints, 5, "Max file size (MB)", sttMaxFileSizeField);
        addRow(sttForm, sttConstraints, 6, "Timeout (seconds)", sttTimeoutField);

        JPanel forms = new JPanel();
        forms.setLayout(new BoxLayout(forms, BoxLayout.Y_AXIS));
        forms.add(form);
        forms.add(sttForm);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton saveButton = new ComicButton("Save");
        saveButton.addActionListener(event -> save());
        JButton testButton = new ComicButton("Test Connection");
        testButton.addActionListener(event -> testConnection());
        buttons.add(saveButton);
        buttons.add(testButton);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(forms, BorderLayout.CENTER);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        add(new JScrollPane(logArea), BorderLayout.CENTER);
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

    private void addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component field) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0.0d;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1.0d;
        form.add(field, constraints);
    }

    private void save() {
        model.setOllamaBaseUrl(baseUrlField.getText());
        model.setModelRoot(Paths.get(modelRootField.getText().trim()));
        model.setDefaultQuantization(quantizationField.getText());
        model.setDefaultKeepAlive(keepAliveField.getText());
        model.setSpeechToTextConfiguration(new SpeechToTextConfiguration(
                sttEnabledBox.isSelected(),
                SpeechToTextConfiguration.parseBackend(String.valueOf(sttBackendCombo.getSelectedItem())),
                sttModelField.getText(),
                sttLanguageField.getText(),
                sttPromptField.getText(),
                parsePositiveInt(sttMaxFileSizeField.getText(), SpeechToTextConfiguration.DEFAULT_MAX_FILE_SIZE_MB),
                parsePositiveInt(sttTimeoutField.getText(), SpeechToTextConfiguration.DEFAULT_TIMEOUT_SECONDS)));
        model.setAiModelSelections(model.getAiModelSelections().withMainModelTimeoutSeconds(
                parsePositiveInt(mainModelTimeoutField.getText(),
                        com.aresstack.askai.config.AiModelSelections
                                .DEFAULT_MAIN_MODEL_TIMEOUT_SECONDS)));
        model.saveSettings();
        append("Saved settings. Ollama: " + model.getOllamaBaseUrl());
        append("Speech-to-Text: " + (sttEnabledBox.isSelected() ? "enabled" : "disabled")
                + (sttModelField.getText().trim().length() > 0
                ? ", model " + sttModelField.getText().trim() : ", using the chat model"));
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
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
