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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
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
            byte[] encoded = Base64.getDecoder().decode(new String(Files.readAllBytes(path),
                    StandardCharsets.US_ASCII).trim());
            try {
                return new SecretKeySpec(encoded, "AES");
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }
        if (!create) {
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
            Files.write(temporary, encoded);
            ownerOnly(temporary);
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

    private static void ownerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the file lies in the user's own profile directory
        }
    }

    private static byte[] utf8(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
        Arrays.fill(buffer.array(), (byte) 0);
        return bytes;
    }
}
