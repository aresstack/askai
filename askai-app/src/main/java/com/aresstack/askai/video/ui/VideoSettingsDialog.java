package com.aresstack.askai.video.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.video.VideoSettings;
import com.aresstack.askai.video.VideoSettingsStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;

/**
 * The video recorder settings dialog — the AskAI port of the WD4J/corenth {@code VideoSettingsPanel}
 * (General on top, one tab per backend). It edits the typed {@link VideoSettings} and persists via
 * {@link VideoSettingsStore}; every offered control is honored by the ported recorders — reference
 * knobs whose machinery was not ported (JCodec container/threads/audio, FFmpeg color/vf/fallbacks)
 * are deliberately absent instead of being dead switches.
 */
public final class VideoSettingsDialog extends JDialog {

    private final VideoSettingsStore store;
    private final VideoSettings settings;
    private boolean saved;

    // General
    private final JComboBox<String> cbBackend = ComicControls.<String>comboBox(new String[] {"jcodec", "vlc", "ffmpeg"});
    private final JSpinner spFps = ComicControls.spinner(new SpinnerNumberModel(15, 1, 120, 1));
    private final JTextField tfOutputDir = ComicControls.textField(26);

    // VLC
    private final JCheckBox cbVlcAutodetect = ComicControls.checkBox("Autodetect (PATH/registry via vlcj discovery)");
    private final JTextField tfVlcBasePath = ComicControls.textField(26);
    private final JCheckBox cbVlcLogEnabled = ComicControls.checkBox("Write a VLC log file");
    private final JTextField tfVlcLogPath = ComicControls.textField(26);
    private final JSpinner spVlcVerbose = ComicControls.spinner(new SpinnerNumberModel(1, 0, 2, 1));
    private final JComboBox<String> cbVlcMux = ComicControls.<String>comboBox(new String[] {"mp4", "ts", "mkv", "avi"});
    private final JTextField tfVlcVcodec = ComicControls.textField(10);
    private final JComboBox<String> cbVlcQuality = ComicControls.<String>comboBox(new String[] {"crf", "bitrate"});
    private final JSpinner spVlcCrf = ComicControls.spinner(new SpinnerNumberModel(23, 0, 51, 1));
    private final JSpinner spVlcBitrate = ComicControls.spinner(new SpinnerNumberModel(4000, 0, 200000, 100));
    private final JTextField tfVlcPreset = ComicControls.textField(10);
    private final JTextField tfVlcTune = ComicControls.textField(10);
    private final JCheckBox cbVlcDeint = ComicControls.checkBox("Deinterlace");
    private final JTextField tfVlcDeintMode = ComicControls.textField(10);
    private final JTextField tfVlcVideoFilter = ComicControls.textField(22);
    private final JTextField tfVlcSoutExtras = ComicControls.textField(22);
    private final JCheckBox cbVlcAudio = ComicControls.checkBox("Record audio (mp3 128k stereo)");
    private final JCheckBox cbVlcFullscreen = ComicControls.checkBox("Full screen (ignore the chosen source bounds)");
    private final JSpinner spVlcLeft = ComicControls.spinner(new SpinnerNumberModel(0, 0, 20000, 1));
    private final JSpinner spVlcTop = ComicControls.spinner(new SpinnerNumberModel(0, 0, 20000, 1));
    private final JSpinner spVlcWidth = ComicControls.spinner(new SpinnerNumberModel(0, 0, 20000, 1));
    private final JSpinner spVlcHeight = ComicControls.spinner(new SpinnerNumberModel(0, 0, 20000, 1));

    // FFmpeg
    private final JComboBox<String> cbFfContainer = ComicControls.<String>comboBox(new String[] {"mp4", "matroska", "avi", "mov", "ts"});
    private final JTextField tfFfCodec = ComicControls.textField(12);
    private final JComboBox<String> cbFfPixFmt = ComicControls.<String>comboBox(new String[] {"yuv420p", "yuv422p", "yuv444p", "rgb24", "bgr24"});
    private final JComboBox<String> cbFfQuality = ComicControls.<String>comboBox(new String[] {"crf", "qscale", "bitrate"});
    private final JSpinner spFfCrf = ComicControls.spinner(new SpinnerNumberModel(23, 0, 51, 1));
    private final JSpinner spFfQscale = ComicControls.spinner(new SpinnerNumberModel(3, 1, 31, 1));
    private final JSpinner spFfBitrate = ComicControls.spinner(new SpinnerNumberModel(0, 0, 200000, 100));
    private final JTextField tfFfPreset = ComicControls.textField(10);
    private final JTextField tfFfTune = ComicControls.textField(10);
    private final JTextField tfFfProfile = ComicControls.textField(10);
    private final JTextField tfFfLevel = ComicControls.textField(10);
    private final JSpinner spFfThreads = ComicControls.spinner(new SpinnerNumberModel(0, 0, 128, 1));
    private final JTextArea taFfExtra = ComicControls.textArea(4, 26);

