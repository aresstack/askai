package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationCodec;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationException;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshot;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.inference.InferenceEndpointDescriptor;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchRunOutcomeInfo;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.capture.VisitedCapture;
import com.aresstack.askai.research.host.BrowserRuntimePort;
import com.aresstack.askai.research.host.ProductiveResearchBackendFactory;
import com.aresstack.askai.research.host.ProductiveResearchSessionResources;
import com.aresstack.askai.research.host.ResearchRuntimeConfig;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The BASELINE: today's single in-process research agent, driven exactly through the productive host wiring
 * the MVP end-to-end test uses ({@link ProductiveResearchBackendFactory} → {@link
 * ProductiveResearchSessionResources} → {@code ResearchSessionBackend}), with the product defaults for the
 * search limits and the real local reranker (the autonomous loop always uses the session's mandatory model
 * reranker). The host state machine stays the only transition authority:
 * the benchmark dispatches START, SUBMIT_SCOPE and START_RESEARCH as the user would, then submits
 * {@code research: <query>}.
 *
 * <p>The main model is the benchmarked model, reached through the session's inference descriptor, which
 * points at the {@link OllamaTokenMeter}. Nothing in the agent is replaced or patched.</p>
 */
public final class SingleAgentBaselineSubject implements BenchmarkSubject {

    public static final String NAME = "single-agent-baseline";
    private static final String BROWSER_ARGS_PROPERTY = "askai.research.browser.args";

    private final LocalRerankerRuntime reranker;

    public SingleAgentBaselineSubject(LocalRerankerRuntime reranker) {
        this.reranker = reranker;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "today's single in-process research agent on develop (productive host wiring, product default "
                + "search limits, local reranker " + (reranker == null ? "n/a" : reranker.getModelName()) + ")";
    }

    @Override
    public BenchmarkRunRecord run(BenchmarkQuestion question, FixtureWorld world, String model, String modelBaseUrl,
                                  int repetition, BenchmarkSettings settings) throws Exception {
        BenchmarkRunRecord record = new BenchmarkRunRecord(NAME, model, question.getId(), repetition);
        try (Session session = Session.open(question, world, model, modelBaseUrl, settings, reranker, record)) {
            if (session == null) {
                return record;
            }
            long start = System.nanoTime();
            session.observer.promptStartedNanos.set(start);
            session.submit(question);
            boolean finished = session.observer.awaitOutcomes(1, settings.runTimeoutSeconds);
            record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
            session.collect(record, world, finished, settings.runTimeoutSeconds);
        }
        return record;
    }

