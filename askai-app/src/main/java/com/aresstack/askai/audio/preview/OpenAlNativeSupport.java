package com.aresstack.askai.audio.preview;

import com.aresstack.askai.settings.AskAiPaths;

import java.io.File;

/**
 * OpenAL is an opt-in, last-resort backend whose native DLLs are NOT shipped in the fat JAR. They are
 * installed later into {@code <app-dir>/addons/audio-openal} (e.g. downloaded on the user's request) and
 * loaded from there via {@code org.lwjgl.librarypath}. This helper points LWJGL at that directory before
 * any OpenAL class initializes, and reports whether the natives are actually present.
 */
public final class OpenAlNativeSupport {

    private static volatile boolean configured;

    private OpenAlNativeSupport() {
    }

    /** @return the directory where the LWJGL/OpenAL native DLLs are expected. */
    public static File nativeDirectory() {
        return AskAiPaths.appDirectory().resolve("addons").resolve("audio-openal").toFile();
    }

    /** True only when both required native libraries are present in the add-on directory. */
    public static boolean nativesInstalled() {
        File dir = nativeDirectory();
        return new File(dir, "lwjgl.dll").isFile() && new File(dir, "OpenAL.dll").isFile();
    }

    /**
     * Point LWJGL at the add-on directory (once) so it loads the externally installed natives instead of
     * expecting them on the classpath. Never overrides an explicitly provided {@code org.lwjgl.librarypath}.
     */
    public static synchronized void configureLibraryPath() {
        if (configured) {
            return;
        }
        configured = true;
        if (System.getProperty("org.lwjgl.librarypath") == null) {
            System.setProperty("org.lwjgl.librarypath", nativeDirectory().getAbsolutePath());
        }
    }
}
