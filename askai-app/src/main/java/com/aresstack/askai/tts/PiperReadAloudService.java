package com.aresstack.askai.tts;

import com.aresstack.askai.agent.model.speech.SpeechSynthesisPort;
import com.aresstack.askai.text.ChatTextAnalysisService;

import java.util.Collections;
import java.util.List;

/**
 * The host's {@link SpeechSynthesisPort}: the WHOLE read-aloud orchestration lives here. An
 * utterance is first run through the on-demand {@link ChatTextAnalysisService} (settings opt-outs:
 * mixed-language split, sentence-wise synthesis — both default ON): NLP-detected German/English
 * passages are routed to their OWN voice, and synthesis runs sentence by sentence with the pieces
 * played in reading order. Per segment the language's configured Piper model voice speaks when it
 * is installed; otherwise the culture-matched Windows voice does — so a mixed answer flows through
 * both voices seamlessly. Without the NLP models (or with both checkboxes off) everything degrades
 * to the previous single-language behavior. Settings are re-read per call; failures return
 * {@code false} so the plugin's own Windows fallback still guarantees audibility.
 *
 * <p>The analysis service is acquired per utterance (listener token = the utterance) and released
 * afterwards — with the service's grace period, back-to-back answers reuse the loaded models and
 * the engine stops shortly after the last utterance, exactly the manual-service lifecycle.</p>
 */
public final class PiperReadAloudService implements SpeechSynthesisPort {

    /** Grace before the analysis engine unloads after the last utterance (reload avoidance). */
    private static final long ANALYSIS_STOP_GRACE_MILLIS = 60_000L;

    private final TtsSettingsStore settings;
    private final PiperTtsStore store;
    private final ChatTextAnalysisService analysis;
    private final PiperSpeechSynthesizer synthesizer = new PiperSpeechSynthesizer();
    private final WindowsSapiVoice windowsVoice = new WindowsSapiVoice();
    private final Object lock = new Object();
    private long generation;

    public PiperReadAloudService() {
        this(new TtsSettingsStore(), new PiperTtsStore(),
                new ChatTextAnalysisService(
                        new com.aresstack.askai.text.OpenNlpAnalysisEngineLoader(),
                        ANALYSIS_STOP_GRACE_MILLIS));
    }

    /** Visible for tests: explicit stores + analysis service (null = no analysis at all). */
    public PiperReadAloudService(TtsSettingsStore settings, PiperTtsStore store) {
        this(settings, store, null);
    }

    public PiperReadAloudService(TtsSettingsStore settings, PiperTtsStore store,
                                 ChatTextAnalysisService analysis) {
        this.settings = settings;
        this.store = store;
        this.analysis = analysis;
    }

    @Override
    public boolean isModelVoiceActive(String languageCode) {
        return activeVoice(settings.load(), languageCode) != null;
    }

    @Override
    public String describeActiveVoice(String languageCode) {
        PiperVoice voice = activeVoice(settings.load(), languageCode);
        return voice == null ? "" : voice.toString();
    }

    /** One chunk to speak: its text and the language that picks its voice. */
    private static final class Chunk {
        final String text;
        final String languageCode;

        Chunk(String text, String languageCode) {
            this.text = text;
            this.languageCode = languageCode;
        }
    }

    /** The assertive delivery: slightly slower, weightier pacing on BOTH engines. */
    private static final double EMPHATIC_LENGTH_SCALE = 1.15;
    private static final int EMPHATIC_SAPI_RATE = -2;

    @Override
    public boolean speak(String plainText, String languageCode) {
        return speak(plainText, languageCode, false);
    }

    @Override
    public boolean speakEmphatic(String plainText, String languageCode) {
        return speak(plainText, languageCode, true);
    }

    private boolean speak(String plainText, String languageCode, boolean emphatic) {
        TextToSpeechSettings current = settings.load();
        final long myGeneration;
        synchronized (lock) {
            myGeneration = ++generation;
        }
        List<Chunk> chunks = new java.util.ArrayList<Chunk>();
        for (ChatTextAnalysisService.Segment segment : segments(plainText, languageCode, current)) {
            for (String chunk : segment.getSentences()) {
                if (!chunk.trim().isEmpty()) {
                    chunks.add(new Chunk(chunk, segment.getLanguageCode()));
                }
            }
        }
        if (chunks.isEmpty()) {
            return true; // nothing to say is not a failure
        }
        // BATCH pipeline: EVERY chunk is submitted to the worker pool immediately — reusable
        // workers synthesize in the background while playback walks the results strictly in
        // reading order. No chunk ever waits for a previous playback to START its synthesis.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.max(1, current.getSynthesisWorkers()),
                new java.util.concurrent.ThreadFactory() {
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "askai-tts-worker");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        List<java.util.concurrent.Future<java.nio.file.Path>> prepared =
                new java.util.ArrayList<java.util.concurrent.Future<java.nio.file.Path>>();
        try {
            for (Chunk chunk : chunks) {
                prepared.add(prefetch(pool, chunk, current, emphatic));
            }
            boolean anySpoken = false;
            for (int i = 0; i < chunks.size(); i++) {
                if (!isCurrent(myGeneration)) {
                    return anySpoken; // stopped (or replaced) — interrupted still counts
                }
                Chunk chunk = chunks.get(i);
                java.nio.file.Path wav = resolve(prepared.get(i));
                if (!isCurrent(myGeneration)) {
                    deleteQuietly(wav);
                    return anySpoken;
                }
                if (wav != null) {
                    try {
                        synthesizer.playWav(wav);
                        anySpoken = true;
                    } catch (Exception failedPlayback) {
                        System.err.println("[tts] playback failed: " + failedPlayback.getMessage());
                    } finally {
                        deleteQuietly(wav);
                    }
                } else {
                    // No model voice for this chunk's language (or its synthesis failed): the
                    // culture-matched Windows voice keeps the flow audible.
                    String reason = windowsVoice.speak(chunk.text, chunk.languageCode,
                            current.getStartupTimeoutSeconds(),
                            emphatic ? EMPHATIC_SAPI_RATE : 0);
                    if (reason.isEmpty()) {
                        anySpoken = true;
                    } else {
                        System.err.println("[tts] windows voice failed: " + reason);
                    }
                }
            }
            return anySpoken;
        } finally {
            pool.shutdownNow(); // idle after the batch; stop() already killed live piper children
            cleanupAbandoned(prepared);
        }
    }

