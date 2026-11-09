package com.aresstack.askai.research.search;

import com.aresstack.askai.research.runtime.search.service.PageCandidate;
import com.aresstack.askai.research.runtime.search.service.UrlRegistryException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileUrlRegistryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static PageCandidate page(String url) {
        return new PageCandidate(url, "Titel ä", "Snippet", "Abfrage", "www.google.com", 3,
                url.contains("goto"));
    }

    @Test
    public void pendingPagesSurviveARestartWithTheirData() throws Exception {
        File project = folder.newFolder("p");
        FileUrlRegistry first = new FileUrlRegistry(project);
        assertTrue(first.enqueue(page("https://a.org/ä"), "search-service"));
        assertTrue(first.enqueue(page("https://www.google.com/goto?url=x"), "search-service"));
        assertFalse(first.enqueue(page("https://a.org/ä"), "search-service"));
        first.complete("https://www.google.com/goto?url=x", "resolved");

        FileUrlRegistry reopened = new FileUrlRegistry(project);
        assertEquals(1, reopened.pending().size());
        PageCandidate back = reopened.pending().get(0);
        assertEquals("https://a.org/ä", back.getUrl());
        assertEquals("Titel ä", back.getTitle());
        assertEquals("Abfrage", back.getQuery());
        assertTrue(reopened.isKnown("https://a.org/ä"));
        assertFalse(reopened.isCompleted("https://a.org/ä"));
        assertTrue(reopened.isCompleted("https://www.google.com/goto?url=x"));
    }

    @Test
    public void aTornLineIsSkippedAndTheNextAppendStartsFresh() throws Exception {
        File project = folder.newFolder("p");
        FileUrlRegistry registry = new FileUrlRegistry(project);
        registry.enqueue(page("https://a.org/1"), "x");
        FileOutputStream out = new FileOutputStream(registry.getFile(), true);
        out.write("{\"url\":\"https://b.or".getBytes(StandardCharsets.UTF_8));
        out.close();

        FileUrlRegistry afterCrash = new FileUrlRegistry(project);
        assertTrue(afterCrash.isKnown("https://a.org/1"));
        assertTrue(afterCrash.enqueue(page("https://c.org/2"), "x"));
        assertTrue(new FileUrlRegistry(project).isKnown("https://c.org/2"));
    }

    @Test
    public void anUnwritableFileFailsTypedAndChangesNothing() throws Exception {
        File project = folder.newFolder("p");
        File dir = new File(project, FileUrlRegistry.DIRECTORY);
        assertTrue(dir.mkdirs());
        assertTrue(new File(dir, FileUrlRegistry.FILE_NAME).mkdir()); // a directory where the file belongs
        FileUrlRegistry registry = new FileUrlRegistry(project);
        try {
            registry.enqueue(page("https://a.org/1"), "x");
            fail("expected a typed failure");
        } catch (UrlRegistryException expected) {
            assertFalse(registry.isKnown("https://a.org/1"));
            assertTrue(registry.pending().isEmpty());
        }
    }
}
