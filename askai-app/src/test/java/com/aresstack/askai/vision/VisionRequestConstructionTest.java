package com.aresstack.askai.vision;

import com.aresstack.askai.client.OllamaChatTurn;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Conclusively verifies per-turn image request construction for the vision path (issue #11):
 * each turn carries exactly its own images, a replaced attachment yields different encoded bytes,
 * and the safe diagnostics report the correct turn/image association.
 */
public class VisionRequestConstructionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final ImageAttachmentContentLoader loader = new ImageAttachmentContentLoader();

    private File png(String name, int rgb) throws Exception {
        File file = folder.newFile(name);
        BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 4; y++) {
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", file);
        return file;
    }

    @Test
    public void eachTurnKeepsItsOwnImages() throws Exception {
        String imgA = loader.encodeToBase64(ImageAttachment.of(png("a.png", 0xFF0000)));
        String imgB = loader.encodeToBase64(ImageAttachment.of(png("b.png", 0x00FF00)));

        OllamaChatTurn turn1 = OllamaChatTurn.user("first", Arrays.asList(imgA));
        OllamaChatTurn turn2 = OllamaChatTurn.user("second", Arrays.asList(imgB));

        assertEquals(1, turn1.getImages().size());
        assertEquals(1, turn2.getImages().size());
        assertEquals(imgA, turn1.getImages().get(0));
        assertEquals(imgB, turn2.getImages().get(0));
        assertFalse("different images must encode differently", imgA.equals(imgB));
    }

    @Test
    public void replacingAnAttachmentChangesTheEncodedContent() throws Exception {
        String original = loader.encodeToBase64(ImageAttachment.of(png("orig.png", 0x112233)));
        String replaced = loader.encodeToBase64(ImageAttachment.of(png("new.png", 0x445566)));
        assertFalse(original.equals(replaced));
    }

    @Test
    public void imageOnlyAndTextPlusImageUseTheSameTurnShape() throws Exception {
        String img = loader.encodeToBase64(ImageAttachment.of(png("c.png", 0x010203)));
        OllamaChatTurn imageOnly = OllamaChatTurn.user("", Arrays.asList(img));
        OllamaChatTurn textPlusImage = OllamaChatTurn.user("look", Arrays.asList(img));
        assertTrue(imageOnly.isUser());
        assertTrue(textPlusImage.isUser());
        assertEquals(1, imageOnly.getImages().size());
        assertEquals(1, textPlusImage.getImages().size());
    }

    @Test
    public void diagnosticsReportPerTurnAssociation() throws Exception {
        String imgA = loader.encodeToBase64(ImageAttachment.of(png("d.png", 0xAABBCC)));
        String imgB = loader.encodeToBase64(ImageAttachment.of(png("e.png", 0xCCBBAA)));
        List<OllamaChatTurn> turns = new ArrayList<OllamaChatTurn>();
        turns.add(OllamaChatTurn.system("sys"));
        turns.add(OllamaChatTurn.user("no image here"));
        turns.add(OllamaChatTurn.user("first", Arrays.asList(imgA)));
        turns.add(OllamaChatTurn.assistant("ok"));
        turns.add(OllamaChatTurn.user("second", Arrays.asList(imgB)));

        List<String> lines = VisionDiagnostics.describe(turns);
        // Two image lines (turnIndex 2 and 4) plus a summary line.
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("turnIndex=2"));
        assertTrue(lines.get(0).contains("imageIndex=0"));
        assertTrue(lines.get(0).contains("mediaType=image/png"));
        assertTrue(lines.get(1).contains("turnIndex=4"));
        assertTrue(lines.get(2).contains("imageTurns=2"));
        assertTrue(lines.get(2).contains("totalImages=2"));
        // Safety: no diagnostic line may leak Base64 payloads.
        for (String line : lines) {
            assertFalse(line.contains(imgA));
            assertFalse(line.contains(imgB));
        }
    }
}
