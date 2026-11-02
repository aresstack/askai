package com.aresstack.askai.hf;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** vision/audio capabilities must be proven by the installed GGUF files, not merely declared. */
public class RuntimeCapabilitiesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void visionSurvivesOnlyWhenAVisionProjectorIsIncluded() throws IOException {
        Map<String, Object> language = new LinkedHashMap<String, Object>();
        language.put("general.architecture", "qwen3");
        File main = writeGguf("model.gguf", language);

        Map<String, Object> projector = new LinkedHashMap<String, Object>();
        projector.put("general.architecture", "clip");
        projector.put("clip.has_vision_encoder", Boolean.TRUE);
        File encoder = writeGguf("encoder.gguf", projector);

        RuntimeCapabilities runtime = RuntimeCapabilities.fromFiles(main, Collections.singletonList(encoder));
        assertTrue(runtime.hasVision());
        assertFalse(runtime.hasAudio());
        assertEquals(Arrays.asList("completion", "vision"),
                runtime.intersect(Arrays.asList("completion", "vision", "audio")));
    }

    @Test
    public void withoutAnyEncoderVisionAndAudioAreDropped() throws IOException {
        Map<String, Object> language = new LinkedHashMap<String, Object>();
        language.put("general.architecture", "llama");
        File main = writeGguf("model.gguf", language);

        // No companion encoder (e.g. the projector dialog was cancelled).
        RuntimeCapabilities runtime = RuntimeCapabilities.fromFiles(main, Collections.<File>emptyList());
        assertFalse(runtime.hasVision());
        assertFalse(runtime.hasAudio());
        assertEquals(Arrays.asList("completion"),
                runtime.intersect(Arrays.asList("completion", "vision", "audio")));
    }

    @Test
    public void audioProjectorKeepsAudioButNotVision() throws IOException {
        Map<String, Object> projector = new LinkedHashMap<String, Object>();
        projector.put("general.architecture", "mtmd");
        projector.put("clip.has_audio_encoder", Boolean.TRUE);
        File encoder = writeGguf("mmproj.gguf", projector);

        RuntimeCapabilities runtime = RuntimeCapabilities.fromFiles(null, Collections.singletonList(encoder));
        assertTrue(runtime.hasAudio());
        assertFalse(runtime.hasVision());
        assertEquals(Arrays.asList("completion", "audio"),
                runtime.intersect(Arrays.asList("completion", "vision", "audio")));
    }

    // -------------------------------------------------------------------- minimal GGUF writer

    private File writeGguf(String name, Map<String, Object> meta) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'G', 'G', 'U', 'F'});
        writeU32(out, 3);
        writeU64(out, 0);
        writeU64(out, meta.size());
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            writeString(out, entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                writeU32(out, 7); // BOOL
                out.write(((Boolean) value).booleanValue() ? 1 : 0);
            } else {
                writeU32(out, 8); // STRING
                writeString(out, String.valueOf(value));
            }
        }
        File file = folder.newFile(name);
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(out.toByteArray());
        } finally {
            fos.close();
        }
        return file;
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(Charset.forName("UTF-8"));
        writeU64(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 4; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }
}
