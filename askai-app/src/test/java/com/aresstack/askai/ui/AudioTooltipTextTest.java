package com.aresstack.askai.ui;

import com.aresstack.audio.pipeline.AudioBlockDescriptor;
import com.aresstack.audio.pipeline.AudioBlockRegistry;
import com.aresstack.audio.pipeline.AudioParameterDescriptor;
import com.aresstack.audio.profile.AudioBlockDefinition;
import com.aresstack.audio.profile.AudioBlockType;

import org.junit.Test;

import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verify the block-hover and inspector-setting tooltip presentation. */
public class AudioTooltipTextTest {

    @Test
    public void everyBlockAndParameterBuildsAnHtmlTooltip() {
        AudioBlockRegistry registry = AudioBlockRegistry.getInstance();
        for (AudioBlockType type : AudioBlockType.values()) {
            AudioBlockDescriptor descriptor = registry.descriptor(type);
            AudioBlockDefinition block = descriptor.createDefaultDefinition("test-" + type.name());
            assertHtml(type.name(), AudioTooltipText.block(block));
            assertHtml(type.name(), AudioTooltipText.blockType(type));
            List<AudioParameterDescriptor> parameters = descriptor.getParameters();
            for (int i = 0; i < parameters.size(); i++) {
                assertHtml(type + "." + parameters.get(i).getKey(),
                        AudioTooltipText.parameter(type, parameters.get(i)));
            }
        }
    }

    @Test
    public void canvasReturnsTheHoveredBlocksTooltip() {
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        AudioBlockDefinition block = AudioBlockRegistry.getInstance()
                .defaultDefinition(AudioBlockType.ADAPTIVE_NOISE_SUPPRESSION, "noise");
        canvas.setBlocks(Collections.singletonList(block));

        MouseEvent overFirstBlock = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0L, 0, 30, 30, 0, false);
        String tooltip = canvas.getToolTipText(overFirstBlock);

        assertNotNull(tooltip);
        assertTrue(tooltip.contains("Adaptive Noise Suppression"));
        assertTrue(tooltip.contains("background noise"));
    }

    @Test
    public void validationTooltipKeepsTheNormalSettingHelp() {
        AudioParameterDescriptor parameter = AudioBlockRegistry.getInstance()
                .descriptor(AudioBlockType.ADAPTIVE_NOISE_SUPPRESSION).getParameters().get(1);
        String help = AudioTooltipText.parameter(AudioBlockType.ADAPTIVE_NOISE_SUPPRESSION, parameter);
        String combined = AudioTooltipText.validation("Example validation problem", help);

        assertTrue(combined.contains("Example validation problem"));
        assertTrue(combined.contains("Maximum attenuation"));
        assertTrue(combined.contains("Default"));
    }

    private static void assertHtml(String subject, String tooltip) {
        assertNotNull("tooltip for " + subject, tooltip);
        assertTrue("html tooltip for " + subject, tooltip.startsWith("<html>"));
        assertTrue("non-empty tooltip for " + subject, tooltip.length() > 40);
    }
}
