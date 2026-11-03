package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.runtime.InProcessBrowser;
import com.aresstack.askai.browser.runtime.camoufox.CamoufoxInstaller;
import com.aresstack.askai.plugin.api.agent.setup.AgentSetupContext;
import com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution;
import com.aresstack.askai.plugin.api.agent.setup.AgentSetupPage;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.FlowLayout;

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
        private final JLabel browserStatus = new JLabel(" ");
        private final ComicButton download = new ComicButton("Download search browser", ComicButton.Accent.ACTION);
        private final JProgressBar progress = ComicControls.progressBar(0, 1000);
        private final SearchProviderCardsPanel providers;
        private final Timer poll;
        private boolean started;

        Page(AgentSetupContext context) {
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            add(note("The research agent searches the web in its own browser (Camoufox). It is downloaded once – "
                    + "about 1.3 GB – through the network settings above; download it now so the first research "
                    + "starts right away."));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
            row.add(download);
            row.add(javax.swing.Box.createHorizontalStrut(8));
            row.add(browserStatus);
            add(row);
            progress.setStringPainted(true);
            progress.setVisible(false);
            add(progress);
            JTextArea providerIntro = note("Search provider: Google in the browser needs no key. With a SerpApi, "
                    + "Brave, DataForSEO or Bright Data key the agent can search through that service instead.");
            providerIntro.setBorder(BorderFactory.createEmptyBorder(10, 0, 2, 0));
            add(providerIntro);
            SearchProviderCardsPanel cards;
            try {
                cards = new SearchProviderCardsPanel(context.getHostStateStore());
                cards.setBorder(BorderFactory.createEmptyBorder());
                transparent(cards);
                add(cards);
            } catch (RuntimeException unavailable) {
                cards = null;
            }
            providers = cards;
            download.addActionListener(e -> startDownload());
            poll = new Timer(500, e -> refresh());
            refresh();
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

        private void startDownload() {
            if (started) {
                return;
            }
            started = true;
            Thread install = new Thread(InProcessBrowser::installCamoufox, "camoufox-setup");
            install.setDaemon(true);
            install.start();
            progress.setIndeterminate(true);
            progress.setString("Connecting …");
            progress.setVisible(true);
            poll.start();
            refresh();
        }

        private void refresh() {
            CamoufoxInstaller.Status status;
            try {
                status = InProcessBrowser.camoufoxStatus();
            } catch (RuntimeException | LinkageError unavailable) {
                browserStatus.setText("The search browser cannot be installed here.");
                download.setEnabled(false);
                poll.stop();
                return;
            }
            browserStatus.setText(status.describe());
            CamoufoxInstaller.Phase phase = status.phase();
            boolean busy = phase == CamoufoxInstaller.Phase.DOWNLOADING || phase == CamoufoxInstaller.Phase.VERIFYING;
            download.setVisible(phase != CamoufoxInstaller.Phase.READY);
            download.setEnabled(!busy);
            if (phase == CamoufoxInstaller.Phase.DOWNLOADING && status.size() > 0) {
                progress.setVisible(true);
                progress.setIndeterminate(false);
                progress.setValue((int) Math.min(1000, 1000 * status.downloaded() / status.size()));
                progress.setString((100 * status.downloaded() / status.size()) + " %");
            } else if (phase == CamoufoxInstaller.Phase.VERIFYING) {
                progress.setVisible(true);
                progress.setIndeterminate(true);
                progress.setString("Unpacking …");
            } else if (phase == CamoufoxInstaller.Phase.READY || phase == CamoufoxInstaller.Phase.FAILED) {
                progress.setVisible(false);
                started = phase == CamoufoxInstaller.Phase.READY;
                if (phase == CamoufoxInstaller.Phase.FAILED) {
                    download.setText("Try again");
                }
                poll.stop();
            }
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void save() {
            if (providers != null) {
                providers.save();
            }
        }

        @Override
        public void dispose() {
            poll.stop();
            if (providers != null) {
                providers.dispose();
            }
        }
    }
}
