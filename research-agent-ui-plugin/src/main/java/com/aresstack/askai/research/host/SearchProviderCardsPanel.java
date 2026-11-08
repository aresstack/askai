package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.search.config.DataForSeoSettingsDraft;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The initial-search provider surface: ONE combo on top selects the provider, a {@link CardLayout} below
 * shows exactly that provider's own settings. The combo only PICKS the card — it no longer reduces every
 * provider to four shared fields. Switching cards neither saves nor discards; each provider keeps its own
 * live draft for the dialog's lifetime.
 *
 * <p>The persisted strategy SELECTION (which provider a new session uses) still round-trips through
 * {@link ResearchRuntimeSettings}; the browser is the default. Provider CREDENTIALS/parameters live in
 * {@code ~/agents/research/providers/} via the per-provider cards, not here. This slice ships the browser
 * card (the legacy default) and the full DataForSEO card; Brave and Bright Data get their own cards next.</p>
 */
public final class SearchProviderCardsPanel extends JPanel {

    /** Index-aligned with the combo; "" = the legacy browser SERP path. */
    private static final String[] PROVIDER_IDS = {"", "BRAVE_SEARCH_API", "BRIGHT_DATA", "DATA_FOR_SEO", "SERP_API"};
    private static final String[] PROVIDER_LABELS =
            {"Browser (SERP)", "Brave Search API", "Bright Data", "DataForSEO", "SerpApi"};

    private final WorkspaceStateStore store;
    private final JComboBox<String> source = ComicControls.<String>comboBox(PROVIDER_LABELS);
    private final CardLayout cards = new CardLayout();
    /** As tall as the card shown, not as the tallest card (a short card leaves no empty block below it). */
    private final JPanel cardHost = new JPanel(cards) {
        @Override
        public Dimension getPreferredSize() {
            for (java.awt.Component card : getComponents()) {
                if (card.isVisible()) {
                    Dimension size = card.getPreferredSize();
                    java.awt.Insets insets = getInsets();
                    return new Dimension(size.width + insets.left + insets.right,
                            size.height + insets.top + insets.bottom);
                }
            }
            return super.getPreferredSize();
        }
    };

    private final ResearchProviderServices providers;
    private final DataForSeoSettingsDraft dataForSeoDraft;
    private DataForSeoSettingsPanel dataForSeoPanel;
    private SerpApiSettingsPanel serpApiPanel;
    private BraveSettingsPanel bravePanel;
    private BrightDataSettingsPanel brightDataPanel;

    /** {@code false}: no settings below the combo, a "Configure…" button opens them in a dialog instead. */
    private final boolean inline;
    private final com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot activeProfile;
    private final javax.swing.JButton configure = new com.aresstack.comiccontrols.control.ComicButton("Configure…");

    /** The settings dialog's tab: the selected provider's settings right below the combo. */
    public SearchProviderCardsPanel(WorkspaceStateStore store) {
        this(store, true);
    }

    /**
     * @param inline {@code true}: the selected provider's settings below the combo (settings tab); {@code false}:
     *               the combo with a "Configure…" button beside it that opens them in a dialog (setup wizard,
     *               where the large provider panels would burst the page)
     */
    public SearchProviderCardsPanel(WorkspaceStateStore store, boolean inline) {
        this(store, inline, null);
    }

