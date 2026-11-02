package com.aresstack.askai.speech;

import com.aresstack.audio.application.NormalizationResult;

import java.io.File;

/**
 * Port that turns a raw recording into the canonical 16 kHz mono WAV. Backed by the audio-dsp
 * {@code SpeechAudioNormalizer}; an interface here keeps the use case testable.
 */
public interface RecordingNormalizer {

    NormalizationResult normalize(File rawWav, File targetWav) throws Exception;
}
