package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.AudioBuffer;

import java.io.File;
import java.io.IOException;

/**
 * A reusable test source for the DSP preview: either a chosen local file or a saved microphone test
 * recording. Reading always yields the RAW, unprocessed audio; the pipeline is applied separately by the
 * preview service, so the same source can be re-processed with different settings any number of times.
 */
public interface AudioTestSource {

    /** @return a stable id (used for provenance / cache keys). */
    String getId();

    String getDisplayName();

    File getFile();

    /** @return true when this source is a microphone test recording (vs. a chosen file). */
    boolean isRecording();

    /** @return the decoded raw audio; never mutated by the preview. */
    AudioBuffer readBuffer() throws IOException;
}
