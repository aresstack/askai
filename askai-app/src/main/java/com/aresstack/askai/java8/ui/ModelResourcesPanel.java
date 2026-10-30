package com.aresstack.askai.java8.ui;

import com.aresstack.askai.java8.modelport.CentralModelDirectories;
import com.aresstack.askai.java8.modelport.ModelPortSettings;
import com.aresstack.askai.java8.modelport.ModelPortSettingsRepository;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
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
 * the GPU memory budget per endpoint, keep-alive, and per model slot (role) the model and the context size.
 * Every value is a setting with a default; nothing here is a constant in code. Saved to the model-port
 * settings file; the port reads it on every call, so a save applies to the next call.
 */
public final class ModelResourcesPanel extends JPanel {

    private static final String[] CAPACITY_COLUMNS = {"Model", "Concurrent calls"};
    private static final String[] SLOT_COLUMNS = {"Slot (role)", "Model (empty = main model)", "Context tokens (0 = model default)"};

    private final ModelPortSettingsRepository repository;
    private final JSpinner defaultCapacity = ComicControls.spinner(new SpinnerNumberModel(1, 1, 64, 1));
    private final JTextField keepAlive = ComicControls.textField(8);
    private final JSpinner implicitWait = ComicControls.spinner(new SpinnerNumberModel(600, 0, 86400, 30));
    private final JSpinner ollamaBudget = ComicControls.spinner(new SpinnerNumberModel(-1, -1, 1048576, 1024));
    private final JSpinner localBudget = ComicControls.spinner(new SpinnerNumberModel(-1, -1, 1048576, 1024));
    private final DefaultTableModel capacities = new DefaultTableModel(CAPACITY_COLUMNS, 0);
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

        JPanel tables = new JPanel(new GridLayout(1, 2, 8, 8));
        tables.add(capacityPanel);
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
        slots.setRowCount(0);
        for (String slot : settings.knownSlotNames()) {
            slots.addRow(new Object[]{slot, settings.slotModel(slot),
                    String.valueOf(settings.slotContextTokens(slot))});
        }
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
        Map<String, String> slotModels = new LinkedHashMap<String, String>();
        Map<String, Integer> slotContexts = new LinkedHashMap<String, Integer>();
        for (int row = 0; row < slots.getRowCount(); row++) {
            String slot = text(slots.getValueAt(row, 0));
            slotModels.put(slot, text(slots.getValueAt(row, 1)));
            slotContexts.put(slot, Math.max(0, number(slots.getValueAt(row, 2))));
        }
        Map<String, Long> budgets = new LinkedHashMap<String, Long>(current.getEndpointMemoryBudgetsMb());
        budgets.put(CentralModelDirectories.OLLAMA, ((Number) ollamaBudget.getValue()).longValue());
        budgets.put(CentralModelDirectories.LOCAL_RUNTIME, ((Number) localBudget.getValue()).longValue());
        ModelPortSettings updated = new ModelPortSettings(((Number) defaultCapacity.getValue()).intValue(),
                modelCapacities, budgets, slotModels, slotContexts, keepAlive.getText(),
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
