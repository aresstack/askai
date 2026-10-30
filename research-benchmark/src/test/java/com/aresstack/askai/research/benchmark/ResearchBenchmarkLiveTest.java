package com.aresstack.askai.research.benchmark;

import org.junit.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The live research benchmark (ADR 0001, work item H). Runs every selected question with every selected model
 * against every subject — today the single-agent BASELINE — {@code repetitions} times, plus the resume
 * scenario once per question and model, and writes one Markdown report.
 *
 * <p>Run it with {@code gradlew :research-benchmark:benchmark}; configure it with {@code -Daskai.benchmark.*}
 * (see {@link BenchmarkSettings}). Without a reachable Ollama, or without the requested models, it SKIPS with
 * the concrete reason. A benchmark measures; it does not fail on poor scores. It fails only when the harness
 * itself could not produce a report.</p>
 */
public class ResearchBenchmarkLiveTest {

    @Test
    public void benchmarkTheSubjectsAndWriteTheReport() throws Exception {
        BenchmarkSettings settings = BenchmarkSettings.fromSystem();
        OllamaProbe ollama = OllamaProbe.probe(settings.ollamaUrl);
        List<String> models = ollama.modelsToRun(settings.models);
        skipUnless(!models.isEmpty(), ollama.modelProblem(settings.models));

        String rerankModel = LocalRerankerRuntime.rerankModelOrNull(settings.localModelRoot);
        skipUnless(rerankModel != null, "no installed rerank-capable local model under "
                + settings.localModelRoot + " (the autonomous research loop needs the mandatory reranker); set -D"
                + BenchmarkSettings.PREFIX + "localModelRoot=<dir>");

        BenchmarkQuestionSet questionSet = BenchmarkQuestionSet.load();
        List<BenchmarkQuestion> questions = questionSet.select(settings.questionIds);
        try (LocalRerankerRuntime reranker = LocalRerankerRuntime.start(settings.localModelRoot, rerankModel)) {
            run(settings, ollama, models, questionSet, questions, reranker);
        }
    }

    private void run(BenchmarkSettings settings, OllamaProbe ollama, List<String> models,
                     BenchmarkQuestionSet questionSet, List<BenchmarkQuestion> questions,
                     LocalRerankerRuntime reranker) throws Exception {
        List<BenchmarkSubject> subjects = select(reranker, settings.subjects);
        BenchmarkScorer scorer = new BenchmarkScorer(settings);

        BenchmarkReport report = new BenchmarkReport(Instant.now(), settings, questionSet.getVersion());
        report.environment("Ollama", settings.ollamaUrl + " (version " + ollama.getVersion() + ")");
        report.environment("Models", models.toString());
        report.environment("Installed chat models", ollama.getChatModels().toString());
        report.environment("Reranker", reranker.getModelName() + " (local model runtime, CPU)");
        report.environment("Java", System.getProperty("java.version") + " on " + System.getProperty("os.name"));
        for (BenchmarkSubject subject : subjects) {
            report.subject(subject);
        }
        for (BenchmarkQuestion question : questions) {
            report.question(question);
        }

        int environmentFailures = 0;
        int runs = 0;
        try (OllamaTokenMeter meter = OllamaTokenMeter.start(settings.ollamaUrl,
                settings.inferenceTimeoutMillis)) {
            for (BenchmarkSubject subject : subjects) {
                for (String model : models) {
                    for (BenchmarkQuestion question : questions) {
                        for (int repetition = 1; repetition <= settings.repetitions; repetition++) {
                            final int number = repetition;
                            BenchmarkRunRecord record = measured(meter, settings, () -> {
                                try (FixtureWorld world = FixtureWorld.start(question, settings.serpResults)) {
                                    BenchmarkRunRecord run = subject.run(question, world, model, meter.baseUrl(),
                                            number, settings);
                                    run.resolvePages(world);
                                    return run;
                                }
                            });
                            runs++;
                            if (record.isEnvironmentFailure()) {
                                environmentFailures++;
                            }
                            report.add(scorer.score(question, record));
                            log(record);
                        }
                        if (settings.resumeScenario) {
                            BenchmarkRunRecord record = measured(meter, settings, () -> {
                                try (FixtureWorld world = FixtureWorld.start(question, settings.serpResults)) {
                                    BenchmarkRunRecord run = subject.runInterrupted(question, world, model,
                                            meter.baseUrl(), settings);
                                    run.resolvePages(world);
                                    return run;
                                }
                            });
                            report.addResume(scorer.score(question, record));
                            log(record);
                        }
                    }
                }
            }
        }
        File file = report.write();
        System.out.println("[benchmark] report: " + file.getAbsolutePath());
        // Without an installed browser no session can start; that is the environment, not a result.
        skipUnless(environmentFailures < runs, "no run had a working environment (is a browser installed for "
                + "channel '" + settings.browserChannel + "'?); report: " + file.getAbsolutePath());
        assertTrue("the report was written", file.isFile());
        assertFalse("the report is not empty", report.render().isEmpty());
    }

