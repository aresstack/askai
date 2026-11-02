package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.PcmAudioFormat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The VLC sidecar plays through an external process, fails loudly when VLC is missing, and cleans up. */
public class VlcSidecarPlaybackServiceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void buildsAHeadlessSystemDefaultCommand() throws Exception {
        File vlc = folder.newFile("vlc.exe");
        File wav = folder.newFile("clip.wav");
        File config = new File(folder.getRoot(), "vlc.cfg");
        List<String> command = VlcSidecarPlaybackService.buildCommand(
                vlc, wav, config, AudioOutputDevice.vlcSystemDefault());

        assertEquals(vlc.getAbsolutePath(), command.get(0));
        assertTrue(command.contains("--intf=dummy"));
        assertTrue(command.contains("--no-video"));
        assertTrue(command.contains("--play-and-exit"));
        assertTrue(command.contains("--no-one-instance"));
        assertTrue(command.contains("--aout=mmdevice"));
        assertTrue(command.contains("--config=" + config.getAbsolutePath()));
        assertEquals("the WAV is the last argument", wav.getAbsolutePath(), command.get(command.size() - 1));
        // System-default slice: no explicit endpoint selection.
        for (String arg : command) {
            assertFalse(arg.startsWith("--mmdevice-audio-device="));
        }
    }

    @Test
    public void failsWithoutFallbackWhenVlcIsNotInstalled() {
        VlcInstallation missing = new VlcInstallation(scratchPrefs()) {
            public File resolve() {
                return null;
            }
        };
        RecordingLauncher launcher = new RecordingLauncher(0, "");
        VlcSidecarPlaybackService service =
                new VlcSidecarPlaybackService(missing, launcher, folder.getRoot());
        AtomicReference<String> error = new AtomicReference<String>();
        service.setErrorHandler(capture(error));

        service.play(new short[]{1, 2}, new PcmAudioFormat(16000, 1, 16), null);

        assertNull("VLC must not be launched when it is not installed", launcher.lastCommand);
        assertNotNull(error.get());
        assertTrue(error.get().toLowerCase().contains("vlc is not installed"));
    }

    @Test
    public void writesTempWavLaunchesVlcAndCleansUpOnSuccess() throws Exception {
        final File vlc = folder.newFile("vlc.exe");
        VlcInstallation installed = new VlcInstallation(scratchPrefs()) {
            public File resolve() {
                return vlc;
            }
        };
        RecordingLauncher launcher = new RecordingLauncher(0, "");
        File tempDir = folder.newFolder("vlc-temp");
        VlcSidecarPlaybackService service = new VlcSidecarPlaybackService(installed, launcher, tempDir);
        service.setOutputDevice(AudioOutputDevice.vlcSystemDefault());
        AtomicReference<String> info = new AtomicReference<String>();
        service.setInfoHandler(capture(info));

        CountDownLatch finished = new CountDownLatch(1);
        service.play(new short[]{1, 2, 3, 4}, new PcmAudioFormat(44100, 2, 16), countDown(finished));

        assertTrue("playback finished", finished.await(2, TimeUnit.SECONDS));
        assertNotNull("VLC was launched", launcher.lastCommand);
        assertTrue(launcher.lastWavExistedAtLaunch);
        assertNotNull(info.get());
        // Cleanup runs in the finally block after onFinished; wait for the worker to fully settle.
        for (int i = 0; i < 80 && service.isPlaying(); i++) {
            Thread.sleep(25);
        }
        // The temp WAV is deleted after playback (no leftovers in the temp dir).
        File[] leftovers = tempDir.listFiles();
        assertTrue("temp files cleaned up", leftovers == null || leftovers.length == 0);
    }

    @Test
    public void reportsNonZeroExitWithoutClaimingSuccess() throws Exception {
        final File vlc = folder.newFile("vlc.exe");
        VlcInstallation installed = new VlcInstallation(scratchPrefs()) {
            public File resolve() {
                return vlc;
            }
        };
        RecordingLauncher launcher = new RecordingLauncher(1, "some vlc error");
        VlcSidecarPlaybackService service =
                new VlcSidecarPlaybackService(installed, launcher, folder.newFolder("t2"));
        AtomicReference<String> error = new AtomicReference<String>();
        service.setErrorHandler(capture(error));

        service.play(new short[]{1, 2}, new PcmAudioFormat(16000, 1, 16), null);
        for (int i = 0; i < 80 && service.isPlaying(); i++) {
            Thread.sleep(25);
        }
        assertNotNull(error.get());
        assertTrue(error.get().contains("code 1"));
    }

    // ------------------------------------------------------------------ helpers

    private static Preferences scratchPrefs() {
        return Preferences.userRoot().node("com/aresstack/askai/audio/vlc-test");
    }

    private static java.util.function.Consumer<String> capture(final AtomicReference<String> sink) {
        return new java.util.function.Consumer<String>() {
            public void accept(String value) {
                sink.set(value);
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

    private static final class RecordingLauncher implements VlcProcessLauncher {
        private final int exitCode;
        private final String output;
        volatile List<String> lastCommand;
        volatile boolean lastWavExistedAtLaunch;

        RecordingLauncher(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public Handle start(List<String> command) {
            lastCommand = command;
            lastWavExistedAtLaunch = new File(command.get(command.size() - 1)).isFile();
            final InputStream stream = new ByteArrayInputStream(output.getBytes());
            return new Handle() {
                public InputStream mergedOutput() {
                    return stream;
                }

                public int awaitExit() {
                    return exitCode;
                }

                public void destroy() {
                    // nothing to do for the fake
                }
            };
        }
    }
}
