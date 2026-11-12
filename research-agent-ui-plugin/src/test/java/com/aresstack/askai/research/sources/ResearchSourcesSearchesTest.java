package com.aresstack.askai.research.sources;

import com.aresstack.askai.research.plan.SearchPlanEntries;
import com.aresstack.askai.research.plan.SearchPlanEntry;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Searches list of the Sources tab: the planned web searches, apart from the found sources. */
public class ResearchSourcesSearchesTest {

    /** A plan over a fixed list that records what the buttons asked for. */
    private static final class FakePlan implements SourceSearchPlan {
        final List<SearchPlanEntry> entries = new ArrayList<SearchPlanEntry>();
        final List<String> calls = new ArrayList<String>();
        boolean running;
        boolean runnable = true;

        public List<SearchPlanEntry> entries() {
            return entries;
        }

        public boolean running() {
            return running;
        }

        public boolean runnable() {
            return runnable;
        }

        public List<String> uncovered() {
            return Collections.singletonList("Licensing");
        }

        public void run() {
            calls.add("run");
        }

        public void stop() {
            calls.add("stop");
        }

        public void drop(String entryId) {
            calls.add("drop " + entryId);
        }

        public void edit(String entryId, String query) {
            calls.add("edit " + entryId);
        }

        public void rerun(String entryId) {
            calls.add("rerun " + entryId);
        }

        public void searchAgain() {
            calls.add("again");
        }
    }

    private static ResearchSourcesView view() {
        return new ResearchSourcesView(InMemoryResearchSourceRepository.empty(),
                ResearchSourcesView.demoKnownSections());
    }

    @Test
    public void withoutAPlanThereIsNoSwitchAndOnlyTheResults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ResearchSourcesView view = view();
            assertFalse(view.switchShown());
            assertEquals(ResearchSourcesView.CARD_RESULTS, view.shownCard());
            assertEquals("0 sources", view.summaryText());
        });
    }

    @Test
    public void searchesAreOrderedToDoRunningDoneFailedAndNeverAppearAsSources() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FakePlan plan = new FakePlan();
            plan.entries.addAll(Arrays.asList(
                    SearchPlanEntries.failed("e-failed", "Law", "wearables law", "no engine answered"),
                    SearchPlanEntries.done("e-done", "Sensors", "wearable sensors", "s-1", "s-2"),
                    SearchPlanEntries.running("e-running", "Privacy", "wearables privacy"),
                    SearchPlanEntries.pending("e-pending", "Safety", "wearables safety")));
            ResearchSourcesView view = view();
            view.setSearchPlan(plan);

            assertTrue(view.switchShown());
            assertEquals("a planned search is no source", 0, view.rowCount());
            assertEquals(4, view.searchCount());
            assertEquals("wearables safety", view.searchCellAt(0, 0));
            assertEquals("Pending", view.searchCellAt(0, 2));
            assertEquals("Running", view.searchCellAt(1, 2));
            assertEquals("Done", view.searchCellAt(2, 2));
            assertEquals(Integer.valueOf(2), view.searchCellAt(2, 3));
            assertTrue(String.valueOf(view.searchCellAt(3, 2)).startsWith("Failed"));

            view.selectCard(ResearchSourcesView.CARD_SEARCHES);
            assertEquals("1 of 4 searches done · uncovered: Licensing", view.summaryText());
        });
    }

    @Test
    public void oneButtonDropsAPendingSearchAndRepeatsAnEndedOne() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FakePlan plan = new FakePlan();
            plan.entries.addAll(Arrays.asList(
                    SearchPlanEntries.pending("e-pending", "Safety", "wearables safety"),
                    SearchPlanEntries.done("e-done", "Sensors", "wearable sensors")));
            ResearchSourcesView view = view();
            view.setSearchPlan(plan);
            view.selectCard(ResearchSourcesView.CARD_SEARCHES);
            assertFalse("nothing selected: nothing to drop or repeat", view.dropOrRerun.isEnabled());
            assertFalse(view.edit.isEnabled());

            view.selectSearch(0);
            assertTrue(view.edit.isEnabled());
            assertEquals("Drop the selected search", view.dropOrRerun.getToolTipText());
            view.dropOrRerun.doClick();

            view.selectSearch(1);
            assertFalse("a finished search cannot be edited", view.edit.isEnabled());
            assertEquals("Run the selected search again", view.dropOrRerun.getToolTipText());
            view.dropOrRerun.doClick();

            assertEquals(Arrays.asList("drop e-pending", "rerun e-done"), plan.calls);
        });
    }

    @Test
    public void runAndStopFollowThePlan() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FakePlan plan = new FakePlan();
            plan.entries.add(SearchPlanEntries.pending("e-1", "Safety", "wearables safety"));
            ResearchSourcesView view = view();
            view.setSearchPlan(plan);
            assertTrue(view.run.isEnabled());
            assertFalse(view.stop.isEnabled());
            view.run.doClick();
            plan.running = true;
            view.refresh();
            assertFalse(view.run.isEnabled());
            assertTrue(view.stop.isEnabled());
            view.stop.doClick();
            assertEquals(Arrays.asList("run", "stop"), plan.calls);
        });
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

    @Test
    public void aWeakNodeIsMarkedInTheSearchSummary() {
        SearchPlanEntry done = SearchPlanEntry.submit("e1", "research", "a", "A", "alpha",
                SearchPlanEntry.Origin.BASE, 1L).started("r1", 2L).done(3L);
        assertEquals("1 of 1 searches done · uncovered: Licensing, Implementierung (weak evidence)",
                ResearchSourcesView.searchSummary(Collections.singletonList(done),
                        Arrays.asList("Licensing", "Implementierung"), Collections.singletonList("Implementierung")));
        assertEquals("1 of 1 searches done · uncovered: Licensing", ResearchSourcesView.searchSummary(
                Collections.singletonList(done), Collections.singletonList("Licensing")));
    }
}
