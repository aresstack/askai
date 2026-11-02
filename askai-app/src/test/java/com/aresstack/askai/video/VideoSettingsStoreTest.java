package com.aresstack.askai.video;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The typed video settings and their properties persistence (reference key names, safe defaults). */
public class VideoSettingsStoreTest {

    @Test
    public void defaultsMatchTheReferenceDefaults() {
        VideoSettings s = new VideoSettings();
        assertEquals("jcodec", s.getGeneral().getDefaultBackend());
        assertEquals(15, s.getGeneral().getFps());
        assertTrue(s.getVlc().isAutodetect());
        assertEquals("crf", s.getVlc().getQuality());
        assertEquals(23, s.getVlc().getCrf());
        assertEquals("mp4", s.getVlc().getMux());
        assertEquals("h264", s.getVlc().getVideoCodec());
        assertFalse(s.getVlc().isAudioEnabled());
        assertEquals("libx264", s.getFfmpeg().getCodecName());
        assertEquals("yuv420p", s.getFfmpeg().getPixelFormat());
        assertEquals("crf", s.getFfmpeg().getQualityMode());
    }

    @Test
    public void settingsRoundTripThroughThePropertiesFile() throws Exception {
        Path file = Files.createTempDirectory("video-settings-test").resolve("video-settings.properties");
        VideoSettingsStore store = new VideoSettingsStore(file);

        VideoSettings s = store.load(); // defaults when the file does not exist yet
        s.getGeneral().setDefaultBackend("vlc");
        s.getGeneral().setFps(30);
        s.getGeneral().setOutputDirectory("C:/Recordings");
        s.getVlc().setAutodetect(false);
        s.getVlc().setBasePath("C:/Tools/VLC");
        s.getVlc().setQuality("bitrate");
        s.getVlc().setBitrateKbps(8000);
        s.getVlc().setAudioEnabled(true);
        s.getVlc().setScreenFullscreen(true);
        s.getVlc().setVerbose(0);
        s.getFfmpeg().setContainer("matroska");
        s.getFfmpeg().setQualityMode("qscale");
        s.getFfmpeg().setQscale(5);
        s.getFfmpeg().setExtraOptions("movflags=+faststart\ng=60");
        store.save(s);

        VideoSettings reloaded = store.load();
        assertEquals("vlc", reloaded.getGeneral().getDefaultBackend());
        assertEquals(30, reloaded.getGeneral().getFps());
        assertEquals("C:/Recordings", reloaded.getGeneral().getOutputDirectory());
        assertFalse(reloaded.getVlc().isAutodetect());
        assertEquals("C:/Tools/VLC", reloaded.getVlc().getBasePath());
        assertEquals("bitrate", reloaded.getVlc().getQuality());
        assertEquals(8000, reloaded.getVlc().getBitrateKbps());
        assertTrue(reloaded.getVlc().isAudioEnabled());
        assertTrue(reloaded.getVlc().isScreenFullscreen());
        assertEquals(0, reloaded.getVlc().getVerbose());
        assertEquals("matroska", reloaded.getFfmpeg().getContainer());
        assertEquals("qscale", reloaded.getFfmpeg().getQualityMode());
        assertEquals(5, reloaded.getFfmpeg().getQscale());
        assertEquals("movflags=+faststart\ng=60", reloaded.getFfmpeg().getExtraOptions());
    }

    @Test
    public void aBrokenOrMissingFileYieldsDefaultsInsteadOfFailing() throws Exception {
        Path file = Files.createTempDirectory("video-settings-test").resolve("nope.properties");
        VideoSettingsStore store = new VideoSettingsStore(file);
        VideoSettings s = store.load();
        assertEquals("jcodec", s.getGeneral().getDefaultBackend());

        Files.write(file, "video.fps=notanumber\nvideo.vlc.crf=###".getBytes("UTF-8"));
        VideoSettings tolerant = store.load();
        assertEquals(15, tolerant.getGeneral().getFps());
        assertEquals(23, tolerant.getVlc().getCrf());
    }
}
