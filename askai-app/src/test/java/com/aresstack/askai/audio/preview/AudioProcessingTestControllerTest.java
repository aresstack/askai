package com.aresstack.askai.audio.preview;

import com.aresstack.audio.application.AudioProcessingPreviewService;
import com.aresstack.audio.application.AudioProfileSignature;
import com.aresstack.audio.application.ProcessedAudioPreview;
import com.aresstack.audio.application.ProcessedWaveExportService;
import com.aresstack.audio.domain.AudioBuffer;
import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.pipeline.AudioProcessingProfiles;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioProcessingProfile;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The preview controller drives snapshots, outdated marking, playback selection and cancel correctly. */
public class AudioProcessingTestControllerTest {

    private static final Executor SYNC = new Executor() {
        public void execute(Runnable command) {
            command.run();
        }
    };

    @Test
    public void processUsesTheCapturedSnapshotAndProducesAPreview() {
        Fixture f = new Fixture(SYNC);
        f.setProfile(AudioProcessingProfiles.defaultSpeech());
        f.controller.setSource(source("a"));
        f.controller.process();

        assertEquals(AudioProfileSignature.of(f.currentProfile.get()), AudioProfileSignature.of(f.preview.received));
        assertNotNull(f.controller.getPreview());
        assertFalse(f.controller.isOutdated());
        assertEquals(AudioProcessingTestController.State.PROCESSED, f.controller.getState());
    }

    @Test
    public void nextRunUsesChangedParameters() {
        Fixture f = new Fixture(SYNC);
        f.setProfile(AudioProcessingProfiles.defaultSpeech());
        f.controller.setSource(source("a"));
        f.controller.process();
        AudioProcessingProfile changed = disableFirstBlock(AudioProcessingProfiles.defaultSpeech());
        f.setProfile(changed);
        f.controller.process();
        assertEquals(AudioProfileSignature.of(changed), AudioProfileSignature.of(f.preview.received));
    }

    @Test
    public void failedRunKeepsTheLastGoodPreview() {
        Fixture f = new Fixture(SYNC);
        f.setProfile(AudioProcessingProfiles.defaultSpeech());
        f.controller.setSource(source("a"));
        f.controller.process();
        ProcessedAudioPreview good = f.controller.getPreview();
        assertNotNull(good);

        f.preview.failNext = true;
        f.controller.process();
        assertEquals(AudioProcessingTestController.State.FAILED, f.controller.getState());
        assertSame("last good preview retained", good, f.controller.getPreview());
    }

    @Test
    public void pipelineChangeMarksResultOutdatedButKeepsItPlayable() {
        Fixture f = new Fixture(SYNC);
        f.setProfile(AudioProcessingProfiles.defaultSpeech());
        f.controller.setSource(source("a"));
        f.controller.process();
        f.controller.pipelineChanged();
        assertTrue(f.controller.isOutdated());
        assertEquals(AudioProcessingTestController.State.RESULT_OUTDATED, f.controller.getState());
        assertNotNull(f.controller.getPreview());
    }

    @Test
    public void playOriginalUsesTheSourceAndPlayProcessedUsesThePreview() {
        Fixture f = new Fixture(SYNC);
        f.setProfile(AudioProcessingProfiles.defaultSpeech());
        f.controller.setSource(source("a"));
        f.controller.playOriginal();
        assertTrue(f.playback.lastPlayedWasSource);

        f.controller.process();
        f.controller.playProcessed();
        assertFalse(f.playback.lastPlayedWasSource); // processed samples, not the raw source
    }

    @Test
    public void stopEndsPlaybackAndReturnsToIdle() {
        Fixture f = new Fixture(SYNC);
        f.setProfile(AudioProcessingProfiles.defaultSpeech());
        f.controller.setSource(source("a"));
        f.controller.process();
        f.controller.playProcessed();
        f.controller.stop();
        assertTrue(f.playback.stopped);
        assertEquals(AudioProcessingTestController.State.PROCESSED, f.controller.getState());
    }

