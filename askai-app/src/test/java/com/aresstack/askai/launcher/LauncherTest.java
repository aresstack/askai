package com.aresstack.askai.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LauncherTest {

    @Test
    public void java21OrNewerRunsHereAndNothingIsSearched() {
        assertEquals(Launcher.Decision.RUN_HERE, Launcher.decide(21, false));
        assertEquals(Launcher.Decision.RUN_HERE, Launcher.decide(25, false));
        assertEquals(Launcher.Decision.RUN_HERE, Launcher.decide(25, true));
        assertEquals(Launcher.Decision.RUN_HERE, Launcher.decide(Launcher.runningJava(), false));
    }

    @Test
    public void anOlderJavaSearchesAndRestartsOnce() {
        assertEquals(Launcher.Decision.SEARCH_AND_RESTART, Launcher.decide(8, false));
        assertEquals(Launcher.Decision.SEARCH_AND_RESTART, Launcher.decide(17, false));
        assertEquals(Launcher.Decision.SEARCH_AND_RESTART, Launcher.decide(-1, false));
        // Already the restart and still too old: stop, never restart in a loop.
        assertEquals(Launcher.Decision.STOP_LOOP, Launcher.decide(8, true));
        assertEquals(Launcher.Decision.STOP_LOOP, Launcher.decide(20, true));
    }

    @Test
    public void theAppIsStartedFromTheManifestOrTheDefault() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        assertEquals(Launcher.DEFAULT_START_CLASS, Launcher.startClass(manifest));
        assertEquals(Launcher.DEFAULT_START_CLASS, Launcher.startClass(null));
        manifest.getMainAttributes().putValue("Start-Class", " com.example.Other ");
        assertEquals("com.example.Other", Launcher.startClass(manifest));
    }

    @Test
    public void theDefaultStartClassIsTheApp() {
        assertEquals(Launcher.DEFAULT_START_CLASS, com.aresstack.askai.AskAiApp.class.getName());
    }

    @Test
    public void theRestartKeepsPropertiesHeapAndArgumentsAndDropsTheRest() {
        List<String> command = Launcher.relaunchCommand("C:\\zulu-21\\bin\\javaw.exe",
                Arrays.asList("-Dfile.encoding=UTF-8", "-Xmx4g", "-XX:+UseG1GC", "-agentlib:jdwp=transport=dt_socket",
                        "-Daskai.launcher.relaunched=false", "-Daskai.theme=classic"),
                "askai-0.1.0.jar", new String[]{"--some", "argument with space"});
        assertEquals(Arrays.asList("C:\\zulu-21\\bin\\javaw.exe", "-Dfile.encoding=UTF-8", "-Xmx4g",
                "-Daskai.theme=classic", "-Daskai.launcher.relaunched=true", "-cp", "askai-0.1.0.jar",
                "com.aresstack.askai.launcher.Launcher", "--some", "argument with space"), command);
    }

    @Test
    public void theDownloadPageIsAzulZuluForJava21() {
        String windows = Launcher.downloadUrl("Windows 11");
        assertTrue(windows, windows.startsWith("https://www.azul.com/downloads/?version=java-21-lts"));
        assertTrue(windows, windows.contains("os=windows"));
        assertTrue(windows, windows.endsWith("package=jdk#zulu"));
        assertTrue(Launcher.downloadUrl("Linux").contains("os=linux"));
        assertFalse(Launcher.downloadUrl("SunOS").contains("os="));
    }

    @Test
    public void theMessageSaysWhatWasFoundAndWhereToGetJava() {
        String url = Launcher.downloadUrl("Windows 10");
        String message = Launcher.missingJavaMessage(8, url, Collections.<String>emptyList());
        assertTrue(message, message.contains("64-bit Java 21 or newer"));
        assertTrue(message, message.contains("started with Java 8"));
        assertTrue(message, message.contains(url));
        assertTrue(message, message.contains("ASKAI_JAVA"));
        assertFalse(message, message.contains("Named with"));
    }

    @Test
    public void theMessageNamesANamedJavaThatDidNotFit() {
        String message = Launcher.missingJavaMessage(8, Launcher.downloadUrl("Windows 10"),
                Collections.singletonList("D:\\tools\\jdk-17"));
        assertTrue(message, message.contains("Named with ASKAI_JAVA / -Daskai.java, but no 64-bit Java 21+ there:\n"
                + "D:\\tools\\jdk-17"));
    }

    @Test
    public void theMemoryLivesNextToTheAppSettings() {
        assertEquals("launcher.properties", Launcher.memoryFile().getName());
        assertEquals(".askai", Launcher.memoryFile().getParentFile().getName());
    }
}
