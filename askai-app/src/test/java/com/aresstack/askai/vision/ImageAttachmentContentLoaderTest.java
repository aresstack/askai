package com.aresstack.askai.vision;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The loader base64-encodes valid images and rejects every failure mode with a typed reason. */
public class ImageAttachmentContentLoaderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final ImageAttachmentContentLoader loader = new ImageAttachmentContentLoader();

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WEBP_MAGIC = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] GIF_MAGIC = {'G', 'I', 'F', '8', '9', 'a'};

    @Test
    public void encodesAPngToBase64WithoutADataUriPrefix() throws Exception {
        byte[] content = concat(PNG_MAGIC, new byte[]{1, 2, 3});
        ImageAttachment attachment = write("shot.png", content);

        String encoded = loader.encodeToBase64(attachment);

        assertEquals(Base64.getEncoder().encodeToString(content), encoded);
        assertTrue("no data: prefix", !encoded.startsWith("data:"));
    }

    @Test
    public void encodesJpegAndWebp() throws Exception {
        assertEquals(base64("a.jpg", JPEG_MAGIC), loader.encodeToBase64(write("a.jpg", JPEG_MAGIC)));
        assertEquals(base64("b.webp", WEBP_MAGIC), loader.encodeToBase64(write("b.webp", WEBP_MAGIC)));
    }

    @Test
    public void encodesAllInOrder() throws Exception {
        ImageAttachment a = write("a.png", concat(PNG_MAGIC, new byte[]{9}));
        ImageAttachment b = write("b.jpg", concat(JPEG_MAGIC, new byte[]{8}));
        assertEquals(Arrays.asList(loader.encodeToBase64(a), loader.encodeToBase64(b)),
                loader.encodeAll(Arrays.asList(a, b)));
    }

    @Test
    public void missingFileIsReported() throws Exception {
        ImageAttachment attachment = ImageAttachment.of(new File(folder.getRoot(), "gone.png"));
        assertReason(attachment, ImageAttachmentException.Reason.FILE_MISSING);
    }

    @Test
    public void oversizeFileIsReported() throws Exception {
        ImageAttachmentContentLoader tiny = new ImageAttachmentContentLoader(4);
        ImageAttachment attachment = write("big.png", concat(PNG_MAGIC, new byte[]{1, 2, 3, 4}));
        try {
            tiny.encodeToBase64(attachment);
            fail("expected TOO_LARGE");
        } catch (ImageAttachmentException ex) {
            assertEquals(ImageAttachmentException.Reason.TOO_LARGE, ex.getReason());
        }
    }

    @Test
    public void aKnownButUnsupportedFormatIsReported() throws Exception {
        // Content is a real GIF, even though the name claims .png -> format decided by content.
        ImageAttachment attachment = write("fake.png", GIF_MAGIC);
        assertReason(attachment, ImageAttachmentException.Reason.UNSUPPORTED_FORMAT);
    }

    @Test
    public void garbageOrEmptyContentIsReportedAsCorrupt() throws Exception {
        assertReason(write("broken.png", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}),
                ImageAttachmentException.Reason.CORRUPT);
        assertReason(write("empty.png", new byte[0]), ImageAttachmentException.Reason.CORRUPT);
    }

    @Test
    public void theExceptionNamesTheOffendingAttachment() throws Exception {
        ImageAttachment attachment = write("fake.png", GIF_MAGIC);
        try {
            loader.encodeToBase64(attachment);
            fail("expected failure");
        } catch (ImageAttachmentException ex) {
            assertEquals(attachment, ex.getAttachment());
            assertTrue(ex.getMessage().contains("fake.png"));
        }
    }

    // --- helpers ---

    private ImageAttachment write(String name, byte[] content) throws Exception {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), content);
        return ImageAttachment.of(file);
    }

    private String base64(String name, byte[] content) throws Exception {
        return Base64.getEncoder().encodeToString(content);
    }

    private void assertReason(ImageAttachment attachment, ImageAttachmentException.Reason expected) {
        try {
            loader.encodeToBase64(attachment);
            fail("expected " + expected);
        } catch (ImageAttachmentException ex) {
            assertEquals(expected, ex.getReason());
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
