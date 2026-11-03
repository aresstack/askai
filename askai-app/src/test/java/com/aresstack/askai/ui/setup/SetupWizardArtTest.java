package com.aresstack.askai.ui.setup;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Witch's pictures: every step finds its copy on the class path, and every copy has its unedited original in
 * {@code docs/images/setup-wizard/originals} (the originals are kept, only copies are scaled).
 */
public class SetupWizardArtTest {

    @Test
    public void everyStepAndTheRuntimeInstallSceneLoad() {
        for (int step = 0; step < SetupWizardPanel.STEP_NAMES.length; step++) {
            SetupWizardMascot.Art art = SetupWizardMascot.Art.load(SetupWizardPanel.mascotResource(step));
            assertNotNull("picture of step " + step, art);
            assertTrue("the step pictures are cut-outs with a transparent background", art.isCutOut());
        }
        assertNotNull(SetupWizardMascot.Art.load(SetupWizardPanel.RUNTIME_INSTALL_MASCOT));
        assertNotNull(SetupWizardMascot.Art.load(SetupWizardPanel.CHECK_SUCCESS_MASCOT));
        assertNotNull(SetupWizardMascot.Art.load());
    }

    @Test
    public void everyCopyHasItsUneditedOriginal() throws IOException {
        File root = repositoryRoot();
        File copies = new File(root, "askai-app/src/main/resources/com/aresstack/askai/ui/setup/wizard");
        File originals = new File(root, "docs/images/setup-wizard/originals");
        File[] files = copies.listFiles();
        assertNotNull(files);
        assertEquals(20, files.length);
        for (File copy : files) {
            if (copy.getName().startsWith("v3-")) { // third set: originals keep their names in v3/wizard
                File original = new File(originals, "v3/wizard/" + copy.getName().substring(3));
                assertTrue("original of " + copy.getName(), original.isFile());
                assertTrue(ImageIO.read(copy).getHeight() <= 900);
                continue;
            }
            File original = new File(originals, copy.getName());
            assertTrue("original of " + copy.getName(), original.isFile());
            BufferedImage image = ImageIO.read(original);
            BufferedImage scaled = ImageIO.read(copy);
            if (copy.getName().startsWith("witch-")) {
                assertEquals("original keeps its size", 1024, image.getWidth());
                assertEquals(1536, image.getHeight());
                assertEquals(900, scaled.getHeight());
            } else {
                assertEquals("original keeps its size", 1086, image.getWidth());
                assertEquals(1448, image.getHeight());
                assertEquals(480, scaled.getWidth());
                assertEquals(640, scaled.getHeight());
            }
        }
    }

    private static File repositoryRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File candidate = dir; candidate != null; candidate = candidate.getParentFile()) {
            if (new File(candidate, "docs/images/setup-wizard").isDirectory()) {
                return candidate;
            }
        }
        throw new IllegalStateException("repository root not found from " + dir);
    }
}
