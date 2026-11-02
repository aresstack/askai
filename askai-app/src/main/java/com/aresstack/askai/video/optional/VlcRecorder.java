package com.aresstack.askai.video.optional;

import com.aresstack.askai.video.MediaRecorder;
import com.aresstack.askai.video.RecordingException;
import com.aresstack.askai.video.RecordingProfile;
import com.aresstack.askai.video.VideoSettings;

import uk.co.caprica.vlcj.player.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.headless.HeadlessMediaPlayer;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The productive VLC backend (ported from the WD4J/corenth {@code LibVlcRecorder}, vlcj 3.x): a headless
 * media player on the user's INSTALLED VLC captures {@code screen://} and transcodes through a
 * {@code :sout} pipeline. Like the reference it is fully settings-driven ({@link VideoSettings.Vlc}):
 * verbosity/logging, mux, codec, CRF-vs-bitrate quality, x264 preset/tune, deinterlace, video filter,
 * sout extras, audio capture and screen-region overrides. AskAI starts no external process — libvlc runs
 * in-process via vlcj/JNA; {@link LibVlcLocator#configureRuntime} must have succeeded first.
 */
public final class VlcRecorder implements MediaRecorder {

    private final VideoSettings.Vlc settings;
    private MediaPlayerFactory factory;
    private HeadlessMediaPlayer player;
    private volatile boolean recording;

    public VlcRecorder(VideoSettings.Vlc settings) {
        this.settings = settings != null ? settings : new VideoSettings.Vlc();
    }

    @Override
    public void start(RecordingProfile profile) throws Exception {
        if (recording) {
            return;
        }
        if (profile.getOutputFile().getParent() != null) {
            Files.createDirectories(profile.getOutputFile().getParent());
        }
        this.factory = new MediaPlayerFactory(buildLibVlcArgs(settings));
        this.player = factory.newHeadlessMediaPlayer();
        boolean ok;
        try {
            ok = player.playMedia("screen://", buildOptions(profile, settings));
        } catch (Throwable t) {
            safeRelease();
            throw new RecordingException("VLC capture failed to start: " + t.getMessage());
        }
        if (!ok) {
            safeRelease();
            throw new RecordingException(
                    "VLC refused to start the screen capture (playMedia returned false).");
        }
        recording = true;
    }

    @Override
    public void stop() {
        if (!recording) {
            return;
        }
        try {
            player.stop(); // finalizes the sout muxer so the file is playable
        } finally {
            safeRelease();
            recording = false;
        }
    }

    @Override
    public boolean isRecording() {
        return recording;
    }

    private void safeRelease() {
        try {
            if (player != null) {
                player.release();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (factory != null) {
                factory.release();
            }
        } catch (Throwable ignore) {
        }
        player = null;
        factory = null;
    }

    /** libvlc process args: interface off, verbosity per settings, optional file logging (reference). */
    static String[] buildLibVlcArgs(VideoSettings.Vlc s) {
        List<String> args = new ArrayList<String>();
        args.add("--intf");
        args.add("dummy");
        args.add("--no-video-title-show");
        args.add("--no-plugins-cache");
        int verbose = Math.max(0, Math.min(2, s.getVerbose()));
        if (verbose == 0) {
            args.add("--quiet");
        } else {
            args.add("--verbose=" + verbose);
        }
        if (s.isLogEnabled()) {
            args.add("--file-logging");
            if (notEmpty(s.getLogPath())) {
                args.add("--logfile=" + s.getLogPath().trim().replace('\\', '/'));
            }
        }
        return args.toArray(new String[0]);
    }

    /** Screen-module options for the region plus the transcode/mux pipeline — the reference semantics. */
    static String[] buildOptions(RecordingProfile profile, VideoSettings.Vlc s) {
        Rectangle bounds = profile.getSource().getBounds();
        int fps = Math.max(1, profile.getFps());

        int left = bounds.x;
        int top = bounds.y;
        int width = bounds.width;
        int height = bounds.height;
        if (s.isScreenFullscreen()) {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            left = 0;
            top = 0;
            width = (int) screen.getWidth();
            height = (int) screen.getHeight();
        } else {
            // Explicit region overrides from the settings win over the profile bounds (reference).
            if (s.getScreenWidth() > 0) {
                width = s.getScreenWidth();
            }
            if (s.getScreenHeight() > 0) {
                height = s.getScreenHeight();
            }
            if (s.getScreenLeft() > 0 || s.getScreenTop() > 0) {
                left = s.getScreenLeft();
                top = s.getScreenTop();
            }
        }
        // x264 needs even dimensions, exactly like the JCodec path.
        width = width - (width % 2);
        height = height - (height % 2);

        List<String> opts = new ArrayList<String>();
        opts.add(":screen-fps=" + fps);
        opts.add(":screen-left=" + Math.max(0, left));
        opts.add(":screen-top=" + Math.max(0, top));
        opts.add(":screen-width=" + width);
        opts.add(":screen-height=" + height);
        if (notEmpty(s.getVideoFilter())) {
            opts.add(":video-filter=" + s.getVideoFilter().trim());
        }
        if (s.isDeinterlaceEnabled()) {
            opts.add(":deinterlace=1");
            if (notEmpty(s.getDeinterlaceMode())) {
                opts.add(":deinterlace-mode=" + s.getDeinterlaceMode().trim());
            }
        }

        String vcodec = notEmpty(s.getVideoCodec()) ? s.getVideoCodec().trim() : "h264";
        String mux = notEmpty(s.getMux()) ? s.getMux().trim() : "mp4";
        StringBuilder sout = new StringBuilder();
        sout.append(":sout=#transcode{vcodec=").append(vcodec)
                .append(",fps=").append(fps)
                .append(",width=").append(width).append(",height=").append(height);
        if ("bitrate".equalsIgnoreCase(s.getQuality())) {
            if (s.getBitrateKbps() > 0) {
                sout.append(",vb=").append(s.getBitrateKbps()).append("k");
            }
        } else { // crf (default)
            sout.append(",venc=x264{crf=").append(Math.max(0, Math.min(51, s.getCrf())));
            if (notEmpty(s.getVencPreset())) {
                sout.append(",preset=").append(s.getVencPreset().trim());
            }
            if (notEmpty(s.getVencTune())) {
                sout.append(",tune=").append(s.getVencTune().trim());
            }
            sout.append("}");
        }
        if (s.isAudioEnabled()) {
            sout.append(",acodec=mp3,ab=128,channels=2,samplerate=44100");
        } else {
            sout.append(",acodec=none");
        }
        sout.append("}");
        if (notEmpty(s.getSoutExtras())) {
            String extras = s.getSoutExtras().trim();
            if (!extras.startsWith(",")) {
                sout.append(",");
            }
            sout.append(extras);
        }
        String dst = profile.getOutputFile().toString().replace('\\', '/');
        sout.append(":std{access=file,mux=").append(mux).append(",dst=").append(dst).append("}");
        opts.add(sout.toString());
        opts.add(":sout-keep");
        return opts.toArray(new String[0]);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
