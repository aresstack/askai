package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.playwright.camoufox.CamoufoxDistribution;
import com.aresstack.askai.browser.playwright.camoufox.CamoufoxInstaller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Where AskAI keeps the managed Camoufox (the Argos distribution: pinned, patched, SHA-256 verified) and its
 * browser profiles (one per chat), under the same app directory as the logs: {@code <APPDATA>/.askai-java8/browser}
 * and {@code <APPDATA>/.askai-java8/browser-profiles/chats}. The download happens once; later starts only verify.
 *
 * <p>While the installer runs, a progress line {@value #PROGRESS_MARKER} goes to STDERR every few seconds:
 * the host restarts its readiness timeout on each one, so the one-time download never counts as a hung
 * start.</p>
 */
final class CamoufoxProvisioning {

    static final String CHANNEL = "camoufox";
    /** Prefix of the progress lines of the one-time Camoufox download (surfaced on the console/readiness). */
    static final String PROGRESS_MARKER = "camoufox install:";
    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private CamoufoxProvisioning() {
    }

    static Path appDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, ".askai-java8");
    }

    static Path browserRoot() {
        return appDirectory().resolve("browser");
    }

    /** The verified Camoufox executable; downloads and installs it first when needed (blocking). */
    static Path ensureInstalled() {
        CamoufoxInstaller installer = CamoufoxInstaller.shared(browserRoot(), null);
        while (true) {
            try {
                Path executable = installer.awaitExecutable(PROGRESS_INTERVAL);
                System.err.println("[browser-mcp] " + PROGRESS_MARKER + " READY " + executable);
                return executable;
            } catch (CamoufoxInstaller.NotReadyException pending) {
                System.err.println("[browser-mcp] " + PROGRESS_MARKER + " " + pending.getMessage());
            }
        }
    }

    /** {@code --install-camoufox}: install (or verify) and exit; 0 = ready. */
    static int installOnly() {
        try {
            ensureInstalled();
            return 0;
        } catch (RuntimeException failed) {
            System.err.println("[browser-mcp] " + PROGRESS_MARKER + " FAILED " + failed.getMessage());
            return 1;
        }
    }

    static String distributionName() {
        return CamoufoxDistribution.builtIn().name();
    }

    /**
     * The browser profile of ONE chat's browser runtime. Each chat owns its own persistent profile (cookies,
     * consent answers and sign-ins survive restarts) under {@code browser-profiles/chats/<sha256(chat) prefix>/}
     * — a sign-in made in chat A never shows up in chat B. The profile is locked while a browser uses it; a
     * second browser of the SAME chat (a restart overlapping the old generation) gets a temporary profile that
     * is deleted on {@link ProfileLease#close()}, since two Firefox processes can never share one profile.
     * Without a chat key (capability probe, tests) the browser always gets a temporary profile: there is no
     * app-wide shared profile any more.
     *
     * <p>A generation of the same chat that was just terminated (a hung call) releases its lock on a background
     * thread once its browser has exited; the restarted generation waits up to {@link #RELEASE_WAIT_MILLIS} for
     * that instead of falling back to a temporary profile without the chat's sign-ins. Temporary profiles left
     * behind by a crash are swept after a day.</p>
     */
    static ProfileLease leaseProfile(String chatKey) {
        return leaseProfile(appDirectory().resolve("browser-profiles"), chatKey, RELEASE_WAIT_MILLIS);
    }

    /** How long a restart waits for this JVM's previous generation of the same chat to release the profile. */
    static final long RELEASE_WAIT_MILLIS = 15_000L;
    /** Temporary profiles older than this are left-overs of a crash or a failed delete. */
    static final long STALE_TEMPORARY_MILLIS = 24L * 60L * 60L * 1000L;

    static ProfileLease leaseProfile(Path profiles, String chatKey) {
        return leaseProfile(profiles, chatKey, 0L);
    }

    static ProfileLease leaseProfile(Path profiles, String chatKey, long releaseWaitMillis) {
        try {
            if (chatKey == null || chatKey.trim().isEmpty()) {
                Path scratch = profiles.resolve("temporary");
                Files.createDirectories(scratch);
                sweepStaleTemporaries(scratch, CHANNEL + "-");
                return new ProfileLease(Files.createTempDirectory(scratch, CHANNEL + "-"), null, null, true);
            }
            Path chat = chatProfileDirectory(profiles, chatKey);
            Files.createDirectories(chat);
            restrictToOwner(chat);
            FileChannel channel = FileChannel.open(chat.resolve(CHANNEL + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = lockReleasedWithin(channel, releaseWaitMillis);
            if (lock != null) {
                Path persistent = chat.resolve(CHANNEL);
                Files.createDirectories(persistent);
                return new ProfileLease(persistent, channel, lock, false);
            }
            channel.close();
            sweepStaleTemporaries(chat, CHANNEL + "-session-");
            return new ProfileLease(Files.createTempDirectory(chat, CHANNEL + "-session-"), null, null, true);
        } catch (IOException failed) {
            throw new UncheckedIOException("Camoufox profile unavailable under " + profiles, failed);
        }
    }

    /**
     * The profile lock, or null when another browser holds it. A lock held by ANOTHER process (a second AskAI)
     * fails at once; one held inside this JVM (a terminated generation of the same chat still shutting down)
     * is retried until {@code waitMillis} has passed.
     */
    private static FileLock lockReleasedWithin(FileChannel channel, long waitMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        while (true) {
            try {
                return channel.tryLock();
            } catch (OverlappingFileLockException sameJvm) {
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (IOException | RuntimeException busy) {
                return null;
            }
        }
    }

    /** Best effort: remove temporary profiles a crash left behind (a live one is never a day old). */
    private static void sweepStaleTemporaries(Path parent, String prefix) {
        long cutoff = System.currentTimeMillis() - STALE_TEMPORARY_MILLIS;
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                if (name.startsWith(prefix) && !name.endsWith(".lock") && Files.isDirectory(entry)
                        && Files.getLastModifiedTime(entry).toMillis() < cutoff) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    static void deleteRecursively(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** {@code <profiles>/chats/<first 16 hex digits of sha256(chat key)>}: no chat id in a path name. */
    static Path chatProfileDirectory(Path profiles, String chatKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(chatKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i] & 0xff));
            }
            return profiles.resolve("chats").resolve(hex.toString());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void restrictToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the per-user %APPDATA% ACL protects the profile.
        }
    }

    static final class ProfileLease implements AutoCloseable {
        private final Path directory;
        private final FileChannel channel;
        private final FileLock lock;
        private final boolean temporary;

        ProfileLease(Path directory, FileChannel channel, FileLock lock, boolean temporary) {
            this.directory = directory;
            this.channel = channel;
            this.lock = lock;
            this.temporary = temporary;
        }

        Path directory() {
            return directory;
        }

        boolean isTemporary() {
            return temporary;
        }

        @Override
        public void close() {
            try {
                if (lock != null) {
                    lock.release();
                }
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
            }
            if (temporary) {
                deleteRecursively(directory);
            }
        }
    }
}