    @Override
    public BenchmarkRunRecord runInterrupted(BenchmarkQuestion question, FixtureWorld world, String model,
                                             String modelBaseUrl, BenchmarkSettings settings) throws Exception {
        BenchmarkRunRecord record = new BenchmarkRunRecord(NAME, model, question.getId(), 0);
        try (Session session = Session.open(question, world, model, modelBaseUrl, settings, reranker, record)) {
            if (session == null) {
                return record;
            }
            long start = System.nanoTime();
            session.observer.promptStartedNanos.set(start);
            session.submit(question);
            if (!session.observer.awaitFirstSourceBeforeOutcome(settings.interruptTimeoutSeconds)) {
                boolean finished = session.observer.outcomes.get() > 0;
                record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
                session.collect(record, world, finished, settings.interruptTimeoutSeconds);
                record.resume = new BenchmarkRunRecord.ResumeOutcome(0, false, "", false, 0, 0, record.wallMillis,
                        finished ? "the run finished before a first source could be interrupted"
                                : "no source was accepted within " + settings.interruptTimeoutSeconds + " s");
                return record;
            }
            // ---- interruption: the user cancels the running prompt ----
            session.resources.getBackend().cancel(session.handle);
            if (!session.observer.awaitOutcomes(1, settings.cancelSettleSeconds)) {
                // The cancelled run is still going (a browser call is not aborted by a cancel). Submitting the
                // continuation now would run two prompts in one session and mix their outcomes.
                record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
                session.collect(record, world, false, settings.cancelSettleSeconds);
                record.resume = new BenchmarkRunRecord.ResumeOutcome(
                        session.resources.getRepository().find(SourceQuery.all()).size(), false, "", false, 0, 0,
                        record.wallMillis, "the cancelled run did not settle within " + settings.cancelSettleSeconds
                        + " s; the continuation was not submitted");
                return record;
            }
            List<ResearchSourceRecord> before = session.resources.getRepository().find(SourceQuery.all());
            List<VisitedCapture> capturesBefore = session.resources.getCaptures().list();
            Set<String> visitedBefore = new HashSet<String>();
            for (VisitedCapture capture : capturesBefore) {
                visitedBefore.add(capture.getCanonicalUrl());
            }
            int outcomesBefore = session.observer.outcomes.get();
            // ---- continuation: the same prompt again, in the same session ----
            // The host state machine stays the authority: if the cancel left RESEARCH/running, the benchmark
            // restarts the research the way the user would, and records a rejection instead of forcing it.
            String note = "";
            if (!ResearchStateIds.RUNNING.equals(session.resources.currentState().getStateId())
                    && !session.resources.dispatch(ResearchCommandType.START_RESEARCH).isAccepted()) {
                note = "START_RESEARCH after the interruption was rejected (state "
                        + session.resources.currentState().getStateId() + "); ";
            }
            session.submit(question);
            boolean resumed = session.observer.awaitOutcomes(outcomesBefore + 1, settings.runTimeoutSeconds);
            record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
            session.collect(record, world, resumed, settings.runTimeoutSeconds);
            List<VisitedCapture> capturesAfter = session.resources.getCaptures().list();
            int revisited = 0;
            for (int i = capturesBefore.size(); i < capturesAfter.size(); i++) {
                if (visitedBefore.contains(capturesAfter.get(i).getCanonicalUrl())) {
                    revisited++;
                }
            }
            Map<String, Integer> persisted = new HashMap<String, Integer>();
            int duplicates = 0;
            for (ResearchSourceRecord source : session.resources.getRepository().find(SourceQuery.all())) {
                int seen = persisted.merge(source.getUrl(), 1, Integer::sum);
                if (seen > 1) {
                    duplicates++;
                }
            }
            record.resume = new BenchmarkRunRecord.ResumeOutcome(before.size(), resumed, record.stopReason,
                    record.evidenceSufficient, duplicates, revisited, record.wallMillis,
                    note + (resumed ? "continued by submitting the same research prompt in the same session "
                            + "(the baseline has no task resume)"
                            : "the continuation produced no outcome within " + settings.runTimeoutSeconds + " s"));
        }
        return record;
    }

    // ------------------------------------------------------------------ one productive session

    private static final class Session implements AutoCloseable {
        final SolonMcpServerRuntime registry;
        final ProductiveResearchSessionResources resources;
        final ResearchSessionHandle handle;
        final Observer observer;
        final String previousBrowserArgs;
        final File projectDir;

        private Session(SolonMcpServerRuntime registry, ProductiveResearchSessionResources resources,
                        ResearchSessionHandle handle, Observer observer, String previousBrowserArgs, File projectDir) {
            this.registry = registry;
            this.resources = resources;
            this.handle = handle;
            this.observer = observer;
            this.previousBrowserArgs = previousBrowserArgs;
            this.projectDir = projectDir;
        }

