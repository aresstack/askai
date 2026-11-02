package com.aresstack.askai.java8.ui.setup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When the setup wizard opens on its own at startup. */
public class SetupWizardStateTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aFreshInstallationOpensTheWizardUntilItIsCompleted() throws IOException {
        File dir = folder.newFolder("askai");
        SetupWizardState state = new SetupWizardState(new File(dir, "setup-wizard.properties").toPath(),
                new File(dir, "askai.properties").toPath());
        assertTrue(state.shouldOpenAtStartup());
        // AskAI writes its settings during the first run; a setup that was not finished still returns.
        assertTrue(new File(dir, "askai.properties").createNewFile());
        assertTrue("interrupted setup opens again", state.shouldOpenAtStartup());

        state.markCompleted();

        assertTrue(state.isCompleted());
        assertFalse(state.shouldOpenAtStartup());
    }

    @Test
    public void anInstallationFromBeforeTheWizardIsLeftAlone() throws IOException {
        File dir = folder.newFolder("existing");
        assertTrue(new File(dir, "askai.properties").createNewFile());
        SetupWizardState state = new SetupWizardState(new File(dir, "setup-wizard.properties").toPath(),
                new File(dir, "askai.properties").toPath());
        assertFalse(state.shouldOpenAtStartup());
        assertFalse(new File(dir, "setup-wizard.properties").exists());
    }
}
