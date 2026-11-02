package com.aresstack.askai.video.optional;

import com.aresstack.askai.video.VideoSettings;

import java.io.File;

/**
 * Locates an INSTALLED VLC runtime without bundling it (ported from the WD4J/corenth
 * {@code LibVlcLocator}/{@code MediaRuntimeBootstrap}). AskAI ships only the vlcj Java binding; libvlc
 * itself must already be installed by the user — otherwise the VLC backend stays unavailable and is
 * never offered (no silent fallback, no download, no process started by AskAI).
 *
 * <p>{@link #configureRuntime(VideoSettings.Vlc)} follows the reference order: 1) the user's manual
 * base path from the settings, 2) vlcj's {@code NativeDiscovery} when autodetect is enabled, 3) the
 * known standard install locations. It points JNA at the found directory ({@code jna.library.path},
 * {@code VLC_PLUGIN_PATH}) before the first vlcj call.</p>
 */
public final class LibVlcLocator {

    private static final String[] INSTALL_CANDIDATES = {
            "C:\\Program Files\\VideoLAN\\VLC",
            "C:\\Program Files (x86)\\VideoLAN\\VLC",
            "/Applications/VLC.app/Contents/MacOS/lib",
            "/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu"
    };

    private static volatile boolean configured;

    private LibVlcLocator() {
    }

    /** True only when the vlcj binding is loadable AND a VLC installation could be located. */
    public static boolean isAvailable(VideoSettings.Vlc settings) {
        if (!isVlcjOnClasspath()) {
            return false;
        }
        if (settings != null && isDirectory(settings.getBasePath())) {
            return true;
        }
        return findInstallDirectory() != null;
    }

    /**
     * Configure JNA/vlcj so libvlc can actually be loaded. Idempotent; returns false when no VLC
     * installation was found (the caller must then refuse — never fall back silently).
     */
    public static synchronized boolean configureRuntime(VideoSettings.Vlc settings) {
        if (configured) {
            return true;
        }
        if (!isVlcjOnClasspath()) {
            return false;
        }
        boolean found = false;
        // 1) Manual base path from the settings wins (reference: video.vlc.basePath).
        if (settings != null && isDirectory(settings.getBasePath())) {
            applyBasePath(new File(settings.getBasePath().trim()));
            found = true;
        }
        // 2) vlcj's own discovery when autodetect is on (finds non-standard installs via PATH/registry).
        boolean autodetect = settings == null || settings.isAutodetect();
        if (autodetect) {
            try {
                found |= new uk.co.caprica.vlcj.discovery.NativeDiscovery().discover();
            } catch (Throwable discoveryFailed) {
                // Discovery is only one net; the explicit paths may still suffice.
            }
        }
        // 3) Known standard locations as the last net.
        if (!found) {
            File dir = findInstallDirectory();
            if (dir != null) {
                applyBasePath(dir);
                found = true;
            }
        }
        configured = found;
        return found;
    }

    private static void applyBasePath(File vlcHome) {
        String sep = System.getProperty("path.separator", ";");
        String existing = System.getProperty("jna.library.path");
        String combined = (existing == null || existing.trim().isEmpty())
                ? vlcHome.getAbsolutePath()
                : existing + sep + vlcHome.getAbsolutePath();
        System.setProperty("jna.library.path", combined);
        File plugins = new File(vlcHome, "plugins");
        if (plugins.isDirectory()) {
            System.setProperty("VLC_PLUGIN_PATH", plugins.getAbsolutePath());
        }
    }

    private static boolean isVlcjOnClasspath() {
        try {
            Class.forName("uk.co.caprica.vlcj.player.MediaPlayerFactory");
            return true;
        } catch (Throwable notPresent) {
            return false;
        }
    }

    private static boolean isDirectory(String path) {
        return path != null && !path.trim().isEmpty() && new File(path.trim()).isDirectory();
    }

    /** First existing candidate matching the JVM bitness (a 64-bit JVM cannot load 32-bit libvlc). */
    private static File findInstallDirectory() {
        boolean jvm64 = System.getProperty("os.arch", "").contains("64");
        for (int i = 0; i < INSTALL_CANDIDATES.length; i++) {
            String candidate = INSTALL_CANDIDATES[i];
            if (jvm64 && candidate.contains("Program Files (x86)")) {
                continue;
            }
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }
}
