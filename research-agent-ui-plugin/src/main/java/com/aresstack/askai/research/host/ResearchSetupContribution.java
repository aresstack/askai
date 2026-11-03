package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.playwright.InProcessBrowser;
import com.aresstack.askai.browser.playwright.camoufox.CamoufoxInstaller;
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
        private final javax.swing.JTextField folder = ComicControls.textField(28);
        private final ComicButton browse = new ComicButton("Browse…");
        private final ComicButton download = new ComicButton("Download", ComicButton.Accent.ACTION);
        private final JProgressBar progress = ComicControls.progressBar(0, 1000);
        private final SearchProviderCardsPanel providers;
        private final AgentSetupContext context;
        private final Timer poll;
        /** Checks the typed folder once typing pauses (verifying an installation reads its files). */
        private final Timer recheck;
        /** The folder being downloaded into, or {@code null}. */
        private String installing;

        Page(AgentSetupContext context) {
            this.context = context;
            panel.setOpaque(false);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            add(note("The research agent searches the web in its own browser (Camoufox, about 1.3 GB). Enter the "
                    + "folder of an existing Camoufox, or download it now through the network settings above, so "
                    + "the first research starts right away. Download uses AskAI's folder when the field is empty."));
            JPanel row = new JPanel(new java.awt.GridBagLayout());
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
            java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
            c.gridx = 0;
            c.fill = java.awt.GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            row.add(folder, c);
            c = new java.awt.GridBagConstraints();
            c.gridx = 1;
            c.insets = new java.awt.Insets(0, 8, 0, 0);
            row.add(browse, c);
            c = new java.awt.GridBagConstraints();
            c.gridx = 2;
            c.insets = new java.awt.Insets(0, 8, 0, 0);
            row.add(download, c);
            add(row);
            folder.setToolTipText("Folder of the search browser: an existing Camoufox is used as it is");
            browserStatus.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            add(browserStatus);
            progress.setStringPainted(true);
            progress.setVisible(false);
            add(progress);
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
            String stored = ResearchRuntimeSettings.loadCamoufoxFolder(context.getHostStateStore());
            if (stored.isEmpty()) {
                try {
                    stored = InProcessBrowser.foundCamoufox(); // e.g. Argos' Camoufox: used, never downloaded again
                } catch (RuntimeException | LinkageError unavailable) {
                    stored = "";
                }
            }
            folder.setText(stored);
            download.addActionListener(e -> startDownload());
            browse.addActionListener(e -> chooseFolder());
            poll = new Timer(500, e -> refresh());
            recheck = new Timer(400, e -> refresh());
            recheck.setRepeats(false);
            folder.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    recheck.restart();
                }

                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    recheck.restart();
                }

                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                    recheck.restart();
                }
            });
            refresh();
        }

        private String chosenFolder() {
            return folder.getText() == null ? "" : folder.getText().trim();
        }

        private void chooseFolder() {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Folder of the search browser");
            String current = chosenFolder();
            java.io.File start = new java.io.File(current.isEmpty() ? System.getProperty("user.home") : current);
            chooser.setCurrentDirectory(start.isDirectory() ? start : start.getParentFile());
            if (chooser.showOpenDialog(panel) == javax.swing.JFileChooser.APPROVE_OPTION
                    && chooser.getSelectedFile() != null) {
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
                refresh();
            }
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
            if (installing != null) {
                return;
            }
            if (chosenFolder().isEmpty()) {
                try {
                    folder.setText(InProcessBrowser.defaultCamoufoxFolder());
                } catch (RuntimeException | LinkageError unavailable) {
                    refresh();
                    return;
                }
            }
            final String target = chosenFolder();
            installing = target;
            Thread install = new Thread(() -> InProcessBrowser.installCamoufox(target), "camoufox-setup");
            install.setDaemon(true);
            install.start();
            progress.setIndeterminate(true);
            progress.setString("Connecting …");
            progress.setVisible(true);
            poll.start();
            refresh();
        }

        /** What the folder field holds, or what the download into it is doing. */
        void refresh() {
            String chosen = installing != null ? installing : chosenFolder();
            folder.setEnabled(installing == null);
            browse.setEnabled(installing == null);
            if (chosen.isEmpty()) {
                progress.setVisible(false);
                browserStatus.setText("Camoufox is not installed yet. Download puts it into AskAI's folder.");
                download.setVisible(true);
                download.setEnabled(true);
                return;
            }
            CamoufoxInstaller.Status status;
            String found;
            try {
                status = InProcessBrowser.camoufoxStatus(chosen);
                found = installing != null ? "NONE" : InProcessBrowser.inspectCamoufox(chosen);
            } catch (RuntimeException | LinkageError unavailable) {
                browserStatus.setText("The search browser cannot be installed here.");
                download.setEnabled(false);
                poll.stop();
                installing = null;
                return;
            }
            CamoufoxInstaller.Phase phase = status.phase();
            if (installing == null) {
                progress.setVisible(false);
                if ("VERIFIED".equals(found) || phase == CamoufoxInstaller.Phase.READY) {
                    browserStatus.setText("Camoufox is installed here.");
                    download.setVisible(false);
                } else if ("FOREIGN".equals(found)) {
                    browserStatus.setText("This folder holds a Camoufox that is not AskAI's verified build; nothing "
                            + "is downloaded over it. Choose the installation folder itself or another folder.");
                    download.setVisible(false);
                } else {
                    browserStatus.setText(phase == CamoufoxInstaller.Phase.FAILED ? status.describe()
                            : "Camoufox is not installed here yet.");
                    download.setText(phase == CamoufoxInstaller.Phase.FAILED ? "Try again" : "Download");
                    download.setVisible(true);
                    download.setEnabled(true);
                }
                return;
            }
            browserStatus.setText(status.describe());
            download.setEnabled(false);
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
                installing = null;
                poll.stop();
                if (phase == CamoufoxInstaller.Phase.READY) {
                    ResearchRuntimeSettings.saveCamoufoxFolder(context.getHostStateStore(), chosen);
                }
                refresh();
            }
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void save() {
            ResearchRuntimeSettings.saveCamoufoxFolder(context.getHostStateStore(), chosenFolder());
            if (providers != null) {
                providers.save();
            }
        }

        @Override
        public void dispose() {
            poll.stop();
            recheck.stop();
            if (providers != null) {
                providers.dispose();
            }
        }
    }
}
