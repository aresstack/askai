package com.aresstack.askai.ui;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.client.ModelContextSizes;
import com.aresstack.askai.modelport.CentralModelDirectories;
import com.aresstack.askai.modelport.ModelPortSettings;
import com.aresstack.askai.modelport.ModelPortSettingsRepository;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings of the model port's resource scheduling (ADR 0001, 4.5): capacity per model and endpoint,
 * the GPU memory budget per endpoint, keep-alive, the context per MODEL (plan 4.1, rule 1: every caller sends
 * it), and per model slot (role) the model, the thinking mode and the answer budget.
 * Every value is a setting with a default; nothing here is a constant in code. Saved to the model-port
 * settings file; the port reads it on every call, so a save applies to the next call.
 */
public final class ModelResourcesPanel extends JPanel {

    private static final String[] CAPACITY_COLUMNS = {"Model", "Concurrent calls"};
    private static final String[] SLOT_COLUMNS = {"Slot (role)", "Model (empty = main model)", "Thinking",
            "Answer budget (0 = role default)"};
    private static final String[] CONTEXT_COLUMNS = {"Model", "Context tokens"};
    private static final String[] THINKING_CHOICES = {"Auto", "Off", "Low", "Medium", "High"};

    private final ModelPortSettingsRepository repository;
    private final JSpinner defaultCapacity = ComicControls.spinner(new SpinnerNumberModel(1, 1, 64, 1));
    private final JTextField keepAlive = ComicControls.textField(8);
    private final JSpinner implicitWait = ComicControls.spinner(new SpinnerNumberModel(600, 0, 86400, 30));
    private final JSpinner ollamaBudget = ComicControls.spinner(new SpinnerNumberModel(-1, -1, 1048576, 1024));
    private final JSpinner localBudget = ComicControls.spinner(new SpinnerNumberModel(-1, -1, 1048576, 1024));
    private final DefaultTableModel capacities = new DefaultTableModel(CAPACITY_COLUMNS, 0);
    private final DefaultTableModel contexts = new DefaultTableModel(CONTEXT_COLUMNS, 0);
    private final DefaultTableModel slots = new DefaultTableModel(SLOT_COLUMNS, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return column > 0;
        }
    };
    private final JLabel status = new JLabel(" ");

    public ModelResourcesPanel(ModelPortSettingsRepository repository) {
        this.repository = repository;
        buildUserInterface();
        load(repository.load());
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(ComicControls.titledBorder("Model resources (capacity, memory, slots)"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(form, constraints, row++, "Concurrent calls per model (default, mirrors OLLAMA_NUM_PARALLEL)",
                defaultCapacity);
        addRow(form, constraints, row++, "Keep model loaded after a call (keep_alive)", keepAlive);
        addRow(form, constraints, row++, "Max. wait for a free place (seconds)", implicitWait);
        addRow(form, constraints, row++, "GPU memory budget, Ollama (MiB, -1 = not set)", ollamaBudget);
        addRow(form, constraints, row, "GPU memory budget, local runtime (MiB, -1 = not set)", localBudget);

        JTable capacityTable = new JTable(capacities);
        JTable slotTable = new JTable(slots);
        slotTable.getColumnModel().getColumn(2).setCellEditor(
                new DefaultCellEditor(new JComboBox<String>(THINKING_CHOICES)));
        JTable contextTable = new JTable(contexts);
        JPanel contextButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addContext = new ComicButton("Add model");
        addContext.addActionListener(event -> contexts.addRow(new Object[]{"",
                String.valueOf(ModelContextSizes.OTHER_MODELS)}));
        JButton removeContext = new ComicButton("Remove model");
        removeContext.addActionListener(event -> {
            int selected = contextTable.getSelectedRow();
            if (selected >= 0) {
                contexts.removeRow(selected);
            }
        });
        contextButtons.add(addContext);
        contextButtons.add(removeContext);
        JPanel contextPanel = new JPanel(new BorderLayout(4, 4));
        contextPanel.setBorder(ComicControls.titledBorder("Context per model (num_ctx, sent by every caller)"));
        contextPanel.add(scroll(contextTable), BorderLayout.CENTER);
        contextPanel.add(contextButtons, BorderLayout.SOUTH);
        JPanel capacityButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addCapacity = new ComicButton("Add model");
        addCapacity.addActionListener(event -> capacities.addRow(new Object[]{"", "1"}));
        JButton removeCapacity = new ComicButton("Remove model");
        removeCapacity.addActionListener(event -> {
            int selected = capacityTable.getSelectedRow();
            if (selected >= 0) {
                capacities.removeRow(selected);
            }
        });
        capacityButtons.add(addCapacity);
        capacityButtons.add(removeCapacity);
        JPanel capacityPanel = new JPanel(new BorderLayout(4, 4));
        capacityPanel.setBorder(ComicControls.titledBorder("Concurrent calls per model (overrides)"));
        capacityPanel.add(scroll(capacityTable), BorderLayout.CENTER);
        capacityPanel.add(capacityButtons, BorderLayout.SOUTH);
        JPanel slotPanel = new JPanel(new BorderLayout(4, 4));
        slotPanel.setBorder(ComicControls.titledBorder("Model slots (one per role)"));
        slotPanel.add(scroll(slotTable), BorderLayout.CENTER);

        JPanel tables = new JPanel(new GridLayout(1, 3, 8, 8));
        tables.add(capacityPanel);
        tables.add(contextPanel);
        tables.add(slotPanel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton save = new ComicButton("Save model resources");
        save.addActionListener(event -> {
            if (slotTable.isEditing()) {
                slotTable.getCellEditor().stopCellEditing();
            }
            if (capacityTable.isEditing()) {
                capacityTable.getCellEditor().stopCellEditing();
            }
            if (contextTable.isEditing()) {
                contextTable.getCellEditor().stopCellEditing();
            }
            save();
        });
        buttons.add(save);
        buttons.add(status);

        add(form, BorderLayout.NORTH);
        add(tables, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private static JScrollPane scroll(JTable table) {
        JScrollPane pane = new JScrollPane(table);
        pane.setPreferredSize(new Dimension(320, 140));
        return pane;
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

    private void load(ModelPortSettings settings) {
        defaultCapacity.setValue(settings.getDefaultCapacity());
        keepAlive.setText(settings.getKeepAlive());
        implicitWait.setValue(settings.getImplicitWaitSeconds());
        ollamaBudget.setValue(budgetMb(settings, CentralModelDirectories.OLLAMA));
        localBudget.setValue(budgetMb(settings, CentralModelDirectories.LOCAL_RUNTIME));
        capacities.setRowCount(0);
        for (Map.Entry<String, Integer> entry : settings.getModelCapacities().entrySet()) {
            capacities.addRow(new Object[]{entry.getKey(), String.valueOf(entry.getValue())});
        }
        contexts.setRowCount(0);
        for (String model : contextModels(settings)) {
            contexts.addRow(new Object[]{model, String.valueOf(settings.contextOf(model))});
        }
        slots.setRowCount(0);
        for (String slot : settings.knownSlotNames()) {
            slots.addRow(new Object[]{slot, settings.slotModel(slot), thinkingLabel(settings.slotThinking(slot)),
                    String.valueOf(settings.slotAnswerTokens(slot))});
        }
    }

    /** The presets, every configured context, and every model named for a slot or a capacity. */
    static java.util.List<String> contextModels(ModelPortSettings settings) {
        java.util.List<String> models = new java.util.ArrayList<String>(ModelContextSizes.PRESETS.keySet());
        java.util.List<String> named = new java.util.ArrayList<String>(settings.getModelContextTokens().keySet());
        named.addAll(settings.getSlotModels().values());
        named.addAll(settings.getModelCapacities().keySet());
        for (String model : named) {
            if (!model.trim().isEmpty() && !models.contains(model.trim())) {
                models.add(model.trim());
            }
        }
        return models;
    }

    static String thinkingLabel(ThinkingMode mode) {
        switch (mode) {
            case OFF:
                return "Off";
            case LOW:
                return "Low";
            case MEDIUM:
                return "Medium";
            case HIGH:
                return "High";
            default:
                return "Auto";
        }
    }

    static ThinkingMode thinkingOf(String label) {
        String value = label == null ? "" : label.trim();
        for (ThinkingMode mode : new ThinkingMode[]{ThinkingMode.OFF, ThinkingMode.LOW, ThinkingMode.MEDIUM,
                ThinkingMode.HIGH}) {
            if (thinkingLabel(mode).equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return ThinkingMode.DEFAULT;
    }

    private static int budgetMb(ModelPortSettings settings, String endpointId) {
        Long mb = settings.getEndpointMemoryBudgetsMb().get(endpointId);
        return mb == null || mb < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, mb);
    }

    private void save() {
        ModelPortSettings current = repository.load();
        Map<String, Integer> modelCapacities = new LinkedHashMap<String, Integer>();
        for (int row = 0; row < capacities.getRowCount(); row++) {
            String model = text(capacities.getValueAt(row, 0));
            int value = number(capacities.getValueAt(row, 1));
            if (!model.isEmpty() && value >= 1) {
                modelCapacities.put(model, value);
            }
        }
        Map<String, Integer> modelContexts = new LinkedHashMap<String, Integer>();
        for (int row = 0; row < contexts.getRowCount(); row++) {
            String model = text(contexts.getValueAt(row, 0));
            int value = number(contexts.getValueAt(row, 1));
            if (!model.isEmpty() && value >= 1 && value != ModelContextSizes.preset(model)) {
                modelContexts.put(model, value); // a preset stays a preset, so it follows the code's value
            }
        }
        Map<String, String> slotModels = new LinkedHashMap<String, String>();
        Map<String, ThinkingMode> slotThinking = new LinkedHashMap<String, ThinkingMode>();
        Map<String, Integer> slotAnswers = new LinkedHashMap<String, Integer>();
        for (int row = 0; row < slots.getRowCount(); row++) {
            String slot = text(slots.getValueAt(row, 0));
            slotModels.put(slot, text(slots.getValueAt(row, 1)));
            slotThinking.put(slot, thinkingOf(text(slots.getValueAt(row, 2))));
            slotAnswers.put(slot, Math.max(0, number(slots.getValueAt(row, 3))));
        }
        Map<String, Long> budgets = new LinkedHashMap<String, Long>(current.getEndpointMemoryBudgetsMb());
        budgets.put(CentralModelDirectories.OLLAMA, ((Number) ollamaBudget.getValue()).longValue());
        budgets.put(CentralModelDirectories.LOCAL_RUNTIME, ((Number) localBudget.getValue()).longValue());
        ModelPortSettings updated = new ModelPortSettings(((Number) defaultCapacity.getValue()).intValue(),
                modelCapacities, budgets, slotModels, modelContexts, slotThinking, slotAnswers, keepAlive.getText(),
                ((Number) implicitWait.getValue()).intValue(), current.getResidencyRefreshSeconds());
        try {
            repository.save(updated);
            load(updated);
            status.setText("Saved. Applies to the next model call.");
        } catch (IOException failed) {
            status.setText("Could not save: " + failed.getMessage());
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static int number(Object value) {
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}
