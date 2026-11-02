package com.aresstack.askai.ui;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Canonical HF-capability → Ollama-tag mapping (the install contract) and tooltip text. */
public class ModelCapabilityTest {

    @Test
    public void textAudioMapToCompletionAudio() {
        List<String> tags = ModelCapability.requiredOllamaTags(
                EnumSet.of(ModelCapability.TEXT, ModelCapability.AUDIO));
        assertEquals(Arrays.asList("completion", "audio"), tags);
    }

    @Test
    public void textVisionMapToCompletionVision() {
        List<String> tags = ModelCapability.requiredOllamaTags(
                EnumSet.of(ModelCapability.TEXT, ModelCapability.VISION));
        assertEquals(Arrays.asList("completion", "vision"), tags);
    }

    @Test
    public void cloudHasNoLocalTagAndIsDropped() {
        assertEquals("", ModelCapability.CLOUD.getOllamaCapabilityTag());
        assertTrue(ModelCapability.requiredOllamaTags(EnumSet.of(ModelCapability.CLOUD)).isEmpty());
    }

    @Test
    public void completionAndTextBothMapToText() {
        assertEquals(ModelCapability.TEXT, ModelCapability.fromOllamaTag("completion"));
        assertEquals(ModelCapability.TEXT, ModelCapability.fromOllamaTag("text"));
        assertEquals(ModelCapability.AUDIO, ModelCapability.fromOllamaTag("audio"));
    }

    @Test
    public void thinkingAndToolsAreDetectedByExactTag() {
        assertEquals(ModelCapability.THINKING, ModelCapability.fromOllamaTag("thinking"));
        assertEquals(ModelCapability.TOOLS, ModelCapability.fromOllamaTag("tools"));
        java.util.Set<ModelCapability> caps = ModelCapability.fromOllamaTags(
                Arrays.asList("completion", "thinking", "tools"));
        assertTrue(caps.contains(ModelCapability.THINKING));
        assertTrue(caps.contains(ModelCapability.TOOLS));
    }

    @Test
    public void insertAndImageAreFullyMapped() {
        assertEquals(ModelCapability.INSERT, ModelCapability.fromOllamaTag("insert"));
        assertEquals(ModelCapability.IMAGE, ModelCapability.fromOllamaTag("image"));
        assertEquals("insert", ModelCapability.INSERT.getOllamaCapabilityTag());
        assertEquals("image", ModelCapability.IMAGE.getOllamaCapabilityTag());
        // Both survive requiredOllamaTags and the installer normalizer.
        assertEquals(Arrays.asList("insert"), ModelCapability.requiredOllamaTags(
                java.util.EnumSet.of(ModelCapability.INSERT)));
        assertEquals(Arrays.asList("image"),
                com.aresstack.askai.service.RemoteGgufInstaller.normalizeCapabilities(
                        Arrays.asList("image")));
    }

    @Test
    public void cloudIsNeverSentToApiCreate() {
        assertEquals("", ModelCapability.CLOUD.getOllamaCapabilityTag());
        assertTrue(com.aresstack.askai.service.RemoteGgufInstaller.normalizeCapabilities(
                Arrays.asList("cloud")).isEmpty());
    }

    @Test
    public void duplicatesAreRemovedFromRequiredTags() {
        List<String> tags = ModelCapability.requiredOllamaTags(
                EnumSet.of(ModelCapability.TEXT, ModelCapability.AUDIO));
        // A set has no duplicates, and the mapping keeps a single tag per capability in enum order.
        assertEquals(Arrays.asList("completion", "audio"), tags);
    }

    @Test
    public void perCapabilityTags() {
        assertEquals("completion", ModelCapability.TEXT.getOllamaCapabilityTag());
        assertEquals("audio", ModelCapability.AUDIO.getOllamaCapabilityTag());
        assertEquals("vision", ModelCapability.VISION.getOllamaCapabilityTag());
        assertEquals("tools", ModelCapability.TOOLS.getOllamaCapabilityTag());
        assertEquals("thinking", ModelCapability.THINKING.getOllamaCapabilityTag());
        assertEquals("embedding", ModelCapability.EMBEDDING.getOllamaCapabilityTag());
    }

}
