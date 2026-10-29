package com.aresstack.askai.java8.video.optional;

import com.aresstack.askai.java8.video.MediaRecorder;
import com.aresstack.askai.java8.video.MediaRecorderProvider;

/**
 * OPTIONAL FFmpeg/JavaCV backend. The native libraries are NEVER bundled and NEVER fetched silently:
 * they arrive exclusively through the user-confirmed download in {@link FfmpegRuntimeLoader} (triggered
 * from the Record Video dialog with an explicit Yes/No prompt). Until then this backend reports
 * unavailable and is never used as a fallback. After that one confirmed download the persisted jars are
 * re-attached on later runs, so availability sticks.
 */
public final class FfmpegRecorderProvider implements MediaRecorderProvider {

    public static final String ID = "ffmpeg";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "FFmpeg (native, downloaded on request)";
    }

    @Override
    public boolean isAvailable() {
        return FfmpegRuntimeLoader.isReady();
    }

    @Override
    public MediaRecorder createRecorder() {
        if (!FfmpegRuntimeLoader.isReady()) {
            throw new IllegalStateException("The FFmpeg libraries are not installed. The Record Video "
                    + "dialog offers the download — it runs only on your explicit confirmation.");
        }
        try {
            // Created inside the backend classloader: FfmpegRecorder links against the downloaded JavaCV
            // jars, which the application classloader never sees (compileOnly, attached at runtime).
            return FfmpegRuntimeLoader.newRecorder(
                    com.aresstack.askai.java8.video.VideoSettingsStore.shared().load().getFfmpeg());
        } catch (Exception failed) {
            throw new IllegalStateException("The FFmpeg backend could not be created: " + failed, failed);
        }
    }
}
