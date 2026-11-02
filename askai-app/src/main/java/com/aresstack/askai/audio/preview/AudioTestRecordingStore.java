package com.aresstack.askai.audio.preview;

import com.aresstack.askai.settings.AskAiPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Persist confirmed microphone test recordings under the AskAI config directory ({@code audio-tests/}).
 * The raw recording is moved in unchanged (no DSP normalization); it stays after the app closes and is a
 * stable, reusable test source. A processed preview is written elsewhere and never overwrites a raw file.
 */
public final class AudioTestRecordingStore {

    private final File directory;

    public AudioTestRecordingStore() {
        this(AskAiPaths.appDirectory().resolve("audio-tests").toFile());
    }

    public AudioTestRecordingStore(File directory) {
        this.directory = directory;
    }

    public File getDirectory() {
        return directory;
    }

    /**
     * Move a confirmed raw recording into the store under a collision-free name derived from
     * {@code desiredName}. The source temp file is consumed (moved).
     */
    public File saveConfirmed(File rawTemp, String desiredName) throws IOException {
        if (rawTemp == null || !rawTemp.isFile()) {
            throw new IOException("The raw recording file does not exist.");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create the audio-tests directory: " + directory);
        }
        File target = resolveCollisionFree(sanitize(desiredName));
        try {
            Files.move(rawTemp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(rawTemp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private File resolveCollisionFree(String baseName) {
        File candidate = new File(directory, baseName + ".wav");
        int suffix = 1;
        while (candidate.exists()) {
            candidate = new File(directory, baseName + "-" + suffix + ".wav");
            suffix++;
        }
        return candidate;
    }

    private static String sanitize(String desiredName) {
        String name = desiredName == null ? "" : desiredName.trim();
        if (name.toLowerCase().endsWith(".wav")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isEmpty() ? "dsp-test-recording" : name;
    }
}
