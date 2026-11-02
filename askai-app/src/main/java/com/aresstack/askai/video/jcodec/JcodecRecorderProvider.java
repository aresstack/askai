package com.aresstack.askai.video.jcodec;

import com.aresstack.askai.video.MediaRecorder;
import com.aresstack.askai.video.MediaRecorderProvider;

/**
 * The portable DEFAULT backend: JCodec is a pure-Java dependency bundled with AskAI, so it is available
 * whenever the classes and a screen device are present (checked once, defensively). No native runtime.
 */
public final class JcodecRecorderProvider implements MediaRecorderProvider {

    public static final String ID = "jcodec";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "JCodec (portable, no install)";
    }

    @Override
    public boolean isAvailable() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return false;
        }
        try {
            Class.forName("org.jcodec.api.awt.AWTSequenceEncoder");
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    @Override
    public MediaRecorder createRecorder() {
        return new JcodecRecorder();
    }
}
