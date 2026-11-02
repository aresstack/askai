package com.aresstack.askai.audio.preview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Carry playable format candidates and non-fatal preparation diagnostics. */
final class AudioPlaybackPlan {

    private final List<PreparedAudio> candidates;
    private final List<String> failures;

    AudioPlaybackPlan(List<PreparedAudio> candidates, List<String> failures) {
        this.candidates = Collections.unmodifiableList(new ArrayList<PreparedAudio>(candidates));
        this.failures = Collections.unmodifiableList(new ArrayList<String>(failures));
    }

    List<PreparedAudio> getCandidates() {
        return candidates;
    }

    List<String> getFailures() {
        return failures;
    }
}
