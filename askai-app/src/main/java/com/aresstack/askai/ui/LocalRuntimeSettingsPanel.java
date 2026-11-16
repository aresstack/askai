package com.aresstack.askai.ui;

import com.aresstack.askai.localmodels.LocalRuntimeSettings;
import com.aresstack.askai.localmodels.LocalRuntimeSettingsRepository;
import com.aresstack.askai.localruntime.onnx.OnnxRuntimeOptions;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;

/**
 * Settings of the built-in local runtime (ONNX Runtime): execution provider, threads, int8 graphs, batch size, token
 * cap, and the sentence-embedding slot. ONNX values apply on the next runtime start (AskAI restart).
 */
public final class LocalRuntimeSettingsPanel extends JPanel {

    private final LocalRuntimeSettingsRepository repository;
    private final JComboBox<OnnxRuntimeOptions.ExecutionProvider> provider =
            ComicControls.comboBox(OnnxRuntimeOptions.ExecutionProvider.values());
    private final JSpinner intraOp = ComicControls.spinner(new SpinnerNumberModel(0, 0, 256, 1));
    private final JSpinner interOp = ComicControls.spinner(new SpinnerNumberModel(0, 0, 256, 1));
    private final JCheckBox preferQuantized = ComicControls.checkBox("Install int8-quantized graphs when available");
    private final JSpinner batchSize = ComicControls.spinner(new SpinnerNumberModel(1, 1, 1024, 1));
    private final JSpinner maxTokens = ComicControls.spinner(new SpinnerNumberModel(0, 0, 32768, 64));
    private final JTextField sentenceModel = ComicControls.textField(28);
    private final JLabel status = new JLabel(" ");

    public LocalRuntimeSettingsPanel(LocalRuntimeSettingsRepository repository) {
        this.repository = repository;
        buildUserInterface();
        load(repository.load());
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(ComicControls.titledBorder("Local runtime (ONNX Runtime, CPU)"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        provider.setToolTipText("CPU always works. DirectML needs an ONNX Runtime build with the DirectML provider;"
                + " without it the runtime falls back to the CPU.");
        addRow(form, c, row++, "Execution provider", provider);
        addRow(form, c, row++, "Threads per inference (0 = automatic)", intraOp);
        addRow(form, c, row++, "Parallel graph threads (0 = automatic)", interOp);
        addRow(form, c, row++, "Quantized models", preferQuantized);
        addRow(form, c, row++, "Texts per inference call", batchSize);
        addRow(form, c, row++, "Max. tokens per text (0 = model limit)", maxTokens);
        sentenceModel.setToolTipText("Model for sentence-against-sentence comparisons. Empty, or not installed ="
                + " the main embedding model.");
        addRow(form, c, row, "Sentence embedding model", sentenceModel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton save = new ComicButton("Save local runtime");
        save.addActionListener(event -> save());
        buttons.add(save);
        buttons.add(status);
        add(form, BorderLayout.NORTH);
        add(buttons, BorderLayout.SOUTH);
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, Component field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0.0d;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1.0d;
        form.add(field, c);
    }

    private void load(LocalRuntimeSettings settings) {
        OnnxRuntimeOptions onnx = settings.getOnnx();
        provider.setSelectedItem(onnx.executionProvider());
        intraOp.setValue(onnx.intraOpThreads());
        interOp.setValue(onnx.interOpThreads());
        preferQuantized.setSelected(onnx.preferQuantized());
        batchSize.setValue(onnx.batchSize());
        maxTokens.setValue(onnx.maxTokens());
        sentenceModel.setText(settings.getSentenceEmbeddingModel());
    }

    private void save() {
        OnnxRuntimeOptions onnx = OnnxRuntimeOptions.builder()
                .executionProvider((OnnxRuntimeOptions.ExecutionProvider) provider.getSelectedItem())
                .intraOpThreads(((Number) intraOp.getValue()).intValue())
                .interOpThreads(((Number) interOp.getValue()).intValue())
                .preferQuantized(preferQuantized.isSelected())
                .batchSize(((Number) batchSize.getValue()).intValue())
                .maxTokens(((Number) maxTokens.getValue()).intValue())
                .build();
        LocalRuntimeSettings updated = new LocalRuntimeSettings(onnx, sentenceModel.getText());
        try {
            repository.save(updated);
            load(updated);
            status.setText("Saved. ONNX settings apply after restarting AskAI.");
        } catch (IOException failed) {
            status.setText("Could not save: " + failed.getMessage());
        }
    }
}
