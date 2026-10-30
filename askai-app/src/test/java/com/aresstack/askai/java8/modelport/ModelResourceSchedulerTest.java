package com.aresstack.askai.java8.modelport;

import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.SlotUnavailableException;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Admission control of the model port (ADR 0001, 4.5): capacity 1 runs one call after another, capacity 4
 * runs four at once, waiting work is grouped by model so a model switch is a deliberate step, and neither
 * model starves.
 */
public class ModelResourceSchedulerTest {

    private static final ModelSlot JUDGE = ModelSlot.of("judge");
    private static final ModelSlot SYNTHESIS = ModelSlot.of("synthesis");
    private static final ModelSlot SCOPE_SLOT = ModelSlot.of("scope");
    private static final Duration WAIT = Duration.ofSeconds(30);

    private final Map<String, Integer> capacities = new HashMap<String, Integer>();
    private final List<String> switches = new ArrayList<String>();
    private boolean coexist;
    /** When set (>= 0), coexistence is decided by summed model sizes against this budget instead. */
    private long memoryBudget = -1L;
    private final Map<String, Long> sizes = new HashMap<String, Long>();
    private final ModelResourceScheduler scheduler = new ModelResourceScheduler(
            new ModelResourceScheduler.Policy() {
                public int capacity(String endpointId, String model) {
                    Integer value = capacities.get(model);
                    return value == null ? 1 : value;
                }

                public boolean mayCoexist(String endpointId, List<String> loaded, String next) {
                    if (memoryBudget < 0) {
                        return coexist;
                    }
                    long total = sizes.get(next);
                    for (String model : loaded) {
                        total += sizes.get(model);
                    }
                    return total <= memoryBudget;
                }
            }, new ModelResourceScheduler.SwitchListener() {
                public void onModelSwitch(String endpointId, String fromModel, String toModel) {
                    switches.add(fromModel + "->" + toModel);
                }
            });

    @After
    public void tearDown() {
        scheduler.close();
    }

    private CompletableFuture<ModelResourceScheduler.Grant> acquire(ModelSlot slot, String model) {
        return scheduler.acquire(slot, "ollama", model, WAIT);
    }

    private static ModelResourceScheduler.Grant granted(CompletableFuture<ModelResourceScheduler.Grant> future)
            throws Exception {
        assertTrue("granted", future.isDone() && !future.isCompletedExceptionally());
        return future.get();
    }

    @Test
    public void capacityOneRunsOneCallAfterAnother() throws Exception {
        CompletableFuture<ModelResourceScheduler.Grant> first = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> second = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> third = acquire(JUDGE, "gemma4:e2b");

        granted(first);
        assertFalse("the second call waits for the first", second.isDone());
        assertEquals(1, scheduler.usage("ollama", "gemma4:e2b").active);
        assertEquals(2, scheduler.usage("ollama", "gemma4:e2b").waiting);

        first.get().release();
        granted(second);
        assertFalse("still one at a time", third.isDone());
        second.get().release();
        granted(third).release();
        assertEquals(0, scheduler.usage("ollama", "gemma4:e2b").active);
    }

    @Test
    public void capacityFourRunsFourCallsAtOnceAndQueuesTheFifth() throws Exception {
        capacities.put("gemma4:e2b", 4);
        List<CompletableFuture<ModelResourceScheduler.Grant>> grants =
                new ArrayList<CompletableFuture<ModelResourceScheduler.Grant>>();
        for (int i = 0; i < 5; i++) {
            grants.add(acquire(JUDGE, "gemma4:e2b"));
        }
        for (int i = 0; i < 4; i++) {
            granted(grants.get(i));
        }
        assertFalse("never more than the capacity", grants.get(4).isDone());
        assertEquals(4, scheduler.usage("ollama", "gemma4:e2b").active);

        grants.get(2).get().release();
        granted(grants.get(4));
        assertEquals(4, scheduler.usage("ollama", "gemma4:e2b").active);
    }

    @Test
    public void waitingWorkIsGroupedByModelAndTheSwitchIsOneDeliberateStep() throws Exception {
        // gemma is busy; then judgements and syntheses (gpt-oss) arrive interleaved.
        CompletableFuture<ModelResourceScheduler.Grant> judge1 = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> synth1 = acquire(SYNTHESIS, "gpt-oss:20b");
        CompletableFuture<ModelResourceScheduler.Grant> judge2 = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> synth2 = acquire(SYNTHESIS, "gpt-oss:20b");
        CompletableFuture<ModelResourceScheduler.Grant> judge3 = acquire(JUDGE, "gemma4:e2b");
        granted(judge1);
        assertFalse("another model never runs while gemma holds the endpoint", synth1.isDone());

        judge1.get().release();
        // Arrival order J1 S1 J2 S2 J3 becomes J1 | S1 S2 | J2 J3: the switch to gpt-oss happens once and
        // serves every synthesis queued by then, then one switch back serves both remaining judgements.
        granted(synth1);
        assertFalse("gemma waits for the gpt-oss batch", judge2.isDone());
        synth1.get().release();
        granted(synth2);
        assertFalse(judge2.isDone());
        synth2.get().release();
        granted(judge2);
        judge2.get().release();
        granted(judge3).release();
        assertEquals("exactly one switch per model change", 1, countSwitches("gemma4:e2b->gpt-oss:20b"));
        assertEquals(1, countSwitches("gpt-oss:20b->gemma4:e2b"));
        assertEquals(2, switches.size());
    }

