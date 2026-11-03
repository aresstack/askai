package com.aresstack.askai.research.state;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskState;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The resume rules of ADR 0001, section 4.4, on the task graph alone (no store). */
public class ResearchTaskResumptionTest {

    private static ResearchTask task(String id, int retryLimit, String... dependsOn) {
        return ResearchTask.submit(id, "research", "web-research", Arrays.asList(dependsOn), null, "", 0,
                TaskBudget.of(0L, 1_000L), retryLimit, 100L);
    }

    @Test
    public void terminalTasksAreReturnedAsTheSameObject() {
        ResearchTask completed = task("c", 1).start("local", 200L).complete(300L);
        ResearchTask failed = task("f", 0).start("local", 200L).fail("boom", 300L);
        ResearchTask canceled = task("x", 0).cancel("user", 200L);
        ResearchTask rejected = task("r", 0).reject("scope", 200L);

        ResearchTaskResumption.Outcome outcome = ResearchTaskResumption.resume(
                Arrays.asList(completed, failed, canceled, rejected), 1_000L);

        assertSame(completed, outcome.getTasks().get(0));
        assertSame(failed, outcome.getTasks().get(1));
        assertSame(canceled, outcome.getTasks().get(2));
        assertSame(rejected, outcome.getTasks().get(3));
        assertTrue(outcome.getChangedTasks().isEmpty());
    }

    @Test
    public void aWorkingTaskIsRequeuedWhileRetriesAreLeftAndFailedAfterwards() {
        ResearchTask working = task("w", 1).start("local", 200L);

        ResearchTaskResumption.Outcome first = ResearchTaskResumption.resume(
                Collections.singletonList(working), 1_000L);
        ResearchTask requeued = first.getTasks().get(0);
        assertEquals(TaskState.SUBMITTED, requeued.getState());
        assertEquals(1, requeued.getRetryCount());

        ResearchTask interruptedAgain = requeued.start("local", 1_100L);
        ResearchTaskResumption.Outcome second = ResearchTaskResumption.resume(
                Collections.singletonList(interruptedAgain), 2_000L);
        assertEquals(TaskState.FAILED, second.getTasks().get(0).getState());
        assertEquals(Collections.singletonList("w"), second.getFailedTaskIds());
    }

    @Test
    public void aRetryLimitOfZeroFailsAnInterruptedTaskImmediately() {
        ResearchTaskResumption.Outcome outcome = ResearchTaskResumption.resume(
                Collections.singletonList(task("w", 0).start("local", 200L)), 1_000L);
        assertEquals(TaskState.FAILED, outcome.getTasks().get(0).getState());
    }

    @Test
    public void inputRequiredWaitsForTheUserAndIsNeverReady() {
        ResearchTask asking = task("a", 1).start("local", 200L).requireInput("Which year?", 300L);
        ResearchTaskResumption.Outcome outcome = ResearchTaskResumption.resume(
                Collections.singletonList(asking), 1_000L);
        assertSame(asking, outcome.getTasks().get(0));
        assertEquals(Collections.singletonList("a"), outcome.getAwaitingInputTaskIds());
        assertTrue(outcome.getReadyTaskIds().isEmpty());
    }

    @Test
    public void aClockBehindTheStoredHistoryDoesNotBreakTheResume() {
        ResearchTask working = task("w", 1).start("local", 5_000L);
        ResearchTask requeued = ResearchTaskResumption.resume(
                Collections.singletonList(working), 1_000L).getTasks().get(0);
        assertEquals(TaskState.SUBMITTED, requeued.getState());
        assertEquals(5_000L, requeued.getUpdatedAtMillis());
    }

    @Test
    public void readyTasksAreTheSubmittedOnesWhoseDependenciesCompleted() {
        ResearchTask done = task("done", 0).start("local", 200L).complete(300L);
        ResearchTask next = task("next", 0, "done");
        ResearchTask blocked = task("blocked", 0, "next");
        ResearchTask orphan = task("orphan", 0, "missing");

        List<String> ready = ResearchTaskResumption.resume(
                Arrays.asList(done, next, blocked, orphan), 1_000L).getReadyTaskIds();
        assertEquals(Collections.singletonList("next"), ready);
    }

    @Test
    public void budgetsCarryOverUnchanged() {
        ResearchTask working = task("w", 1).start("local", 200L).withSpending(400L, 600L);
        ResearchTask requeued = ResearchTaskResumption.resume(
                Collections.singletonList(working), 1_000L).getTasks().get(0);
        assertEquals(400L, requeued.getBudget().getTimeUsedMillis());
        assertEquals(600L, requeued.getBudget().getTokensUsed());
        assertEquals(1_000L, requeued.getBudget().getTokenLimit());
    }
}
