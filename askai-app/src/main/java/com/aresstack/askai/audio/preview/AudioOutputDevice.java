package com.aresstack.askai.audio.preview;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

/**
 * A backend-neutral playback target. It carries which backend owns it ({@link Backend}), the native
 * identity for that backend (a Java Sound {@link Mixer.Info} or an OpenAL device specifier) and a display
 * name. OpenAL and Java Sound devices are never treated as the same device or linked by name — each keeps
 * its own backend identity so the exact endpoint can be reopened without guessing.
 */
public final class AudioOutputDevice {

    public enum Backend {
        JAVA_SOUND, OPENAL, VLC
    }

    private static final AudioOutputDevice SYSTEM_DEFAULT =
            new AudioOutputDevice(Backend.JAVA_SOUND, null, null, null, "System default (Java Sound)");

    private static final AudioOutputDevice VLC_SYSTEM_DEFAULT =
            new AudioOutputDevice(Backend.VLC, null, null, "", "VLC (system default)");

    private final Backend backend;
    private final Mixer.Info mixerInfo;      // Java Sound identity (null for OpenAL / VLC)
    private final String openAlSpecifier;    // OpenAL identity (null otherwise)
    private final String vlcDeviceId;        // VLC MMDevice endpoint id ("" = system default; null otherwise)
    private final String displayName;

    private AudioOutputDevice(Backend backend, Mixer.Info mixerInfo, String openAlSpecifier,
                             String vlcDeviceId, String displayName) {
        this.backend = backend;
        this.mixerInfo = mixerInfo;
        this.openAlSpecifier = openAlSpecifier;
        this.vlcDeviceId = vlcDeviceId;
        this.displayName = displayName;
    }

    public static AudioOutputDevice systemDefault() {
        return SYSTEM_DEFAULT;
    }

    /** The VLC sidecar playing on the Windows default endpoint (slice-1 scope: no per-endpoint selection). */
    public static AudioOutputDevice vlcSystemDefault() {
        return VLC_SYSTEM_DEFAULT;
    }

    public static AudioOutputDevice forMixer(Mixer.Info mixerInfo, String displayName) {
        if (mixerInfo == null) {
            throw new IllegalArgumentException("Mixer info must not be null.");
        }
        requireDisplayName(displayName);
        return new AudioOutputDevice(Backend.JAVA_SOUND, mixerInfo, null, null, displayName.trim());
    }

    public static AudioOutputDevice forOpenAl(String specifier, String displayName) {
        if (specifier == null || specifier.trim().length() == 0) {
            throw new IllegalArgumentException("OpenAL specifier must not be empty.");
        }
        requireDisplayName(displayName);
        return new AudioOutputDevice(Backend.OPENAL, null, specifier, null, displayName.trim());
    }

    public Backend getBackend() {
        return backend;
    }

    public boolean isSystemDefault() {
        return backend == Backend.JAVA_SOUND && mixerInfo == null;
    }

    public boolean isOpenAl() {
        return backend == Backend.OPENAL;
    }

    public boolean isVlc() {
        return backend == Backend.VLC;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** @return the OpenAL device specifier, or null for non-OpenAL devices. */
    public String getOpenAlSpecifier() {
        return openAlSpecifier;
    }

    /** @return the VLC MMDevice endpoint id ("" = system default), or null for non-VLC devices. */
    public String getVlcDeviceId() {
        return vlcDeviceId;
    }

    Mixer getMixer() {
        return mixerInfo == null ? null : AudioSystem.getMixer(mixerInfo);
    }

    Mixer.Info getMixerInfo() {
        return mixerInfo;
    }

    private static void requireDisplayName(String displayName) {
        if (displayName == null || displayName.trim().length() == 0) {
            throw new IllegalArgumentException("Display name must not be empty.");
        }
    }

    public String toString() {
        return displayName;
    }
}
