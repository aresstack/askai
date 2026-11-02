package com.aresstack.askai.ui;

import com.aresstack.askai.config.HuggingFaceSearchSuggestion.Target;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** The dropdown groups AskAI-local-engine suggestions under their own heading, distinct from general imports. */
public class LocalEngineSuggestionRenderingTest {

    @Test
    public void groupHeadersDistinguishLocalEngineFromGeneral() {
        assertEquals("ASKAI LOCAL ENGINE", OllamaInstallPanel.groupHeader(Target.ASKAI_LOCAL_ENGINE));
        assertEquals("GENERAL / OLLAMA IMPORT", OllamaInstallPanel.groupHeader(Target.GENERAL));
        assertNotEquals(OllamaInstallPanel.groupHeader(Target.ASKAI_LOCAL_ENGINE),
                OllamaInstallPanel.groupHeader(Target.GENERAL));
    }
}
