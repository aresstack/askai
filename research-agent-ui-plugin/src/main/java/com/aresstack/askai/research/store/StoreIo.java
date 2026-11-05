package com.aresstack.askai.research.store;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Small filesystem helpers for the project store: UTF-8 atomic writes and SHA-256 checksums. */
final class StoreIo {

    static final Charset UTF8 = Charset.forName("UTF-8");

    private StoreIo() {
    }

    /** Write {@code content} to {@code target} atomically: temp file in the same dir, then move. */
    static void atomicWrite(File target, String content) throws IOException {
        File dir = target.getParentFile();
        if (dir != null) {
            Files.createDirectories(dir.toPath());
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        Files.write(tmp.toPath(), content.getBytes(UTF8));
        Path targetPath = target.toPath();
        try {
            Files.move(tmp.toPath(), targetPath,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Reads a store's {@code .properties} file as UTF-8 (never the ISO-8859-1 of {@code Properties.load(InputStream)}). */
    static java.util.Properties loadUtf8Properties(File file) throws IOException {
        return com.aresstack.askai.research.knowledge.processing.Utf8Properties.load(file);
    }

    static String readUtf8(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), UTF8);
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(UTF8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "";
        }
    }
}
