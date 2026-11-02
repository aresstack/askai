package com.aresstack.askai.audio.preview;

import java.io.File;
import java.util.prefs.Preferences;

/**
 * Locates a user-provided VLC installation. VLC is never downloaded or bundled — the user points AskAI at
 * an existing {@code vlc.exe}. Resolution order: an explicitly configured path (persisted), then the common
 * Windows install locations, then any {@code vlc(.exe)} on {@code PATH}.
 */
public class VlcInstallation {

    private static final String PREF_NODE = "com/aresstack/askai/audio/vlc";
    private static final String KEY_PATH = "vlcExecutablePath";

    private final Preferences prefs;

    public VlcInstallation() {
        this(Preferences.userRoot().node(PREF_NODE));
    }

    VlcInstallation(Preferences prefs) {
        this.prefs = prefs;
    }

    /** @return the resolved VLC executable, or null if none can be found. */
    public File resolve() {
        String configured = prefs.get(KEY_PATH, null);
        if (configured != null && configured.trim().length() > 0) {
            File file = new File(configured.trim());
            if (file.isFile()) {
                return file;
            }
        }
        for (File candidate : commonLocations()) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return findOnPath();
    }

    public boolean isAvailable() {
        return resolve() != null;
    }

    /** @return the persisted VLC executable path, or "" when none is configured (automatic detection). */
    public String getConfiguredPath() {
        String configured = prefs.get(KEY_PATH, null);
        return configured == null ? "" : configured.trim();
    }

    /** Persist a user-chosen executable so future launches reuse it. */
    public void setExecutable(File executable) {
        if (executable == null) {
            prefs.remove(KEY_PATH);
        } else {
            prefs.put(KEY_PATH, executable.getAbsolutePath());
        }
    }

    /** Remove the manually configured path so resolution falls back to automatic detection. */
    public void clearExecutable() {
        prefs.remove(KEY_PATH);
    }

    /**
     * Map a chosen file to the real {@code vlc.exe} to persist. A {@code VLCPortable.exe} resolves to the
     * bundled {@code App\vlc\vlc.exe} next to it (the actual engine); a {@code vlc.exe} is taken as is.
     *
     * @return the real executable to store, or {@code null} when the choice is not a usable VLC executable.
     */
    public static File resolveChosenExecutable(File chosen) {
        if (chosen == null || !chosen.isFile()) {
            return null;
        }
        String name = chosen.getName();
        if (name.equalsIgnoreCase("VLCPortable.exe")) {
            File engine = new File(new File(new File(chosen.getParentFile(), "App"), "vlc"), "vlc.exe");
            return engine.isFile() ? engine : null;
        }
        if (name.equalsIgnoreCase("vlc.exe")) {
            return chosen;
        }
        return null;
    }

    private static File[] commonLocations() {
        String programFiles = envDir("ProgramFiles", "C:\\Program Files");
        String programFilesX86 = envDir("ProgramFiles(x86)", "C:\\Program Files (x86)");
        return new File[]{
                new File(programFiles, "VideoLAN\\VLC\\vlc.exe"),
                new File(programFilesX86, "VideoLAN\\VLC\\vlc.exe")
        };
    }

    private static File findOnPath() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] names = windows ? new String[]{"vlc.exe", "vlc"} : new String[]{"vlc"};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.trim().length() == 0) {
                continue;
            }
            for (String name : names) {
                File candidate = new File(dir.trim(), name);
                if (candidate.isFile()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String envDir(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().length() == 0 ? fallback : value;
    }
}
