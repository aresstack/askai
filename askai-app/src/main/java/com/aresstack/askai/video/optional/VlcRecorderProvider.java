package com.aresstack.askai.video.optional;

import com.aresstack.askai.video.MediaRecorder;
import com.aresstack.askai.video.MediaRecorderProvider;
import com.aresstack.askai.video.VideoSettings;
import com.aresstack.askai.video.VideoSettingsStore;

/**
 * OPTIONAL VLC backend. AskAI bundles only the vlcj binding — libvlc must already be installed by the
 * user, and availability is reported strictly through {@link LibVlcLocator}. When VLC is absent this
 * backend is unavailable and the app never silently switches to another one.
 */
public final class VlcRecorderProvider implements MediaRecorderProvider {

    public static final String ID = "vlc";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "VLC (uses your installed VLC)";
    }

    @Override
    public boolean isAvailable() {
        return LibVlcLocator.isAvailable(VideoSettingsStore.shared().load().getVlc());
    }

    @Override
    public MediaRecorder createRecorder() {
        VideoSettings.Vlc settings = VideoSettingsStore.shared().load().getVlc();
        if (!LibVlcLocator.configureRuntime(settings)) {
            throw new IllegalStateException(
                    "No VLC installation was found. Install VLC (or set its base path in the video "
                            + "settings) or choose another backend.");
        }
        return new VlcRecorder(settings);
    }
}
