package com.aresstack.askai.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ON-DEMAND chat-text analysis service: splits a message into sentences and detects the
 * language of each, so read-aloud can route German and English passages to their own voices and
 * synthesize sentence by sentence. Lifecycle like a manually started Windows service: it does NOT
 * run permanently — a feature that needs it {@link #acquire(Object) acquires} it with a listener
 * token (the first listener starts the engine, i.e. loads the OpenNLP models), and the engine
 * stops only after the LAST listener {@link #release(Object) released} (with a short grace period
 * so back-to-back utterances do not reload the models every time).
 *
 * <p>Degradation is built in, never an error: without a loaded engine (models not installed, or
 * no listener) {@link #segment} returns the whole text as ONE segment in the fallback language —
 * exactly the pre-analysis behavior.</p>
 */
public final class ChatTextAnalysisService {

    /** One same-language run of the text, in reading order. */
    public static final class Segment {
        private final String languageCode;
        private final List<String> sentences;

        public Segment(String languageCode, List<String> sentences) {
            this.languageCode = languageCode;
            this.sentences = Collections.unmodifiableList(new ArrayList<String>(sentences));
        }

        public String getLanguageCode() {
            return languageCode;
        }

        /** The chunks to synthesize, in order (one per sentence, or one joined chunk). */
        public List<String> getSentences() {
            return sentences;
        }
    }

    /** How the spoken chunks are cut — the user's choice in the settings. */
    public enum Granularity {
        /** One chunk per same-language segment (the whole answer when monolingual). */
        ANSWER,
        /** One chunk per paragraph (blank-line breaks). */
        PARAGRAPHS,
        /** One chunk per sentence. */
        SENTENCES
    }

    /** Detects a sentence's language: ISO-639-1 ("de"/"en") or "" when unknown/unsupported. */
    public interface LanguageDetector {
        String detectLanguage(String sentence);
    }

    /** The loaded analysis engine; closed when the service stops. */
    public interface Engine {
        /** @return the detector, or null when the language-detection model is not installed. */
        LanguageDetector languageDetector();

        /**
         * The sentence splitter for this language — the knowledge pipeline's CANONICAL
         * {@code SentenceSegmentationPort} (shared with the source review, never a second
         * implementation); never null (a naive fallback is fine).
         */
        com.aresstack.askai.research.knowledge.SentenceSegmentationPort splitterFor(
                String languageCode);

        void close();
    }

    /** Loads the engine from the installed models when the first listener arrives. */
    public interface EngineLoader {
        Engine load();
    }

    private final EngineLoader loader;
    private final long stopGraceMillis;
    private final Map<Object, Boolean> listeners = new IdentityHashMap<Object, Boolean>();
    private Engine engine;
    private long stopGeneration;

    public ChatTextAnalysisService(EngineLoader loader, long stopGraceMillis) {
        this.loader = loader;
        this.stopGraceMillis = Math.max(0, stopGraceMillis);
    }

    /** Register a listener; the FIRST one starts the engine (loads the installed models). */
    public synchronized void acquire(Object listener) {
        listeners.put(listener, Boolean.TRUE);
        stopGeneration++; // any pending grace-stop is void — the service is wanted again
        if (engine == null) {
            try {
                engine = loader.load();
            } catch (RuntimeException brokenModels) {
                System.err.println("[text-analysis] engine load failed: " + brokenModels);
                engine = null; // degrade: segment() falls back to the single-segment path
            }
        }
    }

    /** Deregister; after the LAST listener the engine stops (after the grace period). */
    public synchronized void release(Object listener) {
        listeners.remove(listener);
        if (!listeners.isEmpty()) {
            return;
        }
        final long myGeneration = ++stopGeneration;
        if (stopGraceMillis == 0) {
            stopNow(myGeneration);
            return;
        }
        Thread stopper = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(stopGraceMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                stopNow(myGeneration);
            }
        }, "askai-text-analysis-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    private synchronized void stopNow(long myGeneration) {
        if (myGeneration != stopGeneration || !listeners.isEmpty()) {
            return; // re-acquired (or superseded) during the grace period — keep running
        }
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    public synchronized boolean isRunning() {
        return engine != null;
    }

    public synchronized boolean isLanguageDetectionAvailable() {
        return engine != null && engine.languageDetector() != null;
    }

    /** One same-language run inside one paragraph: the sentences it consists of. */
    private static final class Run {
        final String language;
        final List<String> sentences = new ArrayList<String>();

        Run(String language) {
            this.language = language;
        }

        String joined() {
            StringBuilder text = new StringBuilder();
            for (String sentence : sentences) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(sentence);
            }
            return text.toString();
        }
    }

    /**
     * Split {@code text} into same-language segments whose chunks follow the requested
     * {@link Granularity}: whole segment, per paragraph (blank-line breaks — the flattened chat
     * text preserves them) or per sentence. Sentences always come from the knowledge pipeline's
     * canonical splitter; language detection votes per sentence, and a language change inside a
     * paragraph still splits cleanly.
     *
     * @param detectLanguages when false, NLP language detection is not used AT ALL (the whole
     *                        text stays in the fallback language)
     */
    public synchronized List<Segment> segment(String text, String fallbackLanguage,
                                              boolean detectLanguages, Granularity granularity) {
        String value = text == null ? "" : text.trim();
        List<Segment> single = Collections.singletonList(
                new Segment(fallbackLanguage, Collections.singletonList(value)));
        if (value.isEmpty()) {
            return single;
        }
        if (engine == null || (!detectLanguages && granularity == Granularity.ANSWER)) {
            return single; // service not running (models missing) or nothing requested → as before
        }
        LanguageDetector detector = detectLanguages ? engine.languageDetector() : null;
        List<Run> runs = new ArrayList<Run>();
        for (String paragraph : value.split("\\n{2,}")) {
            String trimmed = paragraph.trim().replaceAll("\\s+", " ");
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> sentences = engine.splitterFor(fallbackLanguage).segment(trimmed);
            if (sentences == null || sentences.isEmpty()) {
                sentences = Collections.singletonList(trimmed);
            }
            Run run = null;
            for (String sentence : sentences) {
                String detected = detector == null ? "" : detector.detectLanguage(sentence);
                String language = detected == null || detected.isEmpty()
                        ? fallbackLanguage : detected;
                if (run == null || !language.equals(run.language)) {
                    if (run != null) {
                        runs.add(run);
                    }
                    run = new Run(language);
                }
                run.sentences.add(sentence);
            }
            if (run != null) {
                runs.add(run);
            }
        }
        if (runs.isEmpty()) {
            return single;
        }
        // Merge consecutive same-language runs into segments; the granularity decides the chunks.
        List<Segment> segments = new ArrayList<Segment>();
        String currentLanguage = null;
        List<Run> currentRuns = new ArrayList<Run>();
        for (Run run : runs) {
            if (currentLanguage != null && !run.language.equals(currentLanguage)) {
                segments.add(new Segment(currentLanguage, chunks(currentRuns, granularity)));
                currentRuns = new ArrayList<Run>();
            }
            currentLanguage = run.language;
            currentRuns.add(run);
        }
        segments.add(new Segment(currentLanguage, chunks(currentRuns, granularity)));
        return segments;
    }

    private static List<String> chunks(List<Run> runs, Granularity granularity) {
        List<String> chunks = new ArrayList<String>();
        if (granularity == Granularity.SENTENCES) {
            for (Run run : runs) {
                chunks.addAll(run.sentences);
            }
            return chunks;
        }
        if (granularity == Granularity.PARAGRAPHS) {
            for (Run run : runs) {
                chunks.add(run.joined());
            }
            return chunks;
        }
        StringBuilder joined = new StringBuilder();
        for (Run run : runs) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(run.joined());
        }
        chunks.add(joined.toString());
        return chunks;
    }
}
