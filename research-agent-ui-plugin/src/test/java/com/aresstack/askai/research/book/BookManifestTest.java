package com.aresstack.askai.research.book;

import com.aresstack.askai.research.transition.CorruptProjectFileException;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BookManifestTest {

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("askai-book-manifest").toFile();
    }

    private static ConceptPin pin(String id, int revision, String mission) {
        return new ConceptPin(id, revision, true, "hash" + revision, 7L, 1000L, mission, "", "de",
                Arrays.asList("Applets"));
    }

    @Test
    public void pinsAreAppendedAndOnlyCommittedOnesAreValid() throws Exception {
        BookManifest manifest = new BookManifest(tempDir());
        manifest.appendConceptPin(pin("t1", 1, "Grüße „Zitat“ – ß 🙂"));
        manifest.appendConceptPin(pin("t2", 2, "second"));

        BookManifest.CommitCheck onlyFirst = new BookManifest.CommitCheck() {
            public boolean isCommitted(String transitionId) {
                return "t1".equals(transitionId);
            }
        };
        ConceptPin valid = manifest.validConceptPin(onlyFirst);
        assertEquals("a pin whose transition never committed is ignored", "t1", valid.getTransitionId());
        assertEquals("Grüße „Zitat“ – ß 🙂", new BookManifest(manifest.file().getParentFile())
                .validConceptPin(onlyFirst).getMission());
        assertEquals(2, manifest.conceptPins().size());
        assertEquals(Arrays.asList("Applets"), valid.getBlacklist());
        assertEquals("de", valid.getLanguage());
    }

    @Test
    public void noPinMeansNothingIsFrozen() throws Exception {
        BookManifest manifest = new BookManifest(tempDir());
        assertNull(manifest.validConceptPin(new BookManifest.CommitCheck() {
            public boolean isCommitted(String transitionId) {
                return true;
            }
        }));
        assertEquals(Collections.<String>emptyList(), manifest.notes());
    }

    @Test
    public void aDamagedManifestFailsClosedAndIsNeverOverwritten() throws Exception {
        BookManifest manifest = new BookManifest(tempDir());
        Files.write(manifest.file().toPath(), "{\"schema\":1,\"pins\":[{\"phase\":\"concept\"}]".getBytes("UTF-8"));
        try {
            manifest.conceptPins();
            fail("a damaged manifest must not read as empty");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage().contains("book.json"));
        }
        try {
            manifest.appendConceptPin(pin("t1", 1, "m"));
            fail("a damaged manifest must not be overwritten");
        } catch (IOException expected) {
            // refused
        }
        assertEquals("{\"schema\":1,\"pins\":[{\"phase\":\"concept\"}]",
                new String(Files.readAllBytes(manifest.file().toPath()), "UTF-8"));
        assertTrue(manifest.corruptReason() != null);
    }

    @Test
    public void theEffectiveConceptDropsTheRecordedBlacklist() {
        String document = "{\"title\":\"\",\"concept\":[{\"Tasks\":[{\"Applets\":[]},\"Timers\"],\"Applets\":[]}]}";
        String effective = FrozenConcept.withoutTerms(document, Arrays.asList("applets"));
        assertTrue(effective.contains("Tasks"));
        assertTrue(effective.contains("Timers"));
        assertTrue(!effective.contains("Applets"));
        assertEquals(Arrays.asList("Tasks", "Applets"), FrozenConcept.firstLevelCards(document));
    }
}
