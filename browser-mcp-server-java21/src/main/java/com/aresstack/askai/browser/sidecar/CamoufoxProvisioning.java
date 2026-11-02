package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.sidecar.camoufox.CamoufoxDistribution;
import com.aresstack.askai.browser.sidecar.camoufox.CamoufoxInstaller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Where AskAI finds the managed Camoufox (the Argos distribution: pinned, patched, SHA-256 verified) and keeps
 * its browser profile ({@code <APPDATA>/.askai/browser-profiles}). An existing installation (configured,
 * Argos' or AskAI's own) is reused; a download into {@code <APPDATA>/.askai/browser} happens only when
 * none exists. Later starts only verify.
 *
 * <p>While the installer runs, a progress line {@value #PROGRESS_MARKER} goes to STDERR every few seconds:
 * the host restarts its readiness timeout on each one, so the one-time download never counts as a hung
 * start.</p>
 */
final class CamoufoxProvisioning {

    static final String CHANNEL = "camoufox";
    /** Read by the host (BrowserMcpSidecarProcess.CAMOUFOX_INSTALL_MARKER) — keep both in sync. */
    static final String PROGRESS_MARKER = "camoufox install:";
    private static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private CamoufoxProvisioning() {
    }

    static Path appDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        return Paths.get(appData, ".askai");
    }

    /** Explicit Camoufox location: a browser root that holds the pinned install directory, or that directory. */
    static final String ROOT_PROPERTY = "askai.camoufox.root";
    static final String ROOT_ENV = "ASKAI_CAMOUFOX_ROOT";

    /**
     * Where the Camoufox installation lives. An existing verified installation is always reused and never
     * downloaded again: first the explicitly configured location ({@value #ROOT_PROPERTY} /
     * {@value #ROOT_ENV}), then Argos' installation ({@code ~/.argos/browser}), then AskAI's own directory.
     * Only when none of them holds the pinned distribution is AskAI's own directory the download target.
     */
    static Path browserRoot() {
        Path configured = configuredRoot();
        if (configured != null) {
            return configured;
        }
        for (Path candidate : new Path[]{
                Paths.get(System.getProperty("user.home"), ".argos", "browser"),
                appDirectory().resolve("browser")}) {
            if (installedUnder(candidate)) {
                return candidate;
            }
        }
        return appDirectory().resolve("browser");
    }

    private static Path configuredRoot() {
        String value = System.getProperty(ROOT_PROPERTY, "").trim();
        if (value.isEmpty()) {
            String env = System.getenv(ROOT_ENV);
            value = env == null ? "" : env.trim();
        }
        if (value.isEmpty()) {
            return null;
        }
        Path path = Paths.get(value).toAbsolutePath().normalize();
        if (installedUnder(path)) {
            return path;
        }
        // The variable may also name the installation directory itself.
        Path parent = path.getParent();
        if (parent != null && path.getFileName().toString().equals(installDirectoryName()) && installedUnder(parent)) {
            return parent;
        }
        throw new IllegalStateException("No verified Camoufox " + installDirectoryName() + " under " + path
                + " (" + ROOT_PROPERTY + " / " + ROOT_ENV + "); nothing is downloaded for a configured location");
    }

    private static boolean installedUnder(Path root) {
        return Files.isDirectory(root) && new CamoufoxInstaller(root, CamoufoxDistribution.builtIn(),
                CamoufoxDistribution.currentTarget()).isInstalled();
    }

    private static String installDirectoryName() {
        return CamoufoxDistribution.builtIn().name() + "-" + CamoufoxDistribution.currentTarget();
    }

    /**
     * The verified Camoufox executable. An existing installation is reused (see {@link #browserRoot()}); only
     * when none exists is it downloaded and installed first (blocking).
     */
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
     * The browser profile for this sidecar. One persistent profile (cookies, consent answers survive) owned
     * by the first sidecar that locks it; a concurrent research session gets a temporary profile that is
     * deleted on {@link ProfileLease#close()} — two Firefox processes can never share one profile.
     */
    static ProfileLease leaseProfile() {
        return leaseProfile(appDirectory().resolve("browser-profiles"));
    }

    static ProfileLease leaseProfile(Path profiles) {
        try {
            Files.createDirectories(profiles);
            FileChannel channel = FileChannel.open(profiles.resolve(CHANNEL + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (IOException | RuntimeException busy) {
                lock = null;
            }
            if (lock != null) {
                Path persistent = profiles.resolve(CHANNEL);
                Files.createDirectories(persistent);
                return new ProfileLease(persistent, channel, lock, false);
            }
            channel.close();
            return new ProfileLease(Files.createTempDirectory(profiles, CHANNEL + "-session-"), null, null, true);
        } catch (IOException failed) {
            throw new UncheckedIOException("Camoufox profile unavailable under " + profiles, failed);
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
                try (Stream<Path> walk = Files.walk(directory)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
    }
}
