package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.research.capture.VisitedCapture;
import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorRun;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings;
import com.aresstack.askai.research.runtime.orchestrator.RoundDecision;
import com.aresstack.askai.research.runtime.orchestrator.SearchPlannerWorker;
import com.aresstack.askai.research.runtime.orchestrator.SourceVerifierWorker;
import com.aresstack.askai.research.runtime.orchestrator.WorkerArtifacts;
import com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings;
import com.aresstack.askai.research.runtime.orchestrator.WriterWorker;
import com.aresstack.askai.research.runtime.service.ResearchServiceCommand;
import com.aresstack.askai.research.search.ResearchServiceCommandWire;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.store.ProjectTaskBoard;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Lane F: the orchestrated run (ADR 0001, 4.2) on the same productive wiring as the {@link
 * SingleAgentBaselineSubject}, with the orchestrated mode switched on through the same settings the user sets.
 * The benchmark plays the user: it asks the question in plain chat (the orchestrator proposes a plan), approves
 * the plan with the State tab's typed command, approves at most {@link #EXTENSIONS} overruns the run hands
 * back, and answers at most {@link #ANSWERS} questions with a fixed "use your judgement". Everything else is
 * the product's defaults (rounds, packages, budgets, retries, run rule).
 *
 * <p>Gaps and contradictions are the run's structured findings, so the scorer uses them instead of its text
 * heuristic. A run is sufficient when it CLOSED: the envelope accepts a close only when the measured run rule
 * and outline coverage agree.</p>
 */
public final class OrchestratedResearchSubject implements BenchmarkSubject {

    public static final String NAME = "orchestrated-run";
    /** Overruns the simulated user approves; a further one ends the run where it stands. */
    static final int EXTENSIONS = 1;
    /** Questions the simulated user answers. */
    static final int ANSWERS = 1;
    static final String ANSWER = "Use your own judgement within the question as asked.";
    /** The journal line the cycle writes when a cancelled turn interrupts it. */
    static final String INTERRUPTED_JOURNAL = "Interrupted";
    /** The semantic outcome the question set expects of a run that ended sufficient (the baseline's word). */
    static final String SUFFICIENT = "SUFFICIENT_EVIDENCE";

    /** Lane G: the worker roles that can be measured one by one on top of the orchestrator. */
    public enum Role {
        SEARCH_PLANNER("search-planner", WorkerRoleSettings.ENV_SEARCH_PLANNER,
                "the search planner writes distinct queries that dependent web packages search"),
        SOURCE_VERIFIER("source-verifier", WorkerRoleSettings.ENV_SOURCE_VERIFIER,
                "the source verifier judges accepted sources; rejected ones stop counting as evidence"),
        WRITER("writer", WorkerRoleSettings.ENV_WRITER,
                "the writer drafts cited section text from the sources (scored as the run's statements)");

        final String id;
        final String env;
        final String what;

        Role(String id, String env, String what) {
            this.id = id;
            this.env = env;
            this.what = what;
        }
    }

    private final LocalRerankerRuntime reranker;
    /** The one lane G role switched on, or null for the orchestrator alone (lane F's measurement). */
    private final Role role;

    /** The orchestrator alone: every lane G role off, so lane F's numbers stay comparable. */
    public OrchestratedResearchSubject(LocalRerankerRuntime reranker) {
        this(reranker, null);
    }

    /** The orchestrator plus exactly one lane G worker role. */
    public OrchestratedResearchSubject(LocalRerankerRuntime reranker, Role role) {
        this.reranker = reranker;
        this.role = role;
    }

    /** The orchestrator alone and the orchestrator with each lane G role, in report order. */
    public static List<BenchmarkSubject> all(LocalRerankerRuntime reranker) {
        List<BenchmarkSubject> subjects = new ArrayList<BenchmarkSubject>();
        subjects.add(new OrchestratedResearchSubject(reranker));
        for (Role each : Role.values()) {
            subjects.add(new OrchestratedResearchSubject(reranker, each));
        }
        return subjects;
    }

    @Override
    public String name() {
        return role == null ? NAME : "orchestrated+" + role.id;
    }

    @Override
    public String description() {
        if (role != null) {
            return "lane G: the orchestrated run of `" + NAME + "` plus one worker role: " + role.what
                    + " (every other lane G role off)";
        }
        return "lane F: orchestrator role plans work packages, the deterministic executor runs them through the "
                + "same web research funnel, the envelope measures sufficiency (product defaults, the simulated "
                + "user approves the plan and " + EXTENSIONS + " overrun; lane G roles off)";
    }

    static Map<String, String> environment() {
        return environment(null);
    }

    /** The orchestrated mode, and of the lane G roles only {@code role} (none when null). */
    static Map<String, String> environment(Role role) {
        Map<String, String> env = new HashMap<String, String>();
        env.put(OrchestratorSettings.ENV_MODE, OrchestratorSettings.Mode.ORCHESTRATED.wireName());
        for (Role each : Role.values()) {
            env.put(each.env, each == role ? "on" : "off");
        }
        return env;
    }

    @Override
    public BenchmarkRunRecord run(BenchmarkQuestion question, FixtureWorld world, String model, String modelBaseUrl,
                                  int repetition, BenchmarkSettings settings) throws Exception {
        BenchmarkRunRecord record = new BenchmarkRunRecord(name(), model, question.getId(), repetition);
        try (SingleAgentBaselineSubject.Session session = SingleAgentBaselineSubject.Session.open(question, world,
                model, modelBaseUrl, settings, reranker, record, environment(role))) {
            if (session == null) {
                return record;
            }
            long start = System.nanoTime();
            long deadline = start + TimeUnit.SECONDS.toNanos(settings.runTimeoutSeconds);
            session.observer.promptStartedNanos.set(start);
            Driver driver = new Driver(session, deadline);
            session.submitText(question.getQuestion());
            boolean finished = driver.awaitTurn() && driver.drive();
            record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
            collect(session, record, world, finished, settings.runTimeoutSeconds, driver);
        }
        return record;
    }

    @Override
    public BenchmarkRunRecord runInterrupted(BenchmarkQuestion question, FixtureWorld world, String model,
                                             String modelBaseUrl, BenchmarkSettings settings) throws Exception {
        BenchmarkRunRecord record = new BenchmarkRunRecord(name(), model, question.getId(), 0);
        try (SingleAgentBaselineSubject.Session session = SingleAgentBaselineSubject.Session.open(question, world,
                model, modelBaseUrl, settings, reranker, record, environment(role))) {
            if (session == null) {
                return record;
            }
            long start = System.nanoTime();
            session.observer.promptStartedNanos.set(start);
            Driver driver = new Driver(session, start + TimeUnit.SECONDS.toNanos(settings.runTimeoutSeconds));
            session.submitText(question.getQuestion());
            OrchestratorRun planned = driver.awaitTurn() ? driver.board.currentRun() : null;
            if (planned == null || planned.getState() != OrchestratorRun.State.PLAN_PROPOSED) {
                record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
                collect(session, record, world, planned != null, settings.runTimeoutSeconds, driver);
                record.resume = new BenchmarkRunRecord.ResumeOutcome(0, false, "", false, 0, 0, record.wallMillis,
                        "no plan to approve (" + (planned == null ? "no run" : planned.getState().wireName()) + ")");
                return record;
            }
            int completionsBefore = session.observer.completions.get();
            driver.decide(ResearchServiceCommand.TYPE_ORCHESTRATOR_APPROVE, "");
            if (!awaitFirstSourceOrTurnEnd(session, completionsBefore, settings.interruptTimeoutSeconds)) {
                boolean finished = driver.awaitTurn() && driver.drive();
                record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
                collect(session, record, world, finished, settings.runTimeoutSeconds, driver);
                record.resume = new BenchmarkRunRecord.ResumeOutcome(0, false, "", false, 0, 0, record.wallMillis,
                        "no source was accepted before the approved run ended or within "
                                + settings.interruptTimeoutSeconds + " s");
                return record;
            }
            // ---- interruption: the user cancels the running turn; the run stays RUNNING on its record ----
            session.resources.getBackend().cancel(session.handle);
            if (!awaitInterruptionSettled(driver, settings.cancelSettleSeconds)) {
                record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
                collect(session, record, world, false, settings.cancelSettleSeconds, driver);
                record.resume = new BenchmarkRunRecord.ResumeOutcome(
                        session.resources.getRepository().find(SourceQuery.all()).size(), false, "", false, 0, 0,
                        record.wallMillis, "the interrupted cycle did not settle within "
                        + settings.cancelSettleSeconds + " s after the cancel; the continuation was not submitted");
                return record;
            }
            List<ResearchSourceRecord> before = session.resources.getRepository().find(SourceQuery.all());
            List<VisitedCapture> capturesBefore = session.resources.getCaptures().list();
            Set<String> visitedBefore = new HashSet<String>();
            for (VisitedCapture capture : capturesBefore) {
                visitedBefore.add(capture.getCanonicalUrl());
            }
            // ---- continuation: "Continue" on the State tab resumes the run from its task record ----
            driver.decide(ResearchServiceCommand.TYPE_ORCHESTRATOR_APPROVE, "");
            boolean resumed = driver.awaitTurn() && driver.drive();
            record.wallMillis = (System.nanoTime() - start) / 1_000_000L;
            collect(session, record, world, resumed, settings.runTimeoutSeconds, driver);
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
                if (persisted.merge(source.getUrl(), 1, Integer::sum) > 1) {
                    duplicates++;
                }
            }
            record.resume = new BenchmarkRunRecord.ResumeOutcome(before.size(), resumed, record.stopReason,
                    record.evidenceSufficient, duplicates, revisited, record.wallMillis,
                    resumed ? "continued with the State tab's Continue: the run resumes from its task record"
                            : "the continuation did not settle within " + settings.runTimeoutSeconds + " s");
        }
        return record;
    }

    /** Plays the user against the run record after every turn, until the run is final or waits beyond policy. */
    static final class Driver {
        final SingleAgentBaselineSubject.Session session;
        final ProjectTaskBoard board;
        final long deadlineNanos;
        int expectedCompletions;
        int extensions;
        int answers;
        final List<String> decisions = new ArrayList<String>();

        Driver(SingleAgentBaselineSubject.Session session, long deadlineNanos) {
            this.session = session;
            this.board = new ProjectTaskBoard(session.resources.getProjectContext());
            this.deadlineNanos = deadlineNanos;
            this.expectedCompletions = session.observer.completions.get() + 1;
        }

        boolean awaitTurn() throws InterruptedException {
            int left = (int) Math.max(0, TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
            return session.observer.awaitCompletions(expectedCompletions, left);
        }

        void decide(String type, String text) {
            decisions.add(type.replace("orchestrator_", ""));
            expectedCompletions = session.observer.completions.get() + 1;
            session.submitText(ResearchServiceCommandWire.orchestrator(type, text));
        }

        /** @return true when the run reached a state the simulated user leaves it in */
        boolean drive() throws InterruptedException {
            while (true) {
                OrchestratorRun run = board.currentRun();
                if (run == null) {
                    return true;
                }
                OrchestratorRun.State state = run.getState();
                if (state == OrchestratorRun.State.PLAN_PROPOSED || state == OrchestratorRun.State.RUNNING) {
                    decide(ResearchServiceCommand.TYPE_ORCHESTRATOR_APPROVE, "");
                } else if (state == OrchestratorRun.State.AWAITING_APPROVAL && extensions < EXTENSIONS) {
                    extensions++;
                    decide(ResearchServiceCommand.TYPE_ORCHESTRATOR_APPROVE, "");
                } else if (state == OrchestratorRun.State.AWAITING_INPUT && answers < ANSWERS) {
                    answers++;
                    decide(ResearchServiceCommand.TYPE_ORCHESTRATOR_ANSWER, ANSWER);
                } else {
                    return true;
                }
                if (!awaitTurn()) {
                    return false;
                }
            }
        }
    }

    private static boolean awaitFirstSourceOrTurnEnd(SingleAgentBaselineSubject.Session session,
                                                     int completionsBefore, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (session.observer.firstSource.await(200, TimeUnit.MILLISECONDS)) {
                return true;
            }
            if (session.observer.completions.get() > completionsBefore) {
                return false;
            }
        }
        return session.observer.firstSource.getCount() == 0;
    }

    /**
     * A cancelled turn reports no completion, and an interrupted package deliberately stays {@code working} on
     * the blackboard until the next approval resumes it. The cycle's own end is the signal: it journals the
     * interruption on the run record (or the run left {@code running} for another reason).
     */
    static boolean awaitInterruptionSettled(Driver driver, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            OrchestratorRun run = driver.board.currentRun();
            if (run == null || isSettled(run)) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    static boolean isSettled(OrchestratorRun run) {
        List<String> journal = run.getJournal();
        return run.getState() != OrchestratorRun.State.RUNNING
                || (!journal.isEmpty() && journal.get(journal.size() - 1).startsWith(INTERRUPTED_JOURNAL));
    }

    private void collect(SingleAgentBaselineSubject.Session session, BenchmarkRunRecord record,
                         FixtureWorld world, boolean finished, int timeoutSeconds, Driver driver) {
        session.collect(record, world, finished, timeoutSeconds);
        OrchestratorRun run = driver.board.currentRun();
        record.pagesVisited = record.visitedUrls.size();
        Set<String> hosts = new HashSet<String>();
        for (String url : record.visitedUrls) {
            try {
                URI uri = URI.create(url);
                hosts.add(uri.getHost() + ":" + uri.getPort());
            } catch (IllegalArgumentException ignored) {
                // not a URL: not a host
            }
        }
        record.distinctHosts = hosts.size();
        if (run == null) {
            record.stopReason = "NO_RUN";
            return;
        }
        // The scorer compares the semantic outcome; the diagnostics go to the benchmark log.
        record.stopReason = stopReasonOf(run);
        record.evidenceSufficient = run.getState() == OrchestratorRun.State.CLOSED;
        System.out.println("[benchmark] " + NAME + " " + record.getQuestionId() + ": run "
                + run.getState().wireName()
                + (run.getWaitingReason() == null || run.getWaitingReason().isEmpty() ? ""
                : " (" + run.getWaitingReason() + ")")
                + ", decisions " + String.join("/", driver.decisions) + ", rounds " + run.getRoundsUsed()
                + ", packages " + run.getTasksSubmitted() + ", tokens " + run.getTokensUsed());
        record.reportedGaps = describe(run.getOpenGaps());
        record.reportedContradictions = describe(run.getContradictions());
        if (role != null) {
            observeRoles(record, run, driver.board.tasks());
        }
    }

    /**
     * Lane G: what the worker roles did, from the run's task record. The verifier's contradictions join the
     * structured findings; the writer's drafts (citations stripped) become the run's statements, because they
     * are the run's answer.
     */
    static void observeRoles(BenchmarkRunRecord record, OrchestratorRun run, List<ResearchTask> tasks) {
        BenchmarkRunRecord.RoleObservation roles = new BenchmarkRunRecord.RoleObservation();
        if (record.reportedContradictions == null) {
            record.reportedContradictions = new ArrayList<String>();
        }
        Map<String, String> urlBySource = new HashMap<String, String>();
        List<String> drafts = new ArrayList<String>();
        for (ResearchTask task : tasks) {
            if (!run.owns(task.getTaskId())) {
                continue;
            }
            for (TaskArtifact artifact : task.getArtifacts()) {
                if (artifact.getKind() == TaskArtifact.Kind.SOURCE_REFERENCE) {
                    urlBySource.put(artifact.getProjectRef(), artifact.getContent().trim().split("\\s+", 2)[0]);
                }
            }
        }
        for (ResearchTask task : tasks) {
            if (!run.owns(task.getTaskId())) {
                continue;
            }
            boolean rolePackage = true;
            if (SearchPlannerWorker.ROLE.getId().equals(task.getRole())) {
                roles.plannerPackages++;
            } else if (SourceVerifierWorker.ROLE.getId().equals(task.getRole())) {
                roles.verifierPackages++;
            } else if (WriterWorker.ROLE.getId().equals(task.getRole())) {
                roles.writerPackages++;
            } else {
                rolePackage = false;
            }
            if (rolePackage && task.isTerminal() && task.getState() != TaskState.COMPLETED) {
                roles.rolePackagesFailed++;
            }
            for (TaskArtifact artifact : task.getArtifacts()) {
                String ref = artifact.getProjectRef();
                if (artifact.getKind() == TaskArtifact.Kind.TEXT && WorkerArtifacts.PLANNED_QUERY_REF.equals(ref)) {
                    roles.plannedQueries++;
                } else if (artifact.getKind() == TaskArtifact.Kind.TEXT && ref.startsWith(WorkerArtifacts.DRAFT_PREFIX)) {
                    for (String paragraph : artifact.getContent().split("\n\n")) {
                        if (!paragraph.trim().isEmpty()) {
                            roles.draftParagraphs++;
                            drafts.add(paragraph.replaceAll("\\s*\\[[^\\]]*\\]\\s*$", "").trim());
                        }
                    }
                } else if (artifact.getKind() == TaskArtifact.Kind.VERDICT
                        && ref.startsWith(WorkerArtifacts.CONTRADICTION_PREFIX)) {
                    roles.verifierContradictions++;
                    record.reportedContradictions.add(ref.substring(WorkerArtifacts.CONTRADICTION_PREFIX.length())
                            + ": " + artifact.getContent());
                }
            }
        }
        Map<String, WorkerArtifacts.Verdict> verdicts = WorkerArtifacts.verdicts(ownedBy(run, tasks));
        roles.verifiedSources = verdicts.size();
        for (Map.Entry<String, WorkerArtifacts.Verdict> verdict : verdicts.entrySet()) {
            String url = urlBySource.get(verdict.getKey());
            if (verdict.getValue().isRejected() && url != null) {
                roles.rejectedUrls.add(url);
            }
        }
        if (!drafts.isEmpty()) {
            record.statements.clear();
            record.statements.addAll(drafts);
        }
        record.roles = roles;
    }

    private static List<ResearchTask> ownedBy(OrchestratorRun run, List<ResearchTask> tasks) {
        List<ResearchTask> owned = new ArrayList<ResearchTask>();
        for (ResearchTask task : tasks) {
            if (run.owns(task.getTaskId())) {
                owned.add(task);
            }
        }
        return owned;
    }

    /** A closed run ended sufficient (the envelope closes only on sufficient measurements). */
    static String stopReasonOf(OrchestratorRun run) {
        return run.getState() == OrchestratorRun.State.CLOSED ? SUFFICIENT : "RUN_" + run.getState().name();
    }

    private static List<String> describe(List<RoundDecision.Finding> findings) {
        List<String> lines = new ArrayList<String>();
        for (RoundDecision.Finding finding : findings) {
            lines.add(finding.getSection() + ": " + finding.getDescription());
        }
        return lines;
    }
}
