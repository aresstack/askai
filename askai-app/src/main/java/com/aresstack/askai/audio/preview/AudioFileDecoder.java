package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.AudioBuffer;

import java.io.File;
import java.io.IOException;

/** Decode a supported audio container into the PCM buffer consumed by the DSP pipeline. */
public interface AudioFileDecoder {

    AudioBuffer decode(File file) throws IOException;
}
