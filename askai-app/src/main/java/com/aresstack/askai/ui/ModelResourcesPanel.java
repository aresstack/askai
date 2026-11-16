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
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The settings of the model port's resource scheduling (ADR 0001, 4.5): capacity per model and endpoint,
 * the GPU memory budget per endpoint, keep-alive, the context per MODEL (plan 4.1, rule 1: every caller sends
 * it), and per model slot (role) the model, the thinking mode and the answer budget.
 * The model of a slot is picked from an editable dropdown: an empty entry (the slot inherits along
 * {@link ModelPortSettings#resolveModel}) and every model of the model catalog (the installed models); a name
 * that is not installed can still be typed. The list is never a fixed list in code.
 * Every value is a setting with a default; nothing here is a constant in code. Saved to the model-port
 * settings file; the port reads it on every call, so a save applies to the next call.
 */
public final class ModelResourcesPanel extends JPanel {

    private static final String[] CAPACITY_COLUMNS = {"Model", "Concurrent calls"};
    private static final String[] SLOT_COLUMNS = {"Slot (role)", "Model (empty = inherited, default gemma4:e2b)", "Thinking",
            "Answer budget (0 = role default)"};
    private static final String[] CONTEXT_COLUMNS = {"Model", "Context tokens"};
    private static final String[] THINKING_CHOICES = {"Auto", "Off", "Low", "Medium", "High"};
    private static final int SLOT_COLUMN = 0;
    private static final int MODEL_COLUMN = 1;
    /** How the empty dropdown entry reads in the list: the slot keeps no model of its own. */
    static final String INHERIT_CHOICE = "(inherit)";

    /** The models a slot may use: the installed models of the model catalog. Called off the EDT. */
    public interface ModelCatalog {
        List<String> modelNames() throws Exception;
    }

    private final ModelPortSettingsRepository repository;
    private final ModelCatalog catalog;
    private final Executor background;
    private final Executor ui;
    /** The catalog's model names, as last loaded (EDT only). */
    private List<String> catalogModels = Collections.emptyList();
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
    private final JTable slotTable = new JTable(slots);
    private final JComboBox<String> slotModelChoice = ComicControls.comboBox();

    public ModelResourcesPanel(ModelPortSettingsRepository repository, ModelCatalog catalog) {
        this(repository, catalog, ModelResourcesPanel::loadInBackground, SwingUtilities::invokeLater);
    }

    /** {@code background} lists the catalog; {@code ui} applies the result on the EDT. */
    ModelResourcesPanel(ModelPortSettingsRepository repository, ModelCatalog catalog, Executor background,
                        Executor ui) {
        this.repository = repository;
        this.catalog = catalog;
        this.background = background;
        this.ui = ui;
        buildUserInterface();
        load(repository.load());
        reloadCatalog();
        // The installed models change under Models > Setup: re-read them whenever the page is shown again.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                reloadCatalog();
            }
        });
    }

    private static void loadInBackground(Runnable task) {
        Thread thread = new Thread(task, "model-resources-catalog");
        thread.setDaemon(true);
        thread.start();
    }

    /** Lists the catalog off the EDT; the dropdown offers the result from its next opening on. */
    void reloadCatalog() {
        background.execute(() -> {
            List<String> names;
            String failure = null;
            try {
                List<String> listed = catalog == null ? null : catalog.modelNames();
                names = listed == null ? Collections.<String>emptyList() : new ArrayList<String>(listed);
            } catch (Exception unreachable) {
                names = Collections.emptyList();
                failure = unreachable.getMessage();
            }
            final List<String> loaded = names;
            final String problem = failure;
            ui.execute(() -> {
                catalogModels = loaded;
                if (problem != null) {
                    status.setText("Installed models could not be listed (" + problem
                            + "); a model name can still be typed.");
                }
            });
        });
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
        slotModelChoice.setEditable(true); // a model that is not installed (yet) can still be typed
        slotModelChoice.setRenderer(new InheritChoiceRenderer(slotModelChoice.getRenderer()));
        slotTable.getColumnModel().getColumn(MODEL_COLUMN).setCellEditor(new SlotModelEditor());
        slotTable.getColumnModel().getColumn(MODEL_COLUMN).setCellRenderer(new SlotModelRenderer());
        slotTable.getColumnModel().getColumn(2).setCellEditor(
                new DefaultCellEditor(ComicControls.comboBox(THINKING_CHOICES)));
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

    /**
     * The dropdown entries for a slot's model: the empty entry (inherit) first, then every catalog model, then
     * every model already named for a slot and the cell's own value when the catalog does not list them, so a
     * saved choice is never lost silently. No entry comes from code.
     */
    static List<String> slotModelOptions(String current, List<String> catalogModels, Collection<String> named) {
        List<String> options = new ArrayList<String>();
        options.add("");
        List<String> candidates = new ArrayList<String>();
        if (catalogModels != null) {
            candidates.addAll(catalogModels);
        }
        if (named != null) {
            candidates.addAll(named);
        }
        candidates.add(current);
        for (String candidate : candidates) {
            String model = candidate == null ? "" : candidate.trim();
            if (!model.isEmpty() && !options.contains(model)) {
                options.add(model);
            }
        }
        return options;
    }

    /** The model a slot without its own model inherits, along the port's own chain (parent, default, preset). */
    static String inheritedModel(String slot, Map<String, String> slotModels) {
        Map<String, String> others = new LinkedHashMap<String, String>(slotModels);
        others.remove(slot);
        return new ModelPortSettings(ModelPortSettings.DEFAULT_CAPACITY, null, null, others, null, null, null, null,
                ModelPortSettings.DEFAULT_IMPLICIT_WAIT_SECONDS, ModelPortSettings.DEFAULT_RESIDENCY_REFRESH_SECONDS)
                .resolveModel(slot);
    }

    /** The slot models as the table shows them now (empty = inherited). */
    private Map<String, String> tableSlotModels() {
        Map<String, String> models = new LinkedHashMap<String, String>();
        for (int row = 0; row < slots.getRowCount(); row++) {
            models.put(text(slots.getValueAt(row, SLOT_COLUMN)), text(slots.getValueAt(row, MODEL_COLUMN)));
        }
        return models;
    }

    /** The model column's editor: the dropdown, refilled for the cell each time editing starts. */
    private final class SlotModelEditor extends DefaultCellEditor {
        private static final long serialVersionUID = 1L;

        SlotModelEditor() {
            super(slotModelChoice);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row,
                                                     int column) {
            String current = text(value);
            slotModelChoice.removeAllItems();
            for (String option : slotModelOptions(current, catalogModels, tableSlotModels().values())) {
                slotModelChoice.addItem(option);
            }
            return super.getTableCellEditorComponent(table, current, selected, row, column);
        }

        @Override
        public Object getCellEditorValue() {
            return text(super.getCellEditorValue());
        }
    }

    /** An empty model cell shows what the slot inherits, so the chain stays visible. */
    private final class SlotModelRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            String model = text(value);
            boolean inherited = model.isEmpty();
            String shown = inherited ? "inherited: " + inheritedModel(text(slots.getValueAt(row, SLOT_COLUMN)),
                    tableSlotModels()) : model;
            Component cell = super.getTableCellRendererComponent(table, shown, selected, focused, row, column);
            if (!selected) {
                java.awt.Color muted = UIManager.getColor("Label.disabledForeground");
                cell.setForeground(inherited && muted != null ? muted : table.getForeground());
            }
            return cell;
        }
    }

    /** Shows the empty dropdown entry as {@link #INHERIT_CHOICE}; every other entry as the theme renders it. */
    private static final class InheritChoiceRenderer implements ListCellRenderer<Object> {
        private final ListCellRenderer<Object> base;

        @SuppressWarnings("unchecked")
        InheritChoiceRenderer(ListCellRenderer<?> base) {
            this.base = (ListCellRenderer<Object>) base;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                      boolean focused) {
            Object shown = index >= 0 && text(value).isEmpty() ? INHERIT_CHOICE : value;
            @SuppressWarnings("unchecked")
            JList<Object> objects = (JList<Object>) list;
            return base.getListCellRendererComponent(objects, shown, index, selected, focused);
        }
    }

    /** The slot table (tests drive its editor). */
    JTable slotTable() {
        return slotTable;
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

    void save() {
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
