package com.aresstack.askai.ui;

import com.aresstack.askai.modelport.ModelPortSettings;
import com.aresstack.askai.modelport.ModelPortSettingsRepository;
import com.aresstack.askai.modelport.ModelProviderKind;
import com.aresstack.askai.modelport.ModelProviderSettings;
import com.aresstack.askai.modelport.ModelProviderSettingsRepository;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
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
import java.awt.Insets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Which model service fills the model slots (the research roles): Ollama (the default) or an OpenAI-compatible
 * API. For the API: base URL, API key (stored encrypted, never shown again), the concurrent calls per model, the
 * context the port plans with, and a model per task (slot), picked from the API's own model list or typed. Nothing
 * names a concrete service. Saved to {@code model-provider.properties}; the model port reads it on every call, so
 * a save applies to the next call. Switching to the API turns on what it allows at once: several calls per model,
 * several models side by side, no loading or unloading.
 */
public final class ModelProviderPanel extends JPanel {

    private static final String[] SLOT_COLUMNS = {"Task (slot)", "API model (empty = inherited)"};
    private static final int MODEL_COLUMN = 1;

    private final ModelProviderSettingsRepository repository;
    private final ModelPortSettingsRepository portSettings;
    private final OpenAiCompatibleModelProvider.ConnectionOpener connections;
    private final Executor background;
    private final Executor ui;
    private final JComboBox<ModelProviderKind> provider = ComicControls.comboBox(ModelProviderKind.values());
    private final JTextField baseUrl = ComicControls.textField(32);
    private final JPasswordField apiKey = ComicControls.passwordField(32);
    private final JLabel keyState = new JLabel(" ");
    private final JSpinner capacity = ComicControls.spinner(new SpinnerNumberModel(
            ModelProviderSettings.DEFAULT_API_CAPACITY, 1, 256, 1));
    private final JSpinner context = ComicControls.spinner(new SpinnerNumberModel(
            ModelProviderSettings.DEFAULT_API_CONTEXT_TOKENS, 512, 4_194_304, 1024));
    private final JSpinner retries = ComicControls.spinner(new SpinnerNumberModel(
            ModelProviderSettings.DEFAULT_RETRIES, 0, 10, 1));
    private final JSpinner readTimeout = ComicControls.spinner(new SpinnerNumberModel(
            ModelProviderSettings.DEFAULT_READ_TIMEOUT_SECONDS, 5, 86_400, 30));
    private final DefaultTableModel slots = new DefaultTableModel(SLOT_COLUMNS, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == MODEL_COLUMN;
        }
    };
    private final JTable slotTable = new JTable(slots);
    private final JComboBox<String> slotModelChoice = ComicControls.comboBox();
    private final JLabel status = new JLabel(" ");
    /** The API's model ids, as last listed (EDT only). */
    private List<String> apiModels = Collections.emptyList();

    public ModelProviderPanel(ModelProviderSettingsRepository repository, ModelPortSettingsRepository portSettings,
                              OpenAiCompatibleModelProvider.ConnectionOpener connections) {
        this(repository, portSettings, connections, task -> {
            Thread thread = new Thread(task, "model-provider-models");
            thread.setDaemon(true);
            thread.start();
        }, SwingUtilities::invokeLater);
    }

    ModelProviderPanel(ModelProviderSettingsRepository repository, ModelPortSettingsRepository portSettings,
                       OpenAiCompatibleModelProvider.ConnectionOpener connections, Executor background, Executor ui) {
        this.repository = repository;
        this.portSettings = portSettings;
        this.connections = connections == null ? OpenAiCompatibleModelProvider.PLAIN : connections;
        this.background = background;
        this.ui = ui;
        buildUserInterface();
        load(repository.load());
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(ComicControls.titledBorder("Model provider (Ollama or an OpenAI-compatible API)"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(form, constraints, row++, "Provider of the model slots", provider);
        addRow(form, constraints, row++, "API base URL (usually ends with /v1)", baseUrl);
        JPanel keyRow = new JPanel(new BorderLayout(6, 0));
        keyRow.add(apiKey, BorderLayout.CENTER);
        JButton removeKey = new ComicButton("Remove stored key");
        removeKey.addActionListener(event -> removeKey());
        keyRow.add(removeKey, BorderLayout.EAST);
        addRow(form, constraints, row++, "API key (stored encrypted; empty = keep)", keyRow);
        addRow(form, constraints, row++, " ", keyState);
        addRow(form, constraints, row++, "Concurrent calls per model on the API", capacity);
        addRow(form, constraints, row++, "Context per API model (tokens, planning only)", context);
        addRow(form, constraints, row++, "Retries on HTTP 429, 502, 503, 504", retries);
        addRow(form, constraints, row, "Longest wait for the next answer bytes (seconds)", readTimeout);
        provider.addActionListener(event -> updateEnabled());

        slotModelChoice.setEditable(true); // a model the list does not show can still be typed
        slotTable.getColumnModel().getColumn(MODEL_COLUMN).setCellEditor(new SlotModelEditor());
        slotTable.getColumnModel().getColumn(MODEL_COLUMN).setCellRenderer(new SlotModelRenderer());
        JScrollPane slotScroll = new JScrollPane(slotTable);
        slotScroll.setPreferredSize(new Dimension(520, 160));
        JPanel slotPanel = new JPanel(new BorderLayout(4, 4));
        slotPanel.setBorder(ComicControls.titledBorder("Model per task on the API (thinking and answer budget: "
                + "Model resources)"));
        slotPanel.add(slotScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton list = new ComicButton("Load models from the API");
        list.addActionListener(event -> listModels());
        JButton save = new ComicButton("Save model provider");
        save.addActionListener(event -> {
            if (slotTable.isEditing()) {
                slotTable.getCellEditor().stopCellEditing();
            }
            save();
        });
        buttons.add(list);
        buttons.add(save);
        buttons.add(status);

        add(form, BorderLayout.NORTH);
        add(slotPanel, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
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

    private void load(ModelProviderSettings settings) {
        provider.setSelectedItem(settings.getProvider());
        baseUrl.setText(settings.getApiBaseUrl());
        apiKey.setText("");
        keyState.setText(keyStateFor(settings.hasStoredApiKey()));
        capacity.setValue(settings.getApiCapacity());
        context.setValue(settings.getApiContextTokens());
        retries.setValue(settings.getRetries());
        readTimeout.setValue(settings.getReadTimeoutSeconds());
        slots.setRowCount(0);
        for (String slot : slotNames(portSettings == null ? ModelPortSettings.defaults() : portSettings.load(),
                settings)) {
            slots.addRow(new Object[]{slot, settings.apiSlotModel(slot)});
        }
        updateEnabled();
    }

    private static String keyStateFor(boolean stored) {
        return stored ? "A key is stored." : "No key stored; " + ModelProviderSettings.API_KEY_ENVIRONMENT_VARIABLE
                + " is read when it is set.";
    }

    /** The slots the model resources show, plus every slot with an API model, in a stable order. */
    static List<String> slotNames(ModelPortSettings port, ModelProviderSettings settings) {
        List<String> names = new ArrayList<String>(port.knownSlotNames());
        for (String slot : settings.getApiSlotModels().keySet()) {
            if (!names.contains(slot)) {
                names.add(slot);
            }
        }
        return names;
    }

    private void updateEnabled() {
        boolean api = provider.getSelectedItem() == ModelProviderKind.OPENAI_COMPATIBLE;
        for (Component field : Arrays.<Component>asList(baseUrl, apiKey, capacity, context, retries, readTimeout,
                slotTable)) {
            field.setEnabled(api);
        }
    }

    void save() {
        ModelProviderSettings current = repository.load();
        Map<String, String> models = tableSlotModels();
        ModelProviderSettings updated = new ModelProviderSettings(
                (ModelProviderKind) provider.getSelectedItem(), baseUrl.getText(), current.getEncryptedApiKey(),
                ((Number) capacity.getValue()).intValue(), models, ((Number) context.getValue()).intValue(),
                current.getApiModelContextTokens(), current.getConnectTimeoutSeconds(),
                ((Number) readTimeout.getValue()).intValue(), ((Number) retries.getValue()).intValue());
        char[] typed = apiKey.getPassword();
        try {
            repository.save(updated);
            if (new String(typed).trim().length() > 0) {
                repository.storeApiKey(typed);
            }
            load(repository.load());
            status.setText(savedMessage(repository.load()));
        } catch (IOException | IllegalArgumentException failed) {
            status.setText("Could not save: " + failed.getMessage());
        } finally {
            Arrays.fill(typed, '\0');
        }
    }

    /** What a save leaves to do: a base URL, a default model, or nothing. */
    static String savedMessage(ModelProviderSettings saved) {
        if (saved.usesApi()) {
            String url = saved.getApiBaseUrl();
            if (url.isEmpty()) {
                return "Saved. Enter the API base URL; without it no call reaches the API.";
            }
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return "Saved. The API base URL must start with http:// or https://.";
            }
            if (saved.resolveApiModel(ModelPortSettings.DEFAULT_SLOT).isEmpty()) {
                return "Saved. Set at least the default slot's model, every task inherits it.";
            }
        }
        return "Saved. Applies to the next model call.";
    }

    /** Removes only the stored key; whatever else the page shows stays as typed (and unsaved). */
    void removeKey() {
        try {
            repository.removeApiKey();
            apiKey.setText("");
            keyState.setText(keyStateFor(false));
            status.setText("The stored API key is removed.");
        } catch (IOException failed) {
            status.setText("Could not remove the key: " + failed.getMessage());
        }
    }

    /** Lists the API's models off the EDT with the typed (or stored) key; the dropdown offers them. */
    void listModels() {
        final String url = baseUrl.getText().trim();
        if (url.isEmpty()) {
            status.setText("Enter the API base URL first.");
            return;
        }
        final char[] typed = apiKey.getPassword();
        status.setText("Asking " + url + " for its models ...");
        background.execute(() -> {
            String message;
            List<String> listed = Collections.emptyList();
            try {
                listed = OpenAiCompatibleModelProvider.listModels(url,
                        () -> new String(typed).trim().isEmpty() ? repository.apiKey() : typed.clone(), connections);
                message = listed.isEmpty() ? "The API lists no models; model names can still be typed."
                        : "The API offers " + listed.size() + " models; pick them in the task table.";
            } catch (Exception failed) {
                message = "Models could not be listed: " + failed.getMessage();
            } finally {
                Arrays.fill(typed, '\0');
            }
            final List<String> models = listed;
            final String shown = message;
            ui.execute(() -> {
                apiModels = models;
                status.setText(shown);
            });
        });
    }

    JComboBox<ModelProviderKind> providerChoice() {
        return provider;
    }

    JTextField baseUrlField() {
        return baseUrl;
    }

    JPasswordField apiKeyField() {
        return apiKey;
    }

    JTable slotTable() {
        return slotTable;
    }

    String statusText() {
        return status.getText();
    }

    String keyStateText() {
        return keyState.getText();
    }

    private Map<String, String> tableSlotModels() {
        Map<String, String> models = new LinkedHashMap<String, String>();
        for (int row = 0; row < slots.getRowCount(); row++) {
            models.put(text(slots.getValueAt(row, 0)), text(slots.getValueAt(row, MODEL_COLUMN)));
        }
        return models;
    }

    /** The model column's editor: the API's models, refilled each time editing starts. */
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
            for (String option : ModelResourcesPanel.slotModelOptions(current, apiModels,
                    tableSlotModels().values())) {
                slotModelChoice.addItem(option);
            }
            return super.getTableCellEditorComponent(table, current, selected, row, column);
        }

        @Override
        public Object getCellEditorValue() {
            return text(super.getCellEditorValue());
        }
    }

    /** An empty model cell shows what the task inherits on the API, so the chain stays visible. */
    private final class SlotModelRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            String model = text(value);
            boolean inherited = model.isEmpty();
            String shown = model;
            if (inherited) {
                Map<String, String> others = tableSlotModels();
                String slot = text(slots.getValueAt(row, 0));
                others.remove(slot);
                String resolved = new ModelProviderSettings(ModelProviderKind.OPENAI_COMPATIBLE, "", "", 0, others,
                        0, null, 0, 0, 0).resolveApiModel(slot);
                shown = resolved.isEmpty() ? "not set (the task is unavailable on the API)"
                        : "inherited: " + resolved;
            }
            Component cell = super.getTableCellRendererComponent(table, shown, selected, focused, row, column);
            if (!selected) {
                java.awt.Color muted = UIManager.getColor("Label.disabledForeground");
                cell.setForeground(inherited && muted != null ? muted : table.getForeground());
            }
            return cell;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
