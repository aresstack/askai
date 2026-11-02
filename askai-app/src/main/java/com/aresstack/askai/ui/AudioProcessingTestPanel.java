package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.audio.preview.AudioOutputDevice;
import com.aresstack.askai.audio.preview.AudioOutputDeviceCatalog;
import com.aresstack.askai.audio.preview.AudioProcessingTestController;
import com.aresstack.askai.audio.preview.AudioTestRecordingStore;
import com.aresstack.askai.audio.preview.DispatchingAudioPreviewPlaybackService;
import com.aresstack.askai.audio.preview.VlcInstallation;
import com.aresstack.askai.audio.preview.WavAudioTestSource;
import com.aresstack.audio.openal.StereoTestTone;
import com.aresstack.askai.speech.JavaSoundMicrophoneRecorder;
import com.aresstack.askai.speech.MicrophoneRecorder;
import com.aresstack.askai.speech.RawRecording;
import com.aresstack.audio.application.DefaultAudioProcessingPreviewService;
import com.aresstack.audio.application.DefaultProcessedWaveExportService;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.infrastructure.AvailableAudioDevices;
import com.aresstack.audio.profile.AudioProcessingProfile;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * The DSP test/preview area under the pipeline editor: pick a local WAV or record a microphone test wave,
 * process the current (possibly unsaved) pipeline snapshot through the productive preview path, play the
 * original or processed audio, and export the processed result as a WAV. All heavy work runs off the EDT
 * via {@link AudioProcessingTestController}; this panel only wires buttons and reflects state.
 */
public final class AudioProcessingTestPanel extends JPanel {

    private static final String SYSTEM_DEFAULT = "System default";

    private final AudioProcessingTestController controller;
    private final AudioTestRecordingStore recordingStore = new AudioTestRecordingStore();
    private final MicrophoneRecorder recorder = new JavaSoundMicrophoneRecorder();
    private final DispatchingAudioPreviewPlaybackService playback = new DispatchingAudioPreviewPlaybackService();
    private final File tempDir = new File(System.getProperty("java.io.tmpdir"), "askai-audio-tests-temp");
    private final ExecutorService executor;

    private final JComboBox<String> micCombo = ComicControls.<String>comboBox();
    private final JComboBox<AudioOutputDevice> outputCombo = ComicControls.<AudioOutputDevice>comboBox();
    private final JButton testMicButton = new ComicButton("Test microphone");
    private final JButton testOutputButton = new ComicButton("Test output");
    private final JButton testBeepButton = new ComicButton("Test beep");
    private final JTextField vlcPathField = ComicControls.textField(30);
    private final VlcInstallation vlcInstallation = new VlcInstallation();
    private final JLabel sourceLabel = new JLabel("No test file selected");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton processAndPlayButton = new ComicButton("Process and play");
    private final JButton processButton = new ComicButton("Process");
    private final JButton playOriginalButton = new ComicButton("Play original");
    private final JButton playProcessedButton = new ComicButton("Play processed");
    private final JButton stopButton = new ComicButton("Stop");
    private final JButton saveButton = new ComicButton("Save processed WAV…");

    private File lastDirectory;
    private File currentSourceFile;
    private String currentSourceText = "No test file selected";
    private MicrophoneRecorder.Session micTestSession;
    private Timer micTestTimer;

