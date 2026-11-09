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

    /** The file an export to {@code target} writes: {@code .md} is appended when the name has no extension. */
    public static File resolve(File target) {
        return target.getName().contains(".") ? target : new File(target.getPath() + ".md");
    }

    public static File write(String documentMarkdown, File target) throws IOException {
        File file = resolve(target);
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
