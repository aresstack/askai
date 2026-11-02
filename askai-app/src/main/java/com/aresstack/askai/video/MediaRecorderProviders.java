package com.aresstack.askai.video;

import com.aresstack.askai.video.jcodec.JcodecRecorderProvider;
import com.aresstack.askai.video.optional.FfmpegRecorderProvider;
import com.aresstack.askai.video.optional.VlcRecorderProvider;

import java.util.ArrayList;
import java.util.List;

/** The default backend registry: JCodec first (portable default), then the optional VLC/FFmpeg backends. */
public final class MediaRecorderProviders {

    private MediaRecorderProviders() {
    }

    public static List<MediaRecorderProvider> defaults() {
        List<MediaRecorderProvider> providers = new ArrayList<MediaRecorderProvider>();
        providers.add(new JcodecRecorderProvider());
        providers.add(new VlcRecorderProvider());
        providers.add(new FfmpegRecorderProvider());
        return providers;
    }
}