    /**
     * The baseline, the orchestrator alone and the orchestrator with each lane G role, filtered by
     * {@code askai.benchmark.subjects} (empty = all).
     */
    static List<BenchmarkSubject> select(LocalRerankerRuntime reranker, List<String> names) {
        List<BenchmarkSubject> all = new java.util.ArrayList<BenchmarkSubject>();
        all.add(new SingleAgentBaselineSubject(reranker));
        all.addAll(OrchestratedResearchSubject.all(reranker));
        if (names.isEmpty()) {
            return all;
        }
        List<BenchmarkSubject> chosen = new java.util.ArrayList<BenchmarkSubject>();
        List<String> known = new java.util.ArrayList<String>();
        for (BenchmarkSubject subject : all) {
            known.add(subject.name());
            if (names.contains(subject.name())) {
                chosen.add(subject);
            }
        }
        if (chosen.size() != names.size()) {
            throw new IllegalArgumentException(BenchmarkSettings.PREFIX + "subjects names an unknown subject: "
                    + names + "; known: " + known);
        }
        return chosen;
    }

    /** Skip with the reason, printed too: Gradle's console shows a skip but not its message. */
    private static void skipUnless(boolean condition, String reason) {
        if (!condition) {
            System.out.println("[benchmark] SKIPPED: " + reason);
        }
        assumeTrue("SKIPPED: " + reason, condition);
    }

    /**
     * Run one measurement and attach the model usage the meter saw during it. The final snapshot waits until
     * the run's last model call has finished (bounded by the model-call timeout), so a call that outlives its
     * run is charged to that run and not to the next one.
     */
    private static BenchmarkRunRecord measured(OllamaTokenMeter meter, BenchmarkSettings settings,
                                               Measurement measurement) throws Exception {
        if (!meter.awaitIdle(settings.inferenceTimeoutMillis)) {
            throw new IllegalStateException("model calls of the previous run are still in flight");
        }
        OllamaTokenMeter.Usage before = meter.snapshot();
        BenchmarkRunRecord record = measurement.run();
        if (!meter.awaitIdle(settings.inferenceTimeoutMillis + 10_000L)) {
            record.failure = (record.failure.isEmpty() ? "" : record.failure + "; ")
                    + "model calls were still in flight after the run";
        }
        record.usage = meter.snapshot().minus(before);
        return record;
    }

    private static void log(BenchmarkRunRecord record) {
        System.out.println("[benchmark] " + record.getSubject() + " " + record.getModel() + " "
                + record.getQuestionId() + " #" + record.getRepetition() + ": finished=" + record.isFinished()
                + " stop=" + record.getStopReason() + " accepted=" + record.getAcceptedSources().size()
                + " wall=" + record.getWallMillis() + "ms" + (record.getFailure().isEmpty() ? ""
                : " failure=" + record.getFailure()));
    }

    private interface Measurement {
        BenchmarkRunRecord run() throws Exception;
    }
}