    public VideoSettingsDialog(Window owner, VideoSettingsStore store) {
        super(owner, "Video Settings", ModalityType.APPLICATION_MODAL);
        this.store = store;
        this.settings = store.load();
        buildUi();
        loadIntoFields();
        pack();
        setMinimumSize(new Dimension(560, 480));
        setLocationRelativeTo(owner);
    }

    /** True when the user pressed Save (the caller may re-read the store then). */
    public boolean isSaved() {
        return saved;
    }

    private void buildUi() {
        JPanel general = new JPanel();
        general.setLayout(new BoxLayout(general, BoxLayout.Y_AXIS));
        general.setBorder(ComicControls.titledBorder("General"));
        general.add(row("Default backend:", cbBackend));
        general.add(row("FPS:", spFps));
        general.add(row("Output folder:", withBrowse(tfOutputDir, false)));

        JTabbedPane tabs = ComicControls.tabbedPane();
        tabs.addTab("JCodec", buildJcodecTab());
        tabs.addTab("VLC", buildVlcTab());
        tabs.addTab("FFmpeg", buildFfmpegTab());

        JButton save = new ComicButton("Save");
        JButton cancel = new ComicButton("Cancel");
        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onSave();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(general, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
    }

    private JComponent buildJcodecTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel info = new JLabel("<html>JCodec — pure Java, no native setup, always available.<br>"
                + "It records H.264/MP4 using the general FPS and output folder above;<br>"
                + "it has no further knobs in AskAI (the reference's container/threads/audio<br>"
                + "belonged to machinery that was intentionally not ported).</html>");
        info.setFont(info.getFont().deriveFont(Font.ITALIC));
        p.add(info, BorderLayout.NORTH);
        return p;
    }

    private JComponent buildVlcTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel install = new JPanel();
        install.setLayout(new BoxLayout(install, BoxLayout.Y_AXIS));
        install.setBorder(ComicControls.titledBorder("Installation / paths"));
        install.add(leftAligned(cbVlcAutodetect));
        install.add(row("VLC base path:", withBrowse(tfVlcBasePath, false)));
        install.add(leftAligned(cbVlcLogEnabled));
        install.add(row("Log file:", withBrowse(tfVlcLogPath, true)));
        install.add(row("Verbose (0-2):", spVlcVerbose));
        p.add(install);

        JPanel transcode = new JPanel();
        transcode.setLayout(new BoxLayout(transcode, BoxLayout.Y_AXIS));
        transcode.setBorder(ComicControls.titledBorder("Transcode / output"));
        transcode.add(row("Mux:", cbVlcMux));
        transcode.add(row("vcodec:", tfVlcVcodec));
        transcode.add(row("Quality:", cbVlcQuality));
        transcode.add(row("CRF:", spVlcCrf));
        transcode.add(row("Bitrate (kbps):", spVlcBitrate));
        transcode.add(row("x264 preset:", tfVlcPreset));
        transcode.add(row("x264 tune:", tfVlcTune));
        transcode.add(leftAligned(cbVlcDeint));
        transcode.add(row("Deinterlace mode:", tfVlcDeintMode));
        transcode.add(row("Video filter:", tfVlcVideoFilter));
        transcode.add(row("sout extras:", tfVlcSoutExtras));
        p.add(transcode);

        JPanel source = new JPanel();
        source.setLayout(new BoxLayout(source, BoxLayout.Y_AXIS));
        source.setBorder(ComicControls.titledBorder("Source: screen:// region / audio"));
        source.add(leftAligned(cbVlcFullscreen));
        source.add(row("Left:", spVlcLeft));
        source.add(row("Top:", spVlcTop));
        source.add(row("Width (0 = from source):", spVlcWidth));
        source.add(row("Height (0 = from source):", spVlcHeight));
        source.add(leftAligned(cbVlcAudio));
        p.add(source);

