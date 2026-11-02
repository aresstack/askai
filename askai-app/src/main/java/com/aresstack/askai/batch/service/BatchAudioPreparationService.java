package com.aresstack.askai.batch.service;

import com.aresstack.askai.audio.preview.AudioFileDecoder;
import com.aresstack.askai.audio.preview.JavaSoundAudioFileDecoder;
import com.aresstack.audio.application.AudioProcessingPreviewService;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.application.SpeechToTextAudioPreparer;
import com.aresstack.audio.application.WavSpeechAudioPreparer;
import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioProcessingProfile;

import java.io.File;
import java.io.IOException;

/**
 * Prepare one audio file for the STT backend under a single DSP profile.
 *
 * <p>The container is decoded to PCM preserving the source sample rate and channel count. When the profile
 * has enabled blocks the DSP pipeline runs (format-neutral: 48&nbsp;kHz stereo stays 48&nbsp;kHz stereo
 * unless a resampler/channel block changes it). Then the shared {@link SpeechToTextAudioPreparer} writes the
 * STT transport WAV <b>in that same format</b> — the audio reaches the model as unaltered as the pipeline
 * left it, with no forced down-mix or resampling.</p>
 *
 * <p>"Off" (no enabled block) means <b>no DSP effects</b>: the source is decoded and written straight to the
 * transport WAV in its original rate/channels. Reducing to 16&nbsp;kHz mono (or any other target) only
 * happens when the selected profile explicitly contains a resampler/channel block.</p>
 */
public final class BatchAudioPreparationService {

    private final AudioProcessingPreviewService processingService;
    private final AudioFileDecoder audioFileDecoder;
    private final SpeechToTextAudioPreparer sttPreparer;

    public BatchAudioPreparationService(AudioProcessingPreviewService processingService) {
        this(processingService, new JavaSoundAudioFileDecoder(), new WavSpeechAudioPreparer());
    }

    public BatchAudioPreparationService(AudioProcessingPreviewService processingService,
                                        AudioFileDecoder audioFileDecoder,
                                        SpeechToTextAudioPreparer sttPreparer) {
        if (processingService == null) {
            throw new IllegalArgumentException("Processing service must not be null.");
        }
        if (audioFileDecoder == null) {
            throw new IllegalArgumentException("Audio file decoder must not be null.");
        }
        if (sttPreparer == null) {
            throw new IllegalArgumentException("STT preparer must not be null.");
        }
        this.processingService = processingService;
        this.audioFileDecoder = audioFileDecoder;
        this.sttPreparer = sttPreparer;
    }

    public PreparedBatchAudio prepare(File sourceFile, AudioProcessingProfile profile) throws IOException {
        AudioBuffer decoded = audioFileDecoder.decode(sourceFile);
        AudioBuffer forStt;
        if (hasEnabledBlock(profile)) {
            String sourceId = sourceId(sourceFile);
            ProcessedAudioPreview preview = processingService.process(decoded, profile, sourceId);
            forStt = new AudioBuffer(preview.getSamples(), preview.getFormat());
        } else {
            // "Off": no DSP effects — send the decoded audio in its original format below.
            forStt = decoded;
        }
        File temporaryFile = File.createTempFile("askai-batch-", ".wav");
        boolean prepared = false;
        try {
            sttPreparer.prepare(forStt, temporaryFile);
            prepared = true;
            return new PreparedBatchAudio(temporaryFile);
        } finally {
            if (!prepared && temporaryFile.exists()) {
                temporaryFile.delete();
            }
        }
    }

    /** @return true when the profile has at least one enabled block, i.e. it actually processes audio. */
    private static boolean hasEnabledBlock(AudioProcessingProfile profile) {
        if (profile == null) {
            return false;
        }
        for (AudioBlockDefinition block : profile.getBlocks()) {
            if (block.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    private static String sourceId(File sourceFile) {
        if (sourceFile == null) {
            return "missing-audio-file";
        }
        return sourceFile.getAbsolutePath() + "@" + sourceFile.length() + "@" + sourceFile.lastModified();
    }

    /** The temporary STT transport file this service created; deleted on {@link #close()}. */
    public static final class PreparedBatchAudio implements AutoCloseable {
        private final File file;

        private PreparedBatchAudio(File file) {
            this.file = file;
        }

        public File getFile() {
            return file;
        }

        public void close() {
            if (file != null && file.exists()) {
                file.delete();
            }
        }
    }
}
