package com.aresstack.askai.settings;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppDirectoryMigrationTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void movesEverythingRenamesTheSettingsAndDeletesTheOldFolder() throws Exception {
        Path legacy = temporary.newFolder(".askai-java8").toPath();
        Path target = temporary.getRoot().toPath().resolve(".askai");
        write(legacy.resolve("askai-java8.properties"), "ui.theme=arcane");
        write(legacy.resolve("serpapi.json"), "{\"key\":\"enc\"}");
        write(legacy.resolve("tts/voices/de/voice.onnx"), "x");
        // .askai already holds the local models: the folders merge.
        write(target.resolve("models/local/m1/askai-local-model.json"), "{}");
        write(legacy.resolve("models/gguf/a.gguf"), "g");

        List<String> report = AppDirectoryMigration.migrate(legacy, target);

        assertTrue(report.toString(), report.isEmpty());
        assertFalse("the old folder is gone", Files.exists(legacy));
        assertEquals("ui.theme=arcane", read(target.resolve("askai.properties")));
        assertEquals("{\"key\":\"enc\"}", read(target.resolve("serpapi.json")));
        assertTrue(Files.isRegularFile(target.resolve("tts/voices/de/voice.onnx")));
        assertTrue(Files.isRegularFile(target.resolve("models/gguf/a.gguf")));
        assertTrue(Files.isRegularFile(target.resolve("models/local/m1/askai-local-model.json")));
    }

    @Test
    public void neverOverwritesAndReportsWhatStayed() throws Exception {
        Path legacy = temporary.newFolder(".askai-java8").toPath();
        Path target = temporary.getRoot().toPath().resolve(".askai");
        write(legacy.resolve("askai-tts.properties"), "old");
        write(target.resolve("askai-tts.properties"), "new");

        List<String> report = AppDirectoryMigration.migrate(legacy, target);

        assertEquals("new", read(target.resolve("askai-tts.properties")));
        assertEquals("old", read(legacy.resolve("askai-tts.properties")));
        assertTrue(report.toString(), report.toString().contains("askai-tts.properties"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
