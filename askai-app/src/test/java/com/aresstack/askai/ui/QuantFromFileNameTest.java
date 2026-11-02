package com.aresstack.askai.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The quantization shown on the Install tab is derived from the GGUF file name, not typed by hand. */
public class QuantFromFileNameTest {

    @Test
    public void extractsQuantTokenUpperCased() {
        assertEquals("Q4_K_M", OllamaInstallPanel.quantFromFileName("mistral-7b-instruct-q4_k_m.gguf"));
        assertEquals("Q8_0", OllamaInstallPanel.quantFromFileName("Model-Q8_0.GGUF"));
        assertEquals("Q4_0", OllamaInstallPanel.quantFromFileName("gemma-2b.q4_0.gguf"));
    }

    @Test
    public void returnsEmptyWhenNoRecognizedQuant() {
        assertEquals("", OllamaInstallPanel.quantFromFileName("model-f16.gguf"));
        assertEquals("", OllamaInstallPanel.quantFromFileName(""));
        assertEquals("", OllamaInstallPanel.quantFromFileName(null));
    }
}
