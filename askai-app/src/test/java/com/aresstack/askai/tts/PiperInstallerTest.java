package com.aresstack.askai.tts;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The installer's local plumbing: safe zip extraction and hashing (network paths not covered). */
public class PiperInstallerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void extractsNestedEntries() throws Exception {
        Path zip = temp.getRoot().toPath().resolve("ok.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("piper/piper.exe"));
            out.write("exe".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("piper/espeak-ng-data/de_dict"));
            out.write("dict".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path target = temp.getRoot().toPath().resolve("out");
        PiperInstaller.extractZip(zip, target);
        assertTrue(Files.isRegularFile(target.resolve("piper").resolve("piper.exe")));
        assertTrue(Files.isRegularFile(
                target.resolve("piper").resolve("espeak-ng-data").resolve("de_dict")));
    }

    @Test
    public void refusesZipSlipEntries() throws Exception {
        Path zip = temp.getRoot().toPath().resolve("evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../evil.exe"));
            out.write("boom".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try {
            PiperInstaller.extractZip(zip, temp.getRoot().toPath().resolve("out"));
            fail("zip-slip entry must be refused");
        } catch (IOException refused) {
            assertTrue(refused.getMessage().contains("escapes"));
        }
        assertTrue("nothing escaped the target",
                !Files.exists(temp.getRoot().toPath().resolve("evil.exe")));
    }

    @Test
    public void sha256MatchesAKnownVector() throws Exception {
        Path file = temp.getRoot().toPath().resolve("abc.txt");
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PiperInstaller.sha256(file));
    }
}
