package com.aresstack.askai.batch.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The writer upserts through the editor and writes atomically, leaving the file intact on failure. */
public class BatchMarkdownResultWriterTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void upsertsIntoASourceAdjacentMarkdownFile() throws Exception {
        BatchMarkdownResultWriter writer = new BatchMarkdownResultWriter();
        File audio = new File(folder.getRoot(), "clip.wav");

        File md = writer.append(audio, "gemma4:e2b", "off", "Off", "first");
        writer.append(audio, "gemma4:e2b", "crystal-voice", "Crystal voice", "second");

        assertEquals(new File(folder.getRoot(), "clip.md"), md);
        String content = read(md);
        assertEquals("single model heading, not appended twice", 1, count(content, "\n# gemma4:e2b\n", "# gemma4:e2b\n"));
        assertTrue(content.contains("## Audio profile: Off"));
        assertTrue(content.contains("## Audio profile: Crystal voice"));
        assertTrue(content.contains("first"));
        assertTrue(content.contains("second"));
    }

    @Test
    public void atomicWriteFailureKeepsTheOriginalFileUnchanged() throws Exception {
        File audio = new File(folder.getRoot(), "clip.wav");
        new BatchMarkdownResultWriter().append(audio, "m", "off", "Off", "original content");
        File md = new File(folder.getRoot(), "clip.md");
        String before = read(md);

        BatchMarkdownResultWriter failing = new BatchMarkdownResultWriter(
                new BatchTranscriptionDocumentEditor() {
                    public String upsertTranscription(String markdown, TranscriptionDocumentEntry entry) {
                        throw new RuntimeException("boom");
                    }
                });
        try {
            failing.append(audio, "m", "clean", "Clean", "should never be written");
            fail("expected the failing editor to abort the write");
        } catch (RuntimeException expected) {
            // the write must not have happened
        }

        assertEquals("the existing document is untouched on failure", before, read(md));
        assertTrue(read(md).contains("original content"));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** Count how many stand-alone model headings the document has (start-of-file or after a blank line). */
    private static int count(String content, String midForm, String startForm) {
        int total = indexCount(content, midForm);
        if (content.startsWith(startForm)) {
            total++;
        }
        return total;
    }

    private static int indexCount(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + 1;
        }
    }
}
