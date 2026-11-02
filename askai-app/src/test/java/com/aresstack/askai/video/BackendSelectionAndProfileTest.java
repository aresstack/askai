package com.aresstack.askai.video;

import com.aresstack.askai.video.jcodec.JcodecRecorderProvider;
import com.aresstack.askai.video.optional.FfmpegRecorderProvider;
import com.aresstack.askai.video.optional.VlcRecorderProvider;
import org.junit.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Backend availability/selection semantics and RecordingProfile validation. */
public class BackendSelectionAndProfileTest {

    @Test
    public void jcodecIsTheDefaultAndAvailableWithoutNativeRuntimes() {
        JcodecRecorderProvider jcodec = new JcodecRecorderProvider();
        assertEquals("jcodec", jcodec.getId());
        // Available whenever not headless (in CI it may be headless → then unavailable, which is honest).
        assertEquals(!GraphicsEnvironment.isHeadless(), jcodec.isAvailable());
        assertNotNull(jcodec.createRecorder());
    }

    @Test
    public void ffmpegNeverBecomesAvailableWithoutTheUserConfirmedDownload() {
        // The FFmpeg backend depends on natives that only FfmpegRuntimeLoader may fetch — and only on
        // the user's explicit confirmation. Unless that download already happened on this machine,
        // isAvailable() (which never downloads) must stay false, and createRecorder() must refuse.
        FfmpegRecorderProvider ffmpeg = new FfmpegRecorderProvider();
        boolean downloaded = com.aresstack.askai.video.optional.FfmpegRuntimeLoader.isReady();
        assertEquals(downloaded, ffmpeg.isAvailable());
        if (!downloaded) {
            try {
                ffmpeg.createRecorder();
                fail("createRecorder must refuse while the libs are missing");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("explicit confirmation"));
            }
        }
    }

    @Test
    public void theFfmpegDownloadSetIsExplicitHttpsMavenCentral() {
        // What the user is asked to confirm is a fixed, inspectable list of Maven Central HTTPS URLs —
        // no opaque installers, no side channels.
        java.util.List<String> urls =
                com.aresstack.askai.video.optional.FfmpegRuntimeLoader.requiredDownloadUrls();
        if (com.aresstack.askai.video.optional.FfmpegRuntimeLoader.platformClassifier() == null) {
            assertTrue(urls.isEmpty()); // unsupported platform → nothing offered at all
            return;
        }
        assertEquals(5, urls.size());
        for (String url : urls) {
            assertTrue(url, url.startsWith("https://repo1.maven.org/maven2/"));
            assertTrue(url, url.endsWith(".jar"));
        }
    }

    @Test
    public void vlcAvailabilityTracksAnInstalledVlcOnly() {
        // vlcj (the binding) is always on the classpath now; availability must reduce to "is VLC
        // installed on this machine" and createRecorder must refuse cleanly when it is not.
        VlcRecorderProvider vlc = new VlcRecorderProvider();
        if (!vlc.isAvailable()) {
            try {
                vlc.createRecorder();
                fail("createRecorder must refuse without a VLC installation");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("VLC"));
            }
        }
    }

    @Test
    public void anUnavailableBackendCannotBeSelectedAndThereIsNoSilentFallback() {
        // Only an unavailable provider: selecting it is refused, the selection does not change to
        // some other backend behind the user's back.
        MediaRecorderProvider unavailable = new MediaRecorderProvider() {
            public String getId() {
                return "unavailable";
            }
            public String getDisplayName() {
                return "unavailable";
            }
            public boolean isAvailable() {
                return false;
            }
            public MediaRecorder createRecorder() {
                throw new IllegalStateException("unavailable");
            }
        };
        VideoRecordingController controller = new VideoRecordingController(
                java.util.Collections.<MediaRecorderProvider>singletonList(unavailable));
        final boolean[] errored = {false};
        controller.setListener(new VideoRecordingController.Listener() {
            public void onStateChanged(VideoRecordingController.State state) {
            }
            public void onRecordingStarted(RecordingProfile profile) {
            }
            public void onRecordingStopped(java.nio.file.Path outputFile) {
            }
            public void onError(String message) {
                errored[0] = true;
            }
        });
        controller.selectProvider("unavailable");
        assertTrue("selecting an unavailable backend is refused", errored[0]);
    }

    @Test
    public void availableProvidersReflectsRuntimePresenceOnly() {
        VideoRecordingController controller =
                new VideoRecordingController(MediaRecorderProviders.defaults());
        List<MediaRecorderProvider> available = controller.availableProviders();
        for (MediaRecorderProvider provider : available) {
            assertTrue(provider.isAvailable());
        }
    }

    @Test
    public void profileRejectsInvalidFpsDimensionsSourceAndOutput() {
        Rectangle bounds = new Rectangle(0, 0, 320, 240);
        try {
            RecordingProfile.builder().outputFile(Paths.get("x.mp4")).fps(15).build();
            fail("source required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source"));
        }
        try {
            RecordingProfile.builder().source(RecordingSource.window(bounds, "s")).fps(15).build();
            fail("output required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("outputFile"));
        }
        try {
            RecordingProfile.builder().source(RecordingSource.window(bounds, "s"))
                    .outputFile(Paths.get("x.mp4")).fps(0).build();
            fail("fps must be > 0");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("fps"));
        }
    }

    @Test
    public void aWindowSourceDerivesDimensionsFromItsBounds() {
        RecordingProfile profile = RecordingProfile.builder()
                .source(RecordingSource.window(new Rectangle(10, 20, 640, 480), "AskAI Window"))
                .outputFile(Paths.get("x.mp4")).fps(15).build();
        assertEquals(640, profile.getWidth());
        assertEquals(480, profile.getHeight());
        assertEquals(RecordingSource.Kind.WINDOW, profile.getSource().getKind());
    }

    @Test
    public void recordingSourceRejectsEmptyBounds() {
        try {
            RecordingSource.window(new Rectangle(0, 0, 0, 100), "x");
            fail("empty bounds rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("positive size"));
        }
    }
}
