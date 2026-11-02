package com.aresstack.askai.batch.ui;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

/** The batch panel looks for a sibling .md with the same base name, next to the audio file. */
public class BatchTranscriptionPanelMarkdownTest {

    @Test
    public void mapsAudioToASiblingMarkdownWithTheSameBaseName() {
        File audio = new File("recordings" + File.separator + "sample-001.wav");
        File md = BatchTranscriptionPanel.markdownSiblingOf(audio);
        assertEquals("sample-001.md", md.getName());
        assertEquals(audio.getParentFile(), md.getParentFile());
    }

    @Test
    public void stripsOnlyTheLastExtension() {
        assertEquals("foo.bar.md",
                BatchTranscriptionPanel.markdownSiblingOf(new File("foo.bar.mp3")).getName());
    }

    @Test
    public void handlesANameWithoutAnExtension() {
        assertEquals("clip.md", BatchTranscriptionPanel.markdownSiblingOf(new File("clip")).getName());
    }
}
