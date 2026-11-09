package com.aresstack.askai.research.assembly;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** "Export book..." (plan PR 30): writes {@code document.md} byte for byte, UTF-8, to the file the user chose. */
public final class BookExport {

    private BookExport() {
    }

    public static File write(String documentMarkdown, File target) throws IOException {
        File file = target.getName().contains(".") ? target : new File(target.getPath() + ".md");
        File parent = file.getAbsoluteFile().getParentFile();
        File temp = File.createTempFile("book-export", ".md", parent);
        try {
            Files.write(temp.toPath(), (documentMarkdown == null ? "" : documentMarkdown)
                    .getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
        return file;
    }
}
