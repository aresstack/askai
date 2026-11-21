package com.aresstack.askai.modelport;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;

/**
 * Keeps the API key of the OpenAI-compatible API encrypted at rest: AES-GCM with a random key in its own file next
 * to the settings (owner-only where the file system allows it), the same scheme the research plugin uses for the
 * search providers' keys. The settings file holds only {@code v1:<iv>:<cipher text>}; neither the key nor the
 * plain API key is ever written to a log. Thread-safe.
 *
 * <p>A damaged key file (empty after a crash, garbled, a key of the wrong length) can decrypt nothing: reading a
 * stored key then asks the user to enter it again, and storing a key replaces the damaged file with a new key, so
 * entering it again always works. The key file is written owner-only from its creation and synced before it
 * replaces the old one.</p>
 */
public final class ApiKeyVault {

    private static final String PREFIX = "v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 128;

    private final File keyFile;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyVault(File keyFile) {
        if (keyFile == null) {
            throw new IllegalArgumentException("keyFile must not be null");
        }
        this.keyFile = keyFile.getAbsoluteFile();
    }

    /** Encrypts a typed key; blank input is refused. The caller clears {@code plain}. */
    public String encrypt(char[] plain) throws IOException {
        if (plain == null || new String(plain).trim().isEmpty()) {
            throw new IllegalArgumentException("the API key must not be empty");
        }
        byte[] bytes = utf8(plain);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key(true), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(bytes);
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException failed) {
            throw new IOException("the API key could not be encrypted: " + failed.getClass().getSimpleName());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * The plain key of a stored value, or null for "" (nothing stored). The caller clears the array.
     *
     * @throws IOException when the value or the key file is unusable (for example the key file was deleted)
     */
    public char[] decrypt(String stored) throws IOException {
        String value = stored == null ? "" : stored.trim();
        if (value.isEmpty()) {
            return null;
        }
        String[] parts = value.startsWith(PREFIX) ? value.substring(PREFIX.length()).split(":") : new String[0];
        if (parts.length != 2) {
            throw new IOException("the stored API key has an unknown format; enter it again");
        }
        byte[] plain = null;
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(TAG_BITS, iv));
            plain = cipher.doFinal(encrypted);
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] result = Arrays.copyOfRange(chars.array(), chars.position(), chars.limit());
            Arrays.fill(chars.array(), '\0');
            return result;
        } catch (GeneralSecurityException | IllegalArgumentException failed) {
            throw new IOException("the stored API key cannot be read (" + failed.getClass().getSimpleName()
                    + "); enter it again");
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private synchronized SecretKey key(boolean create) throws IOException {
        Path path = keyFile.toPath();
        if (Files.exists(path)) {
            SecretKey stored = readKey(path);
            if (stored != null) {
                return stored;
            }
            if (!create) {
                throw new IOException("the key file of the stored API key is damaged; enter the API key again");
            }
            // a damaged key file decrypts nothing any more: a new key replaces it
        } else if (!create) {
            throw new IOException("the key file of the stored API key is missing; enter the API key again");
        }
        SecretKey key;
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_BITS, random);
            key = generator.generateKey();
        } catch (GeneralSecurityException impossible) {
            throw new IOException("no AES key could be generated: " + impossible.getClass().getSimpleName());
        }
        File directory = keyFile.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create " + directory);
        }
        byte[] encoded = Base64.getEncoder().encode(key.getEncoded());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.deleteIfExists(temporary);
            createOwnerOnly(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer content = ByteBuffer.wrap(encoded);
                while (content.hasRemaining()) {
                    channel.write(content);
                }
                channel.force(true); // on disk before it replaces the old file: a crash never leaves it empty
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
            Files.deleteIfExists(temporary);
        }
        return key;
    }

    /** The AES key in the file, or null when the file holds none (empty, not Base64, not 16, 24 or 32 bytes). */
    private static SecretKey readKey(Path path) throws IOException {
        byte[] raw = Files.readAllBytes(path);
        byte[] encoded = null;
        try {
            encoded = Base64.getDecoder().decode(new String(raw, StandardCharsets.US_ASCII).trim());
            if (encoded.length != 16 && encoded.length != 24 && encoded.length != 32) {
                return null;
            }
            return new SecretKeySpec(encoded, "AES");
        } catch (IllegalArgumentException damaged) {
            return null;
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /** Creates an empty file readable only by its owner where the file system knows POSIX permissions. */
    private static void createOwnerOnly(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(path); // Windows: the file lies in the user's own profile directory
        }
    }

    private static byte[] utf8(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
        Arrays.fill(buffer.array(), (byte) 0);
        return bytes;
    }
}
