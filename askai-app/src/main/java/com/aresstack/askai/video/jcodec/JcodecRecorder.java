package com.aresstack.askai.video.jcodec;

import com.aresstack.askai.video.MediaRecorder;
import com.aresstack.askai.video.RecordingProfile;

/**
 * The JCodec backend adapter onto {@link MediaRecorder}: it maps the neutral {@link RecordingProfile} to
 * the technical {@link JcodecWindowRecorder} (capture bounds from the profile's source, fps from the
 * profile). Clear split — this class is the backend adapter, {@code JcodecWindowRecorder} is the capture.
 */
public final class JcodecRecorder implements MediaRecorder {

    private JcodecWindowRecorder recorder;

    @Override
    public void start(RecordingProfile profile) throws Exception {
        if (recorder != null) {
            return;
        }
        JcodecWindowRecorder started = new JcodecWindowRecorder(
                profile.getSource().getBounds(), profile.getOutputFile(), profile.getFps());
        started.start();
        recorder = started;
    }

    @Override
    public void stop() {
        if (recorder == null) {
            return;
        }
        try {
            recorder.stop();
        } catch (Exception ignored) {
            // The controller reports failures; the file is finalized best-effort regardless.
        } finally {
            recorder = null;
        }
    }

    @Override
    public boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }
}
