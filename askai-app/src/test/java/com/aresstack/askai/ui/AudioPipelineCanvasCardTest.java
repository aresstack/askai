package com.aresstack.askai.ui;

import com.aresstack.audio.pipeline.AudioProcessingProfiles;

import org.junit.Test;

import javax.swing.JLabel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The settings card lives inside the canvas and is placed under the selected block (no gap component). */
public class AudioPipelineCanvasCardTest {

    @Test
    public void cardIsHostedInsideTheCanvasBelowTheSelectedBlock() {
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        AudioInspectorCard card = new AudioInspectorCard(new JLabel("inspector"), AudioPipelineCanvas.BLOCK_WIDTH);
        canvas.setInspectorCard(card);
        canvas.setBlocks(AudioProcessingProfiles.defaultSpeech().getBlocks());
        canvas.setSelectedIndex(3);
        canvas.setSize(2400, 600);
        canvas.doLayout();

        // The card is a child of the canvas (so it scrolls with the pipeline), not a sibling below a scroll bar.
        assertSame(canvas, card.getParent());

        int center = canvas.selectedBlockCenterX();
        int expectedLeft = Math.max(0, center - AudioPipelineCanvas.BLOCK_WIDTH / 2);
        assertEquals("card is horizontally centered under the selected block", expectedLeft, card.getX());

        // The blocks sit at the top; the card is placed below the block row.
        assertTrue("card drops below the block row", card.getY() > 92);
    }

    @Test
    public void noSelectionKeepsTheCanvasAtTheBlockRowHeight() {
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        AudioInspectorCard card = new AudioInspectorCard(new JLabel("inspector"), AudioPipelineCanvas.BLOCK_WIDTH);
        canvas.setInspectorCard(card);
        canvas.setBlocks(AudioProcessingProfiles.defaultSpeech().getBlocks());
        canvas.setSelectedIndex(-1);

        // With nothing selected the canvas only needs the block row (no reserved card space).
        assertTrue(canvas.getPreferredSize().height <= 160);
    }
}
