package com.aresstack.askai.vision;

/**
 * Raised when an {@link ImageAttachment} cannot be turned into sendable base64 content. Carries the
 * offending attachment and a typed {@link Reason} so the composer can show a clear message and keep the
 * draft intact instead of silently discarding images.
 */
public final class ImageAttachmentException extends Exception {

    public enum Reason {
        FILE_MISSING("file no longer exists"),
        NOT_READABLE("file could not be read"),
        TOO_LARGE("file is too large"),
        UNSUPPORTED_FORMAT("unsupported image format"),
        CORRUPT("image file is corrupt");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private final ImageAttachment attachment;
    private final Reason reason;

    public ImageAttachmentException(ImageAttachment attachment, Reason reason) {
        this(attachment, reason, null);
    }

    public ImageAttachmentException(ImageAttachment attachment, Reason reason, Throwable cause) {
        super(attachment.getDisplayName() + ": " + reason.getDescription(), cause);
        this.attachment = attachment;
        this.reason = reason;
    }

    public ImageAttachment getAttachment() {
        return attachment;
    }

    public Reason getReason() {
        return reason;
    }
}
