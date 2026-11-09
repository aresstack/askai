package com.aresstack.askai.research.benchmark.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class WriterAbTest {

    private static final List<String> MODELS =
            Arrays.asList(WriterAbArm.GEMMA, WriterAbArm.LLAMA, WriterAbArm.GPT_OSS);

    @Test
    public void planArmsFollowSection54() {
        List<WriterAbArm> arms = WriterAbArm.planArms();
        assertEquals(3, arms.size());
        assertEquals(450, arms.get(0).getWordsPerCall());
        assertEquals(1100, arms.get(1).getWordsPerCall());
        assertEquals("low", arms.get(2).getThinking());
    }

    @Test
    public void metricsCountCitationsUncitedParagraphsAndDrift() {
        String text = "## Heading\n\nDie Energiewende ist ein Prozess [1] und wird von der Politik getragen [2, 9].\n\n"
                + "Ohne Beleg ist das nur eine Behauptung der Autoren.\n\n"
                + "This paragraph is written in the wrong language and it has a source [3].";
        WriterSampleMetrics m = WriterSampleMetrics.measure(text, 8, "de");
        assertEquals(3, m.getParagraphs());
        assertEquals(4, m.getCitations());
        assertEquals(1, m.getInvalidCitations());
        assertEquals(0.75, m.citationValidity(), 1e-9);
        assertEquals(1.0 / 3, m.uncitedParagraphRate(), 1e-9);
        assertEquals(1.0 / 3, m.languageDrift(), 1e-9);
    }

    @Test
    public void emptyTextHasNeutralMetrics() {
        WriterSampleMetrics m = WriterSampleMetrics.measure("", 8, "de");
        assertEquals(0, m.getParagraphs());
        assertEquals(1.0, m.citationValidity(), 1e-9);
        assertEquals(0.0, m.uncitedParagraphRate(), 1e-9);
    }

    @Test
    public void blindSheetAndMetricsNameNoModelButKeyResolves() {
        Random random = new Random(31);
        List<BlindAssignment> assignments = new ArrayList<BlindAssignment>();
        List<WriterAbSample> samples = new ArrayList<WriterAbSample>();
        for (String item : Arrays.asList("section 1", "chapter")) {
            assignments.add(BlindAssignment.shuffle(item, MODELS, random));
            for (String model : MODELS) {
                samples.add(new WriterAbSample(item, model, "Text von " + item + " [1].", 12.5, 900,
                        WriterSampleMetrics.measure("Text [1].", 8, "de"),
                        Collections.singletonMap("writer gaps", 0)));
            }
        }
        String sheet = WriterAbReport.ratingSheet(assignments, samples);
        String metrics = WriterAbReport.metrics(assignments, samples);
        for (String model : MODELS) {
            assertFalse(sheet.contains(model));
            assertFalse(metrics.contains(model));
            assertTrue(WriterAbReport.key(assignments).contains(model));
        }
        assertTrue(metrics.contains("writer gaps"));
        BlindAssignment first = assignments.get(0);
        List<String> resolved = first.resolve(Arrays.asList(first.letterOf(WriterAbArm.LLAMA),
                first.letterOf(WriterAbArm.GEMMA), first.letterOf(WriterAbArm.GPT_OSS)));
        assertEquals(Arrays.asList(WriterAbArm.LLAMA, WriterAbArm.GEMMA, WriterAbArm.GPT_OSS), resolved);
    }

    @Test(expected = IllegalArgumentException.class)
    public void incompleteRankingIsRejected() {
        BlindAssignment.shuffle("section 1", MODELS, new Random(1)).resolve(Arrays.asList("A", "B"));
    }

    @Test
    public void llamaStaysWhenItWinsChapterAndTwoSections() {
        Map<String, List<String>> r = rankings(order(WriterAbArm.LLAMA), order(WriterAbArm.LLAMA),
                order(WriterAbArm.GEMMA), order(WriterAbArm.LLAMA));
        assertEquals(WriterAbArm.LLAMA, WriterAbDecision.decide(r));
    }

    @Test
    public void gemmaWhenLlamaLosesTheChapter() {
        Map<String, List<String>> r = rankings(order(WriterAbArm.LLAMA), order(WriterAbArm.LLAMA),
                order(WriterAbArm.LLAMA), order(WriterAbArm.GEMMA));
        assertEquals(WriterAbArm.GEMMA, WriterAbDecision.decide(r));
    }

    @Test
    public void gemmaWhenLlamaWinsOnlyOneSection() {
        Map<String, List<String>> r = rankings(order(WriterAbArm.LLAMA), order(WriterAbArm.GEMMA),
                order(WriterAbArm.GEMMA), order(WriterAbArm.LLAMA));
        assertEquals(WriterAbArm.GEMMA, WriterAbDecision.decide(r));
    }

    @Test
    public void gptOssOnlyWhenFirstEverywhere() {
        assertEquals(WriterAbArm.GPT_OSS, WriterAbDecision.decide(rankings(order(WriterAbArm.GPT_OSS),
                order(WriterAbArm.GPT_OSS), order(WriterAbArm.GPT_OSS), order(WriterAbArm.GPT_OSS))));
        String notClearly = WriterAbDecision.decide(rankings(order(WriterAbArm.GPT_OSS),
                order(WriterAbArm.GPT_OSS), order(WriterAbArm.LLAMA), order(WriterAbArm.GPT_OSS)));
        assertEquals(WriterAbArm.LLAMA, notClearly);
        assertTrue(WriterAbReport.decision(rankings(order(WriterAbArm.GEMMA), order(WriterAbArm.GEMMA),
                order(WriterAbArm.GEMMA), order(WriterAbArm.GEMMA))).contains("`gemma4:e2b`"));
    }

    /** The given model first; gemma before llama before gpt-oss for the rest, except where named. */
    private static List<String> order(String first) {
        List<String> rest = new ArrayList<String>(Arrays.asList(WriterAbArm.LLAMA, WriterAbArm.GEMMA,
                WriterAbArm.GPT_OSS));
        if (WriterAbArm.GPT_OSS.equals(first)) {
            rest = new ArrayList<String>(Arrays.asList(WriterAbArm.LLAMA, WriterAbArm.GEMMA));
        } else {
            rest.remove(first);
        }
        List<String> order = new ArrayList<String>();
        order.add(first);
        order.addAll(rest);
        return order;
    }

    private static Map<String, List<String>> rankings(List<String> s1, List<String> s2, List<String> s3,
                                                      List<String> chapter) {
        Map<String, List<String>> r = new LinkedHashMap<String, List<String>>();
        r.put("section 1", s1);
        r.put("section 2", s2);
        r.put("section 3", s3);
        r.put(WriterAbDecision.CHAPTER, chapter);
        return r;
    }
}
