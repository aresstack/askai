package com.aresstack.askai.research.transition;

import com.aresstack.askai.research.state.CommandActor;
import com.aresstack.askai.research.state.CommandSurface;
import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.store.SessionStateFileStore;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** PR 06: the transition engine's actor policy, hooks, journal, fault injection and recovery (D2, D3). */
public class TransitionEngineTest {

    /** Stands in for a process crash at a write step. */
    private static final class Crash extends Error {
        Crash(String step) {
            super("simulated crash after " + step);
        }
    }

    /**
     * The test hook: writes a record (standing in for the pins of PR 07/14/21/26) carrying the transition id,
     * and an idempotent effect that appends the transition id to a result file once.
     */
    private static final class Fixture {
        final File dir;
        final TransitionEngine engine;
        final File records;
        final File results;

        Fixture(File dir) {
            this(dir, null, null);
        }

        Fixture(File dir, final String crashStep, final String refuse) {
            this.dir = dir;
            this.records = new File(dir, "records.txt");
            this.results = new File(dir, "results.txt");
            engine = new TransitionEngine(new OoResearchStateMachine("s1"),
                    new SessionStateFileStore(new File(dir, "state")),
                    new File(new File(dir, "state"), TransitionJournal.FILE_NAME));
            engine.addHook(new PhaseTransitionHook() {
                public String check(PhaseTransition transition) {
                    return refuse != null && refuse.equals(transition.getCommand().name())
                            ? "refused by the test hook" : null;
                }

                public void record(PhaseTransition transition) throws IOException {
                    if (transition.getCommand() == ResearchCommandType.APPROVE_EVIDENCE) {
                        append(records, transition.getId());
                        if ("records".equals(crashStep)) {
                            throw new Crash("records");
                        }
                    }
                }

                public List<String> effects(PhaseTransition transition) {
                    return transition.changesPhase() ? Arrays.asList("result")
                            : Collections.<String>emptyList();
                }
            });
            engine.registerEffect("result", new TransitionEffect() {
                public void run(PhaseTransition transition) throws Exception {
                    if (!lines(results).contains(transition.getId())) {
                        append(results, transition.getId());
                    }
                }
            });
            if (crashStep != null && !"records".equals(crashStep)) {
                engine.setFaults(new TransitionFaults() {
                    public void after(String step, PhaseTransition transition) {
                        if (step.equals(crashStep)
                                && transition.getCommand() == ResearchCommandType.APPROVE_EVIDENCE) {
                            throw new Crash(step);
                        }
                    }
                });
            }
        }

        TransitionOutcome send(ResearchCommandType type) {
            TransitionOutcome outcome = engine.dispatch(ResearchCommand.of(type, "cmd-" + System.nanoTime()));
            settle();
            return outcome;
        }

        void settle() {
            try {
                assertTrue(engine.awaitEffects(5000));
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
        }

        String phase() {
            return engine.currentState().getPhaseId() + "/" + engine.currentState().getStateId();
        }

        /** Drive to research/waiting_approval: START, SUBMIT_SCOPE (+ chained START_RESEARCH), finish. */
        void toSourcesGate() {
            assertTrue(send(ResearchCommandType.START).isCommitted());
            assertTrue(send(ResearchCommandType.SUBMIT_SCOPE).isCommitted());
            assertEquals("research/running", phase());
            assertTrue(send(ResearchCommandType.REQUEST_EVIDENCE_REVIEW).isCommitted());
            assertEquals("research/waiting_approval", phase());
        }

        List<String> records(String kind) throws Exception {
            List<String> ids = new ArrayList<String>();
            for (JsonObject record : engine.journalRecords()) {
                if (kind.equals(record.get("kind").getAsString())) {
                    ids.add(record.get("id").getAsString());
                }
            }
            return ids;
        }
    }

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("askai-transition-test").toFile();
    }

    private static void append(File file, String line) throws IOException {
        List<String> lines = new ArrayList<String>(lines(file));
        lines.add(line);
        AtomicFiles.write(file, String.join("\n", lines) + "\n");
    }

