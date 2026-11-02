package com.aresstack.askai.notify;

import com.aresstack.comiccontrols.theme.ThemeColor;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Desktop notifications for incoming chat messages: an optional text notification (via the system
 * tray) and an optional sound played on a chosen output device.  Each channel is independently
 * switchable, and a runtime mute silences everything without changing the channel settings.
 *
 * <p>Thread-safe.  Sound playback and tray messages run on a daemon background thread so the EDT is
 * never blocked.  Degrades gracefully when the system tray or audio is unavailable.</p>
 */
public final class DesktopNotifier {

    /** Display name for "let Java Sound pick the default output". */
    public static final String SYSTEM_DEFAULT_DEVICE = "System default";

    /** Selectable notification sound types. */
    public static final String SOUND_CLICK = "click";
    public static final String SOUND_CHIME = "chime";
    public static final String SOUND_BEEP = "beep";
    public static final String SOUND_POP = "pop";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-notify");
            thread.setDaemon(true);
            return thread;
        }
    });

    private volatile boolean textEnabled;
    private volatile boolean soundEnabled;
    private volatile boolean muted;
    private volatile String deviceName = SYSTEM_DEFAULT_DEVICE;
    private volatile String soundType = SOUND_CLICK;
    private volatile int volumePercent = 70;

    private TrayIcon trayIcon; // lazily added while text notifications are on
    // Coalesce sound bursts (e.g. a history sync delivering many missed messages at once).
    private volatile long lastSoundAtMillis;
    private static final long SOUND_COALESCE_MILLIS = 1200;

    /**
     * Update the channels, the output device, the sound type and the volume (0–100).
     */
    public synchronized void configure(boolean textEnabled, boolean soundEnabled, String deviceName,
                                       String soundType, int volumePercent) {
        this.textEnabled = textEnabled;
        this.soundEnabled = soundEnabled;
        this.deviceName = deviceName == null || deviceName.trim().isEmpty()
                ? SYSTEM_DEFAULT_DEVICE : deviceName.trim();
        this.soundType = soundType == null || soundType.trim().isEmpty() ? SOUND_CLICK : soundType.trim();
        this.volumePercent = Math.max(0, Math.min(100, volumePercent));
        if (!textEnabled) {
            removeTrayIcon();
        }
    }

    /** Mute (or unmute) all configured notifications without changing the channel settings. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    /** @return {@code true} when at least one channel is enabled. */
    public boolean isAnyChannelEnabled() {
        return textEnabled || soundEnabled;
    }

    /**
     * Fire a notification for {@code title}/{@code body} on every enabled channel, unless muted.
     * Safe to call from any thread.
     */
    public void notifyMessage(final String title, final String body) {
        if (muted) {
            return;
        }
        final boolean text = textEnabled;
        final boolean sound = soundEnabled;
        if (!text && !sound) {
            return;
        }
        executor.execute(new Runnable() {
            public void run() {
                if (text) {
                    showTrayMessage(title, body);
                }
                if (sound) {
                    long now = System.currentTimeMillis();
                    if (now - lastSoundAtMillis >= SOUND_COALESCE_MILLIS) {
                        lastSoundAtMillis = now;
                        playSound(deviceName, soundType, volumePercent);
                    }
                }
            }
        });
    }

    /** Release the tray icon and stop the background thread (call on shutdown). */
    public synchronized void dispose() {
        removeTrayIcon();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ output devices

    /** Output device display names for the settings combo: system default first, then mixers. */
    public static List<String> outputDeviceNames() {
        List<String> names = new ArrayList<String>();
        names.add(SYSTEM_DEFAULT_DEVICE);
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, toneFormat());
        try {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                String name = info.getName();
                if (name == null || name.trim().isEmpty() || names.contains(name)) {
                    continue;
                }
                try {
                    if (AudioSystem.getMixer(info).isLineSupported(lineInfo)) {
                        names.add(name);
                    }
                } catch (Exception ignored) {
                    // Skip mixers that cannot be queried.
                }
            }
        } catch (Exception ignored) {
            // No enumerable audio system — the system default entry is enough.
        }
        return names;
    }

    // ------------------------------------------------------------------ internals

    private synchronized void showTrayMessage(String title, String body) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            if (trayIcon == null) {
                trayIcon = new TrayIcon(trayImage(), "AskAI");
                trayIcon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(trayIcon);
            }
            trayIcon.displayMessage(
                    title == null ? "AskAI" : title,
                    body == null ? "" : body,
                    TrayIcon.MessageType.INFO);
        } catch (Exception ignored) {
            // Tray unavailable (e.g. full, or headless) — sound still plays if enabled.
        }
    }

    private synchronized void removeTrayIcon() {
        if (trayIcon != null && SystemTray.isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception ignored) {
            }
        }
        trayIcon = null;
    }

    /**
     * The AskAI app icon at the tray's own size (the same artwork as the windows); the drawn blue
     * badge remains only as the fallback when the icon resource is missing.
     */
    private static java.awt.Image trayImage() {
        java.awt.Image appIcon = com.aresstack.askai.ui.AppIcon.sized(trayIconSize());
        return appIcon != null ? appIcon : fallbackTrayImage();
    }

    private static int trayIconSize() {
        try {
            java.awt.Dimension size = SystemTray.getSystemTray().getTrayIconSize();
            return Math.max(size.width, size.height);
        } catch (Exception unsupported) {
            return 16;
        }
    }

    private static BufferedImage fallbackTrayImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(ThemeColor.of("research.ACCENT_BLUE"));
            g.fillRoundRect(1, 1, 14, 14, 6, 6);
            g.setColor(ThemeColor.of("comic.buttonPlate"));
            g.fillOval(5, 5, 6, 6);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static AudioFormat toneFormat() {
        return new AudioFormat(44100f, 16, 1, true, false);
    }

    /** Play a one-shot notification sound of the given type/volume on the selected device. */
    private static void playSound(String deviceName, String soundType, int volumePercent) {
        AudioFormat format = toneFormat();
        byte[] pcm = buildSound(format, soundType, volumePercent / 100.0);
        SourceDataLine line = null;
        try {
            line = openLine(format, deviceName);
            line.open(format);
            line.start();
            line.write(pcm, 0, pcm.length);
            line.drain();
        } catch (Exception ignored) {
            // Device busy/unavailable — a missed chime must never disrupt the chat.
        } finally {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static SourceDataLine openLine(AudioFormat format, String deviceName) throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (deviceName != null && !SYSTEM_DEFAULT_DEVICE.equals(deviceName)) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (deviceName.equals(mixerInfo.getName())) {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) {
                        return (SourceDataLine) mixer.getLine(info);
                    }
                }
            }
        }
        return (SourceDataLine) AudioSystem.getLine(info);
    }

    /** Build the PCM for a notification sound; {@code gain} is the 0–1 volume scale. */
    static byte[] buildSound(AudioFormat format, String soundType, double gain) {
        int rate = (int) format.getSampleRate();
        double amp = 0.9 * Math.max(0.0, Math.min(1.0, gain));
        if (SOUND_CHIME.equals(soundType)) {
            return tones(rate, amp, new double[] {880.0, 1174.66}, new int[] {115, 115});
        }
        if (SOUND_BEEP.equals(soundType)) {
            return tones(rate, amp, new double[] {880.0}, new int[] {150});
        }
        if (SOUND_POP.equals(soundType)) {
            return decayTone(rate, amp, 660.0, 70);
        }
        // Default: a short, dry click.
        return decayTone(rate, amp, 1600.0, 22);
    }

    /** Concatenated fixed-amplitude tones with 8 ms fades to avoid edge clicks. */
    private static byte[] tones(int rate, double amp, double[] freqs, int[] millis) {
        int total = 0;
        for (int ms : millis) {
            total += rate * ms / 1000;
        }
        byte[] data = new byte[total * 2];
        int index = 0;
        for (int note = 0; note < freqs.length; note++) {
            int samples = rate * millis[note] / 1000;
            for (int i = 0; i < samples; i++) {
                double fade = Math.min(1.0, Math.min(i, samples - i) / (rate * 0.008));
                index = writeSample(data, index,
                        Math.sin(2.0 * Math.PI * freqs[note] * i / rate) * amp * 0.4 * fade);
            }
        }
        return data;
    }

    /** A single tone with an exponential decay envelope — a soft click/pop. */
    private static byte[] decayTone(int rate, double amp, double freq, int millis) {
        int samples = rate * millis / 1000;
        byte[] data = new byte[samples * 2];
        int index = 0;
        for (int i = 0; i < samples; i++) {
            double env = Math.exp(-5.0 * i / samples);
            index = writeSample(data, index, Math.sin(2.0 * Math.PI * freq * i / rate) * amp * env);
        }
        return data;
    }

    private static int writeSample(byte[] data, int index, double sample) {
        short value = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample * Short.MAX_VALUE));
        data[index++] = (byte) (value & 0xFF);
        data[index++] = (byte) ((value >> 8) & 0xFF);
        return index;
    }
}
