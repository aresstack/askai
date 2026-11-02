package com.aresstack.askai.settings;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralizes user-specific storage locations: {@code %APPDATA%\.askai} (user home without APPDATA). The
 * former {@code .askai-java8} directory is merged into it once at startup ({@link AppDirectoryMigration}).
 */
public final class AskAiPaths {

    private static final String APP_DIR_NAME = ".askai";
    /** The directory name before askai #51. */
    static final String LEGACY_APP_DIR_NAME = ".askai-java8";

    private AskAiPaths() {
    }

    public static Path appDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, APP_DIR_NAME);
    }

    /** The former app directories (APPDATA and user home), in that order; they may not exist. */
    static java.util.List<Path> legacyAppDirectories() {
        java.util.List<Path> dirs = new java.util.ArrayList<Path>();
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.trim().isEmpty()) {
            dirs.add(Paths.get(appData, LEGACY_APP_DIR_NAME));
        }
        Path home = Paths.get(System.getProperty("user.home", "."), LEGACY_APP_DIR_NAME);
        if (!dirs.contains(home)) {
            dirs.add(home);
        }
        return dirs;
    }

    public static Path settingsFile() {
        return appDirectory().resolve("askai.properties");
    }

    public static Path defaultModelRoot() {
        return appDirectory().resolve("models");
    }
}
