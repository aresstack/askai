package com.aresstack.askai.batch.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Legacy batch documents (duplicate H1 model blocks from the old append-only writer, empty or literal
 * "null" profile ids) are normalized into canonical shape on the next upsert: one section per stable model
 * id, stable built-in profile ids, no invalid ids, no lost transcription. The writer refuses to serialize
 * invalid identities. Conflict rule (documented + tested): same identity → the LAST fully read section
 * wins, at the FIRST occurrence's position; distinct profiles keep their order.
 */
public class BatchMarkdownDocumentNormalizationTest {

    private final BatchTranscriptionDocumentEditor editor = new MarkdownBatchTranscriptionDocumentEditor();

    /** The observed real-world corrupted state: three H1s for one model, empty and "null" profile ids. */
    private static final String CORRUPTED =
            "# gemma4:e2b\n\n<!-- askai:model-id=gemma4:e2b -->\n\n"
            + "## Audio profile: Crystal voice\n\n<!-- askai:profile-id=crystal-voice -->\n\n"
            + "Crystal transcription text.\n\n"
            + "## Audio profile: Default speech\n\n<!-- askai:profile-id=default-speech -->\n\n"
            + "Default transcription old.\n\n"
            + "## Audio profile: Off\n\n<!-- askai:profile-id=off -->\n\n"
            + "Off transcription old.\n\n"
            + "# gemma4:e2b\n\n<!-- askai:model-id=gemma4:e2b -->\n\n"
            + "## Audio profile: Default speech\n\n<!-- askai:profile-id= -->\n\n"
            + "Default transcription newer.\n\n"
            + "# gemma4:e2b\n\n<!-- askai:model-id=gemma4:e2b -->\n\n"
            + "## Audio profile: Off\n\n<!-- askai:profile-id=null -->\n\n"
            + "Off transcription newer.\n";

    @Test
    public void theObservedCorruptedFileIsFullyNormalizedByOneUpsert() {
        String out = editor.upsertTranscription(CORRUPTED, new TranscriptionDocumentEntry(
                "gemma4:e2b", "crystal-voice", "Crystal voice", "Crystal transcription fresh."));

        assertEquals("exactly one model heading", 1, countLines(out, "# gemma4:e2b"));
        assertEquals("exactly one model id comment", 1,
                countOccurrences(out, "<!-- askai:model-id=gemma4:e2b -->"));

        assertEquals(1, countLines(out, "## Audio profile: Crystal voice"));
        assertEquals(1, countLines(out, "## Audio profile: Default speech"));
        assertEquals(1, countLines(out, "## Audio profile: Off"));

        assertEquals(1, countOccurrences(out, "<!-- askai:profile-id=crystal-voice -->"));
        assertEquals(1, countOccurrences(out, "<!-- askai:profile-id=default-speech -->"));
        assertEquals(1, countOccurrences(out, "<!-- askai:profile-id=off -->"));
        assertFalse("no empty id survives", out.contains("profile-id= -->"));
        assertFalse("no literal null id survives", out.contains("profile-id=null"));

        assertTrue("upserted body applied", out.contains("Crystal transcription fresh."));
        assertTrue("last duplicate wins (Default speech)", out.contains("Default transcription newer."));
        assertFalse(out.contains("Default transcription old."));
        assertTrue("last duplicate wins (Off)", out.contains("Off transcription newer."));
        assertFalse(out.contains("Off transcription old."));
    }

    @Test
    public void normalizationIsIdempotentAcrossRepeatedRuns() {
        String once = editor.upsertTranscription(CORRUPTED, new TranscriptionDocumentEntry(
                "gemma4:e2b", "crystal-voice", "Crystal voice", "same text"));
        String twice = editor.upsertTranscription(once, new TranscriptionDocumentEntry(
                "gemma4:e2b", "crystal-voice", "Crystal voice", "same text"));
        assertEquals("repeated runs do not change the structure", once, twice);
    }

    @Test
    public void aRunAfterNormalizationExtendsInsteadOfDuplicating() {
        String out = editor.upsertTranscription(CORRUPTED, new TranscriptionDocumentEntry(
                "gemma4:e2b", "off", "Off", "Off updated"));
        // a new profile joins the same (single) model block
        out = editor.upsertTranscription(out, new TranscriptionDocumentEntry(
                "gemma4:e2b", "user-profile", "My user profile", "user text"));
        // a new model gets exactly one new H1
        out = editor.upsertTranscription(out, new TranscriptionDocumentEntry(
                "whisper:latest", "off", "Off", "whisper text"));

        assertEquals(1, countLines(out, "# gemma4:e2b"));
        assertEquals(1, countLines(out, "# whisper:latest"));
        assertEquals(1, countLines(out, "## Audio profile: My user profile"));
        assertEquals("Off exists under both models", 2, countLines(out, "## Audio profile: Off"));
        assertTrue(out.contains("user text"));
        assertTrue(out.contains("whisper text"));
        assertTrue(out.contains("Off updated"));
    }

    @Test
    public void legacyBuiltInSectionsWithoutAnyIdCommentAreMigrated() {
        String legacy = "# m\n\n## Audio profile: Off\n\nlegacy off text\n\n"
                + "## Audio profile: Default speech\n\nlegacy default text\n";
        String out = editor.upsertTranscription(legacy, new TranscriptionDocumentEntry(
                "m", "off", "Off", "new off text"));
        assertTrue("Off migrated to its stable id", out.contains("<!-- askai:profile-id=off -->"));
        assertTrue("Default speech migrated without being touched",
                out.contains("<!-- askai:profile-id=default-speech -->"));
        assertTrue("untouched legacy transcription preserved", out.contains("legacy default text"));
        assertEquals("migrated Off matched by id, not duplicated", 1, countLines(out, "## Audio profile: Off"));
    }

    @Test
    public void anUnknownUserProfileWithoutIdIsNeverGuessed() {
        String legacy = "# m\n\n## Audio profile: My custom setup\n\ncustom text\n";
        String out = editor.upsertTranscription(legacy, new TranscriptionDocumentEntry(
                "m", "off", "Off", "off text"));
        assertTrue("custom section kept verbatim", out.contains("## Audio profile: My custom setup"));
        assertTrue(out.contains("custom text"));
        assertFalse("no invented id for the custom profile",
                out.contains("profile-id=my-custom") || out.contains("profile-id=My custom"));
        // it gains its real id only when it is itself upserted with one
        out = editor.upsertTranscription(out, new TranscriptionDocumentEntry(
                "m", "user-1", "My custom setup", "custom updated"));
        assertEquals(1, countLines(out, "## Audio profile: My custom setup"));
        assertTrue(out.contains("<!-- askai:profile-id=user-1 -->"));
    }

    // ------------------------------------------------------------------ writer id contract

    @Test
    public void theWriterRejectsInvalidProfileIds() {
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
        assertRejected("null");
        assertRejected("NULL");
    }

    @Test
    public void theWriterRejectsBlankModelIds() {
        try {
            new TranscriptionDocumentEntry(" ", "off", "Off", "x");
            fail("blank modelId must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private static void assertRejected(String profileId) {
        try {
            new TranscriptionDocumentEntry("m", profileId, "Off", "x");
            fail("invalid profileId must be rejected: '" + profileId + "'");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int countLines(String document, String exactLine) {
        int count = 0;
        for (String line : document.split("\n", -1)) {
            if (line.equals(exactLine)) {
                count++;
            }
        }
        return count;
    }

    private static int countOccurrences(String document, String needle) {
        return document.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
