package com.aresstack.askai.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads an {@link ImageAttachment}'s bytes from disk and base64-encodes them for Ollama's {@code images}
 * array. This is the only place image bytes are materialized, and it is meant to run <strong>off the
 * EDT</strong> (file I/O + encoding). Every failure surfaces as a typed {@link ImageAttachmentException}
 * so the caller can keep the draft and tell the user exactly which image failed and why.
 *
 * <p>Validation is by content signature, not file extension: a file whose bytes are not a supported image
 * is rejected as {@code UNSUPPORTED_FORMAT}/{@code CORRUPT} even if it is named {@code .png}. Raw base64 is
 * emitted without a {@code data:} URI prefix, as the native Ollama API expects.
 */
public final class ImageAttachmentContentLoader {

    /** Generous per-image ceiling; a guard against accidentally attaching a huge non-image file. */
    public static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;

    private final long maxBytes;

    public ImageAttachmentContentLoader() {
        this(DEFAULT_MAX_BYTES);
    }

    public ImageAttachmentContentLoader(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Encode every attachment in order; the first failure aborts and propagates its typed reason. */
    public List<String> encodeAll(List<ImageAttachment> attachments) throws ImageAttachmentException {
        List<String> encoded = new ArrayList<String>();
        for (ImageAttachment attachment : attachments) {
            encoded.add(encodeToBase64(attachment));
        }
        return encoded;
    }

    public String encodeToBase64(ImageAttachment attachment) throws ImageAttachmentException {
        Path path = attachment.getFile();
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ImageAttachmentException(attachment, ImageAttachmentException.Reason.FILE_MISSING);
        }
        if (!Files.isReadable(path)) {
            throw new ImageAttachmentException(attachment, ImageAttachmentException.Reason.NOT_READABLE);
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException ex) {
            throw new ImageAttachmentException(attachment, ImageAttachmentException.Reason.NOT_READABLE, ex);
        }
        if (size > maxBytes) {
            throw new ImageAttachmentException(attachment, ImageAttachmentException.Reason.TOO_LARGE);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new ImageAttachmentException(attachment, ImageAttachmentException.Reason.NOT_READABLE, ex);
        }
        verifyImageSignature(attachment, bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Reject anything that is not a supported image by its magic bytes. Recognized-but-unsupported formats
     * (GIF/BMP/TIFF) are UNSUPPORTED_FORMAT; empty or unrecognized content is CORRUPT.
     */
    private static void verifyImageSignature(ImageAttachment attachment, byte[] bytes)
            throws ImageAttachmentException {
        if (isPng(bytes) || isJpeg(bytes) || isWebp(bytes)) {
            return;
        }
        ImageAttachmentException.Reason reason = isKnownUnsupported(bytes)
                ? ImageAttachmentException.Reason.UNSUPPORTED_FORMAT
                : ImageAttachmentException.Reason.CORRUPT;
        throw new ImageAttachmentException(attachment, reason);
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A;
    }

    private static boolean isJpeg(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean isWebp(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static boolean isKnownUnsupported(byte[] b) {
        boolean gif = b.length >= 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        boolean bmp = b.length >= 2 && b[0] == 'B' && b[1] == 'M';
        boolean tiff = b.length >= 4
                && ((b[0] == 'I' && b[1] == 'I' && (b[2] & 0xFF) == 0x2A && b[3] == 0)
                || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && (b[3] & 0xFF) == 0x2A));
        return gif || bmp || tiff;
    }
}
