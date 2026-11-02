package com.aresstack.askai.vision;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A user-selected image queued in the chat composer. It deliberately holds only a reference to the file
 * on disk plus light display metadata — <strong>never</strong> the image bytes or their base64 form — so
 * large screenshots do not sit twice in the UI model. The bytes are read and base64-encoded off the EDT
 * only when the message is actually sent, via {@link ImageAttachmentContentLoader}.
 */
public final class ImageAttachment {

    private final Path file;
    private final String displayName;
    private final String mediaType;

    public ImageAttachment(Path file, String displayName, String mediaType) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
        this.displayName = displayName == null || displayName.isEmpty()
                ? String.valueOf(file.getFileName()) : displayName;
        this.mediaType = mediaType == null ? "" : mediaType;
    }

    /** Build an attachment from a chosen file, deriving the display name and media type from it. */
    public static ImageAttachment of(File file) {
        return new ImageAttachment(file.toPath(), file.getName(), mediaTypeFor(file.getName()));
    }

    public Path getFile() {
        return file;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMediaType() {
        return mediaType;
    }

    private static String mediaTypeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ImageAttachment)) {
            return false;
        }
        return file.equals(((ImageAttachment) other).file);
    }

    @Override
    public int hashCode() {
        return file.hashCode();
    }

    @Override
    public String toString() {
        return "ImageAttachment{" + displayName + "}";
    }
}
