package com.aresstack.askai.text;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The manual-service lifecycle (first listener starts, last release stops) and the segmentation
 * contract: same-language sentence runs merge into segments, unknown languages stay in the
 * fallback, sentence-wise off joins each segment into one chunk, and a missing engine degrades to
 * the single-segment pre-analysis behavior.
 */
public class ChatTextAnalysisServiceTest {

    /** Scripted engine: sentences split on '|', language = leading "DE:"/"EN:" tag (else unknown). */
    private static final class FakeEngine implements ChatTextAnalysisService.Engine {
        boolean closed;

        public ChatTextAnalysisService.LanguageDetector languageDetector() {
            return new ChatTextAnalysisService.LanguageDetector() {
                public String detectLanguage(String sentence) {
                    if (sentence.startsWith("DE:")) {
                        return "de";
                    }
                    if (sentence.startsWith("EN:")) {
                        return "en";
                    }
                    return "";
                }
            };
        }

        public com.aresstack.askai.research.knowledge.SentenceSegmentationPort splitterFor(
                String languageCode) {
            return new com.aresstack.askai.research.knowledge.SentenceSegmentationPort() {
                public List<String> segment(String text) {
                    List<String> sentences = new ArrayList<String>();
                    for (String part : text.split("\\|")) {
                        if (!part.trim().isEmpty()) {
                            sentences.add(part.trim());
                        }
                    }
                    return sentences;
                }
            };
        }

        public void close() {
            closed = true;
        }
    }

    private FakeEngine engine;
    private AtomicInteger loads;
    private ChatTextAnalysisService service;

    private ChatTextAnalysisService build() {
        loads = new AtomicInteger();
        service = new ChatTextAnalysisService(new ChatTextAnalysisService.EngineLoader() {
            public ChatTextAnalysisService.Engine load() {
                loads.incrementAndGet();
                engine = new FakeEngine();
                return engine;
            }
        }, 0); // grace 0: release stops immediately — deterministic tests
        return service;
    }

    @Test
    public void firstListenerStartsLastReleaseStops() {
        build();
        assertFalse(service.isRunning());
        Object a = new Object();
        Object b = new Object();
        service.acquire(a);
        assertTrue(service.isRunning());
        assertEquals(1, loads.get());
        service.acquire(b);
        assertEquals("a second listener never reloads", 1, loads.get());
        service.release(a);
        assertTrue("one listener left — the service keeps running", service.isRunning());
        service.release(b);
        assertFalse("the LAST release stops the service", service.isRunning());
        assertTrue("the engine was closed", engine.closed);
        service.acquire(a);
        assertEquals("a new listener starts it again", 2, loads.get());
    }

    @Test
    public void paragraphsBecomeChunksAndSameLanguageRunsMergeIntoSegments() {
        build();
        service.acquire(this);
        List<ChatTextAnalysisService.Segment> segments = service.segment(
                "DE: eins.|DE: zwei.\n\nEN: three.\n\nDE: vier.", "de", true, ChatTextAnalysisService.Granularity.PARAGRAPHS);
        assertEquals(3, segments.size());
        assertEquals("de", segments.get(0).getLanguageCode());
        assertEquals("a paragraph is ONE chunk (its sentences joined) — sentence-sized chunks"
                        + " caused audible gaps", Arrays.asList("DE: eins. DE: zwei."),
                segments.get(0).getSentences());
        assertEquals("en", segments.get(1).getLanguageCode());
        assertEquals("de", segments.get(2).getLanguageCode());
    }

    @Test
    public void aLanguageSwitchInsideAParagraphStillSplitsTheChunk() {
        build();
        service.acquire(this);
        List<ChatTextAnalysisService.Segment> segments = service.segment(
                "DE: eins.|kurz.|EN: three.", "de", true, ChatTextAnalysisService.Granularity.PARAGRAPHS);
        assertEquals("the unknown middle sentence merges into the German run", 2, segments.size());
        assertEquals(Arrays.asList("DE: eins. kurz."), segments.get(0).getSentences());
        assertEquals(Arrays.asList("EN: three."), segments.get(1).getSentences());
    }

    @Test
    public void paragraphChunksOffJoinsEachSegmentIntoOneChunk() {
        build();
        service.acquire(this);
        List<ChatTextAnalysisService.Segment> segments = service.segment(
                "DE: eins.\n\nDE: zwei.\n\nEN: three.", "de", true, ChatTextAnalysisService.Granularity.ANSWER);
        assertEquals(2, segments.size());
        assertEquals("consecutive same-language paragraphs join into one chunk",
                Arrays.asList("DE: eins. DE: zwei."), segments.get(0).getSentences());
        assertEquals(Arrays.asList("EN: three."), segments.get(1).getSentences());
    }

    @Test
    public void languageDetectionOffKeepsEverythingInTheFallbackButStillChunksParagraphs() {
        build();
        service.acquire(this);
        List<ChatTextAnalysisService.Segment> segments = service.segment(
                "DE: eins.\n\nEN: three.", "de", false, ChatTextAnalysisService.Granularity.PARAGRAPHS);
        assertEquals("no NLP language detection when the checkbox is off", 1, segments.size());
        assertEquals("de", segments.get(0).getLanguageCode());
        assertEquals("one chunk per paragraph", 2, segments.get(0).getSentences().size());
    }

    @Test
    public void sentenceGranularityYieldsOneChunkPerSentence() {
        build();
        service.acquire(this);
        List<ChatTextAnalysisService.Segment> segments = service.segment(
                "DE: eins.|DE: zwei.\n\nEN: three.", "de", true,
                ChatTextAnalysisService.Granularity.SENTENCES);
        assertEquals(2, segments.size());
        assertEquals("the user's per-sentence choice: every sentence is its own chunk",
                Arrays.asList("DE: eins.", "DE: zwei."), segments.get(0).getSentences());
        assertEquals(Arrays.asList("EN: three."), segments.get(1).getSentences());
    }

    @Test
    public void withoutARunningEngineTheTextStaysOneSegment() {
        build(); // never acquired — the service is not running
        List<ChatTextAnalysisService.Segment> segments = service.segment(
                "DE: eins.|EN: three.", "de", true, ChatTextAnalysisService.Granularity.PARAGRAPHS);
        assertEquals(1, segments.size());
        assertEquals("de", segments.get(0).getLanguageCode());
        assertEquals("the pre-analysis behavior: the whole text, one voice",
                1, segments.get(0).getSentences().size());
    }
}
