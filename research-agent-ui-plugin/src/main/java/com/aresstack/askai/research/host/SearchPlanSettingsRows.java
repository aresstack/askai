package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;

import javax.swing.JTextField;

/**
 * Plan PR 14: the caps of the search plan as one titled group of the Search tab ("Sources per node", "Queries per
 * node", "Research cycles", "Sources for the Sources phase"). Each field saves on Enter and on focus loss; an invalid value is reset to
 * the stored one. The queue reads the caps at each start.
 */
final class SearchPlanSettingsRows {

    private SearchPlanSettingsRows() {
    }

    /** The "Search plan" group, laid out through {@link ComicForm#fit}. */
    static ComicForm form(WorkspaceStateStore store) {
        ComicForm form = ComicForm.titled("Search plan");
        form.row("Sources per node", field(store, ResearchRuntimeSettings.KEY_PLAN_SOURCES_PER_NODE,
                ResearchRuntimeSettings.DEFAULT_PLAN_SOURCES_PER_NODE,
                "Accepted sources (from at least two domains) that make a concept node covered."));
        form.row("Queries per node", field(store, ResearchRuntimeSettings.KEY_PLAN_QUERIES_PER_NODE,
                ResearchRuntimeSettings.DEFAULT_PLAN_QUERIES_PER_NODE,
                "Planned queries per concept node and research cycle: the base query and up to three variants."));
        form.row("Research cycles", field(store, ResearchRuntimeSettings.KEY_PLAN_CYCLES,
                ResearchRuntimeSettings.DEFAULT_PLAN_CYCLES,
                "After each cycle the nodes without covering sources are planned again from another angle, "
                        + "until they are covered or the cycles are spent. The outcome names the gaps that remain."));
        form.row("Sources for the Sources phase", field(store, ResearchRuntimeSettings.KEY_PLAN_PHASE_SOURCES,
                ResearchRuntimeSettings.DEFAULT_PLAN_PHASE_SOURCES,
                "The search plan stops once the Sources phase has this many accepted sources."));
        form.row("Skip shop sites", skipNonSourceHosts(store));
        return form;
    }

    /** Every search skips marketplaces, book shops and document re-upload sites (next to the user's blacklist). */
    private static javax.swing.JCheckBox skipNonSourceHosts(final WorkspaceStateStore store) {
        final javax.swing.JCheckBox box = ComicControls.checkBox("Marketplaces, book shops and document re-uploads "
                + "are never sources", ResearchRuntimeSettings.loadSkipNonSourceHosts(store));
        box.setToolTipText("Every search skips amazon, ebay, thalia, beck-shop, scribd and similar sites as a host "
                + "filter next to your blacklist; search-result pages of other websites are always skipped.");
        box.addActionListener(e -> ResearchRuntimeSettings.saveSkipNonSourceHosts(store, box.isSelected()));
        return box;
    }

    private static JTextField field(final WorkspaceStateStore store, final String key, final int fallback,
                                    String tooltip) {
        final JTextField field = ComicControls.textField(5);
        field.setText(String.valueOf(ResearchRuntimeSettings.loadPositiveInt(store, key, fallback)));
        field.setToolTipText(tooltip);
        final Runnable persist = new Runnable() {
            public void run() {
                try {
                    int value = Integer.parseInt(field.getText().trim());
                    if (value > 0) {
                        ResearchRuntimeSettings.savePositiveInt(store, key, value);
                        return;
                    }
                } catch (NumberFormatException invalid) {
                    // reset below
                }
                field.setText(String.valueOf(ResearchRuntimeSettings.loadPositiveInt(store, key, fallback)));
            }
        };
        field.addActionListener(e -> persist.run());
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persist.run();
            }
        });
        return field;
    }
}
