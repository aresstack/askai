package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.domain.task.ResearchTask;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The minimal, read-only task list of the State tab (ADR 0001, section 4.4). */
public class ResearchStateViewTaskListTest {

    private static ResearchTask task(String id, String role, String... dependsOn) {
        return ResearchTask.submit(id, "research", role, Arrays.asList(dependsOn), null, "", 0, null, 2, 1L);
    }

    @Test
    public void aTaskLineShowsIdRoleStateRetriesAndDependencies() {
        ResearchTask retried = task("t-2", "source-verifier", "t-1").start("local", 2L)
                .requeue("interrupted", 3L).start("local", 4L);
        assertEquals("t-2 · source-verifier · working · retry 1/2 · after t-1",
                ResearchStateView.taskLine(retried));
        assertEquals("t-1 · web-research · submitted", ResearchStateView.taskLine(task("t-1", "web-research")));
        assertEquals("t-3 · writer · input-required",
                ResearchStateView.taskLine(task("t-3", "writer").start("local", 2L).requireInput("?", 3L)));
    }

    @Test
    public void theTaskListAppearsOnlyWhenTheRunHasTasks() throws Exception {
        final String[] texts = new String[3];
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = new ResearchStateView();
                texts[0] = view.renderedText();
                view.setTasks(Arrays.asList(task("t-1", "web-research"),
                        task("t-2", "source-verifier", "t-1")));
                texts[1] = view.renderedText();
                view.setTasks(Collections.<ResearchTask>emptyList());
                texts[2] = view.renderedText();
            }
        });
        assertFalse(texts[0].contains("tasks:"));
        assertTrue(texts[1].contains("tasks:"));
        assertTrue(texts[1].contains("  t-1 · web-research · submitted"));
        assertTrue(texts[1].contains("  t-2 · source-verifier · submitted · after t-1"));
        assertFalse(texts[2].contains("tasks:"));
    }
}
