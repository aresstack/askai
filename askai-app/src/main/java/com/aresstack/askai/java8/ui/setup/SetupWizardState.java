package com.aresstack.askai.java8.ui.setup;

import com.aresstack.askai.java8.settings.AskAiPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Whether the setup wizard opens on its own at startup. It lives in its own small file next to AskAI's settings
 * ({@code setup-wizard.properties}), because the settings file is rewritten as a whole on every save.
 *
 * <ul>
 *   <li>A fresh installation (neither the settings file nor this file exists) starts the wizard and remembers
 *       {@code pending}.</li>
 *   <li>{@code pending} (the wizard was cancelled or AskAI closed during the setup) starts it again, like the
 *       Enterprise AI Client's interrupted setup.</li>
 *   <li>{@code completed} never starts it again; the settings still open it any time.</li>
 *   <li>An existing installation without this file (settings already there) is left alone: it was set up before
 *       the wizard existed.</li>
 * </ul>
 */
public final class SetupWizardState {

    static final String FILE_NAME = "setup-wizard.properties";
    static final String KEY_STATE = "state";
    static final String KEY_COMPLETED_AT = "completedAt";
    static final String PENDING = "pending";
    static final String COMPLETED = "completed";

    private final Path stateFile;
    private final Path settingsFile;

    public SetupWizardState(Path stateFile, Path settingsFile) {
        if (stateFile == null || settingsFile == null) {
            throw new IllegalArgumentException("stateFile and settingsFile must not be null");
        }
        this.stateFile = stateFile;
        this.settingsFile = settingsFile;
    }

    /** AskAI's own files in its data folder. */
    public static SetupWizardState forAskAi() {
        return new SetupWizardState(AskAiPaths.appDirectory().resolve(FILE_NAME), AskAiPaths.settingsFile());
    }

    /**
     * Decides at startup whether the wizard opens on its own; a fresh installation is remembered as
     * {@code pending} right away, so the wizard also returns when AskAI saves settings before the setup ends.
     */
    public boolean shouldOpenAtStartup() {
        String state = read();
        if (COMPLETED.equals(state)) {
            return false;
        }
        if (PENDING.equals(state)) {
            return true;
        }
        if (Files.exists(settingsFile)) {
            return false; // set up before the wizard existed
        }
        write(PENDING);
        return true;
    }

    /** Remembers the finished setup: the wizard no longer opens on its own. */
    public void markCompleted() {
        write(COMPLETED);
    }

    boolean isCompleted() {
        return COMPLETED.equals(read());
    }

    private String read() {
        if (!Files.isRegularFile(stateFile)) {
            return "";
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            properties.load(in);
        } catch (IOException e) {
            return "";
        }
        return properties.getProperty(KEY_STATE, "").trim();
    }

    private void write(String state) {
        Properties properties = new Properties();
        properties.setProperty(KEY_STATE, state);
        if (COMPLETED.equals(state)) {
            properties.setProperty(KEY_COMPLETED_AT, java.time.Instant.now().toString());
        }
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(stateFile)) {
                properties.store(out, "AskAI setup wizard");
            }
        } catch (IOException e) {
            // Not fatal: without the file the wizard simply decides again at the next start.
        }
    }
}
