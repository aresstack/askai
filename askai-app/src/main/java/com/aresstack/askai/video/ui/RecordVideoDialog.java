package com.aresstack.askai.video.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.video.MediaRecorderProvider;
import com.aresstack.askai.video.RecordingProfile;
import com.aresstack.askai.video.RecordingSource;
import com.aresstack.askai.video.VideoRecordingController;
import com.aresstack.askai.video.VideoSettings;
import com.aresstack.askai.video.VideoSettingsStore;
import com.aresstack.askai.video.optional.FfmpegRecorderProvider;
import com.aresstack.askai.video.optional.FfmpegRuntimeLoader;
import com.aresstack.askai.video.optional.VlcRecorderProvider;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The generic Record Video dialog (opened from Help → Record Video…). It is the ONLY video class that
 * touches Swing and it works exclusively through {@link VideoRecordingController} — no JCodec/VLC/FFmpeg
 * types here. It hands the controller a NEUTRAL {@link RecordingSource} (bounds), never an AskAiFrame.
 */
public final class RecordVideoDialog extends JDialog {

    private final VideoRecordingController controller;
    private final Rectangle ownerWindowBounds;

    private final JComboBox<String> sourceCombo = ComicControls.<String>comboBox();
    private final JComboBox<String> backendCombo = ComicControls.<String>comboBox();
    private final JTextField outputField = ComicControls.textField(28);
    private final JButton chooseOutput = new ComicButton("Choose…");
    private final JButton startButton = new ComicButton("Start Recording");
    private final JButton stopButton = new ComicButton("Stop Recording");
    private final JButton openFolder = new ComicButton("Open Folder");
    private final JButton settingsButton = new ComicButton("Settings…");
    private final JLabel status = new JLabel(" ");

    private final VideoSettingsStore settingsStore = VideoSettingsStore.shared();
    private VideoSettings videoSettings = settingsStore.load();
    private List<MediaRecorderProvider> providers;
    private Path lastOutput;
    private volatile boolean downloading;

    public RecordVideoDialog(Window owner, VideoRecordingController controller, Rectangle ownerBounds,
                             String defaultFileName) {
        super(owner, "Record Video", ModalityType.MODELESS);
        this.controller = controller;
        this.ownerWindowBounds = ownerBounds;
        buildUi(defaultFileName);
        wireController();
        applyState(controller.getState());
    }

