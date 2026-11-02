package com.aresstack.askai.state;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The application-state store persists values across instances and tolerates a missing/corrupt file. */
public class ApplicationStateServiceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void storesAndReloadsValuesAcrossInstances() throws Exception {
        File file = new File(folder.newFolder("state"), "application-state.json");
        ApplicationStateService first = new ApplicationStateService(file);
        first.putAndSave("audio.selectedProfileId", "crystal-voice");
        first.putAndSave("chat.reasoningEffort", "medium");
        first.putAndSave("chat.think", "true");

        assertTrue(file.isFile());
        ApplicationStateService reloaded = new ApplicationStateService(file);
        assertEquals("crystal-voice", reloaded.get("audio.selectedProfileId", "off"));
        assertEquals("medium", reloaded.get("chat.reasoningEffort", "off"));
        assertTrue(reloaded.getBoolean("chat.think", false));
    }

    @Test
    public void missingFileReturnsDefaults() throws Exception {
        File file = new File(folder.newFolder("state"), "does-not-exist.json");
        ApplicationStateService state = new ApplicationStateService(file);
        assertEquals("default-speech", state.get("audio.selectedProfileId", "default-speech"));
        assertEquals(true, state.getBoolean("chat.think", true));
    }

    @Test
    public void corruptFileStartsEmptyInsteadOfThrowing() throws Exception {
        File file = new File(folder.newFolder("state"), "application-state.json");
        Writer writer = new FileWriter(file);
        writer.write("{ this is not valid json ][");
        writer.close();

        ApplicationStateService state = new ApplicationStateService(file);
        assertEquals("off", state.get("audio.selectedProfileId", "off"));
        state.putAndSave("chat.lastModel", "llama-3.1-8b-instruct");
        assertEquals("llama-3.1-8b-instruct",
                new ApplicationStateService(file).get("chat.lastModel", ""));
    }

    @Test
    public void nullValueRemovesTheKey() throws Exception {
        File file = new File(folder.newFolder("state"), "application-state.json");
        ApplicationStateService state = new ApplicationStateService(file);
        state.putAndSave("chat.agent", "researcher");
        state.putAndSave("chat.agent", null);
        assertEquals("none", new ApplicationStateService(file).get("chat.agent", "none"));
    }
}
