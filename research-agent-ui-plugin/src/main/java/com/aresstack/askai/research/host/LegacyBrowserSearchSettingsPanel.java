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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
 * One half of the browser settings, generated from the {@link LegacyBrowserSearchSettingsCatalog} so every
 * field automatically shows its NAME, description, EFFECTIVE value, DEFAULT value, allowed range and a
 * reset-to-default. The catalog is split by WHAT it configures (see {@link BrowserSettingsSplit}):
 * {@link #browsing} — how the browser opens and reads pages (tab "Browser"), {@link #serpSearch} — how the
 * browser search finds and extracts hits (tab "Search", provider "Browser (SERP)"). Both halves write the
 * SAME store keys; a save merges the half's fields into the stored full set, validates that set hard and
 * shows the concrete violations. Changes apply to NEW research sessions only — the running session keeps
 * its immutable {@link SearchProcessingProfileSnapshot}.
 */
final class LegacyBrowserSearchSettingsPanel extends JPanel {

    private final WorkspaceStateStore store;
    private final Map<String, String> defaults;
    private final Map<String, FieldEditor> editors = new LinkedHashMap<String, FieldEditor>();
    private final JTextArea status = ComicControls.textArea(2, 20);
    private final String idleText;

    /** The browsing half: {@code leading} rows (channel, headless, …) open its first page. */
    static LegacyBrowserSearchSettingsPanel browsing(WorkspaceStateStore store,
            SearchProcessingProfileSnapshot activeProfile, java.util.function.Consumer<ComicForm> leading) {
        return new LegacyBrowserSearchSettingsPanel(store, activeProfile, BrowserSettingsSplit.BROWSING_PAGES,
                BrowserSettingsSplit::browsingPage, leading);
    }

    /** The SERP-search half (only meaningful while the browser is the search provider). */
    static LegacyBrowserSearchSettingsPanel serpSearch(WorkspaceStateStore store,
            SearchProcessingProfileSnapshot activeProfile) {
        return new LegacyBrowserSearchSettingsPanel(store, activeProfile, BrowserSettingsSplit.SEARCH_PAGES,
                BrowserSettingsSplit::searchPage, null);
    }

    private LegacyBrowserSearchSettingsPanel(WorkspaceStateStore store,
            SearchProcessingProfileSnapshot activeProfile, List<String> pages,
            java.util.function.Function<LegacyBrowserSearchSettingsCatalog.Field, String> pageOf,
            java.util.function.Consumer<ComicForm> leading) {
        super(new BorderLayout(8, 8));
        setOpaque(false);
        this.store = store;
        this.defaults = LegacyBrowserSearchSettingsCodec
                .toValues(LegacyBrowserSearchDefaults.create());

        // Sub-pages as a list on the LEFT: the long section names never fit in one tab row.
        JTabbedPane tabs = ComicControls.tabbedPane(JTabbedPane.LEFT);
        Map<String, ComicForm> forms = new LinkedHashMap<String, ComicForm>();
        for (String page : pages) {
            forms.put(page, new ComicForm());
        }
        if (leading != null) {
            leading.accept(forms.get(pages.get(0)));
        }
        Map<String, String> effective = effectiveValues();
        for (LegacyBrowserSearchSettingsCatalog.Field field
                : LegacyBrowserSearchSettingsCatalog.fields()) {
            String page = pageOf.apply(field);
            if (page != null && forms.containsKey(page)) {
                addField(forms.get(page), field, effective.get(field.key));
            }
        }
        for (Map.Entry<String, ComicForm> entry : forms.entrySet()) {
            ComicForm form = entry.getValue();
            form.setBorder(BorderFactory.createEmptyBorder(ComicForm.PAGE_MARGIN,
                    ComicForm.PAGE_MARGIN, ComicForm.PAGE_MARGIN, ComicForm.PAGE_MARGIN));
            tabs.addTab(entry.getKey(), ComicForm.page(form));
        }
        add(tabs, BorderLayout.CENTER);

        // One line at the bottom: what Save does (or what it just did) left, the buttons right of it.
        idleText = "Changes apply to new research sessions.";
        String profileInfo = activeProfile == null ? null
                : "This session uses profile '" + activeProfile.profileId + "' (revision "
                        + activeProfile.profileRevision + ", digest "
                        + shortDigest(activeProfile.settingsDigest) + ").";
        status.setEditable(false);
        status.setFocusable(false);
        status.setOpaque(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setBorder(BorderFactory.createEmptyBorder());
        status.setToolTipText(profileInfo);
        showIdle();
        JScrollPane statusScroll = ComicForm.scroll(status);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setOpaque(false);
        south.add(statusScroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        JButton save = new ComicButton("Save");
        save.addActionListener(e -> save());
        JButton resetAll = new ComicButton("Reset all to defaults");
        resetAll.setToolTipText("Resets the fields of this tab (not yet saved)");
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

    /** The store keys this half edits (tests, and the save merge). */
    java.util.Set<String> editedKeys() {
        return java.util.Collections.unmodifiableSet(editors.keySet());
    }

    private void showIdle() {
        status.setForeground(ThemeColor.of("research.settingsHintText"));
        status.setText(idleText + " Stored revision: " + LegacyBrowserSearchSettingsStore.revision(store) + ".");
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
        // The OTHER half's fields keep what is stored right now (re-read, so its own save is never undone).
        Map<String, String> values = effectiveValues();
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
            status.setForeground(ThemeColor.of("research.settingsErrorText"));
            status.setText("Not saved — please fix:\n"
                    + new SettingsValidationResult(all).describe());
            status.setCaretPosition(0);
            return;
        }
        LegacyBrowserSearchSettingsStore.saveValues(store, values);
        status.setForeground(ThemeColor.of("research.settingsSuccessText"));
        status.setText("Saved. New research sessions will use revision "
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
