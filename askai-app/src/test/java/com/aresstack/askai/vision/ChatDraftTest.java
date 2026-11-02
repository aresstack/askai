package com.aresstack.askai.vision;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The composer draft carries text and attachments as separate, defensively-copied structures. */
public class ChatDraftTest {

    private static final ImageAttachment IMAGE = ImageAttachment.of(new File("shot.png"));

    @Test
    public void textOnlyDraft() {
        ChatDraft draft = new ChatDraft("hello", Collections.<ImageAttachment>emptyList());
        assertTrue(draft.hasText());
        assertFalse(draft.hasAttachments());
        assertFalse(draft.isEmpty());
    }

    @Test
    public void imageOnlyDraftIsNotEmpty() {
        ChatDraft draft = new ChatDraft("   ", Collections.singletonList(IMAGE));
        assertFalse("blank text does not count as text", draft.hasText());
        assertTrue(draft.hasAttachments());
        assertFalse("an image alone is still sendable", draft.isEmpty());
    }

    @Test
    public void blankAndNoAttachmentsIsEmpty() {
        assertTrue(new ChatDraft("  \n ", Collections.<ImageAttachment>emptyList()).isEmpty());
        assertTrue(new ChatDraft(null, null).isEmpty());
    }

    @Test
    public void attachmentsAreDefensivelyCopiedAndUnmodifiable() {
        java.util.List<ImageAttachment> source = new java.util.ArrayList<ImageAttachment>(
                Arrays.asList(IMAGE));
        ChatDraft draft = new ChatDraft("x", source);
        source.clear();
        assertEquals("copy is independent of the source list", 1, draft.getAttachments().size());
        try {
            draft.getAttachments().add(IMAGE);
            org.junit.Assert.fail("attachments must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