    private static List<String> lines(File file) throws IOException {
        if (!file.isFile()) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<String>();
        for (String line : new String(Files.readAllBytes(file.toPath()), "UTF-8").split("\n")) {
            if (!line.trim().isEmpty()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    @Test
    public void aRefusingHookChangesNothingAndShowsItsReason() throws Exception {
        Fixture fx = new Fixture(tempDir(), null, "START");
        File memento = new File(new File(fx.dir, "state"), "research-session.json");
        String before = new String(Files.readAllBytes(memento.toPath()), "UTF-8");
        TransitionOutcome outcome = fx.send(ResearchCommandType.START);
        assertFalse(outcome.isCommitted());
        assertEquals(TransitionOutcome.Kind.HOOK_REFUSED, outcome.getKind());
        assertEquals("refused by the test hook", outcome.getReason());
        assertEquals("scoping/new", fx.phase());
        assertEquals(before, new String(Files.readAllBytes(memento.toPath()), "UTF-8"));
        assertTrue(fx.records("committed").isEmpty());
    }

    @Test
    public void everyCommandActorAndSurfaceGoesThroughTheActorPolicy() throws Exception {
        for (ResearchCommandType type : ResearchCommandType.values()) {
            for (CommandActor actor : CommandActor.values()) {
                for (CommandSurface surface : CommandSurface.values()) {
                    Fixture fx = new Fixture(tempDir());
                    TransitionOutcome outcome = fx.engine.dispatch(new ResearchCommand(type, "c", actor,
                            surface, "corr", ResearchCommand.NO_REVISION, ""));
                    String row = type + " " + actor + " " + surface;
                    if (actor == CommandActor.BOT) {
                        assertEquals(row, TransitionOutcome.Kind.ACTOR_REFUSED, outcome.getKind());
                        assertEquals(row, "Phase changes need the user", outcome.getReason());
                    } else if (actor == CommandActor.HOST) {
                        assertEquals(row, TransitionOutcome.Kind.ACTOR_REFUSED, outcome.getKind());
                    } else {
                        assertTrue(row, outcome.getKind() == TransitionOutcome.Kind.COMMITTED
                                || outcome.getKind() == TransitionOutcome.Kind.INVALID_STATE);
                        if (type == ResearchCommandType.START) {
                            assertTrue(row, outcome.isCommitted()); // the user's way out of scoping/new
                        }
                    }
                    if (!outcome.isCommitted()) {
                        assertEquals(row, "scoping/new", fx.phase());
                        assertTrue(row, fx.records("prepared").isEmpty());
                    }
                    fx.engine.close();
                }
            }
        }
    }

    @Test
    public void submitScopeJournalsItsChainedStartWithTheSameActorAndCorrelation() throws Exception {
        Fixture fx = new Fixture(tempDir());
        assertTrue(fx.send(ResearchCommandType.START).isCommitted());
        assertTrue(fx.engine.dispatch(new ResearchCommand(ResearchCommandType.SUBMIT_SCOPE, "c1",
                CommandActor.USER, CommandSurface.USER_INTERFACE, "corr-1", ResearchCommand.NO_REVISION, ""))
                .isCommitted());
        fx.settle();
        assertEquals("research/running", fx.phase());
        List<String> commands = new ArrayList<String>();
        for (JsonObject record : fx.engine.journalRecords()) {
            if ("prepared".equals(record.get("kind").getAsString())) {
                JsonObject transition = record.getAsJsonObject("transition");
                commands.add(transition.get("command").getAsString());
                assertEquals("USER", transition.get("actor").getAsString());
                if (!"START".equals(transition.get("command").getAsString())) {
                    assertEquals("corr-1", transition.get("correlationId").getAsString());
                }
            }
        }
        assertEquals(Arrays.asList("START", "SUBMIT_SCOPE", "START_RESEARCH"), commands);
    }

    @Test
    public void twoApprovalsWithTheSameRevisionApproveOnce() throws Exception {
        Fixture fx = new Fixture(tempDir());
        fx.toSourcesGate();
        long seen = fx.engine.currentState().getRevision();
        TransitionOutcome first = fx.engine.dispatch(new ResearchCommand(ResearchCommandType.APPROVE_EVIDENCE,
                "a1", CommandActor.USER, CommandSurface.USER_INTERFACE, "x", seen, ""));
        TransitionOutcome second = fx.engine.dispatch(new ResearchCommand(ResearchCommandType.APPROVE_EVIDENCE,
                "a2", CommandActor.USER, CommandSurface.USER_INTERFACE, "y", seen, ""));
        assertTrue(first.isCommitted());
        assertEquals(TransitionOutcome.Kind.OUTDATED, second.getKind());
        assertEquals("The workflow changed. Check the current state and try again.", second.getReason());
        assertEquals("outline/running", fx.phase());
    }

    /** Fault injection after every write step of APPROVE_EVIDENCE, then reopen and recover. */
    @Test
    public void aCrashAfterAnyWriteStepRecoversToAConsistentProject() throws Exception {
        for (String step : new String[] {"prepared", "records", "memento", "committed", "effect:result"}) {
            File dir = tempDir();
            Fixture crashing = new Fixture(dir, step, null);
            crashing.toSourcesGate();
            try {
                crashing.engine.dispatch(ResearchCommand.of(ResearchCommandType.APPROVE_EVIDENCE, "a"));
            } catch (Crash expected) {
                // the dispatching thread "died" here
            }
            crashing.settle();
            crashing.engine.close();

            Fixture reopened = new Fixture(dir);
            assertNull(step, reopened.engine.blockedReason());
            reopened.engine.recover();
            reopened.settle();
            boolean committed = !"prepared".equals(step) && !"records".equals(step);
            assertEquals(step, committed ? "outline/running" : "research/waiting_approval", reopened.phase());
            List<String> testRecords = lines(reopened.records);
            if (committed) {
                assertEquals(step, 1, testRecords.size());
                String approval = testRecords.get(0);
                assertTrue(step, reopened.engine.isCommitted(approval));
                assertEquals(step, approval, reopened.engine.lastTransitionId());
                // every effect of a committed transition has its result exactly once
                int count = 0;
                for (String result : lines(reopened.results)) {
                    count += result.equals(approval) ? 1 : 0;
                }
                assertEquals(step, 1, count);
                assertTrue(step, reopened.records("done").contains(approval));
            } else {
                for (String record : testRecords) {
                    assertFalse(step + ": a record of a transition that never committed is not valid",
                            reopened.engine.isCommitted(record));
                }
                // the project still works: approving now succeeds
                assertTrue(step, reopened.send(ResearchCommandType.APPROVE_EVIDENCE).isCommitted());
            }
            reopened.engine.close();
        }
    }

    @Test
    public void aKillBetweenApproveOutlineAndStartDraftingDispatchesTheStartOnceOnReopen() throws Exception {
        File dir = tempDir();
        Fixture fx = new Fixture(dir);
        fx.toSourcesGate();
        assertTrue(fx.send(ResearchCommandType.APPROVE_EVIDENCE).isCommitted());
        assertTrue(fx.send(ResearchCommandType.PROPOSE_OUTLINE).isCommitted());
        assertEquals("outline/waiting_approval", fx.phase());
        fx.engine.setFaults(new TransitionFaults() {
            public void after(String step, PhaseTransition transition) {
                if ("committed".equals(step) && transition.getCommand() == ResearchCommandType.APPROVE_OUTLINE) {
                    throw new Crash(step);
                }
            }
        });
        try {
            fx.engine.dispatch(ResearchCommand.of(ResearchCommandType.APPROVE_OUTLINE, "ao"));
        } catch (Crash expected) {
            // killed before the chained start was queued
        }
        fx.engine.close();
        assertEquals("draft/waiting", new Fixture(dir).phase());

        for (int open = 0; open < 2; open++) {
            Fixture reopened = new Fixture(dir);
            reopened.engine.recover();
            reopened.settle();
            assertEquals("draft/running", reopened.phase());
            int starts = 0;
            for (JsonObject record : reopened.engine.journalRecords()) {
                if ("prepared".equals(record.get("kind").getAsString()) && "START_DRAFTING".equals(
                        record.getAsJsonObject("transition").get("command").getAsString())) {
                    starts++;
                }
            }
            assertEquals("START_DRAFTING exactly once (open " + open + ")", 1, starts);
            reopened.engine.close();
        }
    }

    @Test
    public void aRefusedChainedStartLeavesTheWaitingStateAndAQueuedOneIsVisible() throws Exception {
        Fixture fx = new Fixture(tempDir(), null, "START_RESEARCH");
        assertTrue(fx.send(ResearchCommandType.START).isCommitted());
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final boolean[] queuedSeen = {false};
        fx.engine.registerEffect("block", new TransitionEffect() {
            public void run(PhaseTransition transition) throws Exception {
                release.await();
            }
        });
        fx.engine.addHook(new PhaseTransitionHook() {
            public List<String> effects(PhaseTransition transition) {
                return transition.getCommand() == ResearchCommandType.SUBMIT_SCOPE
                        ? Arrays.asList("block") : Collections.<String>emptyList();
            }
        });
        assertTrue(fx.engine.dispatch(ResearchCommand.of(ResearchCommandType.SUBMIT_SCOPE, "s")).isCommitted());
        queuedSeen[0] = fx.engine.hasQueuedChainStart();
        release.countDown();
        fx.settle();
        assertTrue("while the chained start is queued", queuedSeen[0]);
        assertFalse(fx.engine.hasQueuedChainStart());
        assertEquals("a refused chained start stays in waiting", "research/waiting", fx.phase());
    }

    @Test
    public void aTornLastJournalLineIsIgnored() throws Exception {
        File dir = tempDir();
        Fixture fx = new Fixture(dir);
        assertTrue(fx.send(ResearchCommandType.START).isCommitted());
        fx.engine.close();
        File journal = new File(new File(dir, "state"), TransitionJournal.FILE_NAME);
        Files.write(journal.toPath(), "{\"kind\":\"prepared\",\"id\":\"t-tor".getBytes("UTF-8"),
                java.nio.file.StandardOpenOption.APPEND);
        Fixture reopened = new Fixture(dir);
        assertNull(reopened.engine.blockedReason());
        reopened.engine.recover();
        assertEquals("scoping/running", reopened.phase());
        assertTrue(reopened.send(ResearchCommandType.SUBMIT_SCOPE).isCommitted());
        assertEquals("research/running", reopened.phase());
    }

    @Test
    public void aCorruptJournalOrMementoKeepsItsFileBlocksAndStartsNothing() throws Exception {
        for (String broken : new String[] {TransitionJournal.FILE_NAME, "research-session.json"}) {
            File dir = tempDir();
            Fixture fx = new Fixture(dir);
            assertTrue(fx.send(ResearchCommandType.START).isCommitted());
            fx.engine.close();
            File file = new File(new File(dir, "state"), broken);
            byte[] garbage = "this is not json\n".getBytes("UTF-8");
            Files.write(file.toPath(), garbage);
            Fixture reopened = new Fixture(dir);
            String reason = reopened.engine.blockedReason();
            assertNotNull(broken, reason);
            assertTrue(reason, reason.contains(broken) && reason.contains("Repair or restore"));
            assertEquals(TransitionOutcome.Kind.BLOCKED,
                    reopened.engine.dispatch(ResearchCommand.of(ResearchCommandType.SUBMIT_SCOPE, "x")).getKind());
            assertEquals(reason, reopened.engine.recover());
            assertTrue("the file is kept", Arrays.equals(garbage, Files.readAllBytes(file.toPath())));
            File[] copies = file.getParentFile().listFiles();
            boolean copied = false;
            for (File copy : copies) {
                copied |= copy.getName().startsWith(broken + ".corrupt-");
            }
            assertTrue(copied);
            reopened.engine.close();
        }
    }
}