        /** Open a session in RESEARCH/running; null (with the reason in the record) when it cannot start. */
        static Session open(BenchmarkQuestion question, FixtureWorld world, String model, String modelBaseUrl,
                            BenchmarkSettings settings, LocalRerankerRuntime reranker, BenchmarkRunRecord record)
                throws IOException {
            // Documented dev/test hand-off (as in the MVP end-to-end test): every loopback server is its own
            // domain family, and the fixture engine is the search engine of this run.
            String previousBrowserArgs = System.setProperty(BROWSER_ARGS_PROPERTY,
                    "--domain-key-mode=host-port --search-url=" + world.searchUrlTemplate());
            SolonMcpServerRuntime registry = new SolonMcpServerRuntime();
            ResearchRuntimeConfig config = new ResearchRuntimeConfig(settings.browserChannel, true, true,
                    reranker.getModelName());
            ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(registry, config, 1L,
                    Collections.<String, String>emptyMap(), 0L, reranker.asProvider(settings.rerankTopN),
                    inference(model, modelBaseUrl, settings.inferenceTimeoutMillis));
            File projectDir = Files.createTempDirectory("askai-benchmark-" + question.getId()).toFile();
            String sessionKey = "bench-" + question.getId() + "-" + System.nanoTime();
            ProductiveResearchSessionResources resources;
            try {
                resources = factory.createSession(sessionKey, projectDir);
            } catch (IOException notReady) {
                restore(previousBrowserArgs);
                registry.shutdown();
                record.failure = "session could not be created (environment): " + notReady.getMessage();
                record.environmentFailure = true;
                return null;
            }
            for (ResearchCommandType command : new ResearchCommandType[]{
                    ResearchCommandType.START, ResearchCommandType.SUBMIT_SCOPE,
                    ResearchCommandType.START_RESEARCH}) {
                if (!resources.dispatch(command).isAccepted()) {
                    resources.close();
                    restore(previousBrowserArgs);
                    registry.shutdown();
                    record.failure = "host state machine rejected " + command;
                    return null;
                }
            }
            Observer observer = new Observer();
            ResearchSessionHandle handle = resources.getBackend().createSession(
                    new ResearchProjectRequest(sessionKey, "benchmark-" + question.getId(), question.getQuestion()),
                    observer);
            return new Session(registry, resources, handle, observer, previousBrowserArgs, projectDir);
        }

        /** The full question, not only its keywords: the subject must see every requirement it is scored on. */
        void submit(BenchmarkQuestion question) {
            resources.getBackend().submitPrompt(handle, new ResearchPrompt("research: " + question.getQuestion(),
                    ""));
        }

        void collect(BenchmarkRunRecord record, FixtureWorld world, boolean finished, int timeoutSeconds) {
            record.finished = finished;
            if (!finished && record.failure.isEmpty()) {
                record.failure = "no structured run outcome within " + timeoutSeconds + " s";
            }
            ResearchRunOutcomeInfo outcome = observer.lastOutcome;
            if (outcome != null) {
                record.stopReason = outcome.getStopReason() == null ? "" : outcome.getStopReason();
                record.evidenceSufficient = outcome.isEvidenceSufficient();
                record.pagesVisited = outcome.getPagesVisited();
                record.distinctHosts = outcome.getDistinctHosts();
            }
            record.toolCalls = observer.toolCalls.get();
            record.toolFailures = observer.failures.get();
            long first = observer.firstSourceNanos.get();
            record.millisToFirstSource = first == 0L ? -1L
                    : (first - observer.promptStartedNanos.get()) / 1_000_000L;
            record.acceptedSources.clear();
            for (ResearchSourceRecord source : resources.getRepository().find(SourceQuery.all())) {
                String text = source.getFullText() == null || source.getFullText().isEmpty()
                        ? source.getExcerpt() : source.getFullText();
                record.acceptedSources.add(new BenchmarkRunRecord.AcceptedSource(source.getUrl(), source.getTitle(),
                        text));
            }
            record.visitedUrls.clear();
            for (VisitedCapture capture : resources.getCaptures().list()) {
                record.visitedUrls.add(capture.getCanonicalUrl());
            }
            record.statements.clear();
            record.statements.addAll(observer.statements);
            // The browser starts lazily on the first web command, so a missing browser does not fail the
            // session start. A run that visited nothing is checked once more: if the browser cannot start,
            // the environment failed, not the subject. (Checked after the run, so the browser start stays
            // part of the measured time.)
            if (record.visitedUrls.isEmpty()) {
                try {
                    resources.getBrowser().ensureStarted();
                } catch (BrowserRuntimePort.BrowserRuntimeException noBrowser) {
                    record.environmentFailure = true;
                    record.failure = "browser could not start (environment): " + noBrowser.getMessage();
                } catch (RuntimeException noBrowser) {
                    record.environmentFailure = true;
                    record.failure = "browser could not start (environment): " + noBrowser;
                }
            }
        }

        @Override
        public void close() {
            try {
                resources.getBackend().close(handle);
            } finally {
                resources.close();
                registry.shutdown();
                restore(previousBrowserArgs);
            }
        }

