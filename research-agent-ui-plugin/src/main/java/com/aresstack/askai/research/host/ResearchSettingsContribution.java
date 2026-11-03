package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSettingsContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;

/**
 * The research agent's settings pages in the HOST's gear menu: the EXISTING runtime and browser-search
 * panels, moved (not reinvented) out of the artifact area — settings are not work products. The panels
 * work on the SESSION's state store, so every chat tab configures exactly its own research session; the
 * central AskAI model selection and the provider credential files stay where they are.
 */
public final class ResearchSettingsContribution implements AgentSettingsContribution {

    @Override
    public String getDisplayName() {
        return "Research Agent";
    }

    @Override
    public JComponent createSettingsComponent(AgentSession session) {
        if (!(session instanceof ResearchAgentSession)) {
            return null; // not this plugin's session → the host omits the category
        }
        ResearchAgentSession research = (ResearchAgentSession) session;
        return build(research.getHostStateStore(), research, research.getActiveSearchProfile());
    }

    /** Session-free: the plugin's persisted defaults (Settings → Plugins), no session or model needed. */
    @Override
    public JComponent createPluginSettingsComponent(WorkspaceStateStore pluginSettings) {
        return pluginSettings == null ? null : build(pluginSettings, null, null);
    }

    private static JComponent build(WorkspaceStateStore store, ResearchAgentSession research,
            com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot profile) {
        final SearchProviderCardsPanel searchProviders = new SearchProviderCardsPanel(store, true, profile);
        JTabbedPane tabs = ComicControls.tabbedPane();
        tabs.addTab("General", buildGeneralTab(store));
        ResearchRuntimeSettingsPanel runtime = new ResearchRuntimeSettingsPanel(store, research);
        tabs.addTab("Runtime", fitted(runtime));
        // The provider PICKER + the selected provider's own settings below it: for "Browser (SERP)" the
        // browser search's engines, page analysis, extraction, reranking (BrowserSettingsSplit). Each card
        // scrolls on its own, so the tab is not wrapped in fitted().
        tabs.addTab("Search", searchProviders);
        // What the browser itself does with EVERY provider: open and read the hits.
        tabs.addTab("Browser", LegacyBrowserSearchSettingsPanel.browsing(store, profile,
                form -> BrowserBasicsRows.addTo(form, store)));
        tabs.addTab("MCP", fitted(runtime.mcpView()));
        tabs.addTab("Check results", runtime.resultsView());
        // The DataForSEO draft + provider selection persist when the gear dialog is disposed; unsaved
        // provider drafts are dropped. The host's settings dialog does not expose an explicit Save hook,
        // so the panel commits on removal from the hierarchy (dialog close).
        tabs.addHierarchyListener(new java.awt.event.HierarchyListener() {
            public void hierarchyChanged(java.awt.event.HierarchyEvent event) {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                        && !searchProviders.isShowing()) {
                    searchProviders.save();
                }
            }
        });
        return tabs;
    }

    /**
     * A tab that fits the dialog: the content takes the tab's WIDTH (rows wrap instead of running off
     * the right edge), keeps its own height at the top (no stretched blank area) and scrolls
     * vertically only when it really is taller than the tab.
     */
    static JComponent fitted(final JComponent content) {
        WidthTracking column = new WidthTracking();
        column.add(content, java.awt.BorderLayout.NORTH);
        return com.aresstack.comiccontrols.control.ComicForm.scroll(column);
    }

    private static final class WidthTracking extends javax.swing.JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        WidthTracking() {
            super(new java.awt.BorderLayout());
            setOpaque(false);
        }

        public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 14;
        }

        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(14, visible.height - 28);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** General research-UI preferences — the first, non-technical tab. */
    private static JComponent buildGeneralTab(final WorkspaceStateStore store) {
        com.aresstack.comiccontrols.control.ComicForm agent =
                com.aresstack.comiccontrols.control.ComicForm.titled("Agent");
        // The DEFAULT language for NEW research sessions; running sessions keep their own session-local
        // language (live-switchable at the session, never globally from here).
        final javax.swing.JComboBox<String> language =
                ComicControls.<String>comboBox(new String[]{"English", "Deutsch"});
        language.setSelectedIndex("de".equals(ResearchRuntimeSettings.loadLanguage(store)) ? 1 : 0);
        language.setToolTipText("Language of new research sessions");
        language.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveLanguage(store, language.getSelectedIndex() == 1 ? "de" : "en");
            }
        });
        // LLM narration (default off): milestone texts phrased by the main model, validated, with fallback.
        final javax.swing.JCheckBox narration = ComicControls.checkBox(
                "AI-phrased guidance (uses the main model; applies to new sessions)",
                ResearchRuntimeSettings.loadLlmNarration(store));
        narration.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveLlmNarration(store, narration.isSelected());
            }
        });
        // Scoping orientation tags (default off = long-standing behaviour): suggestions on every broad turn.
        final javax.swing.JCheckBox suggest = ComicControls.checkBox(
                "Always offer search suggestions (orientation tags; applies to new sessions)",
                ResearchRuntimeSettings.loadAlwaysOfferSearchSuggestions(store));
        suggest.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveAlwaysOfferSearchSuggestions(store, suggest.isSelected());
            }
        });
        agent.row("Language", language);
        agent.check(narration);
        agent.check(suggest);
        com.aresstack.comiccontrols.control.ComicForm tab =
                com.aresstack.comiccontrols.control.ComicForm.titled("Search tags");
        final javax.swing.JCheckBox readTagsBox = ComicControls.checkBox(
                "Read search tags aloud when clicked",
                ResearchRuntimeSettings.loadReadSearchTagsOnClick(store));
        readTagsBox.setToolTipText("Clicking a yellow search/activity tag in the chat speaks its"
                + " text with an assertive delivery.");
        final javax.swing.JCheckBox onlyWhenActiveBox = ComicControls.checkBox(
                "Only while read-aloud is active (Play pressed or automatic reading enabled)",
                ResearchRuntimeSettings.loadReadTagsOnlyWhenReadAloudActive(
                        store));
        onlyWhenActiveBox.setToolTipText("On (default): clicked tags stay silent unless you"
                + " actually use the speech output. Off: tags always speak when clicked.");
        onlyWhenActiveBox.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 22, 0, 0));
        onlyWhenActiveBox.setEnabled(readTagsBox.isSelected());
        onlyWhenActiveBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveReadTagsOnlyWhenReadAloudActive(
                        store, onlyWhenActiveBox.isSelected());
            }
        });
        readTagsBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveReadSearchTagsOnClick(
                        store, readTagsBox.isSelected());
                onlyWhenActiveBox.setEnabled(readTagsBox.isSelected()); // sub-option follows
            }
        });
        tab.check(readTagsBox);
        tab.check(onlyWhenActiveBox);
        tab.note("Uses the configured speech output — set the voice per language under Settings >"
                + " Chat > Audio & Dictation > Read aloud.");
        com.aresstack.comiccontrols.control.ComicForm sources =
                com.aresstack.comiccontrols.control.ComicForm.titled("Sources");
        final javax.swing.JCheckBox autoReviewBox = ComicControls.checkBox(
                "Review new sources automatically after a successful web search",
                ResearchRuntimeSettings.loadAutoReviewAfterSearch(store));
        autoReviewBox.setToolTipText("On: when a web search accepts sources, the review that the"
                + " \"Review new sources\" tag triggers starts by itself. Off (default): you"
                + " decide when to review.");
        autoReviewBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveAutoReviewAfterSearch(
                        store, autoReviewBox.isSelected());
            }
        });
        sources.check(autoReviewBox);
        com.aresstack.comiccontrols.control.ComicForm.alignLabels(agent, tab, sources);
        return com.aresstack.comiccontrols.control.ComicForm.page(agent, tab, sources);
    }
}
