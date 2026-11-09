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
        lastQueue = newQueue(caps);
        return lastQueue;
    }

    private SearchPlanQueue newQueue(SearchPlanCoverage.Caps caps) {
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
        }, caps);
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
    public void acceptedSourcesCoverANodeAndItsOtherQueriesAreSkipped() throws Exception {
        Map<String, List<String>> queries = new HashMap<String, List<String>>();
        queries.put("a", Arrays.asList("Alpha Query", "Alpha Variant"));
        build(Arrays.asList(node("a", "A", "Alpha Query"), node("b", "B", "Beta Query")), queries);
        sources.domains.put("s1", "one.example");
        sources.domains.put("s2", "two.example");
        SearchPlanQueue queue = queue(caps(2, 4, 60));
        sources.next = Arrays.asList("s1", "s2");
        queue.run();
        assertEquals(2, port.caps.get(0).intValue());
        port.complete(false);
        assertEquals("Beta Query", port.searched.get(1)); // the variant of covered a was skipped
        sources.next = Collections.emptyList();
        port.complete(false);
        assertEquals(Collections.singletonList("B"), outcomes.get(0).getUncovered());
        SearchPlanEntry first = queue.entriesInOrder().get(0);
        assertEquals(Arrays.asList("s1", "s2"), first.getAcceptedSourceIds());
        assertEquals(SearchPlanEntry.Status.SKIPPED, queue.entriesInOrder().get(1).getStatus());
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

    // ---- fakes ---------------------------------------------------------------------------------------

    private static SearchPlanCoverage.Caps caps(int perNode, int queries, int phase) {
        return new SearchPlanCoverage.Caps(perNode, queries, phase);
    }

    private final class FakePort implements SearchPlanQueue.SearchPort {
        final List<String> searched = new ArrayList<String>();
        final List<String> languages = new ArrayList<String>();
        final List<String> rankings = new ArrayList<String>();
        final List<Integer> caps = new ArrayList<Integer>();
        final List<String> requests = new ArrayList<String>();
        SearchPlanQueue owner;
        int cancels;

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