    private void buildUi(String defaultFileName) {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        sourceCombo.addItem("AskAI Window");
        sourceCombo.addItem("Screen / Monitor");
        form.add(row("Source:", sourceCombo));

        // ALL backends are listed so the optional ones stay discoverable; unavailable ones are marked
        // and starting them either refuses (VLC without an install) or asks the user to CONFIRM the
        // one-time library download (FFmpeg). Nothing is ever downloaded or swapped in silently.
        providers = controller.providers();
        refreshBackendCombo();
        // Preselect the persisted default backend; fall back to the controller's selection.
        String preferred = videoSettings.getGeneral().getDefaultBackend();
        int preselect = -1;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i).getId().equals(preferred)) {
                preselect = i;
            }
        }
        for (int i = 0; i < providers.size(); i++) {
            if (preselect < 0 && providers.get(i).getId().equals(controller.getSelectedProvider().getId())) {
                preselect = i;
            }
        }
        if (preselect >= 0) {
            backendCombo.setSelectedIndex(preselect);
        }
        form.add(row("Backend:", backendCombo));

        JPanel out = new JPanel(new BorderLayout(6, 0));
        outputField.setText(defaultOutputPath(defaultFileName));
        out.add(outputField, BorderLayout.CENTER);
        out.add(chooseOutput, BorderLayout.EAST);
        form.add(row("Output:", out));

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(startButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(stopButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(openFolder);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(settingsButton);
        form.add(Box.createVerticalStrut(8));
        form.add(buttons);
        form.add(Box.createVerticalStrut(6));
        form.add(status);

        add(form, BorderLayout.CENTER);
        setMinimumSize(new Dimension(460, 220));
        pack();
        setLocationRelativeTo(getOwner());

        chooseOutput.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                chooseOutput();
            }
        });
        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                startRecording();
            }
        });
        stopButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                controller.stop();
            }
        });
        openFolder.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openOutputFolder();
            }
        });
        settingsButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                openVideoSettings();
            }
        });
        if (backendCombo.getItemCount() == 0) {
            status.setText("No recording backend is available.");
            startButton.setEnabled(false);
        }
    }

    private void wireController() {
        controller.setListener(new VideoRecordingController.Listener() {
            public void onStateChanged(final VideoRecordingController.State state) {
                onEdt(new Runnable() {
                    public void run() {
                        applyState(state);
                    }
                });
            }

            public void onRecordingStarted(RecordingProfile profile) {
                onEdt(new Runnable() {
                    public void run() {
                        status.setText("Recording…");
                    }
                });
            }

            public void onRecordingStopped(final Path outputFile) {
                onEdt(new Runnable() {
                    public void run() {
                        lastOutput = outputFile;
                        status.setText("Saved: " + outputFile);
                    }
                });
            }

            public void onError(final String message) {
                onEdt(new Runnable() {
                    public void run() {
                        status.setText(message);
                    }
                });
            }
        });
    }

    private void startRecording() {
        int backendIndex = backendCombo.getSelectedIndex();
        MediaRecorderProvider chosen =
                (backendIndex >= 0 && backendIndex < providers.size()) ? providers.get(backendIndex) : null;
        if (chosen != null && !chosen.isAvailable()) {
            if (FfmpegRecorderProvider.ID.equals(chosen.getId())) {
                offerFfmpegDownloadThenStart();
            } else if (VlcRecorderProvider.ID.equals(chosen.getId())) {
                status.setText("VLC is not installed. Install VLC (videolan.org) or choose another backend.");
            } else {
                status.setText("The '" + chosen.getDisplayName() + "' backend is not available.");
            }
            return; // never a silent fallback to another backend
        }
        // Persist the backend choice first (rejected by the controller while recording).
        if (chosen != null) {
            controller.selectProvider(chosen.getId());
        }
        Path output = Paths.get(outputField.getText().trim());
        RecordingSource source = buildSource();
        if (source == null) {
            status.setText("The capture source has no valid bounds.");
            return;
        }
        RecordingProfile profile;
        try {
            profile = RecordingProfile.builder()
                    .source(source)
                    .outputFile(output)
                    .fps(Math.max(1, videoSettings.getGeneral().getFps()))
                    .build();
        } catch (RuntimeException invalid) {
            status.setText(invalid.getMessage());
            return;
        }
        controller.start(profile);
    }

    private RecordingSource buildSource() {
        boolean window = sourceCombo.getSelectedIndex() == 0;
        if (window) {
            if (ownerWindowBounds == null || ownerWindowBounds.width <= 0
                    || ownerWindowBounds.height <= 0) {
                return null;
            }
            return RecordingSource.window(ownerWindowBounds, "AskAI Window");
        }
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return RecordingSource.screen(screen, "Screen");
    }

    /**
     * FFmpeg needs native libraries AskAI does not ship. Exactly as in the WD4J/corenth reference they
     * are downloaded ONLY on the user's explicit, confirmed request: this shows the exact files and
     * asks Yes/No; on No nothing is downloaded and no other backend is used instead.
     */
    private void offerFfmpegDownloadThenStart() {
        StringBuilder message = new StringBuilder();
        message.append("The FFmpeg backend needs native libraries (JavaCV/FFmpeg) that AskAI does not ship.\n");
        message.append("Download them now from Maven Central? (one time, roughly 100-200 MB)\n\n");
        message.append("Files:\n");
        List<String> urls = FfmpegRuntimeLoader.requiredDownloadUrls();
        for (int i = 0; i < urls.size(); i++) {
            message.append("  ").append(urls.get(i)).append('\n');
        }
        message.append("\nTarget: ").append(FfmpegRuntimeLoader.libDirectory());
        int confirmed = javax.swing.JOptionPane.showConfirmDialog(this, message.toString(),
                "Download FFmpeg libraries?", javax.swing.JOptionPane.YES_NO_OPTION);
        if (confirmed != javax.swing.JOptionPane.YES_OPTION) {
            status.setText("Download declined - the FFmpeg backend stays unavailable (no fallback used).");
            return;
        }
        downloading = true;
        applyState(controller.getState());
        status.setText("Downloading the FFmpeg libraries...");
        Thread downloader = new Thread(new Runnable() {
            public void run() {
                Exception failure = null;
                try {
                    FfmpegRuntimeLoader.downloadAndAttach(new FfmpegRuntimeLoader.ProgressListener() {
                        public void onFile(final String fileName, final int index, final int total) {
                            onEdt(new Runnable() {
                                public void run() {
                                    status.setText("Downloading " + fileName + " (" + index + "/" + total + ")...");
                                }
                            });
                        }
                    });
                } catch (Exception ex) {
                    failure = ex;
                }
                final Exception result = failure;
                onEdt(new Runnable() {
                    public void run() {
                        downloading = false;
                        applyState(controller.getState());
                        refreshBackendCombo();
                        if (result != null) {
                            status.setText("FFmpeg download failed: " + result.getMessage());
                        } else {
                            status.setText("FFmpeg libraries installed.");
                            startRecording(); // the user asked to record with FFmpeg - now it can
                        }
                    }
                });
            }
        }, "ffmpeg-lib-download");
        downloader.setDaemon(true);
        downloader.start();
    }

    /** Re-label every backend entry with its current availability, keeping the selection stable. */
    private void refreshBackendCombo() {
        int selected = backendCombo.getSelectedIndex();
        backendCombo.removeAllItems();
        for (int i = 0; i < providers.size(); i++) {
            MediaRecorderProvider provider = providers.get(i);
            String label = provider.getDisplayName();
            if (!provider.isAvailable()) {
                label += FfmpegRecorderProvider.ID.equals(provider.getId())
                        ? "  [not installed - download on request]"
                        : "  [not installed]";
            }
            backendCombo.addItem(label);
        }
        if (selected >= 0 && selected < backendCombo.getItemCount()) {
            backendCombo.setSelectedIndex(selected);
        }
    }

    private void applyState(VideoRecordingController.State state) {
        boolean recording = state == VideoRecordingController.State.RECORDING;
        startButton.setEnabled(!recording && !downloading && backendCombo.getItemCount() > 0);
        stopButton.setEnabled(recording);
        sourceCombo.setEnabled(!recording);
        backendCombo.setEnabled(!recording);
        outputField.setEnabled(!recording);
        chooseOutput.setEnabled(!recording);
        settingsButton.setEnabled(!recording && !downloading);
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(outputField.getText().trim()));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            outputField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void openOutputFolder() {
        Path target = lastOutput != null ? lastOutput : Paths.get(outputField.getText().trim());
        File folder = target.getParent() == null ? null : target.getParent().toFile();
        if (folder != null && folder.isDirectory()) {
            try {
                java.awt.Desktop.getDesktop().open(folder);
            } catch (Exception ex) {
                status.setText("Could not open the folder: " + ex.getMessage());
            }
        }
    }

    private String defaultOutputPath(String defaultFileName) {
        String dir = videoSettings.getGeneral().getOutputDirectory();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.home", ".");
        }
        return Paths.get(dir.trim(), defaultFileName).toString();
    }

    /** Open the video settings dialog; on save, re-read the store and refresh what depends on it. */
    private void openVideoSettings() {
        VideoSettingsDialog dialog = new VideoSettingsDialog(this, settingsStore);
        dialog.setVisible(true); // modal
        if (dialog.isSaved()) {
            String previousDefault = defaultOutputPathFileName();
            videoSettings = settingsStore.load();
            refreshBackendCombo();
            // Follow the new output folder unless the user already typed a custom path.
            if (previousDefault != null) {
                outputField.setText(defaultOutputPath(previousDefault));
            }
            status.setText("Video settings saved.");
        }
    }

    /** The file name in the output field, or null when the user left the folder-based default path. */
    private String defaultOutputPathFileName() {
        Path current = Paths.get(outputField.getText().trim());
        return current.getFileName() == null ? null : current.getFileName().toString();
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private static JPanel row(String label, java.awt.Component field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(90, 24));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return p;
    }
}
