package com.aresstack.audio.openal;

import java.util.List;

/**
 * Manual diagnostic launcher: prints every OpenAL playback device and then plays the stereo test tone.
 * Because the native OpenAL DLL is only present at runtime, this must be run from the packaged classpath
 * (not merely compiled). Optional arg: an exact device specifier to target; otherwise the OS default is used.
 *
 * <p>Run from the fat JAR, e.g.:
 * {@code java -cp askai-0.1.0.jar com.aresstack.audio.openal.OpenAlDiagnostics}</p>
 */
public final class OpenAlDiagnostics {

    private OpenAlDiagnostics() {
    }

    public static void main(String[] args) {
        OpenAlPlayback playback = new OpenAlPlayback();
        System.out.println("=== OpenAL playback devices ===");
        List<OpenAlDevice> devices;
        try {
            devices = playback.listPlaybackDevices();
        } catch (Throwable t) {
            System.out.println("Device enumeration failed: " + t);
            t.printStackTrace();
            return;
        }
        String defaultSpecifier = safeDefault(playback);
        System.out.println("OS default: " + defaultSpecifier);
        if (devices.isEmpty()) {
            System.out.println("(no devices reported)");
        }
        for (int i = 0; i < devices.size(); i++) {
            System.out.println("  [" + i + "] " + devices.get(i).getSpecifier());
        }

        String target = args != null && args.length > 0 ? args[0] : null;
        int sampleRate = 44100;
        System.out.println("\n=== Playing stereo test tone on: "
                + (target == null ? "system default" : target) + " ===");
        try {
            OpenAlPlaybackResult result = playback.play(StereoTestTone.interleaved(sampleRate),
                    StereoTestTone.CHANNELS, sampleRate, target, OpenAlCancellation.NEVER);
            System.out.println("SUCCESS: " + result.describe());
        } catch (OpenAlException ex) {
            System.out.println("FAILED: " + ex.getMessage());
        }
    }

    private static String safeDefault(OpenAlPlayback playback) {
        try {
            return String.valueOf(playback.defaultPlaybackSpecifier());
        } catch (Throwable t) {
            return "(unavailable: " + t + ")";
        }
    }
}
