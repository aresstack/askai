package com.aresstack.askai.hf.meta;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The create profiles are a small, tested per-family map; unknown families get nothing. */
public class OllamaCreateProfileRegistryTest {

    @Test
    public void gemma4UsesTheGemma4RendererAndParser() {
        OllamaCreateProfile profile = OllamaCreateProfileRegistry.profileFor("gemma4");
        assertEquals("gemma4", profile.renderer());
        assertEquals("gemma4", profile.parser());
        assertEquals("", profile.requires());
    }

    @Test
    public void otherKnownFamiliesLeaveRendererParserToOllama() {
        for (String family : new String[] {"qwen3", "mistral", "llama"}) {
            OllamaCreateProfile profile = OllamaCreateProfileRegistry.profileFor(family);
            assertEquals("", profile.renderer());
            assertEquals("", profile.parser());
            assertEquals("", profile.requires());
        }
    }

    @Test
    public void unknownFamilyHasNoProfile() {
        assertNull(OllamaCreateProfileRegistry.profileFor("something-else"));
        assertNull(OllamaCreateProfileRegistry.profileFor(null));
    }
}
