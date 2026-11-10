package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.store.FileResearchTaskStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Plan PR 14 (D5): the plan build and merge, and the queue that runs it one query at a time — caps, coverage,
 * Stop, resume after a kill, deferred acquisitions, the keyword fallback note and the uncovered nodes.
 */
public class SearchPlanQueueTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private SearchPlanStore store;
    private FakePort port;
    private FakeSources sources;
    private boolean busy;
    private final List<SearchPlanQueue.Outcome> outcomes = new ArrayList<SearchPlanQueue.Outcome>();
    private long now = 1_000L;
    private int ids;

    private final Executor direct = new Executor() {
        public void execute(Runnable command) {
            command.run();
        }
    };

    @Before
    public void setUp() throws Exception {
        File project = temp.newFolder("project");
        store = new SearchPlanStore(new FileResearchTaskStore(new File(project, "tasks")), project);
        port = new FakePort();
        sources = new FakeSources();
    }

    private SearchPlanQueue queue(SearchPlanCoverage.Caps caps) {
        lastQueue = newQueue(caps, null);
        return lastQueue;
    }

    private SearchPlanQueue queue(SearchPlanCoverage.Caps caps, SearchPlanQueue.Replanner replanner) {
        lastQueue = newQueue(caps, replanner);
        return lastQueue;
    }

    private SearchPlanQueue newQueue(SearchPlanCoverage.Caps caps, SearchPlanQueue.Replanner replanner) {
        return new SearchPlanQueue(store, port, sources, new SearchPlanQueue.Busy() {
            public boolean busy() {
                return busy;
            }
        }, new SearchPlanQueue.Listener() {
            public void changed() {
            }

            public void finished(SearchPlanQueue.Outcome outcome) {
                outcomes.add(outcome);
            }
        }, direct, new SearchPlanQueue.Clock() {
            public long now() {
                return ++now;
            }
        }, caps, replanner);
    }

    private SearchPlanMerger.IdSource counter() {
        return new SearchPlanMerger.IdSource() {
            public String next() {
                return String.format("plan-%04d", ++ids);
            }
        };
    }

    static ConceptQueryPlan.Node node(String id, String label, String base) {
        return new ConceptQueryPlan.Node(id, label, Collections.singletonList(label), "", base);
    }

    private SearchPlanMerger.Result build(List<ConceptQueryPlan.Node> nodes, Map<String, List<String>> queries)
            throws Exception {
        SearchPlanMerger.Result result = SearchPlanMerger.merge(store.loadRecord(), store.loadEntries(), "research",
                1, "de", nodes, queries, counter(), ++now);
        store.saveAll(result.getChanged());
        store.saveRecord(result.getRecord());
        return result;
    }

    // ---- plan build and merge ------------------------------------------------------------------------

    @Test
    public void thePlanHoldsTheBaseQueryAndTheVariantsOfEveryNode() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Classloader Java", "Classloader Leck", "classloader java"));
        build(Arrays.asList(node("a", "Classloader", "Classloader Java"), node("b", "JPMS", "JPMS Java")), queries);
        List<SearchPlanEntry> entries = queue(caps(3, 4, 60)).entriesInOrder();
        assertEquals(3, entries.size()); // duplicate variant dropped, b keeps its base query
        assertEquals(SearchPlanEntry.Origin.BASE, entries.get(0).getOrigin());
        assertEquals(SearchPlanEntry.Origin.VARIANT, entries.get(1).getOrigin());
        assertEquals("JPMS Java", entries.get(2).getQuery());
        assertEquals("de", store.loadRecord().getLanguage());
    }

    @Test
    public void aRenamedLeafRunsAloneAndDoneEntriesOfOtherNodesStay() throws Exception {
        build(Arrays.asList(node("a", "Classloader", "Classloader Java"), node("b", "JPMS", "JPMS Java")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(1, 4, 60));
        sources.next = Arrays.asList("s1");
        sources.domains.put("s1", "a.example");
        queue.run();
        port.complete(false); // a done
        port.complete(false); // b done
        assertFalse(queue.isRunning());
        SearchPlanMerger.Result merged = build(Arrays.asList(node("a", "Classloader", "Classloader Java"),
                node("b", "JPMS-Layer", "JPMS-Layer Java")), Collections.<String, List<String>>emptyMap());
        assertEquals(1, merged.getAdded());
        int pending = 0;
        for (SearchPlanEntry entry : store.loadEntries().values()) {
            if (entry.getStatus() == SearchPlanEntry.Status.PENDING) {
                pending++;
                assertEquals("b", entry.getNodeId());
                assertEquals("JPMS-Layer Java", entry.getQuery());
            }
        }
        assertEquals(1, pending);
        port.searched.clear();
        queue.run();
        assertEquals(Collections.singletonList("JPMS-Layer Java"), port.searched);
    }

    @Test
    public void requestResearchAddsAnEntryForANodeByLabelOrIdAndStartsNothing() throws Exception {
        // plan PR 15: the Sources assistant's request_research
        build(Arrays.asList(node("a", "Classloader", "Classloader Java"), node("b", "JPMS", "JPMS Java")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        String byLabel = queue.addForNode("jpms", "JPMS layers");
        String byId = queue.addForNode("a", "parent delegation");
        String free = queue.addForNode("no such card", "OSGi comparison");
        List<SearchPlanEntry> entries = queue.entriesInOrder();
        SearchPlanEntry last = entries.get(entries.size() - 1);
        assertEquals(free, last.getId());
        assertEquals("", last.getNodeId());
        assertEquals("b", entries.get(entries.size() - 3).getNodeId());
        assertEquals(byLabel, entries.get(entries.size() - 3).getId());
        assertEquals(byId, entries.get(entries.size() - 2).getId());
        assertEquals("a", entries.get(entries.size() - 2).getNodeId());
        assertTrue("nothing searched", port.searched.isEmpty());
        assertFalse(queue.isRunning());
    }

    @Test
    public void withoutAPlanRequestResearchAddsNothingAndTheOverviewIsEmpty() throws Exception {
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        assertNull(queue.addForNode("", "anything"));
        assertEquals("", queue.overview());
    }

    @Test
    public void theOverviewNamesTheCoveragePerNodeAndTheBoundedEntries() throws Exception {
        build(Arrays.asList(node("a", "Classloader", "Classloader Java"), node("b", "JPMS", "JPMS Java")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        for (int i = 0; i < SearchPlanQueue.OVERVIEW_ENTRIES + 3; i++) {
            queue.addForNode("", "extra query " + i);
        }
        String overview = queue.overview();
        assertTrue(overview, overview.contains("- Classloader: open"));
        assertTrue(overview, overview.contains("- JPMS: open"));
        assertTrue(overview, overview.contains("[pending] Classloader Java (Classloader)"));
        assertTrue(overview, overview.contains("more"));
        assertFalse(overview, overview.contains("extra query " + (SearchPlanQueue.OVERVIEW_ENTRIES + 2)));
    }

    @Test
    public void aRemovedNodeIsSkippedAndKeptInactive() throws Exception {
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")),
                Collections.<String, List<String>>emptyMap());
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        SearchPlanRecord record = store.loadRecord();
        assertFalse(record.node("b").isActive());
        for (SearchPlanEntry entry : store.loadEntries().values()) {
            if ("b".equals(entry.getNodeId())) {
                assertEquals(SearchPlanEntry.Status.SKIPPED, entry.getStatus());
            }
        }
    }

    // ---- queue ---------------------------------------------------------------------------------------

    @Test
    public void queriesRunOneAfterAnotherInTheRecordedLanguage() throws Exception {
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        assertEquals(1, port.searched.size());
        assertTrue(queue.hasRunningEntry());
        assertEquals("de", port.languages.get(0));
        assertEquals(SearchPlanQueue.PLANNED_RANKING, port.rankings.get(0));
        port.complete(false);
        assertEquals(Arrays.asList("Alpha Query", "Beta Query"), port.searched);
        port.complete(false);
        assertFalse(queue.isRunning());
        assertEquals(1, outcomes.size());
        assertEquals(Arrays.asList("A", "B"), outcomes.get(0).getUncovered()); // no sources: both exhausted
    }

    @Test
    public void theQueueWaitsWhileAnotherAcquisitionRuns() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        busy = true;
        queue.run();
        assertTrue(port.searched.isEmpty());
        busy = false;
        queue.kick();
        assertEquals(1, port.searched.size());
    }

    @Test
    public void aTypedSearchStartedDuringAPlannedQueryWaitsForItsEnd() throws Exception {
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")),
                Collections.<String, List<String>>emptyMap());
        final SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        final List<String> ran = new ArrayList<String>();
        assertTrue(queue.deferUntilEntryEnds(new Runnable() {
            public void run() {
                ran.add("typed");
                busy = true; // the typed search now runs
            }
        }));
        assertTrue(ran.isEmpty());
        port.complete(false);
        assertEquals(Collections.singletonList("typed"), ran);
        assertEquals(1, port.searched.size()); // the next planned query waits for the typed search
        busy = false;
        queue.kick();
        assertEquals(2, port.searched.size());
    }

    @Test
    public void deferredAcquisitionsAreReleasedOneAtATime() throws Exception {
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        final List<String> ran = new ArrayList<String>();
        for (final String name : Arrays.asList("first", "second")) {
            queue.deferUntilEntryEnds(new Runnable() {
                public void run() {
                    ran.add(name);
                    busy = true;
                }
            });
        }
        port.complete(false);
        assertEquals(Collections.singletonList("first"), ran);
        busy = false;
        queue.kick();
        assertEquals(Arrays.asList("first", "second"), ran);
        assertEquals(1, port.searched.size());
        busy = false;
        queue.kick();
        assertEquals(2, port.searched.size());
    }

    @Test
    public void sourcesAcceptedBeforeAStopStillCount() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        queue.stop();
        sources.byRequest.put("req-1", Arrays.asList("s9"));
        port.fail("CANCELLED");
        SearchPlanEntry entry = queue.entriesInOrder().get(0);
        assertEquals(SearchPlanEntry.Status.PENDING, entry.getStatus());
        assertEquals(Collections.singletonList("s9"), entry.getAcceptedSourceIds());
    }

    @Test
    public void anExplicitRerunOfACoveredNodeRuns() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        sources.domains.put("s1", "one.example");
        sources.domains.put("s2", "two.example");
        sources.next = Arrays.asList("s1", "s2");
        SearchPlanQueue queue = queue(caps(2, 4, 60));
        queue.run();
        port.complete(false);
        assertNotNull(queue.rerun(queue.entriesInOrder().get(0).getId()));
        queue.run();
        assertEquals(2, port.searched.size());
    }

    @Test
    public void threeFailedQueriesInARowPauseThePlanWithTheFailureOnRecord() throws Exception {
        // live run 2026-11-09: Google blocked the search browser and the plan burnt 30 queries in 2.5 minutes
        Map<String, List<String>> variants = new java.util.HashMap<String, List<String>>();
        variants.put("a", Arrays.asList("Alpha Two", "Alpha Three", "Alpha Four", "Alpha Five"));
        build(Collections.singletonList(node("a", "A", "Alpha Query")), variants);
        SearchPlanQueue queue = queue(caps(3, 5, 60));
        queue.run();
        port.fail("The search failed: search engine presented an obstacle: challenge");
        port.fail("The search failed: search engine presented an obstacle: challenge");
        assertTrue("two failures are not yet a pause", outcomes.isEmpty());
        assertEquals(3, port.searched.size());
        port.fail("The search failed: search engine presented an obstacle: challenge");
        assertEquals(1, outcomes.size());
        SearchPlanQueue.Outcome outcome = outcomes.get(0);
        assertEquals(SearchPlanQueue.Reason.SEARCH_FAILED, outcome.getReason());
        assertFalse(queue.isRunning());
        assertEquals("no fourth query was sent against the blocked engine", 3, port.searched.size());
        assertTrue(outcome.text(), outcome.text().startsWith("Search plan paused: 3 planned searches in a row failed"));
        assertTrue(outcome.text(), outcome.text().contains("presented an obstacle: challenge"));
        assertTrue(outcome.text(), outcome.text().contains("remaining queries stay pending"));
        assertTrue(outcome.report(), outcome.report().contains("Reason: search_failed"));
        List<SearchPlanEntry.Status> statuses = new ArrayList<SearchPlanEntry.Status>();
        for (SearchPlanEntry entry : queue.entriesInOrder()) {
            statuses.add(entry.getStatus());
        }
        assertFalse("nothing is failed for good: " + statuses, statuses.contains(SearchPlanEntry.Status.FAILED));
        assertFalse("nothing is skipped: " + statuses, statuses.contains(SearchPlanEntry.Status.SKIPPED));
        assertFalse("nothing is done: " + statuses, statuses.contains(SearchPlanEntry.Status.DONE));
        // a later run starts with fresh chances
        queue.run();
        assertEquals(4, port.searched.size());
        port.fail("still blocked");
        port.fail("still blocked");
        assertEquals("the streak restarted with the run", 1, outcomes.size());
    }

    @Test
    public void aQueryThatSearchedResetsTheFailureStreak() throws Exception {
        Map<String, List<String>> variants = new java.util.HashMap<String, List<String>>();
        variants.put("a", Arrays.asList("Alpha Two", "Alpha Three", "Alpha Four", "Alpha Five"));
        build(Collections.singletonList(node("a", "A", "Alpha Query")), variants);
        SearchPlanQueue queue = queue(caps(3, 5, 60));
        queue.run();
        port.fail("blocked");
        port.fail("blocked");
        port.complete(false); // the engine answered (nothing found is still an answer)
        port.fail("blocked");
        port.fail("blocked");
        assertTrue("two failures after a success are not a pause", outcomes.isEmpty());
        assertTrue(queue.isRunning());
    }

    @Test
    public void stopCancelsTheCurrentQueryAndLeavesItPending() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        queue.stop();
        assertEquals(1, port.cancels);
        port.fail("CANCELLED");
        assertFalse(queue.isRunning());
        assertEquals(SearchPlanEntry.Status.PENDING, queue.entriesInOrder().get(0).getStatus());
        queue.run();
        assertEquals(2, port.searched.size());
        assertEquals("Alpha Query", port.searched.get(1));
    }

    @Test
    public void anEntryInterruptedByAKillIsPendingAgainAndRunsOnRun() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        queue(caps(3, 4, 60)).run(); // the app dies while the query runs
        assertEquals(SearchPlanEntry.Status.RUNNING, store.loadEntries().values().iterator().next().getStatus());
        store.resumeAfterInterruption(++now);
        SearchPlanQueue reopened = queue(caps(3, 4, 60));
        assertEquals(SearchPlanEntry.Status.PENDING, reopened.entriesInOrder().get(0).getStatus());
        assertFalse(reopened.isRunning()); // the plan runs again only on "Run search plan"
        port.searched.clear();
        reopened.run();
        assertEquals(Collections.singletonList("Alpha Query"), port.searched);
    }

    @Test
    public void aNodeIsCoveredOnlyOnceItsPlannedVariantsRanToo() throws Exception {
        // Live run 2026-11-09: every base query met the source minimum at once, the three variants per node were
        // skipped and the plan announced "all 5 nodes covered" after 6 of 19 queries. The variants are the node's
        // angles: they run, each with the node's source number as its cap.
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Alpha Query", "Alpha Variant"));
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")), queries);
        sources.domains.put("s1", "one.example");
        sources.domains.put("s2", "two.example");
        sources.domains.put("s3", "three.example");
        SearchPlanQueue queue = queue(caps(2, 4, 60));
        sources.next = Arrays.asList("s1", "s2");
        queue.run();
        assertEquals(2, port.caps.get(0).intValue());
        port.complete(false);
        assertEquals("the variant of a runs although a already has two sources", "Alpha Variant",
                port.searched.get(1));
        assertEquals("the variant keeps the node's source number as its cap", 2, port.caps.get(1).intValue());
        sources.next = Arrays.asList("s3");
        port.complete(false);
        assertEquals("Beta Query", port.searched.get(2));
        sources.next = Collections.emptyList();
        port.complete(false);
        assertEquals(Collections.singletonList("B"), outcomes.get(0).getUncovered());
        assertTrue(outcomes.get(0).report(), outcomes.get(0).report().contains(
                "A: covered (3 sources from 3 domains, 2 queries run"));
        SearchPlanEntry first = queue.entriesInOrder().get(0);
        assertEquals(Arrays.asList("s1", "s2"), first.getAcceptedSourceIds());
        SearchPlanEntry variant = queue.entriesInOrder().get(1);
        assertEquals(SearchPlanEntry.Status.DONE, variant.getStatus());
        assertEquals(Collections.singletonList("s3"), variant.getAcceptedSourceIds());
    }

    @Test
    public void oneDomainDoesNotCoverANode() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Alpha Query", "Alpha Variant"));
        build(Collections.singletonList(node("a", "A", "Alpha Query")), queries);
        sources.domains.put("s1", "one.example");
        sources.domains.put("s2", "one.example");
        sources.next = Arrays.asList("s1", "s2");
        SearchPlanQueue queue = queue(caps(2, 4, 60));
        queue.run();
        port.complete(false);
        assertEquals("Alpha Variant", port.searched.get(1));
    }

    @Test
    public void theQueryBudgetOfANodeHolds() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Alpha Query", "Alpha One", "Alpha Two"));
        build(Collections.singletonList(node("a", "A", "Alpha Query")), queries);
        SearchPlanQueue queue = queue(caps(3, 2, 60));
        queue.run();
        port.complete(false);
        port.complete(false);
        assertEquals(2, port.searched.size());
        assertFalse(queue.isRunning());
        assertEquals(SearchPlanEntry.Status.SKIPPED, queue.entriesInOrder().get(2).getStatus());
    }

    @Test
    public void thePhaseCapStopsTheQueue() throws Exception {
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")),
                Collections.<String, List<String>>emptyMap());
        sources.next = Arrays.asList("s1");
        SearchPlanQueue queue = queue(caps(3, 4, 1));
        queue.run();
        assertEquals(1, port.caps.get(0).intValue());
        port.complete(false);
        assertEquals(1, port.searched.size());
        assertTrue(outcomes.get(0).isPhaseCapReached());
    }

    @Test
    public void aKeywordFallbackIsNotedOnTheEntry() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        port.complete(true);
        assertEquals(SearchPlanQueue.KEYWORD_FALLBACK, queue.entriesInOrder().get(0).getNote());
    }

    @Test
    public void aGateCommentBecomesOnePriorityEntryWithItsCommentShown() throws Exception {
        build(Arrays.asList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        StringBuilder longComment = new StringBuilder("Please find more on licensing");
        while (longComment.length() < 300) {
            longComment.append(" and more");
        }
        String query = SearchPlanQueue.priorityQuery(longComment.toString());
        assertTrue(query.length() <= 160);
        assertEquals("priority-t1", queue.addPriority("priority-t1", "research", query,
                "Changes requested: " + longComment));
        assertEquals("the effect is idempotent per transition", "priority-t1",
                queue.addPriority("priority-t1", "research", query, "again"));
        List<SearchPlanEntry> entries = queue.entriesInOrder();
        assertEquals(2, entries.size());
        assertEquals("priority-t1", entries.get(0).getId());
        assertTrue(entries.get(0).getNodeLabel().startsWith("Changes requested: Please find"));

        // a crash after the task but before the record: the replay links it again
        SearchPlanRecord record = store.loadRecord();
        store.saveRecord(record.withOrder(record.getOrder(), Collections.<String>emptyList()));
        assertEquals(1, queue.entriesInOrder().size());
        queue.addPriority("priority-t1", "research", query, "replay");
        assertEquals("priority-t1", queue.entriesInOrder().get(0).getId());
    }

    @Test
    public void aPriorityEntryRunsFirstAndUserEditsReplaceEntries() throws Exception {
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        String bId = queue.entriesInOrder().get(1).getId();
        assertNotNull(queue.edit(bId, "Beta Edited"));
        assertNotNull(queue.addPriority("research", "more on licensing"));
        assertNull(queue.edit("missing", "x"));
        queue.run();
        assertEquals("more on licensing", port.searched.get(0));
        port.complete(false);
        port.complete(false);
        assertEquals(Arrays.asList("more on licensing", "Alpha Query", "Beta Edited"), port.searched);
    }

    @Test
    public void searchEverythingAgainAddsPendingEntriesForEndedOnes() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        port.complete(false);
        assertEquals(1, queue.searchAgain());
        assertEquals(SearchPlanEntry.Status.PENDING, queue.entriesInOrder().get(1).getStatus());
    }

    @Test
    public void aDamagedPlanRecordFailsClosed() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        java.nio.file.Files.write(store.recordFile().toPath(), "{broken".getBytes("UTF-8"));
        try {
            store.loadRecord();
            org.junit.Assert.fail("a damaged plan must not load");
        } catch (com.aresstack.askai.research.transition.CorruptProjectFileException expected) {
            assertEquals(store.recordFile(), expected.getFile());
        }
    }

    // ---- research cycles (M2) ------------------------------------------------------------------------

    @Test
    public void anUncoveredNodeIsPlannedAgainFromAnotherAngleInTheNextCycle() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Alpha Query", "Alpha Variant"));
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")), queries);
        sources.domains.put("s1", "shop.example");
        sources.domains.put("s2", "two.example");
        sources.domains.put("s3", "three.example");
        sources.domains.put("s4", "four.example");
        FakeReplanner replanner = new FakeReplanner();
        replanner.answer = answer("a", "Alpha Studie DGUV", "Alpha Vorschrift");
        SearchPlanQueue queue = queue(caps(2, 4, 60, 2), replanner);
        sources.next = Arrays.asList("s1");
        queue.run();
        port.complete(false); // Alpha Query: one source, one domain
        sources.next = Collections.emptyList();
        port.complete(false); // Alpha Variant: nothing
        sources.next = Arrays.asList("s2", "s3");
        port.complete(false); // Beta Query: covered

        // cycle 1 is over, A is uncovered: the queue asks the planner instead of announcing coverage
        assertTrue(queue.isRunning());
        assertTrue(queue.isReplanning());
        assertTrue(outcomes.isEmpty());
        assertEquals(1, replanner.requests.size());
        ConceptQueryPlan.Request request = replanner.requests.get(0);
        assertEquals(2, request.getCycle());
        assertEquals(1, request.getNodes().size());
        assertEquals("a", request.getNodes().get(0).getId());
        assertEquals(Collections.singletonList("shop.example"), request.getNodes().get(0).getFoundDomains());
        assertTrue(request.getQueriesRun().containsAll(Arrays.asList("Alpha Query", "Alpha Variant", "Beta Query")));
        assertEquals(3, request.getMaxVariantsPerNode()); // the planner's per-call limit, never above the budget

        replanner.deliver();
        assertFalse(queue.isReplanning());
        assertEquals(2, store.loadRecord().getCycle());
        assertEquals("Alpha Studie DGUV", port.searched.get(3));
        int cycleEntries = 0;
        for (SearchPlanEntry entry : queue.entriesInOrder()) {
            if (entry.getOrigin() == SearchPlanEntry.Origin.CYCLE) {
                cycleEntries++;
                assertEquals("a", entry.getNodeId());
            }
        }
        assertEquals(2, cycleEntries);
        sources.next = Arrays.asList("s4");
        port.complete(false); // A now has two sources from two domains — its second cycle query still runs
        assertTrue("A is not covered while a planned query of its cycle is pending", outcomes.isEmpty());
        assertEquals("Alpha Vorschrift", port.searched.get(4));
        sources.next = Collections.emptyList();
        port.complete(false); // Alpha Vorschrift: nothing new; every planned query of A ran → covered
        assertEquals(1, outcomes.size());
        SearchPlanQueue.Outcome outcome = outcomes.get(0);
        assertEquals(SearchPlanQueue.Reason.COVERED, outcome.getReason());
        assertEquals(2, outcome.getCycles());
        assertEquals(5, outcome.getQueriesRun());
        assertEquals(4, outcome.getSources());
        assertEquals(4, outcome.getDomains());
        assertTrue(outcome.text(), outcome.text().startsWith("Search plan finished: all 2 nodes are covered"));
        assertTrue(outcome.text(), outcome.text().contains("2 research cycles"));
        assertTrue(store.outcomeFile().isFile());
        assertTrue(outcome.report().contains("A: covered (2 sources from 2 domains, 4 queries run"));
        assertFalse(queue.isRunning());
    }

    @Test
    public void aPlanOutOfCyclesReportsTheGapAsSpentBudgetNeverAsCoverage() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        FakeReplanner replanner = new FakeReplanner();
        replanner.answer = answer("a", "Alpha Two");
        SearchPlanQueue queue = queue(caps(3, 4, 60, 2), replanner);
        queue.run();
        port.complete(false); // nothing found
        replanner.deliver();
        port.complete(false); // nothing found again; the cycles are spent
        assertEquals(1, outcomes.size());
        SearchPlanQueue.Outcome outcome = outcomes.get(0);
        assertEquals(SearchPlanQueue.Reason.BUDGET, outcome.getReason());
        assertEquals(Collections.singletonList("A"), outcome.getUncovered());
        assertTrue(outcome.text(), outcome.text().startsWith(
                "Search plan stopped by its research budget, not because the topic is exhausted: 0 of 1 nodes covered"));
        assertTrue(outcome.text(), outcome.text().contains("Still uncovered: A."));
        assertTrue(outcome.text(), outcome.text().contains("raise \"Research cycles\""));
        assertTrue(outcome.report(), outcome.report().contains("Reason: budget"));
        assertTrue(outcome.report(), outcome.report().contains("- A: uncovered (0 sources from 0 domains, 2 queries run)"));
        assertEquals("the budget stop is on record next to the plan", outcome.report(),
                new String(java.nio.file.Files.readAllBytes(store.outcomeFile().toPath()), "UTF-8"));
        assertEquals(1, replanner.requests.size()); // cycle 3 was never asked for
    }

    @Test
    public void aPlannerWithoutAnAnswerEndsThePlanHonestly() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        FakeReplanner replanner = new FakeReplanner();
        replanner.answer = null;
        SearchPlanQueue queue = queue(caps(3, 4, 60, 3), replanner);
        queue.run();
        port.complete(false);
        replanner.deliver();
        assertFalse(queue.isRunning());
        assertEquals(SearchPlanQueue.Reason.PLANNER_UNAVAILABLE, outcomes.get(0).getReason());
        assertTrue(outcomes.get(0).text(), outcomes.get(0).text().contains(
                "the planner wrote no new queries for research cycle 2"));
        assertEquals(1, store.loadRecord().getCycle());

        // an answer that only repeats queries already run adds nothing either
        outcomes.clear();
        replanner.answer = answer("a", "alpha query");
        queue.run();
        replanner.deliver();
        assertEquals(SearchPlanQueue.Reason.PLANNER_UNAVAILABLE, outcomes.get(0).getReason());
        assertEquals(1, queue.entriesInOrder().size());
    }

    @Test
    public void stopWhilePlanningTheNextCycleDropsTheLateAnswer() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        FakeReplanner replanner = new FakeReplanner();
        replanner.answer = answer("a", "Alpha Two");
        SearchPlanQueue queue = queue(caps(3, 4, 60, 3), replanner);
        queue.run();
        port.complete(false);
        assertTrue(queue.isReplanning());
        queue.stop();
        assertFalse(queue.isRunning());
        assertFalse(queue.isReplanning());
        replanner.deliver(); // too late: nothing is added, nothing runs
        assertEquals(1, store.loadRecord().getCycle());
        assertEquals(1, queue.entriesInOrder().size());
        assertTrue(outcomes.isEmpty());
        assertEquals(1, port.searched.size());

        queue.run(); // "Run search plan" asks again
        assertEquals(2, replanner.requests.size());
        replanner.deliver();
        assertEquals("Alpha Two", port.searched.get(1));
    }

    @Test
    public void theQueryBudgetOfANodeIsPerCycle() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Alpha Query", "Alpha One", "Alpha Two"));
        build(Collections.singletonList(node("a", "A", "Alpha Query")), queries);
        FakeReplanner replanner = new FakeReplanner();
        replanner.answer = answer("a", "Alpha Three", "Alpha Four", "Alpha Five");
        SearchPlanQueue queue = queue(caps(3, 2, 60, 2), replanner);
        queue.run();
        port.complete(false);
        port.complete(false);
        assertEquals(SearchPlanEntry.Status.SKIPPED, queue.entriesInOrder().get(2).getStatus()); // cycle-1 budget
        assertEquals(2, replanner.requests.get(0).getMaxVariantsPerNode());
        replanner.deliver();
        port.complete(false);
        port.complete(false);
        assertEquals(Arrays.asList("Alpha Query", "Alpha One", "Alpha Three", "Alpha Four"), port.searched);
        assertEquals(SearchPlanQueue.Reason.BUDGET, outcomes.get(0).getReason());
    }

    @Test
    public void withoutAReplannerThePlanEndsAfterOneCycleAndSaysSo() throws Exception {
        build(Collections.singletonList(node("a", "A", "Alpha Query")), Collections.<String, List<String>>emptyMap());
        queue(caps(3, 4, 60)).run();
        port.complete(false);
        assertEquals(SearchPlanQueue.Reason.BUDGET, outcomes.get(0).getReason());
        assertEquals(1, outcomes.get(0).getCycles());
        assertFalse("a stop is never announced as coverage", outcomes.get(0).text().contains("covered."));
    }

    @Test
    public void theMissionsPublishersJoinTheNextCycleEvenWithoutAPlannerAnswer() throws Exception {
        build(Collections.singletonList(node("a", "Classloader", "Classloader Java")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.run();
        port.completeRequest("req-1");
        Map<String, SearchPlanEntry> all = store.loadEntries();
        SearchPlanCoverage.Caps c = caps(3, 4, 60);
        ConceptQueryPlan.Request next = SearchPlanCycles.nextCycleRequest(store.loadRecord(), all.values(),
                SearchPlanCoverage.of(store.loadRecord(), all.values(), c, sources), c);
        assertNotNull(next);
        SourcePreference preference = SourcePreference.of(SourcePreferenceTest.RUN3,
                SourcePreferenceTest.settings(1, 3));
        SearchPlanCycles.Result cycle = SearchPlanCycles.apply(store.loadRecord(), all.values(), next, null,
                Collections.<String>emptyList(), SearchPlanQueue.ids(), 2L, preference);
        assertEquals(1, cycle.getAdded().size());
        assertEquals("Classloader Java site:baua.de", cycle.getAdded().get(0).getQuery()); // cycle 2: next publisher
        assertEquals(2, cycle.getRecord().getCycle());
        assertTrue(SearchPlanCycles.apply(store.loadRecord(), all.values(), next, null,
                Collections.<String>emptyList(), SearchPlanQueue.ids(), 2L).getAdded().isEmpty());
        assertEquals(5, c.withPreferenceQueries(1).queriesUpTo(1)); // never skipped as over budget
    }

    // ---- fakes ---------------------------------------------------------------------------------------

    private static SearchPlanCoverage.Caps caps(int perNode, int queries, int phase) {
        return new SearchPlanCoverage.Caps(perNode, queries, phase);
    }

    private static SearchPlanCoverage.Caps caps(int perNode, int queries, int phase, int cycles) {
        return new SearchPlanCoverage.Caps(perNode, queries, phase, cycles);
    }

    private static ConceptQueryPlan.Result answer(String nodeId, String... variants) {
        return new ConceptQueryPlan.Result(Collections.singletonList(
                new ConceptQueryPlan.NodePlan(nodeId, "", Arrays.asList(variants), "")));
    }

    /** The planner of the later cycles: records the request, answers when the test says so. */
    private static final class FakeReplanner implements SearchPlanQueue.Replanner {
        final List<ConceptQueryPlan.Request> requests = new ArrayList<ConceptQueryPlan.Request>();
        ConceptQueryPlan.Result answer;
        private SearchPlanQueue.Replanned pending;

        public void replan(ConceptQueryPlan.Request request, SearchPlanQueue.Replanned callback) {
            requests.add(request);
            pending = callback;
        }

        void deliver() {
            SearchPlanQueue.Replanned callback = pending;
            pending = null;
            callback.done(answer);
        }
    }


    // ---- plan T5: the research team -------------------------------------------------------------------

    private static SearchPlanQueue.Team team(final int workers) {
        return new SearchPlanQueue.Team() {
            public int workers() {
                return workers;
            }
        };
    }

    private void fourQueriesOverTwoNodes() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Classloader Java", "Classloader Leck"));
        queries.put("b", Arrays.asList("JPMS Java", "JPMS Layer"));
        build(Arrays.asList(node("a", "Classloader", "Classloader Java"), node("b", "JPMS", "JPMS Java")), queries);
    }

    @Test
    public void theNextSearchStartsOnlyWhenTheTeamHasFewerPagesOpenThanItsThreshold() throws Exception {
        fourQueriesOverTwoNodes();
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.setTeam(team(4));
        queue.run();
        assertEquals("one search; its hits are read side by side", 1, port.searched.size());
        assertEquals(port.requests, port.teamRequests);
        queue.onOpenPages("req-1", 9);
        assertEquals(1, port.searched.size());
        queue.onOpenPages("req-1", 3); // fewer open pages than workers: the next search starts
        assertEquals(2, port.searched.size());
        assertEquals("Classloader Leck", port.searched.get(1));
        queue.onOpenPages("req-1", 0);
        assertEquals("the second search has no hits yet: it counts as full", 2, port.searched.size());
        port.completeRequest("req-1");
        port.completeRequest("req-2");
        assertEquals(3, port.searched.size());
    }

    @Test
    public void runningQueriesOfOneNodeCountOnceAgainstItsBudget() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Classloader Java", "Classloader Leck", "Classloader OSGi", "Classloader JPMS"));
        build(Collections.singletonList(node("a", "Classloader", "Classloader Java")), queries);
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.setTeam(team(4));
        queue.run();
        for (int i = 1; i <= 3; i++) {
            queue.onOpenPages("req-" + i, 0);
        }
        assertEquals(4, port.searched.size()); // all four angles of the node run, none is skipped as over budget
    }

    @Test
    public void aWorkerQueryRunsBeforeThePlannedOnesAndAFencedOneIsOnlyNoted() throws Exception {
        fourQueriesOverTwoNodes();
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.setTeam(team(4));
        queue.run();
        queue.onTeamQuery("req-1", "Classloader Metaspace Leck", "");
        queue.onTeamQuery("req-1", "Kaffeemaschine kaufen", "outside scope (nearest exclusion: Shopping)");
        queue.onTeamQuery("req-1", "classloader leck", ""); // already planned
        port.completeRequest("req-1");
        assertEquals("the worker's query runs next, before the planned ones", "Classloader Metaspace Leck",
                port.searched.get(1));
        SearchPlanEntry first = null;
        SearchPlanEntry worker = null;
        for (SearchPlanEntry entry : store.loadEntries().values()) {
            if (entry.getQuery().equals("Classloader Java")) {
                first = entry;
            }
            if (entry.getOrigin() == SearchPlanEntry.Origin.WORKER) {
                worker = entry;
            }
        }
        assertEquals("a", worker.getNodeId()); // anchored at the node, so coverage still counts per node
        assertTrue(first.getTeamNotes().toString(),
                first.getTeamNotes().get(0).startsWith("search \"Classloader Metaspace Leck\" added"));
        assertTrue(first.getTeamNotes().get(1).contains("dropped: outside scope"));
        assertTrue(first.getTeamNotes().get(2).contains("dropped: already in the plan"));
    }

    @Test
    public void aWorkerQueryBeyondTheNodesBudgetIsDropped() throws Exception {
        fourQueriesOverTwoNodes();
        SearchPlanQueue queue = queue(caps(3, 2, 60));
        queue.setTeam(team(4));
        queue.run();
        queue.onTeamQuery("req-1", "Classloader Metaspace", "");
        SearchPlanEntry first = null;
        for (SearchPlanEntry entry : store.loadEntries().values()) {
            if (entry.getQuery().equals("Classloader Java")) {
                first = entry;
            }
        }
        assertTrue(first.getTeamNotes().get(0).contains("query budget"));
    }

    @Test
    public void withoutTheTeamThePlanStaysOneQueryAtATime() throws Exception {
        fourQueriesOverTwoNodes();
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.setTeam(team(0));
        queue.run();
        assertEquals(1, port.searched.size());
        assertTrue(port.teamRequests.isEmpty());
    }

    @Test
    public void stopSendsEveryRunningTeamQueryBackToPending() throws Exception {
        fourQueriesOverTwoNodes();
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.setTeam(team(2));
        queue.run();
        queue.onOpenPages("req-1", 0);
        assertEquals(2, port.searched.size());
        queue.stop();
        assertEquals(1, port.cancels);
        current().onSearchEvent("req-1", "failed", "CANCELLED", false);
        current().onSearchEvent("req-2", "failed", "CANCELLED", false);
        assertFalse(queue.isRunning());
        assertEquals(2, port.searched.size()); // nothing new started after the stop
        int pending = 0;
        for (SearchPlanEntry entry : store.loadEntries().values()) {
            if (entry.getStatus() == SearchPlanEntry.Status.PENDING) {
                pending++;
            }
        }
        assertEquals(4, pending);
    }

    @Test
    public void aMissingWorkerBrowserTurnsTheRunIntoOneQueryAtATime() throws Exception {
        fourQueriesOverTwoNodes();
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.setTeam(team(2));
        queue.run();
        current().onSearchEvent("req-1", "failed", SearchPlanQueue.NO_WORKER_BROWSER, false);
        assertTrue(queue.isRunning()); // no failure streak: the query went back to pending
        assertEquals(2, port.searched.size());
        assertEquals(1, port.teamRequests.size()); // the next runs in the chat browser, alone
        assertEquals(1, queue.runningEntryCount());
    }

    @Test
    public void aPageWorkerReportStaysOnItsEntryAndReachesTheNextCyclePlanner() throws Exception {
        build(Collections.singletonList(node("a", "Classloader", "Classloader Java")),
                Collections.<String, List<String>>emptyMap());
        SearchPlanQueue queue = queue(caps(3, 4, 60));
        queue.setTeam(team(2));
        queue.run();
        current().onSearchEvent("req-1", "team_note", "worker 1: search for OSGi class loading", false);
        current().onSearchEvent("req-1", "team_note", "worker 1: search for OSGi class loading", false);
        port.completeRequest("req-1");
        SearchPlanEntry done = store.loadEntries().values().iterator().next();
        assertEquals(Collections.singletonList("worker 1: search for OSGi class loading"), done.getTeamNotes());
        Map<String, SearchPlanEntry> all = store.loadEntries();
        SearchPlanCoverage.Caps c = caps(3, 4, 60);
        ConceptQueryPlan.Request next = SearchPlanCycles.nextCycleRequest(store.loadRecord(), all.values(),
                SearchPlanCoverage.of(store.loadRecord(), all.values(), c, sources), c);
        assertNotNull(next);
        assertEquals(Collections.singletonList("worker 1: search for OSGi class loading"),
                next.getNodes().get(0).getWorkerNotes());
    }

    // ---- fakes ---------------------------------------------------------------------------------------

    private final class FakePort implements SearchPlanQueue.SearchPort {
        final List<String> searched = new ArrayList<String>();
        final List<String> languages = new ArrayList<String>();
        final List<String> rankings = new ArrayList<String>();
        final List<Integer> caps = new ArrayList<Integer>();
        final List<String> requests = new ArrayList<String>();
        SearchPlanQueue owner;
        int cancels;
        final List<String> teamRequests = new ArrayList<String>();
        final List<String> teamNodes = new ArrayList<String>();

        public String searchAsTeam(String query, String language, String ranking, int visitLimit, int sourceCap,
                                   String nodeLabel) {
            String id = search(query, language, ranking, visitLimit, sourceCap);
            teamRequests.add(id);
            teamNodes.add(nodeLabel);
            return id;
        }

        void completeRequest(String id) {
            sources.byRequest.put(id, new ArrayList<String>(sources.next));
            current().onSearchEvent(id, "completed", "", false);
        }

        public String search(String query, String language, String ranking, int visitLimit, int sourceCap) {
            searched.add(query);
            languages.add(language);
            rankings.add(ranking);
            caps.add(sourceCap);
            String id = "req-" + searched.size();
            requests.add(id);
            return id;
        }

        public void cancel() {
            cancels++;
        }

        void complete(boolean keywordFallback) {
            String id = requests.get(requests.size() - 1);
            sources.byRequest.put(id, new ArrayList<String>(sources.next));
            current().onSearchEvent(id, "completed", "", keywordFallback);
        }

        void fail(String reason) {
            current().onSearchEvent(requests.get(requests.size() - 1), "failed", reason, false);
        }
    }

    private SearchPlanQueue lastQueue;

    private SearchPlanQueue current() {
        return lastQueue;
    }

    private final class FakeSources implements SearchPlanQueue.Sources {
        final Map<String, List<String>> byRequest = new LinkedHashMap<String, List<String>>();
        final Map<String, String> domains = new HashMap<String, String>();
        List<String> next = Collections.emptyList();

        public List<String> acceptedBy(String requestId) {
            List<String> ids = byRequest.get(requestId);
            return ids == null ? Collections.<String>emptyList() : ids;
        }

        public String of(String sourceId) {
            String domain = domains.get(sourceId);
            return domain == null ? sourceId + ".example" : domain;
        }
    }
}
