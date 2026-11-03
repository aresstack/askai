package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicForm;
import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.browser.search.DefaultLegacyBrowserSearchSettingsValidator;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCatalog;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot;
import com.aresstack.askai.browser.search.SettingsValidationResult;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Legacy-Browser-Search settings surface (A2d), generated from the
 * {@link LegacyBrowserSearchSettingsCatalog} so every field automatically shows its NAME,
 * description, EFFECTIVE value, DEFAULT value, allowed range and a reset-to-default. Prompts get a
 * multi-line editor whose value IS the productive default text (never an empty editor with a
 * placeholder) plus the available template variables. Saving validates hard and shows the concrete
 * violations; changes apply to NEW research sessions only — the running session keeps its immutable
 * {@link SearchProcessingProfileSnapshot}, which is displayed here.
 */
final class LegacyBrowserSearchSettingsPanel extends JPanel {

    private final WorkspaceStateStore store;
    private final Map<String, String> defaults;
    private final Map<String, FieldEditor> editors = new LinkedHashMap<String, FieldEditor>();
    private final JTextArea problems = ComicControls.textArea(3, 20);
    private final JLabel revisionLabel = new JLabel();

    LegacyBrowserSearchSettingsPanel(WorkspaceStateStore store,
                                     SearchProcessingProfileSnapshot activeProfile) {
        super(new BorderLayout(8, 8));
        this.store = store;
        this.defaults = LegacyBrowserSearchSettingsCodec
                .toValues(LegacyBrowserSearchDefaults.create());

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        header.add(left(bold(new JLabel("Legacy Browser Search"))));
        String info = "Changes apply to NEW research sessions only \u2014 a running session keeps"
                + " its own immutable settings snapshot.";
        if (activeProfile != null) {
            info += " This session uses profile '" + activeProfile.profileId
                    + "' (revision " + activeProfile.profileRevision + ", digest "
                    + shortDigest(activeProfile.settingsDigest) + ").";
        }
        header.add(left(ComicForm.hint(info)));
        header.add(left(revisionLabel));
        refreshRevisionLabel();
        add(header, BorderLayout.NORTH);

        // Sub-pages as a list on the LEFT: seven long section names never fit in one tab row.
        JTabbedPane tabs = ComicControls.tabbedPane(JTabbedPane.LEFT);
        Map<String, ComicForm> forms = new LinkedHashMap<String, ComicForm>();
        for (String section : LegacyBrowserSearchSettingsCatalog.sections()) {
            forms.put(section, new ComicForm());
        }
        Map<String, String> effective = effectiveValues();
        for (LegacyBrowserSearchSettingsCatalog.Field field
                : LegacyBrowserSearchSettingsCatalog.fields()) {
            addField(forms.get(field.section), field, effective.get(field.key));
        }
        for (Map.Entry<String, ComicForm> entry : forms.entrySet()) {
            ComicForm form = entry.getValue();
            form.setBorder(BorderFactory.createEmptyBorder(ComicForm.PAGE_MARGIN,
                    ComicForm.PAGE_MARGIN, ComicForm.PAGE_MARGIN, ComicForm.PAGE_MARGIN));
            tabs.addTab(entry.getKey(), ComicForm.page(form));
        }
        add(tabs, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        problems.setEditable(false);
        problems.setForeground(ThemeColor.of("research.settingsErrorText"));
        problems.setLineWrap(true);
        problems.setWrapStyleWord(true);
        south.add(new JScrollPane(problems), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        JButton save = new ComicButton("Save");
        save.addActionListener(e -> save());
        JButton resetAll = new ComicButton("Reset all to defaults");
        resetAll.addActionListener(e -> {
            for (Map.Entry<String, FieldEditor> entry : editors.entrySet()) {
                entry.getValue().set(defaults.get(entry.getKey()));
            }
        });
        buttons.add(save);
        buttons.add(resetAll);
        south.add(buttons, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
    }

    private Map<String, String> effectiveValues() {
        Map<String, String> effective = new LinkedHashMap<String, String>(defaults);
        effective.putAll(LegacyBrowserSearchSettingsStore.loadValues(store));
        return effective;
    }

    /**
     * One field as a form row: label left, editor right with its Reset button on the same line; the
     * description, default and range go into the tooltip plus a short muted hint. Multi-line editors
     * (prompts, lists, engine order) take the full width below their label.
     */
    private void addField(ComicForm form, final LegacyBrowserSearchSettingsCatalog.Field field,
                          String value) {
        final FieldEditor editor = createEditor(field, value);
        editors.put(field.key, editor);
        JComponent component = editor.component();

        String range = rangeText(field);
        StringBuilder meta = new StringBuilder("Default: " + display(defaults.get(field.key)));
        if (!range.isEmpty()) {
            meta.append(" \u00b7 Range: ").append(range);
        }
        if (!field.templateVariables.isEmpty()) {
            meta.append(" \u00b7 Variables: ").append(field.templateVariables);
        }
        String tooltip = "<html><b>" + escape(field.label) + "</b><br>" + escape(field.description)
                + "<br><i>" + escape(meta.toString()) + "</i></html>";
        component.setToolTipText(tooltip);

        JButton reset = new ComicButton("Reset");
        reset.setToolTipText("Reset to default: " + display(defaults.get(field.key)));
        reset.addActionListener(e -> editor.set(defaults.get(field.key)));

        boolean multiLine = field.kind == LegacyBrowserSearchSettingsCatalog.Kind.TEXT_LIST
                || field.kind == LegacyBrowserSearchSettingsCatalog.Kind.PROMPT
                || field.kind == LegacyBrowserSearchSettingsCatalog.Kind.ENGINE_LIST;
        if (multiLine) {
            form.area(field.label, component);
            form.row("", ComicForm.hint(field.description + " " + meta), reset);
        } else {
            form.row(field.label, component, reset);
            form.row("", ComicForm.hint(field.description
                    + (range.isEmpty() ? "" : " (" + range + ")")));
        }
    }

    private FieldEditor createEditor(LegacyBrowserSearchSettingsCatalog.Field field, String value) {
        switch (field.kind) {
            case BOOLEAN:
                final JCheckBox box = ComicControls.checkBox("enabled", "true".equals(value));
                return new FieldEditor(box) {
                    String get() { return String.valueOf(box.isSelected()); }
                    void set(String v) { box.setSelected("true".equals(v)); }
                };
            case CHOICE:
                final JComboBox<String> combo =
                        ComicControls.<String>comboBox(field.choices.toArray(new String[0]));
                combo.setSelectedItem(value);
                combo.setMaximumSize(combo.getPreferredSize());
                return new FieldEditor(combo) {
                    String get() { return String.valueOf(combo.getSelectedItem()); }
                    void set(String v) { combo.setSelectedItem(v); }
                };
            case ENGINE_LIST:
                final SearchEngineOrderEditor engines = new SearchEngineOrderEditor(value);
                return new FieldEditor(engines) {
                    String get() { return engines.get(); }
                    void set(String v) { engines.set(v); }
                };
            case TEXT_LIST:
            case PROMPT:
                final JTextArea area = ComicControls.textArea(value,
                        field.kind == LegacyBrowserSearchSettingsCatalog.Kind.PROMPT ? 8 : 4, 20);
                area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                final JScrollPane scroll = new JScrollPane(area);
                scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
                return new FieldEditor(scroll) {
                    String get() { return area.getText(); }
                    void set(String v) { area.setText(v); }
                };
            default:
                final JTextField text = ComicControls.textField(value, 12);
                text.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                        text.getPreferredSize().height));
                return new FieldEditor(text) {
                    String get() { return text.getText(); }
                    void set(String v) { text.setText(v); }
                };
        }
    }

    private void save() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<String, FieldEditor> entry : editors.entrySet()) {
            values.put(entry.getKey(), entry.getValue().get());
        }
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(values);
        java.util.List<SettingsValidationResult.Violation> all =
                new java.util.ArrayList<SettingsValidationResult.Violation>(decoded.violations);
        all.addAll(new DefaultLegacyBrowserSearchSettingsValidator()
                .validate(decoded.settings).violations);
        if (!all.isEmpty()) {
            // Invalid settings are NEVER silently corrected or saved — the user sees each violation.
            problems.setForeground(ThemeColor.of("research.settingsErrorText"));
            problems.setText("Not saved — please fix:\n"
                    + new SettingsValidationResult(all).describe());
            return;
        }
        LegacyBrowserSearchSettingsStore.saveValues(store, values);
        refreshRevisionLabel();
        problems.setText("Saved. New research sessions will use revision "
                + LegacyBrowserSearchSettingsStore.revision(store) + ".");
        problems.setForeground(ThemeColor.of("research.settingsSuccessText"));
    }

    private void refreshRevisionLabel() {
        gray(revisionLabel).setText("Stored global settings revision: "
                + LegacyBrowserSearchSettingsStore.revision(store) + ".");
    }

    private String rangeText(LegacyBrowserSearchSettingsCatalog.Field field) {
        boolean hasMin = !Double.isNaN(field.min);
        boolean hasMax = !Double.isNaN(field.max);
        if (!hasMin && !hasMax) {
            return "";
        }
        String min = hasMin ? trimNumber(field.min) : "";
        String max = hasMax ? trimNumber(field.max) : "unbounded";
        return min + " .. " + max;
    }

    private static String trimNumber(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String display(String defaultValue) {
        if (defaultValue == null || defaultValue.isEmpty()) {
            return "(empty)";
        }
        String flat = defaultValue.replace("\n", " | ");
        return flat.length() > 80 ? flat.substring(0, 77) + "..." : flat;
    }

    private static String shortDigest(String digest) {
        return digest.length() > 12 ? digest.substring(0, 12) : digest;
    }

    private static <T extends JComponent> T left(T component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private static JLabel bold(JLabel label) {
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel gray(JLabel label) {
        label.setForeground(ThemeColor.of("research.settingsHintText"));
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** One editor per field: a component plus canonical get/set in codec string form. */
    private abstract static class FieldEditor {
        private final JComponent component;

        FieldEditor(JComponent component) {
            this.component = component;
        }

        JComponent component() {
            return component;
        }

        abstract String get();

        abstract void set(String value);
    }
}
