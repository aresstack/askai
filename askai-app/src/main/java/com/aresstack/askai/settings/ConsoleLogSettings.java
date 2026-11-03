package com.aresstack.askai.settings;

import com.aresstack.askai.plugin.api.service.HostLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Stores which console log categories are switched off ({@code logging.properties} in the data folder) and
 * applies them to {@link HostLog}. Without a stored choice only the long data-folder migration list is off.
 */
public final class ConsoleLogSettings {

    static final String FILE_NAME = "logging.properties";
    static final String DISABLED_KEY = "disabled";
    static final List<String> DEFAULT_DISABLED = Arrays.asList("data-migration");

    private ConsoleLogSettings() {
    }

    /** Loads the stored choice into {@link HostLog}; never throws. */
    public static void apply() {
        HostLog.setDisabled(load(file()));
    }

    /** Persists the current {@link HostLog} state; never throws. */
    public static void save() {
        save(file(), HostLog.disabledCategories());
    }

    static List<String> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return DEFAULT_DISABLED;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException | RuntimeException unreadable) {
            return DEFAULT_DISABLED;
        }
        List<String> disabled = new ArrayList<String>();
        for (String part : properties.getProperty(DISABLED_KEY, "").split(",")) {
            if (!part.trim().isEmpty()) {
                disabled.add(part.trim());
            }
        }
        return disabled;
    }

    static void save(Path file, List<String> disabled) {
        Properties properties = new Properties();
        StringBuilder value = new StringBuilder();
        for (String category : disabled) {
            value.append(value.length() == 0 ? "" : ",").append(category);
        }
        properties.setProperty(DISABLED_KEY, value.toString());
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "AskAI console log categories switched off");
            }
        } catch (IOException | RuntimeException failed) {
            System.err.println("[askai] could not save " + file + ": " + failed);
        }
    }

    private static Path file() {
        return AskAiPaths.appDirectory().resolve(FILE_NAME);
    }
}
