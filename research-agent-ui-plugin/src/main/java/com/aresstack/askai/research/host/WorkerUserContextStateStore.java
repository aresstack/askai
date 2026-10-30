package com.aresstack.askai.research.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Saved sign-in states (cookies, local storage) of research workers that asked to keep theirs
 * ({@code BrowserUserContextRequest.withStateKey}). One file per chat and state key under
 * {@code <state root>/<sha256(chat) prefix>/<key>.json}: the state never lands in the research project
 * folder (which may be shared or synced) but next to the chat browser's own profile, under the user's
 * application directory. On POSIX file systems the file is readable by its owner only; on Windows the
 * per-user {@code %APPDATA%} ACL applies — the same protection the chat browser's cookie database has.
 * Writes are atomic (temp file + move).
 */
final class WorkerUserContextStateStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");

    private final Path chatDirectory;

    WorkerUserContextStateStore(Path root, String chatKey) {
        this.chatDirectory = root.resolve(digest(chatKey));
    }

    Path chatDirectory() {
        return chatDirectory;
    }

    /** The saved state, or null when there is none (or it is unreadable — then the worker starts fresh). */
    String load(String key) {
        Path file = file(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            System.err.println("[browser-workers] worker state " + key + " unreadable, starting fresh: "
                    + unreadable.getMessage());
            return null;
        }
    }

    void save(String key, String state) throws IOException {
        Path file = file(key);
        Files.createDirectories(chatDirectory);
        restrict(chatDirectory, OWNER_ONLY_DIR);
        Path temp = Files.createTempFile(chatDirectory, key + ".", ".partial");
        try {
            restrict(temp, OWNER_ONLY);
            Files.write(temp, (state == null ? "" : state).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Forget a saved sign-in. */
    void delete(String key) throws IOException {
        Files.deleteIfExists(file(key));
    }

    private Path file(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,64}") || key.startsWith(".")) {
            throw new IllegalArgumentException("invalid worker state key: " + key);
        }
        return chatDirectory.resolve(key + ".json");
    }

    private static void restrict(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the per-user profile directory's ACL is the protection.
        }
    }

    private static String digest(String chatKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(chatKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
