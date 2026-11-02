package com.aresstack.askai.tts;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/** Text preparation (piper is line-oriented) and the voice-config sample-rate read. */
public class PiperSpeechSynthesizerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void blankLinesAreDroppedAndCrlfNormalized() {
        assertEquals("Erste Zeile.\nZweite Zeile.",
                PiperSpeechSynthesizer.prepareText("Erste Zeile.\r\n\r\n  \r\nZweite Zeile.\r\n"));
        assertEquals("", PiperSpeechSynthesizer.prepareText(null));
        assertEquals("", PiperSpeechSynthesizer.prepareText("  \n \r\n "));
    }

    @Test
    public void sampleRateComesFromTheVoiceConfig() throws Exception {
        Path config = temp.getRoot().toPath().resolve("voice.onnx.json");
        Files.write(config, "{\"audio\": {\"sample_rate\": 16000}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(16000, PiperSpeechSynthesizer.readSampleRate(config));
    }

    @Test
    public void aMissingOrBrokenConfigFallsBackToPipersUsualRate() {
        assertEquals(22050, PiperSpeechSynthesizer.readSampleRate(
                temp.getRoot().toPath().resolve("missing.json")));
    }
}
