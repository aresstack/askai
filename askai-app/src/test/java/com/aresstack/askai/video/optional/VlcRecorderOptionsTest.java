package com.aresstack.askai.video.optional;

import com.aresstack.askai.video.RecordingProfile;
import com.aresstack.askai.video.RecordingSource;
import com.aresstack.askai.video.VideoSettings;
import org.junit.Test;

import java.awt.Rectangle;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The VLC option/arg building — the settings-driven reference semantics, testable without VLC. */
public class VlcRecorderOptionsTest {

    private static RecordingProfile profile() {
        return RecordingProfile.builder()
                .source(RecordingSource.window(new Rectangle(11, 22, 641, 480), "w"))
                .outputFile(Paths.get("C:\\videos\\out.mp4"))
                .fps(20)
                .build();
    }

    private static String sout(String[] options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].startsWith(":sout=")) {
                return options[i];
            }
        }
        throw new AssertionError("no :sout option in " + Arrays.toString(options));
    }

    @Test
    public void crfModeBuildsAnX264EncoderWithPresetAndTune() {
        VideoSettings.Vlc s = new VideoSettings.Vlc();
        s.setQuality("crf");
        s.setCrf(18);
        s.setVencPreset("fast");
        s.setVencTune("animation");
        String sout = sout(VlcRecorder.buildOptions(profile(), s));
        assertTrue(sout, sout.contains("venc=x264{crf=18,preset=fast,tune=animation}"));
        assertTrue(sout, sout.contains("acodec=none"));
        assertTrue(sout, sout.contains(":std{access=file,mux=mp4,dst=C:/videos/out.mp4}"));
    }

    @Test
    public void bitrateModeUsesVbInsteadOfX264Crf() {
        VideoSettings.Vlc s = new VideoSettings.Vlc();
        s.setQuality("bitrate");
        s.setBitrateKbps(6000);
        String sout = sout(VlcRecorder.buildOptions(profile(), s));
        assertTrue(sout, sout.contains(",vb=6000k"));
        assertFalse(sout, sout.contains("venc=x264"));
    }

    @Test
    public void audioSettingTogglesTheAudioCodecChain() {
        VideoSettings.Vlc s = new VideoSettings.Vlc();
        s.setAudioEnabled(true);
        String sout = sout(VlcRecorder.buildOptions(profile(), s));
        assertTrue(sout, sout.contains("acodec=mp3,ab=128,channels=2,samplerate=44100"));
    }

    @Test
    public void theRegionComesFromTheProfileWithEvenDimensions() {
        VideoSettings.Vlc s = new VideoSettings.Vlc();
        List<String> options = Arrays.asList(VlcRecorder.buildOptions(profile(), s));
        assertTrue(options.contains(":screen-fps=20"));
        assertTrue(options.contains(":screen-left=11"));
        assertTrue(options.contains(":screen-top=22"));
        assertTrue(options.contains(":screen-width=640")); // 641 → even
        assertTrue(options.contains(":screen-height=480"));
    }

    @Test
    public void settingsRegionOverridesWinOverTheProfileBounds() {
        VideoSettings.Vlc s = new VideoSettings.Vlc();
        s.setScreenLeft(100);
        s.setScreenTop(200);
        s.setScreenWidth(800);
        s.setScreenHeight(600);
        List<String> options = Arrays.asList(VlcRecorder.buildOptions(profile(), s));
        assertTrue(options.contains(":screen-left=100"));
        assertTrue(options.contains(":screen-top=200"));
        assertTrue(options.contains(":screen-width=800"));
        assertTrue(options.contains(":screen-height=600"));
    }

    @Test
    public void deinterlaceFilterAndSoutExtrasAreAppended() {
        VideoSettings.Vlc s = new VideoSettings.Vlc();
        s.setDeinterlaceEnabled(true);
        s.setDeinterlaceMode("blend");
        s.setVideoFilter("croppadd");
        s.setSoutExtras("select=novideo");
        String[] options = VlcRecorder.buildOptions(profile(), s);
        List<String> list = Arrays.asList(options);
        assertTrue(list.contains(":deinterlace=1"));
        assertTrue(list.contains(":deinterlace-mode=blend"));
        assertTrue(list.contains(":video-filter=croppadd"));
        assertTrue(sout(options), sout(options).contains("},select=novideo:std{"));
    }

    @Test
    public void libvlcArgsFollowVerbosityAndLoggingSettings() {
        VideoSettings.Vlc quiet = new VideoSettings.Vlc();
        quiet.setVerbose(0);
        List<String> args = Arrays.asList(VlcRecorder.buildLibVlcArgs(quiet));
        assertTrue(args.contains("--quiet"));
        assertTrue(args.contains("--intf"));

        VideoSettings.Vlc logging = new VideoSettings.Vlc();
        logging.setVerbose(2);
        logging.setLogEnabled(true);
        logging.setLogPath("C:\\temp\\vlc.log");
        List<String> logArgs = Arrays.asList(VlcRecorder.buildLibVlcArgs(logging));
        assertTrue(logArgs.contains("--verbose=2"));
        assertTrue(logArgs.contains("--file-logging"));
        assertTrue(logArgs.contains("--logfile=C:/temp/vlc.log"));
    }

    @Test
    public void muxAndCodecSettingsReachTheSoutPipeline() {
        VideoSettings.Vlc s = new VideoSettings.Vlc();
        s.setMux("mkv");
        s.setVideoCodec("hevc");
        String sout = sout(VlcRecorder.buildOptions(profile(), s));
        assertTrue(sout, sout.contains("vcodec=hevc"));
        assertTrue(sout, sout.contains("mux=mkv"));
        assertEquals(-1, sout.indexOf('\\')); // dst is always forward-slashed for VLC
    }
}
