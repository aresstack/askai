package com.aresstack.askai.vision;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Vision gating: only the exact "vision" capability lets a model receive image input. */
public class VisionCapabilityTest {

    @Test
    public void exactVisionCapabilityQualifies() {
        // devstral-small-2:24b reports completion, vision, tools — vision-positive.
        assertTrue(VisionCapability.isVisionCapable(Arrays.asList("completion", "vision", "tools")));
        assertTrue("case-insensitive", VisionCapability.isVisionCapable(Arrays.asList("Vision")));
        assertTrue("trimmed", VisionCapability.isVisionCapable(Arrays.asList("  vision  ")));
    }

    @Test
    public void plainTextModelIsNotVision() {
        assertFalse(VisionCapability.isVisionCapable(Arrays.asList("completion")));
        assertFalse(VisionCapability.isVisionCapable(Arrays.asList("completion", "tools")));
    }

    @Test
    public void audioMmprojMultimodalAreNeverVision() {
        assertFalse(VisionCapability.isVisionCapable(Arrays.asList("audio")));
        assertFalse(VisionCapability.isVisionCapable(Arrays.asList("mmproj")));
        assertFalse(VisionCapability.isVisionCapable(Arrays.asList("multimodal")));
        // A near-miss substring must not match.
        assertFalse(VisionCapability.isVisionCapable(Arrays.asList("vision-language")));
    }

    @Test
    public void emptyOrNullIsNotVision() {
        assertFalse(VisionCapability.isVisionCapable(Collections.<String>emptyList()));
        assertFalse(VisionCapability.isVisionCapable(null));
    }
}
