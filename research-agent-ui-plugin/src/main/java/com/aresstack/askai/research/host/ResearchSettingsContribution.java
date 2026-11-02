package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSettingsContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;

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
        final SearchProviderCardsPanel searchProviders =
                new SearchProviderCardsPanel(research.getHostStateStore());
        JTabbedPane tabs = ComicControls.tabbedPane();
        tabs.addTab("General", buildGeneralTab(research));
        tabs.addTab("Runtime", new ResearchRuntimeSettingsPanel(research.getHostStateStore(), research));
        // The provider PICKER + each provider's own settings card (browser default, full DataForSEO
        // editor). The old shared engine/locale fields are gone — each provider owns its full config.
        tabs.addTab("Search", searchProviders);
        tabs.addTab("Browser SERP", new LegacyBrowserSearchSettingsPanel(research.getHostStateStore(),
                research.getActiveSearchProfile()));
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

    /** General research-UI preferences — the first, non-technical tab. */
    private static JComponent buildGeneralTab(final ResearchAgentSession research) {
        javax.swing.JPanel tab = new javax.swing.JPanel();
        tab.setLayout(new javax.swing.BoxLayout(tab, javax.swing.BoxLayout.Y_AXIS));
        tab.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        final javax.swing.JCheckBox readTagsBox = ComicControls.checkBox(
                "Read search tags aloud when clicked",
                ResearchRuntimeSettings.loadReadSearchTagsOnClick(research.getHostStateStore()));
        readTagsBox.setToolTipText("Clicking a yellow search/activity tag in the chat speaks its"
                + " text with an assertive delivery.");
        readTagsBox.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        final javax.swing.JCheckBox onlyWhenActiveBox = ComicControls.checkBox(
                "Only while read-aloud is active (Play pressed or automatic reading enabled)",
                ResearchRuntimeSettings.loadReadTagsOnlyWhenReadAloudActive(
                        research.getHostStateStore()));
        onlyWhenActiveBox.setToolTipText("On (default): clicked tags stay silent unless you"
                + " actually use the speech output. Off: tags always speak when clicked.");
        onlyWhenActiveBox.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        onlyWhenActiveBox.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 22, 2, 0));
        onlyWhenActiveBox.setEnabled(readTagsBox.isSelected());
        onlyWhenActiveBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveReadTagsOnlyWhenReadAloudActive(
                        research.getHostStateStore(), onlyWhenActiveBox.isSelected());
            }
        });
        readTagsBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveReadSearchTagsOnClick(
                        research.getHostStateStore(), readTagsBox.isSelected());
                onlyWhenActiveBox.setEnabled(readTagsBox.isSelected()); // sub-option follows
            }
        });
        tab.add(readTagsBox);
        tab.add(onlyWhenActiveBox);
        javax.swing.JLabel hint = new javax.swing.JLabel("<html><i>Uses the configured speech"
                + " output — set the voice per language in the Settings (gear) under Chat &gt;"
                + " \"Audio &amp; Dictation\" &gt; Speech output.</i></html>");
        hint.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        hint.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 22, 12, 0));
        tab.add(hint);
        final javax.swing.JCheckBox autoReviewBox = ComicControls.checkBox(
                "Review new sources automatically after a successful web search",
                ResearchRuntimeSettings.loadAutoReviewAfterSearch(research.getHostStateStore()));
        autoReviewBox.setToolTipText("On: when a web search accepts sources, the review that the"
                + " \"Review new sources\" tag triggers starts by itself. Off (default): you"
                + " decide when to review.");
        autoReviewBox.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        autoReviewBox.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                ResearchRuntimeSettings.saveAutoReviewAfterSearch(
                        research.getHostStateStore(), autoReviewBox.isSelected());
            }
        });
        tab.add(autoReviewBox);
        tab.add(javax.swing.Box.createVerticalGlue());
        return tab;
    }
}
