package com.aresstack.askai.tool;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Parallel tool activities stay independent by id; approval keeps a bubble alive until it finishes. */
public class ToolActivityCoordinatorTest {

    @Test
    public void parallelActivitiesAreIndependent() {
        FakeSink sink = new FakeSink();
        ToolActivityCoordinator<Integer> coordinator =
                new ToolActivityCoordinator<Integer>(sink, new ToolPresentationRegistry());

        coordinator.started("call-1", "search", args("q", "gemma"));
        coordinator.started("call-2", "open_page", args("url", "x"));
        assertEquals(2, coordinator.activeCount());

        coordinator.succeeded("call-1", ToolExecutionResult.success("ok"));
        assertEquals(1, coordinator.activeCount());
        // Completing call-1 must not touch call-2.
        assertTrue(sink.completed.contains(0));
        assertFalse(sink.completed.contains(1));

        coordinator.failed("call-2", ToolExecutionResult.failure("nope"));
        assertEquals(0, coordinator.activeCount());
        assertTrue(sink.failed.contains(1));
    }

    @Test
    public void approvalKeepsTheBubbleUntilItFinishes() {
        FakeSink sink = new FakeSink();
        ToolActivityCoordinator<Integer> coordinator =
                new ToolActivityCoordinator<Integer>(sink, new ToolPresentationRegistry());

        coordinator.started("c", "delete_files", Collections.<String, Object>emptyMap());
        coordinator.approvalRequired("c", "This will remove local files.");
        assertTrue(coordinator.isAwaitingApproval("c"));
        // Still on screen, not completed/cancelled while awaiting approval.
        assertEquals(1, coordinator.activeCount());
        assertTrue(sink.completed.isEmpty());
        assertTrue(sink.cancelled.isEmpty());

        coordinator.approved("c");
        assertFalse(coordinator.isAwaitingApproval("c"));
        coordinator.succeeded("c", ToolExecutionResult.success("done"));
        assertEquals(0, coordinator.activeCount());
        assertEquals(1, sink.completed.size());
    }

    @Test
    public void cancelRemovesTheActivity() {
        FakeSink sink = new FakeSink();
        ToolActivityCoordinator<Integer> coordinator =
                new ToolActivityCoordinator<Integer>(sink, new ToolPresentationRegistry());
        coordinator.started("c", "search", null);
        coordinator.cancelled("c", "Stopped");
        assertEquals(0, coordinator.activeCount());
        assertEquals(1, sink.cancelled.size());
    }

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    /** Records sink calls; hands out an incrementing Integer handle per start. */
    private static final class FakeSink implements ToolActivitySink<Integer> {
        private int next;
        final List<Integer> completed = new ArrayList<Integer>();
        final List<Integer> failed = new ArrayList<Integer>();
        final List<Integer> cancelled = new ArrayList<Integer>();

        public Integer start(String title, String explanation) {
            return next++;
        }

        public void update(Integer handle, String title, String explanation) {
        }

        public void complete(Integer handle, String summary) {
            completed.add(handle);
        }

        public void fail(Integer handle, String summary) {
            failed.add(handle);
        }

        public void cancel(Integer handle, String summary) {
            cancelled.add(handle);
        }
    }
}
