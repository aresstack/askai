package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.playwright.BrowserExecutables;
import com.aresstack.askai.browser.playwright.CamoufoxSetup;
import com.aresstack.askai.browser.playwright.camoufox.CamoufoxInstaller;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.PlaceholderTextField;

import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.io.File;

/**
 * Where the research browser is: one field with Browse… (and, for Camoufox, Download) in the same line, shared by
 * the setup wizard and Research Agent → Browser. An empty field means autodetect; the detected location is shown as
 * the field's placeholder. For Camoufox the field is a folder (AskAI's verified build; Download installs it there,
 * or into AskAI's own folder when empty); for Firefox, Chrome and Edge it is the browser's executable (AskAI never
 * downloads those). Every change is stored at once and used by browsers started afterwards.
 */
final class BrowserLocationControl {

    private final WorkspaceStateStore store;
    private final PlaceholderTextField field = new PlaceholderTextField(24);
    private final ComicButton browse = new ComicButton("Browse…");
    private final ComicButton download = new ComicButton("Download", ComicButton.Accent.ACTION);
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = ComicControls.progressBar(0, 1000);
    private final Timer poll;
    private final Timer recheck;
    private String channel = ResearchRuntimeSettings.CAMOUFOX_CHANNEL;
    /** The folder being downloaded into, or {@code null}. */
    private String installing;
    private boolean loading;

    BrowserLocationControl(WorkspaceStateStore store) {
        this.store = store;
        progress.setStringPainted(true);
        progress.setVisible(false);
        poll = new Timer(500, e -> refresh());
        recheck = new Timer(400, e -> {
            persist();
            refresh();
        });
        recheck.setRepeats(false);
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        browse.addActionListener(e -> choose());
        download.addActionListener(e -> startDownload());
    }

    JComponent field() {
        return field;
    }

    JComponent browseButton() {
        return browse;
    }

    JComponent downloadButton() {
        return download;
    }

    JComponent status() {
        return status;
    }

    JComponent progress() {
        return progress;
    }

    /** Shows the stored location of {@code newChannel} (camoufox, firefox, chrome or msedge). */
    void setChannel(String newChannel) {
        if (recheck.isRunning()) {
            recheck.stop();
            persist(); // a pending edit belongs to the channel it was typed for
        }
        channel = newChannel == null || newChannel.trim().isEmpty()
                ? ResearchRuntimeSettings.CAMOUFOX_CHANNEL : newChannel.trim();
        loading = true;
        try {
            field.setText(camoufox() ? ResearchRuntimeSettings.loadCamoufoxFolder(store)
                    : ResearchRuntimeSettings.loadBrowserExecutable(store, channel));
        } finally {
            loading = false;
        }
        field.setToolTipText(camoufox()
                ? "Folder of AskAI's Camoufox; empty = AskAI's own folder (autodetect)"
                : "The browser's program file; empty = the standard install location (autodetect)");
        browse.setToolTipText(camoufox() ? "Choose the Camoufox folder" : "Choose the browser's program file");
        refresh();
    }

    /** The typed location, trimmed ("" = autodetect). */
    String chosen() {
        return field.getText() == null ? "" : field.getText().trim();
    }

    void dispose() {
        poll.stop();
        recheck.stop();
    }

    private boolean camoufox() {
        return ResearchRuntimeSettings.CAMOUFOX_CHANNEL.equals(channel);
    }

    private void changed() {
        if (!loading) {
            recheck.restart();
        }
    }

    private void persist() {
        if (camoufox()) {
            ResearchRuntimeSettings.saveCamoufoxFolder(store, chosen());
        } else {
            ResearchRuntimeSettings.saveBrowserExecutable(store, channel, chosen());
        }
    }