    /**
     * @param activeProfile the running session's browser-search snapshot (shown by the browser card), or null
     */
    SearchProviderCardsPanel(WorkspaceStateStore store, boolean inline,
            com.aresstack.askai.browser.search.SearchProcessingProfileSnapshot activeProfile) {
        super(new BorderLayout(6, 6));
        this.inline = inline;
        this.store = store;
        this.activeProfile = activeProfile;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        ResearchProviderServices services;
        DataForSeoSettingsDraft draft;
        try {
            services = new ResearchProviderServices();
            // Without inline settings the dialog loads a fresh draft each time it opens.
            draft = inline ? DataForSeoSettingsDraft.load(services.configurationService()) : null;
        } catch (RuntimeException providerFilesUnavailable) {
            // The provider store could not be opened (e.g. no home dir): keep the browser card usable.
            services = null;
            draft = null;
        }
        this.providers = services;
        this.dataForSeoDraft = draft;

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel("Search method / provider:"), BorderLayout.WEST);
        top.add(source, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        if (inline) {
            // Every card scrolls on its own: the browser card has its own page list, the API cards are forms.
            cardHost.add(browserCard(), PROVIDER_LABELS[0]);
            cardHost.add(ResearchSettingsContribution.fitted(braveCard()), PROVIDER_LABELS[1]);
            cardHost.add(ResearchSettingsContribution.fitted(brightDataCard()), PROVIDER_LABELS[2]);
            cardHost.add(ResearchSettingsContribution.fitted(dataForSeoCard()), PROVIDER_LABELS[3]);
            cardHost.add(ResearchSettingsContribution.fitted(serpApiCard()), PROVIDER_LABELS[4]);
            add(cardHost, BorderLayout.CENTER);
            // Plan PR 14: the caps of the search plan apply to every provider, so they sit below the cards.
            add(ResearchSettingsContribution.fitted(SearchPlanSettingsRows.form(store)), BorderLayout.SOUTH);
        } else {
            JPanel actions = new JPanel(new BorderLayout());
            actions.setOpaque(false);
            actions.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
            actions.add(configure, BorderLayout.CENTER);
            top.add(actions, BorderLayout.EAST);
            configure.setToolTipText("Key, account and search options of the selected provider");
            configure.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    configureSelected();
                }
            });
        }

        int selected = indexOfProvider(ResearchRuntimeSettings.loadSearchStrategy(store));
        source.setSelectedIndex(selected);
        cards.show(cardHost, PROVIDER_LABELS[selected]);
        configure.setEnabled(selected > 0);
        source.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int i = Math.max(0, source.getSelectedIndex());
                cards.show(cardHost, PROVIDER_LABELS[i]);
                cardHost.revalidate();
                configure.setEnabled(i > 0);
                persistSelection();
            }
        });
    }

    /** The "Configure…" button (only shown when the settings are not inline). */
    javax.swing.JButton configureButton() {
        return configure;
    }

    /** The provider combo. */
    JComboBox<String> providerCombo() {
        return source;
    }

    /**
     * Opens the selected provider's settings in a dialog: a fresh panel each time, saved on "Save" (it stays
     * open with the reason when saving fails), dropped on "Cancel".
     */
    private void configureSelected() {
        final int index = Math.max(0, source.getSelectedIndex());
        final ProviderEditor editor = editorFor(index);
        if (editor == null) {
            return;
        }
        java.awt.Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
        final javax.swing.JDialog dialog = new javax.swing.JDialog(owner, PROVIDER_LABELS[index] + " settings",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(editor.component, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        javax.swing.JButton save = new com.aresstack.comiccontrols.control.ComicButton("Save",
                com.aresstack.comiccontrols.control.ComicButton.Accent.ACTION);
        javax.swing.JButton cancel = new com.aresstack.comiccontrols.control.ComicButton("Cancel");
        buttons.add(save);
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);
        final boolean[] saved = {false};
        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String error = editor.save();
                if (error != null) {
                    javax.swing.JOptionPane.showMessageDialog(dialog, PROVIDER_LABELS[index]
                            + " settings were not saved:\n" + error, PROVIDER_LABELS[index],
                            javax.swing.JOptionPane.WARNING_MESSAGE);
                    return;
                }
                saved[0] = true;
                dialog.dispose();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                if (!saved[0]) {
                    editor.discard();
                }
            }
        });
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(save);
        // Every provider dialog has the same width (ProviderSettingsForm.DIALOG_WIDTH); the wrapping notes know their
        // height only once laid out at that width, so pack a second time after the first layout.
        dialog.pack();
        dialog.validate();
        dialog.pack();
        java.awt.GraphicsConfiguration display = owner != null ? owner.getGraphicsConfiguration() : null;
        java.awt.Rectangle screen = display != null ? display.getBounds() : new java.awt.Rectangle(0, 0, 1280, 900);
        dialog.setSize(Math.min(dialog.getWidth(), screen.width - 40), Math.min(dialog.getHeight(), screen.height - 80));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /** One provider's settings for the dialog: its panel, how to save it (error text or null), how to drop it. */
    static final class ProviderEditor {
        final JComponent component;
        private final java.util.concurrent.Callable<String> saver;
        private final Runnable discarder;

        ProviderEditor(JComponent component, java.util.concurrent.Callable<String> saver, Runnable discarder) {
            this.component = component;
            this.saver = saver;
            this.discarder = discarder;
        }

        String save() {
            try {
                return saver.call();
            } catch (Exception failed) {
                return failed.getMessage() == null ? failed.toString() : failed.getMessage();
            }
        }

        void discard() {
            if (discarder != null) {
                discarder.run();
            }
        }
    }

    /** A fresh editor for the provider at {@code index}; {@code null} for the browser or without provider files. */
    ProviderEditor editorFor(int index) {
        if (providers == null || index <= 0) {
            return null;
        }
        switch (PROVIDER_IDS[index]) {
            case "BRAVE_SEARCH_API": {
                final BraveSettingsPanel panel = new BraveSettingsPanel(providers.configurationService());
                return new ProviderEditor(panel, () -> panel.save() ? null : panel.getLastError(), null);
            }
            case "BRIGHT_DATA": {
                final BrightDataSettingsPanel panel = new BrightDataSettingsPanel(providers.configurationService());
                return new ProviderEditor(panel, () -> panel.save() ? null : panel.getLastError(), null);
            }
            case "DATA_FOR_SEO": {
                final DataForSeoSettingsDraft draft = DataForSeoSettingsDraft.load(providers.configurationService());
                final DataForSeoSettingsPanel panel = new DataForSeoSettingsPanel(
                        draft, providers.dataForSeoPlayground(), providers.secrets());
                return new ProviderEditor(panel, () -> {
                    panel.save();
                    return null;
                }, draft::dispose);
            }
            case "SERP_API": {
                final SerpApiSettingsPanel panel = new SerpApiSettingsPanel(providers.configurationService());
                return new ProviderEditor(panel, () -> panel.save() ? null : panel.getLastError(), null);
            }
            default:
                return null;
        }
    }

    /** Persist EVERYTHING on the dialog's Save: the provider selection and the DataForSEO draft. */
    public void save() {
        persistSelection();
        if (dataForSeoPanel != null) {
            dataForSeoPanel.save();
        }
        if (bravePanel != null && !bravePanel.save()) {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "Brave settings were not saved:\n" + bravePanel.getLastError(),
                    "Brave Search API", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
        if (brightDataPanel != null && !brightDataPanel.save()) {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "Bright Data settings were not saved:\n" + brightDataPanel.getLastError(),
                    "Bright Data", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
        if (serpApiPanel != null && !serpApiPanel.save()) {
            // The settings dialog is closing; the card's own status line is no longer visible.
            javax.swing.JOptionPane.showMessageDialog(null,
                    "SerpApi settings were not saved:\n" + serpApiPanel.getLastError(),
                    "SerpApi", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Drop unsaved provider drafts (dialog cancel/close). */
    public void dispose() {
        if (dataForSeoDraft != null) {
            dataForSeoDraft.dispose();
        }
    }

    private void persistSelection() {
        int i = Math.max(0, source.getSelectedIndex());
        boolean api = i > 0;
        // Engine/locale are per-provider now; the strategy snapshot keeps only the provider id (the
        // agent's DataForSEO adapter reads engine/language/location from its own provider file).
        ResearchRuntimeSettings.saveSearchStrategy(store, new SearchStrategySelection(
                api ? SearchStrategySelection.STRATEGY_API_PROVIDER
                        : SearchStrategySelection.STRATEGY_LEGACY_BROWSER,
                PROVIDER_IDS[i], "", "", ""));
    }

    /**
     * The browser search's own settings (engines, result-page analysis, extraction, reranking — see
     * {@link BrowserSettingsSplit}); what the browser does with every provider is on the "Browser" tab.
     */
    private JComponent browserCard() {
        return LegacyBrowserSearchSettingsPanel.serpSearch(store, activeProfile,
                form -> BrowserBasicsRows.addSearchBrowserTo(form, store));
    }

    private JComponent dataForSeoCard() {
        if (providers == null || dataForSeoDraft == null) {
            return placeholderUnavailable();
        }
        dataForSeoPanel = new DataForSeoSettingsPanel(
                dataForSeoDraft, providers.dataForSeoPlayground(), providers.secrets());
        return dataForSeoPanel;
    }

    private JComponent serpApiCard() {
        if (providers == null) {
            return placeholderUnavailable();
        }
        serpApiPanel = new SerpApiSettingsPanel(providers.configurationService());
        return serpApiPanel;
    }

    private JComponent braveCard() {
        if (providers == null) {
            return placeholderUnavailable();
        }
        bravePanel = new BraveSettingsPanel(providers.configurationService());
        return bravePanel;
    }

    private JComponent brightDataCard() {
        if (providers == null) {
            return placeholderUnavailable();
        }
        brightDataPanel = new BrightDataSettingsPanel(providers.configurationService());
        return brightDataPanel;
    }

    private JComponent placeholderUnavailable() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel l = new JLabel("The provider configuration directory could not be opened.");
        l.setEnabled(false);
        p.add(l, BorderLayout.NORTH);
        p.setPreferredSize(new Dimension(420, 80));
        return p;
    }

    private static int indexOfProvider(SearchStrategySelection selection) {
        if (selection.isApiProvider()) {
            for (int i = 1; i < PROVIDER_IDS.length; i++) {
                if (PROVIDER_IDS[i].equals(selection.getProvider())) {
                    return i;
                }
            }
        }
        return 0;
    }
}
