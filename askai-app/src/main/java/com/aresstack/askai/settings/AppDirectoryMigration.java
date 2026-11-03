package com.aresstack.askai.settings;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the data of the former app directory {@code .askai-java8} into {@code .askai} (askai #51). Runs once at
 * startup, before anything reads the configuration:
 * <ul>
 * <li>every entry missing in the new directory is MOVED (a rename on the same drive — no copy, no extra space);
 *     directories present on both sides are merged entry by entry;</li>
 * <li>an entry that already exists in the new directory is never overwritten; it stays in the old directory and
 *     is reported;</li>
 * <li>the former settings file {@code askai-java8.properties} becomes {@code askai.properties};</li>
 * <li>directories left empty are deleted, so the old directory disappears completely when nothing conflicted.</li>
 * </ul>
 * Secrets (provider keys, AES key files, OAuth refresh tokens) move with their files unchanged.
 */
public final class AppDirectoryMigration {

    static final String LEGACY_SETTINGS_FILE = "askai-java8.properties";

    private AppDirectoryMigration() {
    }

    /** Migrates every existing former directory into {@link AskAiPaths#appDirectory()}; never throws. */
    public static List<String> run() {
        List<String> report = new ArrayList<String>();
        for (Path legacy : AskAiPaths.legacyAppDirectories()) {
            report.addAll(migrate(legacy, AskAiPaths.appDirectory()));
        }
        for (String line : report) {
            // the per-file list is its own category (off by default, Settings > General > Logging)
            System.err.println((line.startsWith("  ") ? "[data-migration] " : "[askai] ") + line);
        }
        return report;
    }

    /** @return what was left behind (conflicts, failures); empty when everything moved. */
    static List<String> migrate(Path legacy, Path target) {
        List<String> report = new ArrayList<String>();
        if (!Files.isDirectory(legacy) || legacy.equals(target)) {
            return report;
        }
        try {
            Files.createDirectories(target);
            Path legacySettings = legacy.resolve(LEGACY_SETTINGS_FILE);
            Path settings = target.resolve("askai.properties");
            if (Files.isRegularFile(legacySettings) && !Files.exists(settings)) {
                Files.move(legacySettings, settings);
            }
            merge(legacy, target, report);
            deleteIfEmpty(legacy);
            if (Files.exists(legacy)) {
                report.add(0, "data folder " + legacy + " was moved to " + target + " except:");
            }
        } catch (IOException | RuntimeException failed) {
            report.add("data folder " + legacy + " could not be moved to " + target + ": " + failed);
        }
        return report;
    }

    private static void merge(Path from, Path to, List<String> report) throws IOException {
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(from)) {
            for (Path entry : stream) {
                entries.add(entry);
            }
        }
        for (Path entry : entries) {
            Path destination = to.resolve(entry.getFileName().toString());
            if (!Files.exists(destination)) {
                try {
                    Files.move(entry, destination);
                } catch (IOException inUse) {
                    report.add("  " + entry + " (" + inUse + ")");
                }
            } else if (Files.isDirectory(entry) && Files.isDirectory(destination)) {
                merge(entry, destination, report);
                deleteIfEmpty(entry);
            } else {
                report.add("  " + entry + " (already exists in " + to + ", left unchanged)");
            }
        }
    }

    private static void deleteIfEmpty(Path directory) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            if (stream.iterator().hasNext()) {
                return;
            }
        }
        Files.delete(directory);
    }
}
