package com.aresstack.askai.video;

import org.junit.Test;

import java.awt.Rectangle;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The controller's state machine + backend selection, driven by fake providers (no real capture). */
public class VideoRecordingControllerTest {

    // ------------------------------------------------------------------ fakes

    private static final class FakeRecorder implements MediaRecorder {
        final AtomicBoolean recording = new AtomicBoolean(false);
        final boolean failOnStart;
        int starts;
        int stops;

        FakeRecorder(boolean failOnStart) {
            this.failOnStart = failOnStart;
        }

        public void start(RecordingProfile profile) throws Exception {
            starts++;
            if (failOnStart) {
                throw new RecordingException("boom");
            }
            recording.set(true);
        }

        public void stop() {
            stops++;
            recording.set(false);
        }

        public boolean isRecording() {
            return recording.get();
        }
    }

    private static final class FakeProvider implements MediaRecorderProvider {
        final String id;
        final boolean available;
        final FakeRecorder recorder;

        FakeProvider(String id, boolean available, FakeRecorder recorder) {
            this.id = id;
            this.available = available;
            this.recorder = recorder;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return id;
        }

        public boolean isAvailable() {
            return available;
        }

        public MediaRecorder createRecorder() {
            return recorder;
        }
    }

    /** A listener that lets a test await the next time the controller reaches a target state. */
    private static final class AwaitableListener implements VideoRecordingController.Listener {
        final List<VideoRecordingController.State> states = new ArrayList<VideoRecordingController.State>();
        final List<String> errors = new ArrayList<String>();
        volatile Path stopped;
        private volatile VideoRecordingController.State awaited;
        private volatile CountDownLatch latch;

        synchronized CountDownLatch awaitState(VideoRecordingController.State state) {
            this.awaited = state;
            this.latch = new CountDownLatch(1);
            return latch;
        }

        public synchronized void onStateChanged(VideoRecordingController.State state) {
            states.add(state);
            if (state == awaited && latch != null) {
                latch.countDown();
            }
        }

        public void onRecordingStarted(RecordingProfile profile) {
        }

        public void onRecordingStopped(Path outputFile) {
            stopped = outputFile;
        }

        public synchronized void onError(String message) {
            errors.add(message);
        }
    }

    private RecordingProfile profile() {
        Path out = Paths.get(System.getProperty("java.io.tmpdir"), "controller-test.mp4");
        return RecordingProfile.builder()
                .source(RecordingSource.window(new Rectangle(0, 0, 320, 240), "test"))
                .outputFile(out).fps(15).build();
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void startDelegatesToTheSelectedBackendAndReachesRecording() throws Exception {
        FakeRecorder rec = new FakeRecorder(false);
        VideoRecordingController controller = new VideoRecordingController(
                java.util.Collections.<MediaRecorderProvider>singletonList(
                        new FakeProvider("jcodec", true, rec)));
        AwaitableListener listener = new AwaitableListener();
        controller.setListener(listener);

        CountDownLatch recording = listener.awaitState(VideoRecordingController.State.RECORDING);
        controller.start(profile());
        assertTrue(recording.await(2, TimeUnit.SECONDS));
        assertEquals(1, rec.starts);
        assertTrue(controller.isRecording());
    }

    @Test
    public void aSecondStartWhileRecordingIsRejected() throws Exception {
        FakeRecorder rec = new FakeRecorder(false);
        VideoRecordingController controller = new VideoRecordingController(
                java.util.Collections.<MediaRecorderProvider>singletonList(
                        new FakeProvider("jcodec", true, rec)));
        AwaitableListener listener = new AwaitableListener();
        controller.setListener(listener);
        CountDownLatch recording = listener.awaitState(VideoRecordingController.State.RECORDING);
        controller.start(profile());
        recording.await(2, TimeUnit.SECONDS);

        controller.start(profile()); // rejected synchronously
        assertTrue(listener.errors.toString(), listener.errors.contains("A recording is already in progress."));
        assertEquals("no second delegate start", 1, rec.starts);
    }

    @Test
    public void stopDelegatesAndReturnsToIdle() throws Exception {
        FakeRecorder rec = new FakeRecorder(false);
        VideoRecordingController controller = new VideoRecordingController(
                java.util.Collections.<MediaRecorderProvider>singletonList(
                        new FakeProvider("jcodec", true, rec)));
        AwaitableListener listener = new AwaitableListener();
        controller.setListener(listener);
        CountDownLatch recording = listener.awaitState(VideoRecordingController.State.RECORDING);
        controller.start(profile());
        assertTrue(recording.await(2, TimeUnit.SECONDS));

        CountDownLatch idle = listener.awaitState(VideoRecordingController.State.IDLE);
        controller.stop();
        assertTrue(idle.await(2, TimeUnit.SECONDS));
        assertEquals(1, rec.stops);
        assertFalse(controller.isRecording());
        assertEquals(Paths.get(System.getProperty("java.io.tmpdir"), "controller-test.mp4"),
                listener.stopped);
    }

    @Test
    public void aFailedStartReturnsToAConsistentIdleState() throws Exception {
        FakeRecorder rec = new FakeRecorder(true);
        VideoRecordingController controller = new VideoRecordingController(
                java.util.Collections.<MediaRecorderProvider>singletonList(
                        new FakeProvider("jcodec", true, rec)));
        AwaitableListener listener = new AwaitableListener();
        controller.setListener(listener);

        // The failure path also ends in IDLE (setState IDLE) — await it.
        final CountDownLatch idle = new CountDownLatch(1);
        controller.setListener(new VideoRecordingController.Listener() {
            public void onStateChanged(VideoRecordingController.State state) {
                if (state == VideoRecordingController.State.IDLE) {
                    idle.countDown();
                }
            }
            public void onRecordingStarted(RecordingProfile profile) {
            }
            public void onRecordingStopped(Path outputFile) {
            }
            public void onError(String message) {
            }
        });
        controller.start(profile());
        // Give the worker a moment; the controller stays IDLE and never RECORDING.
        Thread.sleep(300);
        assertFalse(controller.isRecording());
        assertEquals(VideoRecordingController.State.IDLE, controller.getState());
    }

    @Test
    public void theBackendCannotBeSwitchedWhileRecording() throws Exception {
        FakeRecorder jcodec = new FakeRecorder(false);
        List<MediaRecorderProvider> providers = new ArrayList<MediaRecorderProvider>();
        providers.add(new FakeProvider("jcodec", true, jcodec));
        providers.add(new FakeProvider("vlc", true, new FakeRecorder(false)));
        VideoRecordingController controller = new VideoRecordingController(providers);
        AwaitableListener listener = new AwaitableListener();
        controller.setListener(listener);
        CountDownLatch recording = listener.awaitState(VideoRecordingController.State.RECORDING);
        controller.start(profile());
        assertTrue(recording.await(2, TimeUnit.SECONDS));

        controller.selectProvider("vlc");
        assertTrue(listener.errors.contains("The backend cannot be changed while recording."));
        assertEquals("jcodec", controller.getSelectedProvider().getId());
    }

}
