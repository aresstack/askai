package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The curated catalog offers exactly the official de/en 1.5 sentence detectors and the Apache
 * langdetect model, with correct metadata + pins.
 */
public class ApacheOpenNlpModelCatalogProviderTest {

    @Test
    public void offersTheSentenceDetectorsAndTheLanguageDetector() {
        List<NlpModelCatalogEntry> models = new ApacheOpenNlpModelCatalogProvider().availableModels();
        assertEquals(3, models.size());
        for (NlpModelCatalogEntry e : models) {
            assertEquals("opennlp", e.getImplementation());
            assertEquals("1.9.4", e.getCompatibleRuntime());
            assertTrue("pinned sha", e.getExpectedSha256().length() == 64);
            assertTrue("pinned size", e.getExpectedSize() > 0);
        }
        assertEquals(NlpCapability.SENTENCE_DETECTION, models.get(0).getCapability());
        assertEquals(NlpCapability.SENTENCE_DETECTION, models.get(1).getCapability());
        assertEquals("de", models.get(0).getLanguageCode());
        assertEquals("en", models.get(1).getLanguageCode());
        assertEquals("de-sent.bin", models.get(0).getArtifactFileName());
        assertEquals("en-sent.bin", models.get(1).getArtifactFileName());
        assertTrue("curated SourceForge 1.5 source",
                models.get(0).getSourceUrl().contains("opennlp/files/models-1.5/"));

        NlpModelCatalogEntry langdetect = models.get(2);
        assertEquals(NlpCapability.LANGUAGE_DETECTION, langdetect.getCapability());
        assertEquals("the detector is language-neutral", "*", langdetect.getLanguageCode());
        assertEquals("langdetect-183.bin", langdetect.getArtifactFileName());
        assertTrue("official Apache dist source",
                langdetect.getSourceUrl().startsWith("https://dlcdn.apache.org/opennlp/models/"));
    }
}
