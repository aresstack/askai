package com.aresstack.askai.research.knowledge.processing;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Reads a project-store {@code .properties} file as UTF-8. The stores write their files as UTF-8 bytes, so they must
 * never be read with {@link Properties#load(java.io.InputStream)}, which decodes ISO-8859-1 and turns "ü" into "Ã¼".
 */
public final class Utf8Properties {

    private Utf8Properties() {
    }

    /** Loads {@code file} as UTF-8; an unreadable file throws, a malformed UTF-8 sequence becomes U+FFFD. */
    public static Properties load(File file) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
        return properties;
    }
}