    @Test
    public void stopDuringProcessingCancelsWithoutApplyingAPartialResult() {
        ManualExecutor manual = new ManualExecutor();
        Fixture f = new Fixture(manual);
        f.setProfile(AudioProcessingProfiles.defaultSpeech());
        f.controller.setSource(source("a"));
        f.controller.process(); // queues the work, state PROCESSING
        assertEquals(AudioProcessingTestController.State.PROCESSING, f.controller.getState());
        f.controller.stop(); // cancels
        manual.runAll(); // the (now stale) work runs but must be ignored
        assertEquals(AudioProcessingTestController.State.CANCELLED, f.controller.getState());
        assertNull("no partial preview applied", f.controller.getPreview());
    }

    @Test
    public void anInvalidPipelineIsNotProcessed() {
        Fixture f = new Fixture(SYNC);
        java.util.List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>();
        blocks.add(com.aresstack.audio.pipeline.AudioBlockRegistry.getInstance()
                .defaultDefinition(com.aresstack.audio.profile.AudioBlockType.PARAMETRIC_EQ, "bad")
                .withParameter("centerHz", "0")); // invalid → validation error
        f.setProfile(new AudioProcessingProfile("x", "Invalid", false, blocks));
        f.controller.setSource(source("a"));

        f.controller.process();

        assertEquals(AudioProcessingTestController.State.FAILED, f.controller.getState());
        assertNull("the preview service must not run for an invalid pipeline", f.preview.received);
    }

    // ------------------------------------------------------------------ helpers

    private static AudioProcessingProfile disableFirstBlock(AudioProcessingProfile profile) {
        List<AudioBlockDefinition> blocks = new ArrayList<AudioBlockDefinition>(profile.getBlocks());
        blocks.set(0, blocks.get(0).withEnabled(false));
        return profile.withBlocks(blocks);
    }

    private static AudioTestSource source(final String id) {
        return new AudioTestSource() {
            public String getId() {
                return id;
            }

            public String getDisplayName() {
                return id + ".wav";
            }

            public File getFile() {
                return new File(id + ".wav");
            }

            public boolean isRecording() {
                return false;
            }

            public AudioBuffer readBuffer() {
                return new AudioBuffer(new short[]{10, 20, 30, 40}, new PcmAudioFormat(16000, 1, 16));
            }
        };
    }

    private static final class Fixture {
        final FakePreview preview = new FakePreview();
        final FakePlayback playback = new FakePlayback();
        final AtomicReference<AudioProcessingProfile> currentProfile = new AtomicReference<AudioProcessingProfile>();
        final AudioProcessingTestController controller;

        Fixture(Executor executor) {
            controller = new AudioProcessingTestController(preview, playback, new ProcessedWaveExportService() {
                public void export(ProcessedAudioPreview p, File t) {
                }
            }, new Supplier<AudioProcessingProfile>() {
                public AudioProcessingProfile get() {
                    return currentProfile.get();
                }
            }, executor, new AudioProcessingTestController.Listener() {
                public void stateChanged(AudioProcessingTestController.State state) {
                }

                public void previewUpdated(ProcessedAudioPreview p, boolean outdated) {
                }

                public void failed(String message) {
                }
            });
        }

        void setProfile(AudioProcessingProfile profile) {
            currentProfile.set(profile);
        }
    }

    private static final class FakePreview implements AudioProcessingPreviewService {
        AudioProcessingProfile received;
        boolean failNext;

        public ProcessedAudioPreview process(AudioBuffer source, AudioProcessingProfile profile, String sourceId) {
            if (failNext) {
                failNext = false;
                throw new RuntimeException("boom");
            }
            received = profile;
            return new ProcessedAudioPreview(new short[]{1, 2, 3, 4}, new PcmAudioFormat(16000, 1, 16),
                    1L, sourceId, AudioProfileSignature.of(profile));
        }
    }

    private static final class FakePlayback implements AudioPreviewPlaybackService {
        boolean stopped;
        boolean playing;
        boolean lastPlayedWasSource;

        public void play(short[] samples, PcmAudioFormat format, Runnable onFinished) {
            playing = true;
            // Source buffer is {10,20,30,40}; the processed preview is {1,2,3,4}.
            lastPlayedWasSource = samples.length > 0 && samples[0] == 10;
        }

        public void stop() {
            stopped = true;
            playing = false;
        }

        public boolean isPlaying() {
            return playing;
        }

        public void setOutputDevice(AudioOutputDevice device) {
            // no-op for the fake
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> queue = new ArrayDeque<Runnable>();

        public void execute(Runnable command) {
            queue.add(command);
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }
}
