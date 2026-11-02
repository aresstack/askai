package com.aresstack.askai.audio.preview;

import com.aresstack.audio.domain.AudioBuffer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression: an ffmpeg/Lavf-encoded MP3 (MPEG-1 Layer III, 32 kHz mono — the Common Voice profile) that
 * decodes to ZERO PCM bytes through the Java Sound mp3spi conversion must decode correctly through JLayer.
 * The fixture is a short slice of a CC0 Common Voice clip.
 */
public class Mp3AudioFileDecoderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void decodesAnFfmpegMp3ThatMp3spiCannotDecode() throws Exception {
        File mp3 = fixture("cv-sample.mp3");

        AudioBuffer buffer = new Mp3AudioFileDecoder().decode(mp3);

        assertEquals("original sample rate preserved", 32000, buffer.getFormat().getSampleRateHz());
        assertEquals("original channel count preserved", 1, buffer.getFormat().getChannels());
        assertEquals(16, buffer.getFormat().getBitsPerSample());
        assertTrue("non-empty PCM decoded (mp3spi yields 0 here)", buffer.getSamples().length > 10000);
    }

    @Test
    public void javaSoundDecoderRoutesMp3ThroughJLayer() throws Exception {
        File mp3 = fixture("cv-sample.mp3");

        AudioBuffer buffer = new JavaSoundAudioFileDecoder().decode(mp3);

        assertEquals(32000, buffer.getFormat().getSampleRateHz());
        assertEquals(1, buffer.getFormat().getChannels());
        assertTrue(buffer.getSamples().length > 10000);
    }

    @Test
    public void reportsAClearErrorForAMissingFile() {
        try {
            new Mp3AudioFileDecoder().decode(new File(folder.getRoot(), "nope.mp3"));
            fail("expected an IOException for a missing MP3");
        } catch (IOException expected) {
            // clear, non-technical failure
        }
    }

    private File fixture(String name) throws IOException {
        File target = folder.newFile(name);
        InputStream resource = getClass().getResourceAsStream("/audio/" + name);
        if (resource == null) {
            throw new IOException("Missing test resource: /audio/" + name);
        }
        try {
            Files.copy(resource, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            resource.close();
        }
        return target;
    }
}
