package com.aresstack.askai.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The composer's outgoing draft: the typed text plus any queued {@link ImageAttachment}s. Images are kept
 * structurally here rather than embedded into the message text, so the send path can route them into the
 * Ollama request's {@code images} array and the stored chat code stays plain text.
 */
public final class ChatDraft {

    private final String text;
    private final List<ImageAttachment> attachments;

    public ChatDraft(String text, List<ImageAttachment> attachments) {
        this.text = text == null ? "" : text;
        this.attachments = attachments == null
                ? Collections.<ImageAttachment>emptyList()
                : Collections.unmodifiableList(new ArrayList<ImageAttachment>(attachments));
    }

    public String getText() {
        return text;
    }

    public List<ImageAttachment> getAttachments() {
        return attachments;
    }

    public boolean hasText() {
        return text.trim().length() > 0;
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    /** A draft is empty when there is neither text nor an image to send. */
    public boolean isEmpty() {
        return !hasText() && !hasAttachments();
    }
}
