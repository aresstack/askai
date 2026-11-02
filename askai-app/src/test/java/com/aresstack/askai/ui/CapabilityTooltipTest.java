package com.aresstack.askai.ui;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Central capability tooltip texts and the per-slot icon mapping used for hover tooltips. */
public class CapabilityTooltipTest {

    @Test
    public void tooltipTextIsCentralAndDescriptive() {
        assertEquals("Audio — accepts audio input", ModelCapability.AUDIO.tooltipLine());
        String html = ModelCapability.tooltipHtml(EnumSet.of(ModelCapability.TEXT, ModelCapability.AUDIO));
        assertTrue(html, html.contains("Text — accepts text input"));
        assertTrue(html, html.contains("Audio — accepts audio input"));
    }

    @Test
    public void slotMappingResolvesTheHoveredCapability() {
        EnumSet<ModelCapability> caps = EnumSet.of(ModelCapability.TEXT, ModelCapability.AUDIO);
        // Strip order is enum order: slot 0 = TEXT (x 0..15), gap, slot 1 = AUDIO (x 18..33).
        assertEquals(ModelCapability.TEXT, CapabilityIcons.capabilityAt(caps, 2));
        assertEquals(ModelCapability.AUDIO, CapabilityIcons.capabilityAt(caps, 20));
        assertNull(CapabilityIcons.capabilityAt(caps, 16));   // in the gap
        assertNull(CapabilityIcons.capabilityAt(caps, 500));  // past the strip
    }
}
