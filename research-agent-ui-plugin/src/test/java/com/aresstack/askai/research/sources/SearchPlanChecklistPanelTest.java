package com.aresstack.askai.research.sources;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plan PR 14: the search plan checklist and the gate line. */
public class SearchPlanChecklistPanelTest {

    @Test
    public void anEmptyPlanHidesTheChecklistAndStopIsOffWhileNothingRuns() {
        final boolean[] ran = {false};
        SearchPlanChecklistPanel panel = new SearchPlanChecklistPanel(new SearchPlanChecklistPanel.Plan() {
            public java.util.List<com.aresstack.askai.research.plan.SearchPlanEntry> entries() {
                return Collections.emptyList();
            }
            public boolean running() { return false; }
            public boolean runnable() { return true; }
            public java.util.List<String> uncovered() { return Collections.emptyList(); }
            public void run() { ran[0] = true; }
            public void stop() { }
            public void drop(String entryId) { }
            public void edit(String entryId, String query) { }
            public void rerun(String entryId) { }
            public void searchAgain() { }
        });
        assertFalse(panel.isVisible());
        for (javax.swing.JButton button : new javax.swing.JButton[] {panel.run, panel.stop, panel.again,
                panel.edit, panel.drop, panel.rerun}) {
            assertTrue("icon buttons only, the tooltip names the action", button.getIcon() != null
                    && (button.getText() == null || button.getText().isEmpty()));
            assertFalse(button.getToolTipText().isEmpty());
        }
        assertTrue(panel.run.isEnabled());
        assertFalse(panel.stop.isEnabled());
        assertFalse(panel.edit.isEnabled());
        panel.run.doClick();
        assertTrue(ran[0]);
    }

    @Test
    public void theSummaryNamesTheUncoveredNodes() {
        assertEquals("0 of 0 queries done · uncovered: Licensing, Tooling",
                SearchPlanChecklistPanel.summaryText(
                        Collections.<com.aresstack.askai.research.plan.SearchPlanEntry>emptyList(),
                        Arrays.asList("Licensing", "Tooling")));
    }

    @Test
    public void theGateLineListsUncoveredNodesOnly() {
        assertNull(com.aresstack.askai.research.agent.ResearchAgentSessionGateAccess.gate(
                Collections.<String>emptyList()));
        assertEquals("1 node has no covering sources yet: Licensing. Request changes to search for them, "
                        + "or approve the sources as they are.",
                com.aresstack.askai.research.agent.ResearchAgentSessionGateAccess.gate(
                        Collections.singletonList("Licensing")));
    }
}