        return new JScrollPane(p, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    }

    private JComponent buildFfmpegTab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel enc = new JPanel();
        enc.setLayout(new BoxLayout(enc, BoxLayout.Y_AXIS));
        enc.setBorder(ComicControls.titledBorder("Encoding / format"));
        enc.add(row("Container:", cbFfContainer));
        enc.add(row("Codec:", tfFfCodec));
        enc.add(row("Pixel format:", cbFfPixFmt));
        p.add(enc);

        JPanel quality = new JPanel();
        quality.setLayout(new BoxLayout(quality, BoxLayout.Y_AXIS));
        quality.setBorder(ComicControls.titledBorder("Quality"));
        quality.add(row("Mode:", cbFfQuality));
        quality.add(row("CRF (0-51):", spFfCrf));
        quality.add(row("QScale (1-31):", spFfQscale));
        quality.add(row("Bitrate (kbps):", spFfBitrate));
        p.add(quality);

        JPanel x264 = new JPanel();
        x264.setLayout(new BoxLayout(x264, BoxLayout.Y_AXIS));
        x264.setBorder(ComicControls.titledBorder("x264/x265 (optional)"));
        x264.add(row("preset:", tfFfPreset));
        x264.add(row("tune:", tfFfTune));
        x264.add(row("profile:", tfFfProfile));
        x264.add(row("level:", tfFfLevel));
        x264.add(row("Threads (0 = auto):", spFfThreads));
        p.add(x264);

        JPanel extra = new JPanel(new BorderLayout());
        extra.setBorder(ComicControls.titledBorder("Additional FFmpeg video options (key=value per line)"));
        extra.add(new JScrollPane(taFfExtra), BorderLayout.CENTER);
        p.add(extra);

        return new JScrollPane(p, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    }

    private void loadIntoFields() {
        VideoSettings.General g = settings.getGeneral();
        cbBackend.setSelectedItem(g.getDefaultBackend());
        spFps.setValue(g.getFps() > 0 ? g.getFps() : 15);
        tfOutputDir.setText(g.getOutputDirectory());

        VideoSettings.Vlc v = settings.getVlc();
        cbVlcAutodetect.setSelected(v.isAutodetect());
        tfVlcBasePath.setText(v.getBasePath());
        cbVlcLogEnabled.setSelected(v.isLogEnabled());
        tfVlcLogPath.setText(v.getLogPath());
        spVlcVerbose.setValue(Math.max(0, Math.min(2, v.getVerbose())));
        cbVlcMux.setSelectedItem(v.getMux());
        tfVlcVcodec.setText(v.getVideoCodec());
        cbVlcQuality.setSelectedItem(v.getQuality());
        spVlcCrf.setValue(v.getCrf());
        spVlcBitrate.setValue(v.getBitrateKbps());
        tfVlcPreset.setText(v.getVencPreset());
        tfVlcTune.setText(v.getVencTune());
        cbVlcDeint.setSelected(v.isDeinterlaceEnabled());
        tfVlcDeintMode.setText(v.getDeinterlaceMode());
        tfVlcVideoFilter.setText(v.getVideoFilter());
        tfVlcSoutExtras.setText(v.getSoutExtras());
        cbVlcAudio.setSelected(v.isAudioEnabled());
        cbVlcFullscreen.setSelected(v.isScreenFullscreen());
        spVlcLeft.setValue(v.getScreenLeft());
        spVlcTop.setValue(v.getScreenTop());
        spVlcWidth.setValue(v.getScreenWidth());
        spVlcHeight.setValue(v.getScreenHeight());

        VideoSettings.Ffmpeg f = settings.getFfmpeg();
        cbFfContainer.setSelectedItem(f.getContainer());
        tfFfCodec.setText(f.getCodecName());
        cbFfPixFmt.setSelectedItem(f.getPixelFormat());
        cbFfQuality.setSelectedItem(f.getQualityMode());
        spFfCrf.setValue(f.getCrf());
        spFfQscale.setValue(f.getQscale());
        spFfBitrate.setValue(f.getBitrateKbps());
        tfFfPreset.setText(f.getPreset());
        tfFfTune.setText(f.getTune());
        tfFfProfile.setText(f.getProfile());
        tfFfLevel.setText(f.getLevel());
        spFfThreads.setValue(f.getThreads());
        taFfExtra.setText(f.getExtraOptions());
    }

