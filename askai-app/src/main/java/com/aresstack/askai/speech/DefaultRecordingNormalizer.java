package com.aresstack.askai.speech;

import com.aresstack.audio.application.NormalizationResult;
import com.aresstack.audio.application.SpeechAudioNormalizer;
import com.aresstack.audio.profile.AudioProcessingProfile;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;

import java.io.File;
import java.util.function.Supplier;

/** Resolve the selected profile for every recording and run the audio-dsp normalizer. */
public final class DefaultRecordingNormalizer implements RecordingNormalizer {

    private final Supplier<AudioProcessingProfile> profileSupplier;

    public DefaultRecordingNormalizer() {
        this(new Supplier<AudioProcessingProfile>() {
            public AudioProcessingProfile get() {
                return AudioProcessingProfiles.defaultSpeech();
            }
        });
    }

    public DefaultRecordingNormalizer(Supplier<AudioProcessingProfile> profileSupplier) {
        if (profileSupplier == null) {
            throw new IllegalArgumentException("Profile supplier must not be null.");
        }
        this.profileSupplier = profileSupplier;
    }

    public NormalizationResult normalize(File rawWav, File targetWav) throws Exception {
        AudioProcessingProfile profile = profileSupplier.get();
        return new SpeechAudioNormalizer(profile == null
                ? AudioProcessingProfiles.defaultSpeech() : profile).normalize(rawWav, targetWav);
    }
}
