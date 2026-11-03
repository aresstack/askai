package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.agent.setup.AgentSetupContext;
import com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution;
import com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.Component;

/**
 * The research agent's part of the setup wizard: the search browser (Camoufox, about 1.3 GB, otherwise only
 * downloaded at the first research) and the search provider – Google in the browser needs no key, SerpApi and
 * the other API providers need one. The provider part is the same panel as in the research settings.
 */
public final class ResearchSetupContribution implements AgentSetupContribution {

    @Override
    public String getDisplayName() {
        return "Web search";
    }

    @Override
    public AgentSetupPage createSetupPage(AgentSetupContext context) {
        return new Page(context);
    }

    private static final class Page implements AgentSetupPage {
        private final JPanel panel = new JPanel();
        private final BrowserLocationControl browser;
        private final SearchProviderCardsPanel providers;
        private final AgentSetupContext context;
        Page(AgentSetupContext context) {
            this.context = context;
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            add(note("The research agent searches the web in its own browser (Camoufox, about 1.3 GB). Enter the "
                    + "folder of an existing Camoufox, or download it now through the network settings above, so "
                    + "the first research starts right away. Download uses AskAI's folder when the field is empty"
                    + " (its grey text shows where)."));
            browser = new BrowserLocationControl(context.getHostStateStore());
            JPanel row = new JPanel(new java.awt.GridBagLayout());
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
            java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
            c.gridx = 0;
            c.fill = java.awt.GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            row.add(browser.field(), c);
            c = new java.awt.GridBagConstraints();
            c.gridx = 1;
            c.insets = new java.awt.Insets(0, 8, 0, 0);
            row.add(browser.browseButton(), c);
            c = new java.awt.GridBagConstraints();
            c.gridx = 2;
            c.insets = new java.awt.Insets(0, 8, 0, 0);
            row.add(browser.downloadButton(), c);
            add(row);
            browser.status().setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            add(browser.status());
            add(browser.progress());
            JTextArea providerIntro = note("Search provider: Google in the browser needs no key. With a SerpApi, "
                    + "Brave, DataForSEO or Bright Data key the agent can search through that service instead.");
            providerIntro.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
            add(providerIntro);
            SearchProviderCardsPanel cards;
            try {
                cards = new SearchProviderCardsPanel(context.getHostStateStore(), false);
                cards.setBorder(BorderFactory.createEmptyBorder());
                transparent(cards);
                add(cards);
            } catch (RuntimeException unavailable) {
                cards = null;
            }
            providers = cards;
            browser.setChannel(ResearchRuntimeSettings.CAMOUFOX_CHANNEL);
        }

        /** A wrapping text in the wizard's plain style (it takes the width of the field column). */
        private static JTextArea note(String text) {
            JTextArea note = com.aresstack.comiccontrols.control.ComicControls.textArea(text);
            note.setColumns(20); // a small preferred width: the column decides, the text wraps into it
            note.setEditable(false);
            note.setFocusable(false);
            note.setLineWrap(true);
            note.setWrapStyleWord(true);
            note.setOpaque(false);
            note.setBorder(BorderFactory.createEmptyBorder());
            java.awt.Font font = UIManager.getFont("Label.font");
            if (font != null) {
                note.setFont(font.deriveFont(java.awt.Font.PLAIN));
            }
            note.setForeground(UIManager.getColor("Label.foreground"));
            return note;
        }

        /** The provider cards keep the wizard's background instead of grey panels. */
        private static void transparent(java.awt.Container container) {
            if (container instanceof JPanel) {
                ((JPanel) container).setOpaque(false);
            }
            for (java.awt.Component child : container.getComponents()) {
                if (child instanceof java.awt.Container && !(child instanceof javax.swing.text.JTextComponent)
                        && !(child instanceof javax.swing.JComboBox)) {
                    transparent((java.awt.Container) child);
                }
            }
        }

        private void add(JComponent component) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(component);
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void save() {
            ResearchRuntimeSettings.saveCamoufoxFolder(context.getHostStateStore(), browser.chosen());
            if (providers != null) {
                providers.save();
            }
        }

        @Override
        public void dispose() {
            browser.dispose();
            if (providers != null) {
                providers.dispose();
            }
        }
    }
}
