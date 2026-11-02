package com.aresstack.askai.batch.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Structured upsert by model id + profile id: create, replace-in-place, order, legacy, prefix safety. */
public class MarkdownBatchTranscriptionDocumentEditorTest {

    private final BatchTranscriptionDocumentEditor editor = new MarkdownBatchTranscriptionDocumentEditor();

    private String upsert(String markdown, String modelId, String profileId, String profileName, String text) {
        return editor.upsertTranscription(markdown,
                new TranscriptionDocumentEntry(modelId, profileId, profileName, text));
    }

    @Test
    public void createsTheFirstModelAndProfile() {
        String out = upsert("", "gemma4:e2b", "crystal-voice", "Crystal voice", "hello");
        String expected = "# gemma4:e2b\n\n<!-- askai:model-id=gemma4:e2b -->\n\n"
                + "## Audio profile: Crystal voice\n\n<!-- askai:profile-id=crystal-voice -->\n\nhello\n";
        assertEquals(expected, out);
    }

    @Test
    public void addsASecondProfileUnderTheSameModel() {
        String out = upsert("", "gemma4:e2b", "crystal-voice", "Crystal voice", "a");
        out = upsert(out, "gemma4:e2b", "off", "Off", "b");
        assertEquals("one model heading", 1, countLines(out, "# gemma4:e2b"));
        assertEquals(1, countLines(out, "## Audio profile: Crystal voice"));
        assertEquals(1, countLines(out, "## Audio profile: Off"));
        assertTrue("profiles keep insertion order",
                out.indexOf("Crystal voice") < out.indexOf("## Audio profile: Off"));
    }

    @Test
    public void addsASecondModelAtTheEnd() {
        String out = upsert("", "gemma4:e2b", "off", "Off", "a");
        out = upsert(out, "whisper:latest", "off", "Off", "b");
        assertEquals(1, countLines(out, "# gemma4:e2b"));
        assertEquals(1, countLines(out, "# whisper:latest"));
        assertTrue(out.indexOf("# gemma4:e2b") < out.indexOf("# whisper:latest"));
    }

    @Test
    public void replacesAnExistingProfileWithShorterText() {
        String out = upsert("", "m", "off", "Off", "a very long transcription that should be fully removed");
        out = upsert(out, "m", "off", "Off", "short");
        assertFalse("old longer text is gone", out.contains("fully removed"));
        assertTrue(out.contains("\nshort\n"));
        assertEquals("no duplicate profile section", 1, countLines(out, "## Audio profile: Off"));
    }

    @Test
    public void replacesAnExistingProfileWithLongerText() {
        String out = upsert("", "m", "off", "Off", "short");
        out = upsert(out, "m", "off", "Off", "a much longer replacement text");
        assertTrue(out.contains("a much longer replacement text"));
        assertFalse(out.contains("\nshort\n"));
    }

    @Test
    public void leavesOtherProfilesUntouched() {
        String out = upsert("", "m", "off", "Off", "keep-off");
        out = upsert(out, "m", "clean", "Clean", "keep-clean");
        out = upsert(out, "m", "off", "Off", "new-off");
        assertTrue("other profile untouched", out.contains("keep-clean"));
        assertTrue(out.contains("new-off"));
        assertFalse(out.contains("keep-off"));
    }

    @Test
    public void leavesOtherModelsUntouched() {
        String out = upsert("", "m1", "off", "Off", "one");
        out = upsert(out, "m2", "off", "Off", "two");
        out = upsert(out, "m1", "off", "Off", "one-updated");
        assertTrue(out.contains("two"));
        assertTrue(out.contains("one-updated"));
        assertEquals(1, countLines(out, "# m2"));
    }

    @Test
    public void doesNotConfuseAModelIdWithASimilarPrefix() {
        String out = upsert("", "gemma4:e2b", "off", "Off", "a");
        out = upsert(out, "gemma4:e2b-extra", "off", "Off", "b");
        assertEquals(1, countLines(out, "# gemma4:e2b"));
        assertEquals(1, countLines(out, "# gemma4:e2b-extra"));
    }

