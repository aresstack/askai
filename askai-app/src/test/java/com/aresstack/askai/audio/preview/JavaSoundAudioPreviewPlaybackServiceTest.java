package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;
import org.junit.Test;

import javax.sound.sampled.Mixer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JavaSoundAudioPreviewPlaybackServiceTest {

    @Test
    public void playsOnlyOnTheSelectedDeviceAndReportsTheConcreteBackend() throws Exception {
        RecordingStrategy strategy = new RecordingStrategy(false);
        JavaSoundAudioPreviewPlaybackService service = serviceWith(strategy);
        AudioOutputDevice selected = device("Selected output");
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<String> info = new AtomicReference<String>();

        service.setOutputDevice(selected);
        service.setInfoHandler(message -> {
            info.set(message);
            reported.countDown();
        });
        service.play(new short[]{100, -100, 200, -200}, PcmAudioFormat.speechDefault(),
                finished::countDown);

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertTrue(reported.await(2, TimeUnit.SECONDS));
        assertSame(selected, strategy.lastDevice.get());
        assertEquals(1, strategy.openCount.get());
        assertTrue(info.get().contains("Selected output"));
        assertTrue(info.get().contains("Fake backend"));
    }

    @Test
    public void neverRedirectsAFailedSelectionToAnotherDevice() throws Exception {
        RecordingStrategy strategy = new RecordingStrategy(true);
        JavaSoundAudioPreviewPlaybackService service = serviceWith(strategy);
        AudioOutputDevice selected = device("Broken output");
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<String>();

        service.setOutputDevice(selected);
        service.setErrorHandler(message -> {
            error.set(message);
            failed.countDown();
        });
        service.play(new short[]{100, -100, 200, -200}, PcmAudioFormat.speechDefault(), null);

        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertTrue(strategy.openCount.get() >= 1);
        assertSame(selected, strategy.lastDevice.get());
        assertTrue(error.get().contains("Broken output"));
        assertTrue(error.get().contains("No other device was used"));
    }

    @Test
    public void suppressesCompletionAfterStop() throws Exception {
        BlockingStrategy strategy = new BlockingStrategy();
        JavaSoundAudioPreviewPlaybackService service = serviceWith(strategy);
        AtomicBoolean completed = new AtomicBoolean(false);

        service.play(new short[]{100, -100, 200, -200}, PcmAudioFormat.speechDefault(),
                () -> completed.set(true));
        assertTrue(strategy.started.await(2, TimeUnit.SECONDS));

        service.stop();
        assertTrue(strategy.stopped.await(2, TimeUnit.SECONDS));
        Thread.sleep(50L);

        assertFalse(completed.get());
        assertFalse(service.isPlaying());
    }

    private static JavaSoundAudioPreviewPlaybackService serviceWith(JavaSoundPlaybackStrategy strategy) {
        return new JavaSoundAudioPreviewPlaybackService(new AudioPlaybackFormatPlanner(),
                Collections.singletonList(strategy));
    }

    private static AudioOutputDevice device(String name) {
        return AudioOutputDevice.forMixer(new TestMixerInfo(name, "Vendor", "Description", "1"), name);
    }

    private static final class RecordingStrategy implements JavaSoundPlaybackStrategy {
        private final boolean fail;
        private final AtomicInteger openCount = new AtomicInteger();
        private final AtomicReference<AudioOutputDevice> lastDevice =
                new AtomicReference<AudioOutputDevice>();

        private RecordingStrategy(boolean fail) {
            this.fail = fail;
        }

        public String getName() {
            return "Fake backend";
        }

        public JavaSoundPlaybackSession open(AudioOutputDevice device, PreparedAudio audio) {
            lastDevice.set(device);
            openCount.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("cannot open");
            }
            return new JavaSoundPlaybackSession() {
                public PlaybackMetrics play(PlaybackCancellation cancellation) {
                    return new PlaybackMetrics(4L, 8);
                }

                public void stop() {
                }

                public void close() {
                }
            };
        }
    }

    private static final class BlockingStrategy implements JavaSoundPlaybackStrategy {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        public String getName() {
            return "Blocking backend";
        }

        public JavaSoundPlaybackSession open(AudioOutputDevice device, PreparedAudio audio) {
            return new JavaSoundPlaybackSession() {
                private volatile boolean stopRequested;

                public PlaybackMetrics play(PlaybackCancellation cancellation) throws Exception {
                    started.countDown();
                    while (!stopRequested && !cancellation.isCancelled()) {
                        Thread.sleep(5L);
                    }
                    stopped.countDown();
                    return new PlaybackMetrics(0L, 0);
                }

                public void stop() {
                    stopRequested = true;
                    stopped.countDown();
                }

                public void close() {
                    stopRequested = true;
                }
            };
        }
    }

    private static final class TestMixerInfo extends Mixer.Info {
        private TestMixerInfo(String name, String vendor, String description, String version) {
            super(name, vendor, description, version);
        }
    }
}