    public AudioProcessingTestPanel(Supplier<AudioProcessingProfile> snapshotSupplier) {
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "askai-audio-preview");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.controller = new AudioProcessingTestController(
                new DefaultAudioProcessingPreviewService(),
                playback,
                new DefaultProcessedWaveExportService(),
                snapshotSupplier, executor, new EdtListener());
        playback.setErrorHandler(new java.util.function.Consumer<String>() {
            public void accept(final String message) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setStatus("Playback failed: " + message);
                    }
                });
            }
        });
        playback.setInfoHandler(new java.util.function.Consumer<String>() {
            public void accept(final String message) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        setStatus(message);
                    }
                });
            }
        });
        buildUserInterface();
        refreshCaptureDevices();
        refreshPlaybackDevices();
        refreshControls();
    }

    /** Notify the controller that the editor pipeline changed, so a preview result is marked outdated. */
    public void pipelineChanged() {
        controller.pipelineChanged();
    }

    private void buildUserInterface() {
        setLayout(new BorderLayout(6, 4));
        setBorder(ComicControls.titledBorder("Test & preview"));

        JPanel sourceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton selectFile = new ComicButton("Select test file…");
        selectFile.addActionListener(event -> selectTestFile());
        JButton record = new ComicButton("Record test wave…");
        record.addActionListener(event -> recordTestWave());
        sourceRow.add(selectFile);
        sourceRow.add(record);
        sourceLabel.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) {
                openSourceLocation();
            }

            public void mouseEntered(MouseEvent event) {
                renderSource(true);
            }

            public void mouseExited(MouseEvent event) {
                renderSource(false);
            }
        });
        sourceRow.add(sourceLabel);

        JPanel deviceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        deviceRow.add(new JLabel("Microphone:"));
        micCombo.setToolTipText("Capture device used for Record test wave.");
        deviceRow.add(micCombo);
        JButton micRefresh = new ComicButton("↻");
        micRefresh.setToolTipText("Refresh microphone list");
        micRefresh.addActionListener(event -> refreshCaptureDevices());
        deviceRow.add(micRefresh);
        testMicButton.addActionListener(event -> toggleMicTest());
        deviceRow.add(testMicButton);
        deviceRow.add(new JLabel("   Output:"));
        outputCombo.setToolTipText("Playback device used for Play original / Play processed "
                + "(OpenAL Soft on Windows; Java Sound as legacy).");
        outputCombo.addActionListener(event -> applySelectedOutputDevice());
        deviceRow.add(outputCombo);
        JButton outRefresh = new ComicButton("↻");
        outRefresh.setToolTipText("Refresh output device list");
        outRefresh.addActionListener(event -> refreshPlaybackDevices());
        deviceRow.add(outRefresh);
        testOutputButton.setToolTipText("Play the stereo test tone (left then right) through the selected device.");
        testOutputButton.addActionListener(event -> testOutput());
        deviceRow.add(testOutputButton);
        testBeepButton.setToolTipText("Play a short beep through the selected output device.");
        testBeepButton.addActionListener(event -> testBeep());
        deviceRow.add(testBeepButton);

        JPanel vlcRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        vlcRow.add(new JLabel("VLC executable:"));
        vlcPathField.setEditable(false);
        vlcPathField.setToolTipText("The vlc.exe used for the VLC output backend. Empty = automatic detection.");
        vlcRow.add(vlcPathField);
        JButton vlcBrowse = new ComicButton("Browse…");
        vlcBrowse.setToolTipText("Select vlc.exe (or a VLCPortable.exe) to enable the VLC output backend.");
        vlcBrowse.addActionListener(event -> browseVlcExecutable());
        vlcRow.add(vlcBrowse);
        JButton vlcClear = new ComicButton("Clear");
        vlcClear.setToolTipText("Remove the manual path and fall back to automatic detection.");
        vlcClear.addActionListener(event -> clearVlcExecutable());
        vlcRow.add(vlcClear);
        updateVlcPathField();

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        processAndPlayButton.addActionListener(event -> controller.processAndPlay());
        processButton.addActionListener(event -> controller.process());
        playOriginalButton.addActionListener(event -> controller.playOriginal());
        playProcessedButton.addActionListener(event -> controller.playProcessed());
        stopButton.addActionListener(event -> controller.stop());
        saveButton.addActionListener(event -> saveProcessed());
        actionRow.add(processAndPlayButton);
        actionRow.add(processButton);
        actionRow.add(playOriginalButton);
        actionRow.add(playProcessedButton);
        actionRow.add(stopButton);
        actionRow.add(saveButton);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(sourceRow);
        top.add(deviceRow);
        top.add(vlcRow);
        top.add(actionRow);
        add(top, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    // ------------------------------------------------------------------ device selection

    private void refreshCaptureDevices() {
        fillCombo(micCombo, safeList(true));
    }

    private void refreshPlaybackDevices() {
        String previous = outputCombo.getSelectedItem() == null
                ? null : outputCombo.getSelectedItem().toString();
        outputCombo.removeAllItems();
        List<AudioOutputDevice> devices;
        try {
            devices = new AudioOutputDeviceCatalog().findAll();
        } catch (Exception ex) {
            devices = java.util.Collections.<AudioOutputDevice>emptyList();
        }
        AudioOutputDevice reselect = null;
        for (AudioOutputDevice device : devices) {
            outputCombo.addItem(device);
            if (previous != null && previous.equals(device.getDisplayName())) {
                reselect = device;
            }
        }
        if (reselect != null) {
            outputCombo.setSelectedItem(reselect);
        } else {
            // The PERSISTED selection (shared with the chat settings' Playback output row) —
            // historically this reset to the list head on every open, losing e.g. VLC silently.
            AudioOutputDevice persisted = new com.aresstack.askai.audio.preview
                    .AudioPlaybackSettingsStore().resolve(devices);
            if (persisted != null) {
                outputCombo.setSelectedItem(persisted);
            }
        }
        applySelectedOutputDevice();
    }

    private void applySelectedOutputDevice() {
        AudioOutputDevice selected = (AudioOutputDevice) outputCombo.getSelectedItem();
        playback.setOutputDevice(selected);
        if (selected != null) {
            try {
                new com.aresstack.askai.audio.preview.AudioPlaybackSettingsStore()
                        .persistSelection(selected); // one shared selection with the chat settings
            } catch (java.io.IOException ignored) {
                // the panel keeps working; the chat settings row will simply not see this change
            }
        }
    }

    private static void fillCombo(JComboBox<String> combo, List<String> devices) {
        Object previous = combo.getSelectedItem();
        combo.removeAllItems();
        combo.addItem(SYSTEM_DEFAULT);
        for (String device : devices) {
            combo.addItem(device);
        }
        if (previous != null && devices.contains(previous)) {
            combo.setSelectedItem(previous);
        } else {
            combo.setSelectedItem(SYSTEM_DEFAULT);
        }
    }

    private static List<String> safeList(boolean capture) {
        try {
            return capture ? AvailableAudioDevices.listCaptureDeviceNames()
                    : AvailableAudioDevices.listPlaybackDeviceNames();
        } catch (Exception ex) {
            return java.util.Collections.<String>emptyList();
        }
    }

    private String selectedMicDevice() {
        Object selected = micCombo.getSelectedItem();
        String value = selected == null ? "" : String.valueOf(selected);
        return SYSTEM_DEFAULT.equals(value) ? "" : value;
    }

    /** Open the selected microphone briefly and report whether a signal is detected; nothing is stored. */
    private void toggleMicTest() {
        if (micTestSession != null) {
            stopMicTest("Microphone test stopped.");
            return;
        }
        try {
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
                throw new java.io.IOException("Cannot create the temp recording directory.");
            }
            micTestSession = recorder.start(selectedMicDevice(), tempDir);
        } catch (Exception ex) {
            micTestSession = null;
            setStatus("Microphone test failed: " + message(ex));
            return;
        }
        testMicButton.setText("Stop test");
        final AudioLevelMeter meter = micTestSession.getMeter();
        micTestTimer = new Timer(150, new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent event) {
                boolean signal = meter.getPeak() > 500; // ~ -36 dBFS on the 16-bit scale
                setStatus(signal ? "Microphone test: signal detected." : "Microphone test: listening…");
            }
        });
        micTestTimer.start();
    }

    private void stopMicTest(String status) {
        if (micTestTimer != null) {
            micTestTimer.stop();
            micTestTimer = null;
        }
        if (micTestSession != null) {
            micTestSession.discard();
            micTestSession = null;
        }
        testMicButton.setText("Test microphone");
        setStatus(status);
    }

    /** Play the stereo test tone (left then right) through the selected output device. */
    private void testOutput() {
        applySelectedOutputDevice();
        setStatus("Playing stereo test tone…");
        int rate = 44100;
        playback.play(StereoTestTone.interleaved(rate),
                new com.aresstack.audio.domain.PcmAudioFormat(rate, StereoTestTone.CHANNELS, 16),
                new Runnable() {
                    public void run() {
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                setStatus("Test tone finished.");
                            }
                        });
                    }
                });
    }

    /** Play a short beep through the selected output device so the user can confirm it is audible. */
    private void testBeep() {
        applySelectedOutputDevice();
        setStatus("Playing test beep…");
        playback.play(generateBeep(), new com.aresstack.audio.domain.PcmAudioFormat(44100, 1, 16),
                new Runnable() {
                    public void run() {
                        SwingUtilities.invokeLater(new Runnable() {
                            public void run() {
                                setStatus("Test beep finished.");
                            }
                        });
                    }
                });
    }

    /** A ~350 ms 880 Hz sine "bing" with a short fade-in and exponential decay, 44.1 kHz mono 16-bit. */
    private static short[] generateBeep() {
        int rate = 44100;
        int length = rate * 350 / 1000;
        double frequency = 880.0;
        short[] samples = new short[length];
        for (int i = 0; i < length; i++) {
            double t = (double) i / rate;
            double envelope = Math.min(1.0, i / (rate * 0.01)) * Math.exp(-3.5 * t); // fade-in + decay
            double value = Math.sin(2.0 * Math.PI * frequency * t) * envelope * 0.6;
            samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value * Short.MAX_VALUE));
        }
        return samples;
    }

    /** Show the currently configured VLC path, or a hint that automatic detection is in effect. */
    private void updateVlcPathField() {
        String configured = vlcInstallation.getConfiguredPath();
        vlcPathField.setText(configured.length() == 0 ? "(automatic detection)" : configured);
        vlcPathField.setCaretPosition(0);
    }

    /**
     * Let the user pick a vlc.exe (or VLCPortable.exe, resolved to its bundled vlc.exe), persist it via the
     * existing {@link VlcInstallation} preferences, then reload the output devices so VLC becomes selectable.
     */
    private void browseVlcExecutable() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select vlc.exe (or VLCPortable.exe)");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("VLC executable (vlc.exe, VLCPortable.exe)", "exe"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File executable = VlcInstallation.resolveChosenExecutable(chooser.getSelectedFile());
        if (executable == null) {
            JOptionPane.showMessageDialog(this,
                    "That is not a usable VLC executable.\n"
                            + "Select vlc.exe, or a VLCPortable.exe with App\\vlc\\vlc.exe beside it.",
                    "Invalid VLC executable", JOptionPane.WARNING_MESSAGE);
            return;
        }
        vlcInstallation.setExecutable(executable);
        updateVlcPathField();
        refreshPlaybackDevices();
        setStatus("VLC set: " + executable.getAbsolutePath());
    }

    /** Remove the manual VLC path and fall back to automatic detection, then reload the output devices. */
    private void clearVlcExecutable() {
        vlcInstallation.clearExecutable();
        updateVlcPathField();
        refreshPlaybackDevices();
        setStatus("VLC path cleared — using automatic detection.");
    }

    private void selectTestFile() {
        JFileChooser chooser = new JFileChooser();
        if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory);
        }
        chooser.setDialogTitle("Select a WAV test file");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("WAV files (*.wav)", "wav"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        lastDirectory = file.getParentFile();
        controller.setSource(new WavAudioTestSource(file, false));
        setSourceDisplay(file, "Source: " + file.getName());
    }

    private void saveProcessed() {
        ProcessedAudioPreview preview = controller.getPreview();
        if (preview == null) {
            setStatus("Nothing to save yet — process the pipeline first.");
            return;
        }
        if (controller.isOutdated()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "The preview is from an older pipeline. Re-process the current pipeline before saving?",
                    "Result outdated", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.CANCEL_OPTION) {
                return;
            }
            if (choice == JOptionPane.YES_OPTION) {
                controller.process(); // refresh, then the user saves again once processed
                setStatus("Re-processing… save again when it is ready.");
                return;
            }
        }
        JFileChooser chooser = new JFileChooser();
        if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory);
        }
        chooser.setDialogTitle("Save processed WAV");
        chooser.setFileFilter(new FileNameExtensionFilter("WAV files (*.wav)", "wav"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase().endsWith(".wav")) {
            target = new File(target.getParentFile(), target.getName() + ".wav");
        }
        if (target.exists() && JOptionPane.showConfirmDialog(this,
                "Overwrite the existing file " + target.getName() + "?", "Overwrite",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        lastDirectory = target.getParentFile();
        controller.export(target);
        setStatus("Saving processed WAV…");
    }

    // ------------------------------------------------------------------ recording

    private void recordTestWave() {
        controller.noteRecording(true);
        final MicrophoneRecorder.Session session;
        try {
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
                throw new java.io.IOException("Cannot create the temp recording directory.");
            }
            session = recorder.start(selectedMicDevice(), tempDir);
        } catch (Exception ex) {
            controller.noteRecording(false);
            JOptionPane.showMessageDialog(this, "Could not start recording:\n" + message(ex),
                    "Recording", JOptionPane.ERROR_MESSAGE);
            return;
        }
        // Blocking modal: record until the user stops, then offer play / save / discard.
        int stop = JOptionPane.showConfirmDialog(this,
                "Recording… speak your test sentence, then press OK to stop (Cancel discards).",
                "Recording", JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (stop != JOptionPane.OK_OPTION) {
            session.discard();
            controller.noteRecording(false);
            setStatus("Recording cancelled.");
            return;
        }
        RawRecording raw;
        try {
            raw = session.stop();
        } catch (Exception ex) {
            controller.noteRecording(false);
            JOptionPane.showMessageDialog(this, "Recording failed:\n" + message(ex),
                    "Recording", JOptionPane.ERROR_MESSAGE);
            return;
        }
        controller.noteRecording(false);
        offerRecording(raw);
    }

    private void offerRecording(RawRecording raw) {
        Object[] options = {"Save and use", "Play", "Discard"};
        while (true) {
            int choice = JOptionPane.showOptionDialog(this,
                    "Raw test recording ready (" + raw.getCaptureFormat() + ").",
                    "Test recording", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == 1) { // Play
                try {
                    WavAudioTestSource temp = new WavAudioTestSource(raw.getFile(), true);
                    com.aresstack.audio.domain.AudioBuffer buffer = temp.readBuffer();
                    applySelectedOutputDevice();
                    playback.play(buffer.getSamples(), buffer.getFormat(), null);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Could not play the recording:\n" + message(ex),
                            "Recording", JOptionPane.ERROR_MESSAGE);
                }
                continue;
            }
            playback.stop();
            if (choice == 0) { // Save and use
                String name = JOptionPane.showInputDialog(this, "Name for this test recording:",
                        "dsp-test-recording");
                if (name == null) {
                    continue;
                }
                try {
                    File saved = recordingStore.saveConfirmed(raw.getFile(), name);
                    controller.setSource(new WavAudioTestSource(saved, true));
                    setSourceDisplay(saved, "Source: " + saved.getName() + " (recording)");
                    setStatus("Saved test recording to " + saved.getAbsolutePath());
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Could not save the recording:\n" + message(ex),
                            "Recording", JOptionPane.ERROR_MESSAGE);
                }
                return;
            }
            // Discard
            if (raw.getFile() != null && raw.getFile().exists() && !raw.getFile().delete()) {
                raw.getFile().deleteOnExit();
            }
            setStatus("Recording discarded.");
            return;
        }
    }

    // ------------------------------------------------------------------ state → UI

    private final class EdtListener implements AudioProcessingTestController.Listener {
        public void stateChanged(final AudioProcessingTestController.State state) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    setStatus(describe(state));
                    refreshControls();
                }
            });
        }

        public void previewUpdated(ProcessedAudioPreview preview, boolean outdated) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    refreshControls();
                }
            });
        }

        public void failed(final String message) {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    setStatus("Failed: " + message);
                    refreshControls();
                }
            });
        }
    }

    private void refreshControls() {
        boolean hasSource = controller.getSource() != null;
        boolean hasPreview = controller.getPreview() != null;
        boolean recording = controller.getState() == AudioProcessingTestController.State.RECORDING;
        processAndPlayButton.setEnabled(hasSource && !recording);
        processButton.setEnabled(hasSource && !recording);
        playOriginalButton.setEnabled(hasSource && !recording);
        playProcessedButton.setEnabled(hasPreview && !recording);
        saveButton.setEnabled(hasPreview && !recording);
        stopButton.setEnabled(!recording);
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null || text.isEmpty() ? " " : text);
    }

    /** Remember the selected source file and render its label; a real file becomes a hover link. */
    private void setSourceDisplay(File file, String text) {
        this.currentSourceFile = file;
        this.currentSourceText = text;
        renderSource(false);
    }

    /** Plain text when idle; on hover a real source file shows as an underlined link with a hand cursor. */
    private void renderSource(boolean hovered) {
        boolean isLink = currentSourceFile != null;
        if (isLink && hovered) {
            sourceLabel.setText("<html><a href=''>" + escapeHtml(currentSourceText) + "</a></html>");
            sourceLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            sourceLabel.setToolTipText("Open the file location: " + currentSourceFile.getAbsolutePath());
        } else {
            sourceLabel.setText(currentSourceText);
            sourceLabel.setCursor(Cursor.getDefaultCursor());
            sourceLabel.setToolTipText(isLink
                    ? "Open the file location: " + currentSourceFile.getAbsolutePath() : null);
        }
    }

    /** Open the source file's folder in the OS file manager, selecting the file on Windows. */
    private void openSourceLocation() {
        File file = currentSourceFile;
        if (file == null) {
            return;
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win") && file.exists()) {
                new ProcessBuilder("explorer.exe", "/select,", file.getAbsolutePath()).start();
                return;
            }
            File dir = file.isDirectory() ? file : file.getParentFile();
            if (dir != null && dir.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                setStatus("File location is not available: " + file.getAbsolutePath());
            }
        } catch (Exception ex) {
            setStatus("Could not open the file location: " + message(ex));
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String describe(AudioProcessingTestController.State state) {
        switch (state) {
            case NO_SOURCE:
                return "No test file selected.";
            case READY:
                return "Ready.";
            case RECORDING:
                return "Recording…";
            case PROCESSING:
                return "Processing…";
            case PROCESSED:
                return "Processed.";
            case PLAYING_ORIGINAL:
                return "Playing original…";
            case PLAYING_PROCESSED:
                return "Playing processed…";
            case RESULT_OUTDATED:
                return "Result outdated — pipeline changed since this preview.";
            case CANCELLED:
                return "Cancelled.";
            case FAILED:
                return "Failed.";
            default:
                return " ";
        }
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }

    /** Stops playback and shuts down the background executor (call when the editor/app closes). */
    public void dispose() {
        stopMicTest(" ");
        controller.stop();
        playback.stop();
        executor.shutdownNow();
    }

    JComponent getComponent() {
        return this;
    }
}
