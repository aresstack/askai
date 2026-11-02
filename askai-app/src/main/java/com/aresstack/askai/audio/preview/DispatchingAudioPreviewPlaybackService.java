package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Routes playback to the backend that owns the selected device: the VLC sidecar for VLC targets (primary
 * on Windows once VLC is located), OpenAL Soft for OpenAL endpoints (opt-in, natives installed separately),
 * and Java Sound for Java Sound / system-default targets (legacy). The device's backend decides the path —
 * nothing is matched by name and there is no cross-backend fallback to a different physical device.
 */
public final class DispatchingAudioPreviewPlaybackService implements AudioPreviewPlaybackService {

    private final JavaSoundAudioPreviewPlaybackService javaSound;
    private final OpenAlAudioPreviewPlaybackService openAl;
    private final VlcSidecarPlaybackService vlc;

    private volatile AudioOutputDevice device = AudioOutputDevice.systemDefault();

    public DispatchingAudioPreviewPlaybackService() {
        this(new JavaSoundAudioPreviewPlaybackService(), new OpenAlAudioPreviewPlaybackService(),
                new VlcSidecarPlaybackService());
    }

    DispatchingAudioPreviewPlaybackService(JavaSoundAudioPreviewPlaybackService javaSound,
                                           OpenAlAudioPreviewPlaybackService openAl,
                                           VlcSidecarPlaybackService vlc) {
        this.javaSound = javaSound;
        this.openAl = openAl;
        this.vlc = vlc;
    }

    public void setErrorHandler(Consumer<String> handler) {
        javaSound.setErrorHandler(handler);
        openAl.setErrorHandler(handler);
        vlc.setErrorHandler(handler);
    }

    public void setInfoHandler(Consumer<String> handler) {
        javaSound.setInfoHandler(handler);
        openAl.setInfoHandler(handler);
        vlc.setInfoHandler(handler);
    }

    public void setOutputDevice(AudioOutputDevice device) {
        AudioOutputDevice target = device == null ? AudioOutputDevice.systemDefault() : device;
        this.device = target;
        javaSound.setOutputDevice(target);
        openAl.setOutputDevice(target);
        vlc.setOutputDevice(target);
    }

    public void play(short[] samples, PcmAudioFormat format, Runnable onFinished) {
        AudioPreviewPlaybackService selected = serviceFor(device);
        for (AudioPreviewPlaybackService service : all()) {
            if (service != selected) {
                service.stop(); // ensure only the selected backend is active
            }
        }
        selected.play(samples, format, onFinished);
    }

    public void stop() {
        for (AudioPreviewPlaybackService service : all()) {
            service.stop();
        }
    }

    public boolean isPlaying() {
        for (AudioPreviewPlaybackService service : all()) {
            if (service.isPlaying()) {
                return true;
            }
        }
        return false;
    }

    private AudioPreviewPlaybackService serviceFor(AudioOutputDevice target) {
        if (target != null && target.isVlc()) {
            return vlc;
        }
        if (target != null && target.isOpenAl()) {
            return openAl;
        }
        return javaSound;
    }

    private List<AudioPreviewPlaybackService> all() {
        List<AudioPreviewPlaybackService> services = new ArrayList<AudioPreviewPlaybackService>(3);
        services.add(javaSound);
        services.add(openAl);
        services.add(vlc);
        return services;
    }
}
