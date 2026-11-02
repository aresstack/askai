package com.aresstack.askai.speech;

import com.aresstack.audio.application.SpeechRecordingSession;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.dsp.AudioLevelMeter;
import com.aresstack.audio.dsp.AudioProcessingPipeline;
import com.aresstack.audio.dsp.Pcm16Processor;
import com.aresstack.audio.dsp.Pcm16ProcessingPipeline;
import com.aresstack.audio.infrastructure.AudioCaptureFormatNegotiator;
import com.aresstack.audio.infrastructure.JavaSoundMicrophoneSource;
import com.aresstack.audio.infrastructure.WavFileAudioSink;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * {@link MicrophoneRecorder} backed by Java Sound. Negotiates a capture format the device actually
 * supports (instead of demanding 16 kHz mono), records the raw signal to a temp WAV with a live
 * {@link AudioLevelMeter} attached, and leaves the down-mix/resample to the normalizer. The DSP speech
 * chain runs later at the canonical rate, so capture stays a faithful raw recording.
 */
public final class JavaSoundMicrophoneRecorder implements MicrophoneRecorder {

    private static final int FRAME_MILLIS = 20;

    public Session start(String deviceName, File workingDirectory) throws Exception {
        final String device = deviceName == null ? "" : deviceName;
        PcmAudioFormat format = new AudioCaptureFormatNegotiator(AudioCaptureFormatNegotiator.javaSoundProbe())
                .negotiate(device, AudioCaptureFormatNegotiator.defaultCandidates());

        if (!workingDirectory.isDirectory()) {
            workingDirectory.mkdirs();
        }
        final File tempFile = new File(workingDirectory, "askai-speech-" + System.nanoTime() + ".wav");
        final AudioLevelMeter meter = new AudioLevelMeter();
        final PcmAudioFormat captureFormat = format;

        JavaSoundMicrophoneSource source = new JavaSoundMicrophoneSource(format, device);
        WavFileAudioSink sink = new WavFileAudioSink(tempFile);
        List<Pcm16Processor> processors = Collections.<Pcm16Processor>singletonList(meter);
        AudioProcessingPipeline pipeline = new Pcm16ProcessingPipeline(processors);
        final SpeechRecordingSession session = new SpeechRecordingSession(source, sink, pipeline, FRAME_MILLIS);
        session.start();

        return new Session() {
            public AudioLevelMeter getMeter() {
                return meter;
            }

            public String getDeviceName() {
                return device;
            }

            public RawRecording stop() throws Exception {
                long droppedFrames;
                try {
                    droppedFrames = session.stop();
                } catch (Exception ex) {
                    // Finalize failed: the WAV header was never patched, so the temp is unusable — clean up.
                    if (tempFile.isFile()) {
                        tempFile.delete();
                    }
                    throw ex;
                }
                return new RawRecording(tempFile, captureFormat, droppedFrames, device);
            }

            public void discard() {
                try {
                    session.stop();
                } catch (Exception ignored) {
                    // discarding anyway
                }
                if (tempFile.isFile()) {
                    tempFile.delete();
                }
            }
        };
    }
}
