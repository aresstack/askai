package com.aresstack.askai.config;

import com.aresstack.askai.agent.model.nlp.NlpCapability;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Per-capability+language NLP selections: set/get, empty clears, entry keys, persistence round-trip shape. */
public class NlpModelSelectionsTest {

    @Test
    public void setsAndReadsPerCapabilityAndLanguage() {
        NlpModelSelections s = NlpModelSelections.defaults()
                .withModelId(NlpCapability.SENTENCE_DETECTION, "de", "apache-opennlp/sentence-de")
                .withModelId(NlpCapability.SENTENCE_DETECTION, "EN ", "apache-opennlp/sentence-en");

        assertEquals("apache-opennlp/sentence-de", s.getModelId(NlpCapability.SENTENCE_DETECTION, "de"));
        assertEquals("apache-opennlp/sentence-en", s.getModelId(NlpCapability.SENTENCE_DETECTION, "en"));
        assertEquals("", s.getModelId(NlpCapability.SENTENCE_DETECTION, "fr"));
    }

    @Test
    public void anEmptyValueClearsTheSelection() {
        NlpModelSelections s = NlpModelSelections.defaults()
                .withModelId(NlpCapability.SENTENCE_DETECTION, "de", "x")
                .withModelId(NlpCapability.SENTENCE_DETECTION, "de", "");
        assertEquals("", s.getModelId(NlpCapability.SENTENCE_DETECTION, "de"));
        assertTrue(s.entries().isEmpty());
    }

    @Test
    public void entriesUsePrefixFreeCapabilityDotLanguageKeys() {
        NlpModelSelections s = NlpModelSelections.defaults()
                .withModelId(NlpCapability.SENTENCE_DETECTION, "de", "m");
        assertEquals("m", s.entries().get("sentence-detection.de"));
    }

    @Test
    public void roundTripsThroughEntries() {
        Map<String, String> raw = new HashMap<String, String>();
        raw.put("sentence-detection.de", "m-de");
        raw.put("sentence-detection.en", "m-en");
        raw.put("ignored-empty", "");
        NlpModelSelections s = NlpModelSelections.fromEntries(raw);
        assertEquals("m-de", s.getModelId(NlpCapability.SENTENCE_DETECTION, "de"));
        assertEquals(2, s.entries().size());
    }

    @Test
    public void equalityIncludesTheSelections() {
        NlpModelSelections a = NlpModelSelections.defaults()
                .withModelId(NlpCapability.SENTENCE_DETECTION, "de", "m");
        NlpModelSelections b = NlpModelSelections.defaults()
                .withModelId(NlpCapability.SENTENCE_DETECTION, "de", "m");
        assertEquals(a, b);
        assertNotEquals(a, NlpModelSelections.defaults());
    }
}
