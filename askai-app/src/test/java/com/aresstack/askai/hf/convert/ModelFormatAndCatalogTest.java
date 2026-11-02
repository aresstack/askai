package com.aresstack.askai.hf.convert;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Phase-3 regression: filename→format detection and the bundled Ollama-architecture allowlist. */
public class ModelFormatAndCatalogTest {

    @Test
    public void detectsFormatsFromFileNames() {
        assertEquals(ModelFormat.GGUF, ModelFormat.fromFileName("model-Q4_K_M.gguf"));
        assertEquals(ModelFormat.SAFETENSORS, ModelFormat.fromFileName("model-00001-of-00002.safetensors"));
        assertEquals(ModelFormat.ONNX, ModelFormat.fromFileName("model.onnx"));
        assertEquals(ModelFormat.PYTORCH_BIN, ModelFormat.fromFileName("pytorch_model.bin"));
        // mmproj is an encoder companion, not a standalone model format.
        assertNull(ModelFormat.fromFileName("mmproj-model-f16.gguf"));
        assertNull(ModelFormat.fromFileName("config.json"));
        assertNull(ModelFormat.fromFileName("tokenizer.json"));
    }

    @Test
    public void architectureAllowlistLoadsFromResources() {
        assertTrue("allowlist should be non-empty", ArchitectureCatalog.supported().size() >= 10);
        assertTrue(ArchitectureCatalog.isSupported(java.util.Arrays.asList("Gemma3ForCausalLM")));
        assertTrue(ArchitectureCatalog.isSupported(java.util.Arrays.asList("Qwen2ForCausalLM")));
        assertTrue("case-insensitive", ArchitectureCatalog.isSupported(java.util.Arrays.asList("llamaforcausallm")));
        org.junit.Assert.assertFalse(ArchitectureCatalog.isSupported(java.util.Arrays.asList("MambaForCausalLM")));
    }
}
