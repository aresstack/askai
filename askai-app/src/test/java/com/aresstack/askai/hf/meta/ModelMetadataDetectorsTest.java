package com.aresstack.askai.hf.meta;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/** The safe, in-process detectors: family from a curated registry, quantization from the file name. */
public class ModelMetadataDetectorsTest {

    @Test
    public void familyRegistryMapsKnownTypes() {
        assertEquals("qwen3", OllamaModelFamilyRegistry.familyFor("qwen3"));
        assertEquals("qwen2", OllamaModelFamilyRegistry.familyFor("Qwen2"));
        assertEquals("qwen2", OllamaModelFamilyRegistry.familyFor("qwen2_moe"));
        assertEquals("gemma3", OllamaModelFamilyRegistry.familyFor("gemma3"));
        assertEquals("llama", OllamaModelFamilyRegistry.familyFor("llama"));
        assertEquals("phi3", OllamaModelFamilyRegistry.familyFor("phi3_5"));
    }

    @Test
    public void familyRegistryRejectsUnknownOrClassNames() {
        assertNull(OllamaModelFamilyRegistry.familyFor("Qwen2ForCausalLM"));
        assertNull(OllamaModelFamilyRegistry.familyFor("something-new"));
        assertNull(OllamaModelFamilyRegistry.familyFor(""));
        assertNull(OllamaModelFamilyRegistry.familyFor(null));
    }

    @Test
    public void familyValueKeepsTheInputSourceNotRegistry() {
        // The registry is a transformation, so the mapped family keeps its input's source (config.json),
        // not a synthetic "registry" source — otherwise it could not outrank a lower source.
        MetadataValue<String> value = OllamaModelFamilyRegistry.familyValue("gemma2", MetadataSource.CONFIG_JSON);
        assertEquals("gemma2", value.value());
        assertEquals(Confidence.HIGH, value.confidence());
        assertEquals(MetadataSource.CONFIG_JSON, value.source());
        assertNull(OllamaModelFamilyRegistry.familyValue("nope", MetadataSource.CONFIG_JSON));
    }

    @Test
    public void quantizationIsMatchedAsAWholeToken() {
        assertEquals("Q4_K_M", GgufQuantization.fromFileName("Qwen3-8B-Q4_K_M.gguf"));
        assertEquals("Q8_0", GgufQuantization.fromFileName("model.q8_0.gguf"));
        assertEquals("Q6_K", GgufQuantization.fromFileName("some-model-Q6_K.gguf"));
        assertEquals("IQ4_XS", GgufQuantization.fromFileName("Model-IQ4_XS.gguf"));
        assertEquals("F16", GgufQuantization.fromFileName("model-f16.gguf"));
    }

    @Test
    public void quantizationRejectsUnknownOrAbsent() {
        assertNull(GgufQuantization.fromFileName("model.gguf"));
        assertNull(GgufQuantization.fromFileName("weird-q9_z.gguf"));
        assertNull(GgufQuantization.fromFileName(null));
        // Must not match a substring of a larger token.
        assertNull(GgufQuantization.fromFileName("modelq4_k_mx.gguf"));
    }

    @Test
    public void quantizationFromFileNameIsMediumSoItIsNotSentUnconfirmed() {
        // A file name can be wrong/renamed and would override Ollama's own GGUF detection, so a name-only
        // quantization is MEDIUM (provenance) and must not reach /api/create on its own.
        MetadataValue<String> value = GgufQuantization.fromFileNameValue("m-Q5_K_M.gguf");
        assertEquals("Q5_K_M", value.value());
        assertEquals(Confidence.MEDIUM, value.confidence());
        assertEquals(MetadataSource.FILE_NAME, value.source());
        assertFalse("name-only quant must not be trusted for the wire", value.isTrusted(false));
    }
}