    @Test
    public void workArrivingAfterAPendingSwitchQueuesBehindIt() throws Exception {
        CompletableFuture<ModelResourceScheduler.Grant> judge1 = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> synth = acquire(SYNTHESIS, "gpt-oss:20b");
        granted(judge1);
        // Arrives AFTER the synthesis is already waiting for the switch: it must not starve the synthesis.
        CompletableFuture<ModelResourceScheduler.Grant> lateJudge = acquire(JUDGE, "gemma4:e2b");

        judge1.get().release();
        granted(synth);
        assertFalse("the late judgement waits for the next gemma batch", lateJudge.isDone());
        synth.get().release();
        granted(lateJudge).release();
        assertEquals(1, countSwitches("gemma4:e2b->gpt-oss:20b"));
        assertEquals(1, countSwitches("gpt-oss:20b->gemma4:e2b"));
    }

    @Test
    public void modelsThatFitTheMemoryBudgetRunSideBySide() throws Exception {
        coexist = true;
        CompletableFuture<ModelResourceScheduler.Grant> judge = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> synth = acquire(SYNTHESIS, "gpt-oss:20b");
        granted(judge);
        granted(synth);
        assertTrue("no switch when both fit", switches.isEmpty());
    }

    @Test
    public void theCombinedFootprintOfAllActiveModelsMustFitTheBudget() throws Exception {
        memoryBudget = 10L;
        sizes.put("a", 4L);
        sizes.put("b", 4L);
        sizes.put("c", 4L);
        CompletableFuture<ModelResourceScheduler.Grant> a = scheduler.acquire(JUDGE, "ollama", "a", WAIT);
        CompletableFuture<ModelResourceScheduler.Grant> b = scheduler.acquire(SYNTHESIS, "ollama", "b", WAIT);
        CompletableFuture<ModelResourceScheduler.Grant> c = scheduler.acquire(SCOPE_SLOT, "ollama", "c", WAIT);
        granted(a);
        granted(b);
        assertFalse("every pair fits, but a + b + c (12) exceeds the budget (10)", c.isDone());
        a.get().release();
        granted(c);
    }

    @Test
    public void aRequestThatWaitsLongerThanItsMaximumFailsNamed() throws Exception {
        granted(acquire(JUDGE, "gemma4:e2b"));
        CompletableFuture<ModelResourceScheduler.Grant> waiting =
                scheduler.acquire(JUDGE, "ollama", "gemma4:e2b", Duration.ofMillis(100));
        try {
            waiting.get(5, TimeUnit.SECONDS);
            fail("expected a wait time-out");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof SlotUnavailableException);
            assertEquals(SlotUnavailableException.Reason.WAIT_TIMEOUT,
                    ((SlotUnavailableException) expected.getCause()).getReason());
        }
        assertEquals("the timed-out request left the queue", 0, scheduler.usage("ollama", "gemma4:e2b").waiting);
    }

    @Test
    public void aCancelledRequestLeavesTheQueueAndNeverHoldsAPlace() throws Exception {
        CompletableFuture<ModelResourceScheduler.Grant> first = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> withdrawn = acquire(JUDGE, "gemma4:e2b");
        CompletableFuture<ModelResourceScheduler.Grant> next = acquire(JUDGE, "gemma4:e2b");
        withdrawn.cancel(false);
        first.get().release();
        granted(next);
        assertEquals(1, scheduler.usage("ollama", "gemma4:e2b").active);
        assertEquals(0, scheduler.usage("ollama", "gemma4:e2b").waiting);
    }

    @Test
    public void closingFailsWaitersWithPortClosed() throws Exception {
        granted(acquire(JUDGE, "gemma4:e2b"));
        CompletableFuture<ModelResourceScheduler.Grant> waiting = acquire(JUDGE, "gemma4:e2b");
        scheduler.close();
        try {
            waiting.get(5, TimeUnit.SECONDS);
            fail("expected PORT_CLOSED");
        } catch (ExecutionException expected) {
            assertEquals(SlotUnavailableException.Reason.PORT_CLOSED,
                    ((SlotUnavailableException) expected.getCause()).getReason());
        }
    }

    @Test
    public void releasingTwiceFreesOnePlaceOnly() throws Exception {
        capacities.put("gemma4:e2b", 2);
        ModelResourceScheduler.Grant one = granted(acquire(JUDGE, "gemma4:e2b"));
        granted(acquire(JUDGE, "gemma4:e2b"));
        one.release();
        one.release();
        assertEquals(1, scheduler.usage("ollama", "gemma4:e2b").active);
    }

    private int countSwitches(String change) {
        int count = 0;
        for (String recorded : switches) {
            if (recorded.equals(change)) {
                count++;
            }
        }
        return count;
    }
}
