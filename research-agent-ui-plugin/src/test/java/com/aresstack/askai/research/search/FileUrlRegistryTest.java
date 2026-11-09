package com.aresstack.askai.research.search;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileUrlRegistryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void registeredUrlsSurviveARestart() throws Exception {
        File project = folder.newFolder("p");
        FileUrlRegistry first = new FileUrlRegistry(project);
        assertTrue(first.register("https://a.org/ä", "chat-browser"));
        assertFalse(first.register("https://a.org/ä", "search-service"));

        FileUrlRegistry reopened = new FileUrlRegistry(project);
        assertTrue(reopened.isKnown("https://a.org/ä"));
        assertFalse(reopened.register("https://a.org/ä", "search-service"));
    }

    @Test
    public void aTornLineIsSkippedAndTheNextAppendStartsFresh() throws Exception {
        File project = folder.newFolder("p");
        FileUrlRegistry registry = new FileUrlRegistry(project);
        registry.register("https://a.org/1", "x");
        FileOutputStream out = new FileOutputStream(registry.getFile(), true);
        out.write("{\"url\":\"https://b.or".getBytes(StandardCharsets.UTF_8));
        out.close();

        FileUrlRegistry afterCrash = new FileUrlRegistry(project);
        assertTrue(afterCrash.isKnown("https://a.org/1"));
        assertTrue(afterCrash.register("https://c.org/2", "x"));
        assertTrue(new FileUrlRegistry(project).isKnown("https://c.org/2"));
    }
}
