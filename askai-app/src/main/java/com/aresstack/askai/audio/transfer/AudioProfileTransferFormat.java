package com.aresstack.askai.audio.transfer;

/** Shared constants for the versioned audio-profile transfer format. */
public final class AudioProfileTransferFormat {

    /** Identifies the file as an AskAI audio-profile export (guards against unrelated JSON). */
    public static final String FORMAT = "askai-audio-processing-profiles";

    /** Current schema major version written by this build. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private AudioProfileTransferFormat() {
    }
}