    private void onSave() {
        String dir = tfOutputDir.getText().trim();
        if (dir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please choose an output folder.",
                    "Video Settings", JOptionPane.WARNING_MESSAGE);
            return;
        }
        VideoSettings.General g = settings.getGeneral();
        g.setDefaultBackend(String.valueOf(cbBackend.getSelectedItem()));
        g.setFps(((Number) spFps.getValue()).intValue());
        g.setOutputDirectory(dir);

        VideoSettings.Vlc v = settings.getVlc();
        v.setAutodetect(cbVlcAutodetect.isSelected());
        v.setBasePath(tfVlcBasePath.getText().trim());
        v.setLogEnabled(cbVlcLogEnabled.isSelected());
        v.setLogPath(tfVlcLogPath.getText().trim());
        v.setVerbose(((Number) spVlcVerbose.getValue()).intValue());
        v.setMux(String.valueOf(cbVlcMux.getSelectedItem()));
        v.setVideoCodec(tfVlcVcodec.getText().trim());
        v.setQuality(String.valueOf(cbVlcQuality.getSelectedItem()));
        v.setCrf(((Number) spVlcCrf.getValue()).intValue());
        v.setBitrateKbps(((Number) spVlcBitrate.getValue()).intValue());
        v.setVencPreset(tfVlcPreset.getText().trim());
        v.setVencTune(tfVlcTune.getText().trim());
        v.setDeinterlaceEnabled(cbVlcDeint.isSelected());
        v.setDeinterlaceMode(tfVlcDeintMode.getText().trim());
        v.setVideoFilter(tfVlcVideoFilter.getText().trim());
        v.setSoutExtras(tfVlcSoutExtras.getText().trim());
        v.setAudioEnabled(cbVlcAudio.isSelected());
        v.setScreenFullscreen(cbVlcFullscreen.isSelected());
        v.setScreenLeft(((Number) spVlcLeft.getValue()).intValue());
        v.setScreenTop(((Number) spVlcTop.getValue()).intValue());
        v.setScreenWidth(((Number) spVlcWidth.getValue()).intValue());
        v.setScreenHeight(((Number) spVlcHeight.getValue()).intValue());

        VideoSettings.Ffmpeg f = settings.getFfmpeg();
        f.setContainer(String.valueOf(cbFfContainer.getSelectedItem()));
        f.setCodecName(tfFfCodec.getText().trim());
        f.setPixelFormat(String.valueOf(cbFfPixFmt.getSelectedItem()));
        f.setQualityMode(String.valueOf(cbFfQuality.getSelectedItem()));
        f.setCrf(((Number) spFfCrf.getValue()).intValue());
        f.setQscale(((Number) spFfQscale.getValue()).intValue());
        f.setBitrateKbps(((Number) spFfBitrate.getValue()).intValue());
        f.setPreset(tfFfPreset.getText().trim());
        f.setTune(tfFfTune.getText().trim());
        f.setProfile(tfFfProfile.getText().trim());
        f.setLevel(tfFfLevel.getText().trim());
        f.setThreads(((Number) spFfThreads.getValue()).intValue());
        f.setExtraOptions(taFfExtra.getText());

        try {
            store.save(settings);
        } catch (IOException failed) {
            JOptionPane.showMessageDialog(this, "Could not save the video settings: " + failed.getMessage(),
                    "Video Settings", JOptionPane.ERROR_MESSAGE);
            return;
        }
        saved = true;
        dispose();
    }

    // ------------------------------------------------------------------ small ui helpers

    private JPanel withBrowse(final JTextField field, final boolean file) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        JButton browse = new ComicButton("…");
        browse.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(file ? JFileChooser.FILES_ONLY : JFileChooser.DIRECTORIES_ONLY);
                String current = field.getText().trim();
                if (!current.isEmpty()) {
                    chooser.setSelectedFile(new File(current));
                }
                if (chooser.showOpenDialog(VideoSettingsDialog.this) == JFileChooser.APPROVE_OPTION) {
                    field.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            }
        });
        p.add(field, BorderLayout.CENTER);
        p.add(browse, BorderLayout.EAST);
        return p;
    }

    private static JPanel leftAligned(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        p.add(c, BorderLayout.WEST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return p;
    }

    private static JPanel row(String label, Component field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(170, 24));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return p;
    }
}