    private void choose() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(camoufox() ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        chooser.setDialogTitle(camoufox() ? "Folder of Camoufox" : "Program file of the browser");
        String current = chosen().isEmpty() ? field.getPlaceholder() : chosen();
        File start = new File(current == null || current.isEmpty() || current.startsWith("Not found")
                ? System.getProperty("user.home") : current);
        chooser.setCurrentDirectory(start.isDirectory() ? start : start.getParentFile());
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(field)) == JFileChooser.APPROVE_OPTION
                && chooser.getSelectedFile() != null) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
            recheck.stop();
            persist();
            refresh();
        }
    }

    private void startDownload() {
        if (installing != null || !camoufox()) {
            return;
        }
        final String target;
        try {
            target = chosen().isEmpty() ? CamoufoxSetup.defaultCamoufoxFolder() : chosen();
        } catch (RuntimeException | LinkageError unavailable) {
            refresh();
            return;
        }
        installing = target;
        // The same installer as the browser start (verified, resumable, never over a foreign Camoufox).
        Thread install = new Thread(() -> CamoufoxSetup.installCamoufox(target), "camoufox-setup");
        install.setDaemon(true);
        install.start();
        progress.setIndeterminate(true);
        progress.setString("Connecting …");
        progress.setVisible(true);
        poll.start();
        refresh();
    }

    /** What the location holds, or what the download into it is doing. */
    void refresh() {
        field.setEnabled(installing == null);
        browse.setEnabled(installing == null);
        if (!camoufox() && installing == null) {
            download.setVisible(false);
            progress.setVisible(false);
            String detected;
            try {
                detected = BrowserExecutables.detected(channel);
            } catch (RuntimeException | LinkageError unavailable) {
                detected = "";
            }
            field.setPlaceholder(detected.isEmpty() ? "Not found – choose the program file" : detected);
            if (!chosen().isEmpty()) {
                status.setText(new File(chosen()).isFile() ? "This browser is used."
                        : "No program file at this location.");
            } else {
                status.setText(detected.isEmpty() ? "Not installed in the standard location; AskAI never"
                        + " downloads this browser." : "Autodetected.");
            }
            return;
        }
        String placeholder;
        try {
            String found = CamoufoxSetup.foundCamoufox();
            placeholder = found.isEmpty() ? CamoufoxSetup.defaultCamoufoxFolder() : found;
        } catch (RuntimeException | LinkageError unavailable) {
            placeholder = "";
        }
        field.setPlaceholder(placeholder);
        String location = installing != null ? installing : chosen().isEmpty() ? placeholder : chosen();
        if (location.isEmpty()) {
            status.setText("The browser cannot be installed here.");
            download.setVisible(false);
            return;
        }
        CamoufoxInstaller.Status state;
        String found;
        try {
            state = CamoufoxSetup.camoufoxStatus(location);
            found = installing != null ? "NONE" : CamoufoxSetup.inspectCamoufox(location);
        } catch (RuntimeException | LinkageError unavailable) {
            status.setText("The browser cannot be installed here.");
            download.setEnabled(false);
            poll.stop();
            installing = null;
            return;
        }
        CamoufoxInstaller.Phase phase = state.phase();
        if (installing == null) {
            progress.setVisible(false);
            if ("VERIFIED".equals(found) || phase == CamoufoxInstaller.Phase.READY) {
                status.setText("Camoufox is installed here.");
                download.setVisible(false);
            } else if ("FOREIGN".equals(found)) {
                status.setText("This folder holds a Camoufox that is not AskAI's verified build; nothing is"
                        + " downloaded over it.");
                download.setVisible(false);
            } else {
                status.setText(phase == CamoufoxInstaller.Phase.FAILED ? state.describe()
                        : "Camoufox is not installed here yet (about 1.3 GB).");
                download.setText(phase == CamoufoxInstaller.Phase.FAILED ? "Try again" : "Download");
                download.setVisible(true);
                download.setEnabled(true);
            }
            return;
        }
        status.setText(state.describe());
        download.setEnabled(false);
        if (phase == CamoufoxInstaller.Phase.DOWNLOADING && state.size() > 0) {
            progress.setVisible(true);
            progress.setIndeterminate(false);
            progress.setValue((int) Math.min(1000, 1000 * state.downloaded() / state.size()));
            progress.setString((100 * state.downloaded() / state.size()) + " %");
        } else if (phase == CamoufoxInstaller.Phase.VERIFYING) {
            progress.setVisible(true);
            progress.setIndeterminate(true);
            progress.setString("Unpacking …");
        } else if (phase == CamoufoxInstaller.Phase.READY || phase == CamoufoxInstaller.Phase.FAILED) {
            String done = installing;
            installing = null;
            poll.stop();
            if (phase == CamoufoxInstaller.Phase.READY && !done.equals(placeholder)) {
                if (camoufox()) {
                    loading = true;
                    try {
                        field.setText(done);
                    } finally {
                        loading = false;
                    }
                }
                // Stored for Camoufox even when another channel is shown meanwhile.
                ResearchRuntimeSettings.saveCamoufoxFolder(store, done);
            }
            refresh();
        }
    }
}
