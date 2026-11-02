package com.aresstack.askai.ui;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The About "Copy diagnostics" text must carry environment info but never any credential. */
public class AboutDiagnosticsTest {

    @Test
    public void includesEnvironmentDetails() {
        String diagnostics = OllamaAboutPanel.diagnostics();
        assertTrue(diagnostics, diagnostics.contains("AskAI"));
        assertTrue(diagnostics, diagnostics.contains("Java:"));
        assertTrue(diagnostics, diagnostics.contains("OS:"));
    }

    @Test
    public void neverLeaksAToken() {
        String diagnostics = OllamaAboutPanel.diagnostics().toLowerCase(Locale.ROOT);
        assertFalse(diagnostics, diagnostics.contains("token"));
        assertFalse(diagnostics, diagnostics.contains("hf_"));
    }
}