        private static void restore(String previousBrowserArgs) {
            if (previousBrowserArgs == null) {
                System.clearProperty(BROWSER_ARGS_PROPERTY);
            } else {
                System.setProperty(BROWSER_ARGS_PROPERTY, previousBrowserArgs);
            }
        }
    }

    /** Collects what the session reports; the run's numbers come from these events. */
    static final class Observer implements ResearchSessionListener {
        final AtomicLong promptStartedNanos = new AtomicLong();
        final AtomicLong firstSourceNanos = new AtomicLong();
        final CountDownLatch firstSource = new CountDownLatch(1);
        final AtomicInteger outcomes = new AtomicInteger();
        final AtomicInteger toolCalls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final List<String> statements = new CopyOnWriteArrayList<String>();
        volatile ResearchRunOutcomeInfo lastOutcome;
        private final Object lock = new Object();

        @Override
        public void onEvent(ResearchBackendEvent event) {
            ResearchBackendEventType type = event.getType();
            if (type == ResearchBackendEventType.RUN_PROGRESS && event.getRunProgress() != null) {
                // Tool calls are per run; the maximum over the progress events is the run's count.
                toolCalls.accumulateAndGet(event.getRunProgress().getToolCalls(), Math::max);
                if (event.getRunProgress().getAcceptedSources() > 0
                        && firstSourceNanos.compareAndSet(0L, System.nanoTime())) {
                    firstSource.countDown();
                }
            } else if (type == ResearchBackendEventType.SOURCE_ADDED) {
                if (firstSourceNanos.compareAndSet(0L, System.nanoTime())) {
                    firstSource.countDown();
                }
            } else if (type == ResearchBackendEventType.ERROR || type == ResearchBackendEventType.PROBLEM_REPORTED) {
                failures.incrementAndGet();
            } else if (type == ResearchBackendEventType.ASSISTANT_MESSAGE) {
                String text = firstNonBlank(event.getPublicMessage(), event.getText());
                if (!text.isEmpty()) {
                    statements.add(text);
                }
            } else if (type == ResearchBackendEventType.RUN_OUTCOME) {
                lastOutcome = event.getRunOutcome();
                // The outcome's limitation is user-facing text too ("only two hosts found ...").
                if (lastOutcome != null && !lastOutcome.getLimitation().trim().isEmpty()) {
                    statements.add(lastOutcome.getLimitation().trim());
                }
                synchronized (lock) {
                    outcomes.incrementAndGet();
                    lock.notifyAll();
                }
            }
        }

        /** True once a source is accepted; false when the run ends first or the timeout passes. */
        boolean awaitFirstSourceBeforeOutcome(int timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                if (firstSource.await(200, TimeUnit.MILLISECONDS)) {
                    return true;
                }
                if (outcomes.get() > 0) {
                    return false;
                }
            }
            return firstSource.getCount() == 0;
        }

        boolean awaitOutcomes(int count, int timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            synchronized (lock) {
                while (outcomes.get() < count) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, left);
                }
                return true;
            }
        }

        private static String firstNonBlank(String a, String b) {
            if (a != null && !a.trim().isEmpty()) {
                return a.trim();
            }
            return b == null ? "" : b.trim();
        }
    }

    // ------------------------------------------------------------------ host snapshot providers

    /** The session's main model: the benchmarked model, reached through the token meter. */
    private static InferenceConfigurationSnapshotProvider inference(String model, String baseUrl, long timeoutMillis) {
        return (sessionId, sessionDirectory) -> {
            InferenceConfigurationDocument document = InferenceConfigurationDocument.current(1L,
                    new InferenceEndpointDescriptor(model, baseUrl, "/api/chat", timeoutMillis));
            File target = new File(sessionDirectory, "inference-config.json");
            try {
                Files.write(target.toPath(), InferenceConfigurationCodec.toJson(document)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new InferenceConfigurationException("cannot write the benchmark inference snapshot: "
                        + ex.getMessage(), ex);
            }
            return new InferenceConfigurationSnapshot(target.getAbsoluteFile(), document);
        };
    }
}
