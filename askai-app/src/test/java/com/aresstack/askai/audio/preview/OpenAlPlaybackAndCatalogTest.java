package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.openal.OpenAlAudioBackend;
import com.aresstack.audio.openal.OpenAlCancellation;
import com.aresstack.audio.openal.OpenAlDevice;
import com.aresstack.audio.openal.OpenAlException;
import com.aresstack.audio.openal.OpenAlPlaybackResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** OpenAL is the primary, exactly-selected playback path with no silent fallback to another device. */
public class OpenAlPlaybackAndCatalogTest {

    @Test
    public void catalogListsOpenAlDevicesFirstThenJavaSoundLegacy() {
        List<OpenAlDevice> openAlDevices = Arrays.asList(
                new OpenAlDevice("OpenAL Soft on Sound Blaster AE-7", "OpenAL Soft on Sound Blaster AE-7"),
                new OpenAlDevice("OpenAL Soft on Speakers", "OpenAL Soft on Speakers"));
        AudioOutputDeviceCatalog catalog =
                new AudioOutputDeviceCatalog(fixedSource(openAlDevices), vlcUnavailable());

        List<AudioOutputDevice> all = catalog.findAll();

        assertTrue("expected the two OpenAL devices plus Java Sound entries", all.size() >= 3);
        assertEquals(AudioOutputDevice.Backend.OPENAL, all.get(0).getBackend());
        assertEquals("OpenAL Soft on Sound Blaster AE-7", all.get(0).getOpenAlSpecifier());
        assertEquals(AudioOutputDevice.Backend.OPENAL, all.get(1).getBackend());
        assertEquals("OpenAL Soft on Speakers", all.get(1).getOpenAlSpecifier());
        assertEquals("Java Sound system default follows the OpenAL devices",
                AudioOutputDevice.Backend.JAVA_SOUND, all.get(2).getBackend());
        assertTrue(all.get(2).isSystemDefault());
    }

    @Test
    public void catalogListsVlcFirstWhenAvailable() {
        AudioOutputDeviceCatalog catalog =
                new AudioOutputDeviceCatalog(fixedSource(new ArrayList<OpenAlDevice>()), vlcAvailable());
        List<AudioOutputDevice> all = catalog.findAll();
        assertEquals(AudioOutputDevice.Backend.VLC, all.get(0).getBackend());
        assertTrue(all.get(0).isVlc());
    }

    @Test
    public void catalogDegradesToJavaSoundOnlyWhenOpenAlAndVlcAreUnavailable() {
        AudioOutputDeviceCatalog catalog =
                new AudioOutputDeviceCatalog(fixedSource(new ArrayList<OpenAlDevice>()), vlcUnavailable());
        List<AudioOutputDevice> all = catalog.findAll();
        assertFalse(all.isEmpty());
        for (AudioOutputDevice device : all) {
            assertEquals("no OpenAL/VLC entries when those backends are unavailable",
                    AudioOutputDevice.Backend.JAVA_SOUND, device.getBackend());
        }
    }

    @Test
    public void playsExactlyTheSelectedOpenAlSpecifier() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        OpenAlAudioPreviewPlaybackService service = new OpenAlAudioPreviewPlaybackService(backend);
        AtomicReference<String> info = new AtomicReference<String>();
        service.setInfoHandler(capture(info));
        service.setOutputDevice(AudioOutputDevice.forOpenAl("OpenAL Soft on AE-7", "AE-7"));

        CountDownLatch finished = new CountDownLatch(1);
        service.play(new short[]{1, 2, 3, 4}, new PcmAudioFormat(44100, 2, 16), countDown(finished));

