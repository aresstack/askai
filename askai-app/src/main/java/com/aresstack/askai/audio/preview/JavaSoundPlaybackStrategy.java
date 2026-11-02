package com.aresstack.askai.audio.preview;

/** Open one Java Sound backend on exactly the requested output device. */
interface JavaSoundPlaybackStrategy {

    String getName();

    JavaSoundPlaybackSession open(AudioOutputDevice device, PreparedAudio audio) throws Exception;
}
