package com.aresstack.askai.research.benchmark.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.askai.research.drafting.EvidenceAssignments;
import com.aresstack.askai.research.runtime.writer.EvidencePassage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.Test;

public class WriterQualityMetricsTest {

    private static final List<EvidencePassage> PACK = Arrays.asList(
            new EvidencePassage(1, "a", "c", "p1", "x"), new EvidencePassage(2, "b", "c", "p2", "y"),
            new EvidencePassage(3, "a", "c", "p3", "z"), new EvidencePassage(4, "c", "c", "p4", "w"));

    /** A sentence's vector is its first word, so sentences opening with the same word "repeat" each other. */
    private static final Function<List<String>, List<float[]>> FIRST_WORD = texts -> {
        List<float[]> out = new ArrayList<float[]>();
        for (String text : texts) {
            float[] v = new float[64];
            v[Math.abs(text.split("\\s+")[0].hashCode()) % 64] = 1f;
            out.add(v);
        }
        return out;
    };

    @Test
    public void countsRepetitionRestartsBlocksAndSources() {
        String text = "Sensoren messen den Puls der Beschäftigten [1]. Daten fließen an die Leitstelle [1]. "
                + "Hitze belastet Arbeiter stark [2, 3].\n\n"
                + "Sensoren erfassen den Puls ebenfalls [1]. Ohne Beleg bleibt dieser Satz hier.\n\nGAP: Kosten";
        WriterQualityMetrics m = WriterQualityMetrics.measure(text, PACK, 40, FIRST_WORD);
        assertEquals(5, m.getSentences());
        assertEquals(2, m.getParagraphs());
        assertEquals(1, m.getShortParagraphs());
        assertEquals(1, m.getRestartParagraphs());
        assertEquals(5.0 / m.getWords(), m.repetition(), 1e-9);
        assertEquals(1, m.getUncitedSentences());
        assertEquals(0.25, m.sourceBlocks(), 1e-9);
        assertEquals(0.25, m.multiSource(), 1e-9);
        assertEquals(0.75, m.packUse(), 1e-9);
        assertEquals(m.getWords() / 40.0, m.targetReach(), 1e-9);
    }

    @Test
    public void chapterRepetitionLooksAcrossSections() {
        double share = WriterQualityMetrics.chapterRepetition(Arrays.asList("Sensoren messen [1].",
                "Sensoren zählen [2]. Hitze wirkt [1]."), FIRST_WORD);
        assertEquals(2.0 / 6, share, 1e-9);
        assertEquals(0, WriterQualityMetrics.chapterRepetition(Arrays.asList("A b [1]."), null), 1e-9);
    }

    @Test
    public void sentencesKeepTheirMarkersAndSkipHeadings() {
        List<WriterQualityMetrics.Sentence> s = WriterQualityMetrics.sentences(
                "## Titel\n\nErster Satz endet hier. [2] Zweiter Satz „zitiert“ [1, 3]. 2024 kam ein dritter.");
        assertEquals(3, s.size());
        assertEquals(Arrays.asList(2), s.get(0).cited);
        assertEquals(Arrays.asList(1, 3), s.get(1).cited);
        assertTrue(s.get(2).cited.isEmpty());
    }

    @Test
    public void variantsAreSettingsTheAppReads() {
        List<WriterAbVariant> variants = WriterAbVariant.parse("app,lean:research.writer.wordsPerPassage=0;"
                + "research.writer.topUp.maxCalls=0", Collections.singletonMap("app",
                "research.writer.passages=6"));
        assertEquals(2, variants.size());
        EvidenceAssignments.Targets app = variants.get(0).targets(0);
        assertEquals(6, app.getPack().getPassageCount());
        assertEquals(450, app.getWordsPerCall());
        EvidenceAssignments.Targets lean = variants.get(1).targets(1100);
        assertEquals(0, lean.getWordsPerPassage());
        assertEquals(0, lean.getTopUp().getMaxCalls());
        assertEquals(1100, lean.getWordsPerCall());
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnknownVariantIsRejected() {
        WriterAbVariant.parse("nonsense", null);
    }
}