        assertTrue("playback completed", finished.await(2, TimeUnit.SECONDS));
        assertEquals("OpenAL Soft on AE-7", backend.lastSpecifier.get());
        assertEquals(2, backend.lastChannels.get());
        assertNotNull(info.get());
    }

    @Test
    public void failsWithoutFallbackWhenNoOpenAlDeviceIsSelected() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        OpenAlAudioPreviewPlaybackService service = new OpenAlAudioPreviewPlaybackService(backend);
        AtomicReference<String> error = new AtomicReference<String>();
        service.setErrorHandler(capture(error));
        // A Java Sound device must never be played through the OpenAL path.
        service.setOutputDevice(AudioOutputDevice.systemDefault());

        service.play(new short[]{1, 2}, new PcmAudioFormat(16000, 1, 16), null);
        waitUntilIdle(service);

        assertNull("no device was opened", backend.lastSpecifier.get());
        assertNotNull("failure reported", error.get());
        assertTrue(error.get().toLowerCase().contains("no other device"));
    }

    @Test
    public void reportsTheDeviceAndErrorCodesWhenOpeningFails() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        backend.failure = new OpenAlException("openDevice", "OpenAL Soft on AE-7", 0xA004, 0, "boom");
        OpenAlAudioPreviewPlaybackService service = new OpenAlAudioPreviewPlaybackService(backend);
        AtomicReference<String> error = new AtomicReference<String>();
        service.setErrorHandler(capture(error));
        service.setOutputDevice(AudioOutputDevice.forOpenAl("OpenAL Soft on AE-7", "AE-7"));

        service.play(new short[]{1, 2}, new PcmAudioFormat(48000, 2, 16), null);
        waitUntilIdle(service);

        assertNotNull(error.get());
        assertTrue(error.get().contains("openDevice"));
        assertTrue(error.get().contains("OpenAL Soft on AE-7"));
        assertTrue(error.get().contains("alcGetError"));
    }

    @Test
    public void dispatcherRoutesOpenAlDeviceToOpenAlAndSpareBackendForJavaSound() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        OpenAlAudioPreviewPlaybackService openAlService = new OpenAlAudioPreviewPlaybackService(backend);
        DispatchingAudioPreviewPlaybackService dispatcher =
                new DispatchingAudioPreviewPlaybackService(
                        new JavaSoundAudioPreviewPlaybackService(), openAlService,
                        new VlcSidecarPlaybackService(new VlcInstallation(), neverLaunches(),
                                new java.io.File(System.getProperty("java.io.tmpdir"), "askai-vlc-test")));

        // Java Sound / system default: the OpenAL backend must not be touched.
        dispatcher.setOutputDevice(AudioOutputDevice.systemDefault());
        dispatcher.play(new short[]{1, 2}, new PcmAudioFormat(16000, 1, 16), null);
        Thread.sleep(150);
        assertNull("OpenAL backend not used for a Java Sound device", backend.lastSpecifier.get());

        // OpenAL device: routed to the OpenAL backend with its exact specifier.
        dispatcher.setOutputDevice(AudioOutputDevice.forOpenAl("OpenAL Soft on AE-7", "AE-7"));
        CountDownLatch finished = new CountDownLatch(1);
        dispatcher.play(new short[]{1, 2, 3, 4}, new PcmAudioFormat(44100, 2, 16), countDown(finished));
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals("OpenAL Soft on AE-7", backend.lastSpecifier.get());
        dispatcher.stop();
    }

    // ------------------------------------------------------------------ helpers

    private static void waitUntilIdle(OpenAlAudioPreviewPlaybackService service) throws InterruptedException {
        for (int i = 0; i < 40 && service.isPlaying(); i++) {
            Thread.sleep(25);
        }
    }

    private static AudioOutputDeviceCatalog.OpenAlDeviceSource fixedSource(final List<OpenAlDevice> devices) {
        return new AudioOutputDeviceCatalog.OpenAlDeviceSource() {
            public List<OpenAlDevice> list() {
                return devices;
            }
        };
    }

    private static AudioOutputDeviceCatalog.VlcAvailability vlcAvailable() {
        return new AudioOutputDeviceCatalog.VlcAvailability() {
            public boolean isAvailable() {
                return true;
            }
        };
    }

    private static AudioOutputDeviceCatalog.VlcAvailability vlcUnavailable() {
        return new AudioOutputDeviceCatalog.VlcAvailability() {
            public boolean isAvailable() {
                return false;
            }
        };
    }

    private static java.util.function.Consumer<String> capture(final AtomicReference<String> sink) {
        return new java.util.function.Consumer<String>() {
            public void accept(String value) {
                sink.set(value);
            }
        };
    }

    private static VlcProcessLauncher neverLaunches() {
        return new VlcProcessLauncher() {
            public Handle start(java.util.List<String> command) throws java.io.IOException {
                throw new java.io.IOException("VLC launcher must not be used in this test.");
            }
        };
    }

    private static Runnable countDown(final CountDownLatch latch) {
        return new Runnable() {
            public void run() {
                latch.countDown();
            }
        };
    }

    /** Records the specifier/channels the service asked for; optionally throws to simulate an open failure. */
    private static final class RecordingBackend implements OpenAlAudioBackend {
        final AtomicReference<String> lastSpecifier = new AtomicReference<String>();
        final java.util.concurrent.atomic.AtomicInteger lastChannels =
                new java.util.concurrent.atomic.AtomicInteger();
        OpenAlException failure;

        public List<OpenAlDevice> listPlaybackDevices() {
            return new ArrayList<OpenAlDevice>();
        }

        public OpenAlPlaybackResult play(short[] interleaved, int channels, int sampleRateHz,
                                         String deviceSpecifier, OpenAlCancellation cancellation)
                throws OpenAlException {
            if (failure != null) {
                throw failure;
            }
            lastSpecifier.set(deviceSpecifier);
            lastChannels.set(channels);
            return new OpenAlPlaybackResult("fake", deviceSpecifier, channels, sampleRateHz,
                    10L, interleaved.length * 2, false);
        }
    }
}
