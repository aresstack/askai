package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileAudioSink;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plays preview audio through an external VLC process (no VLC binaries or libVLC are shipped or linked).
 * The PCM is written to a temporary WAV with the existing {@link WavFileAudioSink}, then a user-provided
 * {@code vlc.exe} plays it headless through its Windows MMDevice/WASAPI output. There is no silent fallback
 * to another device: if VLC is not located, playback fails with a clear message. Slice-1 scope is the
 * system-default endpoint only (no VLC per-endpoint enumeration), so the UI must not claim a specific device.
 */
public final class VlcSidecarPlaybackService implements AudioPreviewPlaybackService {

    private final VlcInstallation installation;
    private final VlcProcessLauncher launcher;
    private final File tempDir;

    private Thread thread;
    private long generation;
    private volatile VlcProcessLauncher.Handle activeHandle;
    private volatile File activeWav;
    private volatile File activeConfig;
    private volatile AudioOutputDevice device = AudioOutputDevice.vlcSystemDefault();
    private volatile Consumer<String> errorHandler;
    private volatile Consumer<String> infoHandler;

    public VlcSidecarPlaybackService() {
        this(new VlcInstallation(), VlcProcessLauncher.processBuilder(),
                new File(System.getProperty("java.io.tmpdir"), "askai-vlc-playback"));
    }

    VlcSidecarPlaybackService(VlcInstallation installation, VlcProcessLauncher launcher, File tempDir) {
        this.installation = installation;
        this.launcher = launcher;
        this.tempDir = tempDir;
    }

    public void setErrorHandler(Consumer<String> handler) {
        this.errorHandler = handler;
    }

    public void setInfoHandler(Consumer<String> handler) {
        this.infoHandler = handler;
    }

    public void setOutputDevice(AudioOutputDevice device) {
        this.device = device;
    }

    public synchronized void play(short[] samples, PcmAudioFormat format, final Runnable onFinished) {
        if (samples == null || format == null) {
            throw new IllegalArgumentException("Samples and format must not be null.");
        }
        stop();
        File vlc = installation.resolve();
        if (vlc == null) {
            reportError("VLC is not installed or its location is not set. "
                    + "Use \"Locate VLC…\" to point AskAI at vlc.exe. No other device was used.");
            return;
        }
        final long playbackGeneration = ++generation;
        final AudioOutputDevice target = device;
        File wav;
        File config;
        try {
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
                throw new IOException("Cannot create the temp directory " + tempDir);
            }
            wav = writeTempWav(samples, format);
            config = new File(tempDir, "vlc-" + System.nanoTime() + ".cfg");
        } catch (Exception ex) {
            reportError("Could not prepare the audio for VLC: " + message(ex));
            return;
        }
        final File wavFile = wav;
        final File configFile = config;
        activeWav = wav;
        activeConfig = config;
        Thread playbackThread = new Thread(new Runnable() {
            public void run() {
                runVlc(playbackGeneration, vlc, wavFile, configFile, target, onFinished);
            }
        }, "askai-vlc-playback");
        playbackThread.setDaemon(true);
        thread = playbackThread;
        playbackThread.start();
    }

    private void runVlc(long playbackGeneration, File vlc, File wav, File config,
                        AudioOutputDevice target, Runnable onFinished) {
        List<String> command = buildCommand(vlc, wav, config, target);
        VlcProcessLauncher.Handle handle = null;
        try {
            handle = launcher.start(command);
            activeHandle = handle;
            String output = drain(handle.mergedOutput());
            int exit = handle.awaitExit();
            if (playbackGeneration != generation) {
                return; // superseded or stopped
            }
            if (exit == 0) {
                reportInfo("Played via VLC sidecar (" + target.getDisplayName() + ").");
                if (onFinished != null) {
                    onFinished.run();
                }
            } else {
                reportError("VLC exited with code " + exit
                        + (output.length() == 0 ? "." : ": " + output));
            }
        } catch (Exception ex) {
            if (playbackGeneration == generation) {
                reportError("Could not run VLC: " + message(ex));
            }
        } finally {
            if (activeHandle == handle) {
                activeHandle = null;
            }
            deleteQuietly(wav);
            deleteQuietly(config);
            clearThread(playbackGeneration);
        }
    }

    /** Package-private so the exact command line can be asserted in tests. */
    static List<String> buildCommand(File vlc, File wav, File config, AudioOutputDevice device) {
        List<String> command = new ArrayList<String>();
        command.add(vlc.getAbsolutePath());
        command.add("--intf=dummy");
        command.add("--dummy-quiet");
        command.add("--no-video");
        command.add("--play-and-exit");
        command.add("--no-one-instance");   // never hand the file to an already-running VLC
        command.add("--no-media-library");
        command.add("--aout=mmdevice");     // Windows WASAPI output
        command.add("--config=" + config.getAbsolutePath()); // isolated config: leave user settings untouched
        // Slice-1: system default endpoint only. A specific endpoint would add
        // "--mmdevice-audio-device=<id>" using VLC's own enumeration (never a Java Sound name).
        if (device != null && device.getVlcDeviceId() != null && device.getVlcDeviceId().length() > 0) {
            command.add("--mmdevice-audio-device=" + device.getVlcDeviceId());
        }
        command.add(wav.getAbsolutePath());
        return command;
    }

    public synchronized void stop() {
        ++generation;
        VlcProcessLauncher.Handle handle = activeHandle;
        activeHandle = null;
        if (handle != null) {
            try {
                handle.destroy();
            } catch (Exception ignored) {
                // best effort
            }
        }
        Thread current = thread;
        thread = null;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
        }
        deleteQuietly(activeWav);
        deleteQuietly(activeConfig);
        activeWav = null;
        activeConfig = null;
    }

    public synchronized boolean isPlaying() {
        return thread != null && thread.isAlive();
    }

    private File writeTempWav(short[] samples, PcmAudioFormat format) throws IOException {
        File wav = new File(tempDir, "vlc-preview-" + System.nanoTime() + ".wav");
        WavFileAudioSink sink = new WavFileAudioSink(wav);
        sink.open(format);
        try {
            sink.write(samples, samples.length);
        } finally {
            sink.close();
        }
        return wav;
    }

    private static String drain(InputStream in) {
        StringBuilder text = new StringBuilder();
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (text.length() < 4096) {
                    text.append(new String(buffer, 0, read, "UTF-8"));
                }
            }
        } catch (Exception ignored) {
            // output is diagnostic only
        }
        String trimmed = text.toString().trim();
        return trimmed.length() > 2000 ? trimmed.substring(trimmed.length() - 2000) : trimmed;
    }

    private synchronized void clearThread(long playbackGeneration) {
        if (playbackGeneration == generation && Thread.currentThread() == thread) {
            thread = null;
        }
    }

    private void reportInfo(String message) {
        Consumer<String> handler = infoHandler;
        if (handler != null) {
            handler.accept(message);
        }
    }

    private void reportError(String message) {
        Consumer<String> handler = errorHandler;
        if (handler != null) {
            handler.accept(message);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.toString() : ex.getMessage();
    }
}
