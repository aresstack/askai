package com.aresstack.askai.research.host;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** PR 20: the drawer's clustering run — progress throttled per percent, Cancel only while running, outcomes. */
public class OutlineClusteringRunTest {

    @Test
    public void progressNotifiesOncePerPercentAndCancelOnlyWhileRunning() {
        OutlineClusteringRun run = new OutlineClusteringRun();
        final AtomicInteger notified = new AtomicInteger();
        run.addListener(new Runnable() {
            public void run() {
                notified.incrementAndGet();
            }
        });
        run.cancel();
        assertFalse("no run, nothing to cancel", run.isCancelled());

        run.started();
        int afterStart = notified.get();
        for (int i = 1; i <= 12000; i++) {
            run.progress("Comparing passages", i, 12000);
        }
        assertTrue(notified.get() - afterStart <= 101);
        assertEquals("Comparing passages … 100 %", run.status().describe());

        run.cancel();
        assertTrue(run.isCancelled());
        run.cancelled();
        assertEquals(OutlineClusteringRun.State.CANCELLED, run.status().state);

        run.started();
        assertFalse("a new run starts uncancelled", run.isCancelled());
        run.finished(42);
        assertTrue(run.status().describe().contains("left out 42 passages"));
        run.failed("disk full");
        assertEquals("Clustering failed: disk full", run.status().describe());
    }
}