    @Test
    public void doesNotConfuseAProfileIdWithASimilarPrefix() {
        String out = upsert("", "m", "crystal-voice", "Crystal voice", "a");
        out = upsert(out, "m", "crystal-voice-2", "Crystal voice 2", "b");
        assertEquals(1, countLines(out, "## Audio profile: Crystal voice"));
        assertEquals(1, countLines(out, "## Audio profile: Crystal voice 2"));
    }

    @Test
    public void updatesARenamedProfileViaItsStableId() {
        String out = upsert("", "m", "crystal-voice", "Crystal voice", "old");
        out = upsert(out, "m", "crystal-voice", "Crystal Voice HD", "new");
        assertEquals("no second section for the rename", 1, out.split("askai:profile-id=crystal-voice ", -1).length - 1);
        assertTrue("visible name updated", out.contains("## Audio profile: Crystal Voice HD"));
        assertFalse(out.contains("## Audio profile: Crystal voice\n"));
        assertTrue(out.contains("new"));
        assertFalse(out.contains("old"));
    }

    @Test
    public void recognizesALegacyDocumentWithoutIdComments() {
        String legacy = "# gemma4:e2b\n\n## Audio profile: Crystal voice\n\nold legacy text\n";
        String out = upsert(legacy, "gemma4:e2b", "crystal-voice", "Crystal voice", "fresh");
        assertEquals("legacy section matched, not duplicated", 1, countLines(out, "## Audio profile: Crystal voice"));
        assertEquals(1, countLines(out, "# gemma4:e2b"));
        assertTrue("id comments added on upsert", out.contains("<!-- askai:profile-id=crystal-voice -->"));
        assertTrue(out.contains("<!-- askai:model-id=gemma4:e2b -->"));
        assertTrue(out.contains("fresh"));
        assertFalse(out.contains("old legacy text"));
    }

    @Test
    public void updateDoesNotReorderExistingSections() {
        String out = upsert("", "m", "a", "A", "1");
        out = upsert(out, "m", "b", "B", "2");
        out = upsert(out, "m", "a", "A", "1-updated");
        assertTrue("A stays before B", out.indexOf("## Audio profile: A") < out.indexOf("## Audio profile: B"));
    }

    @Test
    public void preservesSpecialCharactersAndMarkdownInTheTranscription() {
        String tricky = "Spoken **words**, `code`, #hash and a fenced block:\n"
                + "```\n## Audio profile: NOT A HEADING\n# also not a model\n```\nend.";
        String out = upsert("", "m", "off", "Off", tricky);
        out = upsert(out, "m", "clean", "Clean", "x");

        assertEquals("the fake heading did not split the document", 1, countLines(out, "## Audio profile: Off"));
        assertEquals(1, countLines(out, "## Audio profile: Clean"));
        assertTrue("code block preserved verbatim", out.contains("## Audio profile: NOT A HEADING"));
        assertTrue(out.contains("# also not a model"));
        assertTrue(out.indexOf("NOT A HEADING") < out.indexOf("## Audio profile: Clean"));
    }

    @Test
    public void anEmptyTranscriptionYieldsAnEmptyProfileSectionWithNoStaleTail() {
        String out = upsert("", "m", "off", "Off", "some previous long text");
        out = upsert(out, "m", "off", "Off", "");
        assertFalse(out.contains("some previous long text"));
        assertTrue(out.contains("## Audio profile: Off"));
        assertTrue(out.contains("<!-- askai:profile-id=off -->"));
        // section ends right after the comment, no trailing body
        assertTrue(out.trim().endsWith("<!-- askai:profile-id=off -->"));
    }

    private static int countLines(String document, String exactLine) {
        int count = 0;
        for (String line : document.split("\n", -1)) {
            if (line.equals(exactLine)) {
                count++;
            }
        }
        return count;
    }
}