    /** Submit the chunk's synthesis to the pool — null future = a Windows chunk (spoken live). */
    private java.util.concurrent.Future<java.nio.file.Path> prefetch(
            java.util.concurrent.ExecutorService pool, final Chunk chunk,
            final TextToSpeechSettings current, final boolean emphatic) {
        final PiperVoice voice = activeVoice(current, chunk.languageCode);
        if (voice == null) {
            return null; // Windows chunks need no preparation
        }
        return pool.submit(new java.util.concurrent.Callable<java.nio.file.Path>() {
            public java.nio.file.Path call() {
                try {
                    return synthesizer.synthesizeToWav(store, voice, chunk.text,
                            current.getStartupTimeoutSeconds(),
                            emphatic ? EMPHATIC_LENGTH_SCALE : 1.0);
                } catch (Exception failed) {
                    System.err.println("[tts] model voice failed (" + voice.getId() + "): "
                            + failed.getMessage());
                    return null; // the chunk falls to the Windows voice
                }
            }
        });
    }

    private static java.nio.file.Path resolve(
            java.util.concurrent.Future<java.nio.file.Path> future) {
        if (future == null) {
            return null;
        }
        try {
            return future.get();
        } catch (Exception interrupted) {
            return null;
        }
    }

    /** Broken-off batch: collect finished WAVs (stop() killed the pipers) and delete them. */
    private static void cleanupAbandoned(
            List<java.util.concurrent.Future<java.nio.file.Path>> prepared) {
        for (java.util.concurrent.Future<java.nio.file.Path> future : prepared) {
            if (future == null) {
                continue;
            }
            try {
                deleteQuietly(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception gone) {
                future.cancel(true);
            }
        }
    }

    private static void deleteQuietly(java.nio.file.Path wav) {
        if (wav != null) {
            try {
                java.nio.file.Files.deleteIfExists(wav);
            } catch (java.io.IOException held) {
                wav.toFile().deleteOnExit();
            }
        }
    }

    private boolean isCurrent(long myGeneration) {
        synchronized (lock) {
            return generation == myGeneration;
        }
    }

    /** The utterance's segments: NLP-analysed when requested and available, else one segment. */
    private List<ChatTextAnalysisService.Segment> segments(String plainText, String languageCode,
                                                           TextToSpeechSettings current) {
        ChatTextAnalysisService.Granularity granularity = granularityOf(current.getChunking());
        boolean wantsAnalysis = current.isMixedLanguageSplit()
                || granularity != ChatTextAnalysisService.Granularity.ANSWER;
        if (analysis == null || !wantsAnalysis) {
            return Collections.singletonList(singleSegment(plainText, languageCode));
        }
        Object listener = new Object(); // this utterance IS the service's listener
        analysis.acquire(listener);
        try {
            return analysis.segment(plainText, languageCode,
                    current.isMixedLanguageSplit(), granularity);
        } finally {
            analysis.release(listener); // engine stops after the grace once no one else listens
        }
    }

    private static ChatTextAnalysisService.Granularity granularityOf(
            TextToSpeechSettings.Chunking chunking) {
        switch (chunking) {
            case SENTENCES:
                return ChatTextAnalysisService.Granularity.SENTENCES;
            case ANSWER:
                return ChatTextAnalysisService.Granularity.ANSWER;
            case PARAGRAPHS:
            default:
                return ChatTextAnalysisService.Granularity.PARAGRAPHS;
        }
    }

    private static ChatTextAnalysisService.Segment singleSegment(String text, String language) {
        return new ChatTextAnalysisService.Segment(language,
                Collections.singletonList(text == null ? "" : text));
    }

    @Override
    public void stop() {
        synchronized (lock) {
            generation++; // the chunk loop ends before its next chunk
        }
        synthesizer.stop();
        windowsVoice.stop();
    }

    @Override
    public boolean isReadAloudActiveByDefault() {
        return settings.load().isReadAloudAutoStart();
    }

    private PiperVoice activeVoice(TextToSpeechSettings current, String languageCode) {
        TextToSpeechSettings.Selection selection = current.selectionFor(languageCode);
        if (selection.getEngine() != TextToSpeechSettings.Engine.PIPER) {
            return null;
        }
        PiperVoice voice = PiperVoiceCatalog.findById(selection.getVoiceId());
        // The selected voice must MATCH the language — a stale cross-language id never speaks.
        return voice != null && voice.getLanguageCode().equals(languageCode)
                && store.isReadyToSpeak(voice) ? voice : null;
    }
}
