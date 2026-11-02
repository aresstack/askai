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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Content-based GGUF inspection: proves projector-ness and modalities from the header, not the file name. */
public class GgufFileTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final int TYPE_UINT32 = 4;
    private static final int TYPE_BOOL = 7;
    private static final int TYPE_STRING = 8;

    @Test
    public void detectsVisionProjectorFromEncoderFlagRegardlessOfFileName() throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "clip");
        meta.put("general.type", "projector");
        meta.put("clip.has_vision_encoder", Boolean.TRUE);
        meta.put("clip.projector_type", "mlp");

        // File name deliberately does NOT contain "mmproj" — detection must rely on content.
        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("encoder-part-1.gguf", meta));

        assertTrue(info.isProjector());
        assertTrue(info.hasVisionEncoder());
        assertFalse(info.hasAudioEncoder());
        assertEquals("clip", info.architecture());
        assertEquals("mlp", info.projectorType());
        assertEquals("vision", info.projectorKind());
    }

    @Test
    public void detectsAudioAndVisionProjector() throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "mtmd");
        meta.put("clip.has_vision_encoder", Boolean.TRUE);
        meta.put("clip.has_audio_encoder", Boolean.TRUE);

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("mmproj.gguf", meta));

        assertTrue(info.isProjector());
        assertTrue(info.hasVisionEncoder());
        assertTrue(info.hasAudioEncoder());
        assertEquals("vision+audio", info.projectorKind());
    }

    @Test
    public void projectorTypeAloneIsNotEnough() throws IOException {
        // A bare clip file with only a projector_type and no encoder flag / block count is NOT accepted as
        // an attachable projector (Ollama-aligned: projector_type alone is insufficient).
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "clip");
        meta.put("clip.projector_type", "ldpv2");

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("proj.gguf", meta));

        assertFalse(info.isProjector());
        assertTrue(info.modalityCapabilities().isEmpty());
    }

    @Test
    public void clipWithVisionEncoderFlagAndNoMainBlocksIsAVisionProjector() throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "clip");
        meta.put("clip.has_vision_encoder", Boolean.TRUE);

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("proj.gguf", meta));

        assertTrue(info.isProjector());
        assertTrue(info.hasVisionEncoder());
        assertEquals(Arrays.asList("vision"), info.modalityCapabilities());
    }

    @Test
    public void integratedMultimodalModelWithMainBlocksIsNotAProjector() throws IOException {
        // An integrated multimodal model has main transformer blocks (> 0). Even with vision metadata it
        // must never be treated as an attachable projector.
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "gemma3");
        meta.put("gemma3.block_count", 34);
        meta.put("clip.has_vision_encoder", Boolean.TRUE);
        meta.put("clip.vision.block_count", 27);

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("model.gguf", meta));

        assertFalse(info.isProjector());
        assertTrue(info.modalityCapabilities().isEmpty());
    }

    @Test
    public void audioBlockCountAloneProvesAnAudioProjector() throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "clip");
        meta.put("clip.audio.block_count", 24);

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("mmproj.gguf", meta));

        assertTrue(info.isProjector());
        assertTrue(info.hasAudioEncoder());
        assertFalse(info.hasVisionEncoder()); // audio signal present → not defaulted to vision
        assertEquals(24L, info.audioBlockCount());
        assertEquals(Arrays.asList("audio"), info.modalityCapabilities());
    }

    @Test
    public void acceptedProjectorAlwaysReportsAtLeastOneModality() throws IOException {
        // The classification that accepts a projector and the one that reports its modalities are the same:
        // isProjector() ⟺ (vision or audio), so an accepted projector is never wirkungslos.
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.type", "mmproj");

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("x.gguf", meta));

        assertTrue(info.isProjector());
        assertTrue(!info.modalityCapabilities().isEmpty());
    }

    @Test
    public void templateCapabilitiesComeFromTheGgufsOwnBakedInTemplate() throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "qwen3");
        meta.put("tokenizer.chat_template",
                "{% if tool_calls %}...{% endif %}<think>{{ reasoning_content }}</think> <|fim_prefix|>");

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("model.gguf", meta));

        assertEquals(Arrays.asList("tools", "thinking", "insert"), info.templateCapabilities());
        assertFalse(info.isProjector());
    }

    @Test
    public void plainTemplateYieldsNoTemplateCapabilities() throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "llama");
        meta.put("tokenizer.chat_template", "{% for m in messages %}{{ m.role }}: {{ m.content }}{% endfor %}");

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("model.gguf", meta));

        assertTrue(info.templateCapabilities().isEmpty());
    }

    @Test
    public void aPlainLanguageModelIsNotAProjector() throws IOException {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("general.architecture", "qwen3");
        meta.put("general.type", "model");
        meta.put("general.name", "Qwen3 8B");

        GgufFile.GgufInfo info = GgufFile.inspect(writeGguf("model-Q4_K_M.gguf", meta));

        assertFalse(info.isProjector());
        assertFalse(info.hasVisionEncoder());
        assertFalse(info.hasAudioEncoder());
        assertEquals("qwen3", info.architecture());
    }

    // -------------------------------------------------------------------- minimal GGUF writer

    private File writeGguf(String name, Map<String, Object> meta) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'G', 'G', 'U', 'F'});
        writeU32(out, 3);           // version
        writeU64(out, 0);           // tensor count
        writeU64(out, meta.size()); // kv count
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            writeString(out, entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                writeU32(out, TYPE_BOOL);
                out.write(((Boolean) value).booleanValue() ? 1 : 0);
            } else if (value instanceof Integer) {
                writeU32(out, TYPE_UINT32);
                writeU32(out, ((Integer) value).intValue());
            } else {
                writeU32(out, TYPE_STRING);
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
