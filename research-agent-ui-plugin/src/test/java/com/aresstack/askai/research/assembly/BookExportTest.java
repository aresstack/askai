package com.aresstack.askai.research.assembly;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Plan PR 30: the exported file equals {@code document.md}. */
public class BookExportTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void exportedFileEqualsTheDocumentByteForByte() throws Exception {
        String document = "# Büch\n\nText mit „Zitat“ [1].\n";
        File written = BookExport.write(document, new File(folder.getRoot(), "book"));
        assertEquals("book.md", written.getName());
        assertArrayEquals(document.getBytes("UTF-8"), Files.readAllBytes(written.toPath()));
        assertEquals(1, folder.getRoot().listFiles().length);
    }
}
