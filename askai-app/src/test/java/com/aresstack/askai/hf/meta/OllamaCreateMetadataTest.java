package com.aresstack.askai.hf.meta;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The typed metadata plan only lets trusted, non-empty values reach the {@code /api/create} info block. */
public class OllamaCreateMetadataTest {

    @Test
    public void capabilitiesOnlyMatchesTheLegacyShape() {
        Map<String, Object> info = OllamaCreateMetadata
                .ofCapabilities(Arrays.asList("completion", "audio")).toInfoMap();
        assertEquals(Collections.singleton("capabilities"), info.keySet());
        assertEquals(Arrays.asList("completion", "audio"), info.get("capabilities"));
    }

    @Test
    public void emptyMetadataProducesNoInfo() {
        assertTrue(OllamaCreateMetadata.empty().toInfoMap().isEmpty());
        assertTrue(OllamaCreateMetadata.empty().isEmpty());
    }

    @Test
    public void highConfidenceFieldsAreEmitted() {
        OllamaCreateMetadata metadata = new OllamaCreateMetadata.Builder()
                .capabilities(Arrays.asList("completion"))
                .modelFamily(MetadataValue.high("qwen3", MetadataSource.CONFIG_JSON))
                .quantizationLevel(MetadataValue.high("Q4_K_M", MetadataSource.FILE_NAME))
                .contextLength(MetadataValue.high(32768, MetadataSource.CONFIG_JSON))
                .embeddingLength(MetadataValue.high(4096, MetadataSource.CONFIG_JSON))
                .build();
        Map<String, Object> info = metadata.toInfoMap();
        assertEquals("qwen3", info.get("model_family"));
        assertEquals("Q4_K_M", info.get("quantization_level"));
        assertEquals(32768, info.get("context_length"));
        assertEquals(4096, info.get("embedding_length"));
    }

    @Test
    public void topLevelB3FieldsAreExposedButNotUnderInfo() {
        OllamaCreateMetadata metadata = new OllamaCreateMetadata.Builder()
                .renderer(MetadataValue.high("gemma4", MetadataSource.REGISTRY))
                .parser(MetadataValue.high("gemma4", MetadataSource.REGISTRY))
                .requires(MetadataValue.of("0.5.0", MetadataSource.REGISTRY, Confidence.LOW))
                .build();
        assertEquals("gemma4", metadata.renderer());
        assertEquals("gemma4", metadata.parser());
        // Low-confidence requires is not trusted → omitted.
        assertEquals("", metadata.requires());
        // Top-level fields must never leak into the info block.
        assertFalse(metadata.toInfoMap().containsKey("renderer"));
        assertFalse(metadata.toInfoMap().containsKey("parser"));
    }

    @Test
    public void lowAndMediumValuesAreNotEmitted() {
        OllamaCreateMetadata metadata = new OllamaCreateMetadata.Builder()
                .modelFamily(MetadataValue.of("guess", MetadataSource.FILE_NAME, Confidence.LOW))
                .baseName(MetadataValue.of("maybe", MetadataSource.TAG, Confidence.MEDIUM))
                .build();
        assertTrue("uncertain values must never reach the wire", metadata.toInfoMap().isEmpty());
    }

    @Test
    public void blankAndNonPositiveValuesAreDropped() {
        OllamaCreateMetadata metadata = new OllamaCreateMetadata.Builder()
                .modelFamily(MetadataValue.high("   ", MetadataSource.REGISTRY))
                .contextLength(MetadataValue.high(0, MetadataSource.CONFIG_JSON))
                .embeddingLength(MetadataValue.high(-1, MetadataSource.CONFIG_JSON))
                .build();
        assertTrue(metadata.toInfoMap().isEmpty());
    }

    @Test
    public void moreAuthoritativeSourceOutranksOnConflict() {
        MetadataValue<String> fromConfig = MetadataValue.high("qwen3", MetadataSource.CONFIG_JSON);
        MetadataValue<String> fromTag = MetadataValue.high("qwen", MetadataSource.TAG);
        assertTrue(fromConfig.outranks(fromTag));
        assertFalse(fromTag.outranks(fromConfig));
        // Higher confidence beats a better source at lower confidence.
        MetadataValue<String> mediumConfig = MetadataValue.of("x", MetadataSource.CONFIG_JSON, Confidence.MEDIUM);
        assertTrue(fromTag.outranks(mediumConfig));
    }
}
