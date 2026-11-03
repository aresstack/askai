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

    public SearchProviderCardsPanel(WorkspaceStateStore store) {
        super(new BorderLayout(6, 6));
        this.store = store;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        ResearchProviderServices services;
        DataForSeoSettingsDraft draft;
        try {
            services = new ResearchProviderServices();
            draft = DataForSeoSettingsDraft.load(services.configurationService());
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

        cardHost.add(browserCard(), PROVIDER_LABELS[0]);
        cardHost.add(braveCard(), PROVIDER_LABELS[1]);
        cardHost.add(brightDataCard(), PROVIDER_LABELS[2]);
        cardHost.add(dataForSeoCard(), PROVIDER_LABELS[3]);
        cardHost.add(serpApiCard(), PROVIDER_LABELS[4]);
        add(cardHost, BorderLayout.CENTER);

        int selected = indexOfProvider(ResearchRuntimeSettings.loadSearchStrategy(store));
        source.setSelectedIndex(selected);
        cards.show(cardHost, PROVIDER_LABELS[selected]);
        source.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int i = Math.max(0, source.getSelectedIndex());
                cards.show(cardHost, PROVIDER_LABELS[i]);
                cardHost.revalidate();
                persistSelection();
            }
        });
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

    private JComponent browserCard() {
        JPanel p = new JPanel();
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel l = new JLabel("<html><body style='width:420px'>The initial search runs in the local "
                + "browser (SERP). The browser-specific search settings (search URL, headless, channel) "
                + "are in the Runtime tab. This is the default.</body></html>");
        l.setEnabled(false);
        p.add(l);
        return p;
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
