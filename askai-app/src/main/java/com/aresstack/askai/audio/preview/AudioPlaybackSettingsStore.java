package com.aresstack.askai.audio.preview;

import com.aresstack.askai.settings.AskAiPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * The persisted PLAYBACK OUTPUT selection (backend + device display name) shared by every audio
 * preview surface: the chat settings' "Playback output" row, the recording preview behind the
 * Record button, and the DSP editor's test panel. Historically the selection lived only in the
 * buried DSP test panel and was NOT persisted at all — it silently reset to the list head on
 * every open, which is how the VLC backend "disappeared" for the user. File:
 * {@code %APPDATA%\.askai\askai-audio.properties}.
 */
public final class AudioPlaybackSettingsStore {

    private static final String KEY_BACKEND = "playback.backend";
    private static final String KEY_DEVICE = "playback.device";

    private final Path file;

    public AudioPlaybackSettingsStore() {
        this(AskAiPaths.appDirectory().resolve("askai-audio.properties"));
    }

    /** Visible for tests: an explicit file location. */
    public AudioPlaybackSettingsStore(Path file) {
        this.file = file;
    }

    /** Persist the chosen device; null clears back to "first available". */
    public void persistSelection(AudioOutputDevice device) throws IOException {
        Properties properties = new Properties();
        if (device != null) {
            properties.setProperty(KEY_BACKEND, device.getBackend().name());
            properties.setProperty(KEY_DEVICE, device.getDisplayName());
        }
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "AskAI audio preview playback selection");
        }
    }

    /**
     * @return the persisted device resolved against the CURRENT catalog (backend + display name
     *         must both match — devices come and go), the list head as fallback, or null for an
     *         empty catalog
     */
    public AudioOutputDevice resolve(List<AudioOutputDevice> available) {
        if (available == null || available.isEmpty()) {
            return null;
        }
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException unreadable) {
                return available.get(0);
            }
        }
        String backend = properties.getProperty(KEY_BACKEND, "");
        String name = properties.getProperty(KEY_DEVICE, "");
        for (AudioOutputDevice device : available) {
            if (device.getBackend().name().equals(backend)
                    && device.getDisplayName().equals(name)) {
                return device;
            }
        }
        return available.get(0);
    }
}
