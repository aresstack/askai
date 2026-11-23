package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import com.aresstack.askai.research.drafting.GapResearch;
import com.aresstack.askai.research.store.FileResearchTaskStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Component 4 of the component plan (2026-11-21): a writer's gap search passes the same search hygiene as every
 * other query that is not the user's own. Until then the queue asked its gate only about entries that were neither
 * {@code USER} nor {@code PRIORITY}, and a gap search entered through {@code addPriority} like a comment of the user;
 * this test pinned that bypass (found 2026-11-21) and now asserts the opposite.
 * <p>The real {@link GapResearch} adds its query to the real {@link SearchPlanQueue} exactly as
 * {@code ResearchAgentSession.researchGap} wires it ({@code queue.addPriority}, then {@code queue.run}); the gate
 * records every query it is asked about. The control sends the same query through the cycle planner.
 */
public class WriterGapQueryGateTest {

    private static final String FENCE = "outside the Weidezaun: near no concept node of the mind map";
    private static final String BOOK = "Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz";
    /** The off-subject gap of a live run that the old query gate was built against. */
    private static final String GAP = "Datenschutz beim betrieblichen Wiedereingliederungsmanagement";
    private static final String SEED = "Wearables Baustelle Datenschutz";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private SearchPlanStore store;
    private SearchPlanQueue queue;
    private final List<String> searched = new ArrayList<String>();
    private final List<String> requests = new ArrayList<String>();
    private final List<String> asked = new ArrayList<String>();
    private final Map<String, String> rejections = new LinkedHashMap<String, String>();
    private final List<SearchPlanQueue.Outcome> outcomes = new ArrayList<SearchPlanQueue.Outcome>();
    private long now = 1_000L;
    private int ids;

    @Before
    public void setUp() throws Exception {
        File project = temp.newFolder("project");
        store = new SearchPlanStore(new FileResearchTaskStore(new File(project, "tasks")), project);
    }

    private SearchPlanQueue newQueue(SearchPlanQueue.Replanner replanner) {
        return new SearchPlanQueue(store, new SearchPlanQueue.SearchPort() {
            public String search(String query, String language, String ranking, int visitLimit, int sourceCap) {
                searched.add(query);
                String id = "req-" + searched.size();
                requests.add(id);
                return id;
            }

            public void cancel() {
            }
        }, new SearchPlanQueue.Sources() {
            public List<String> acceptedBy(String requestId) {
                return Collections.<String>emptyList();
            }

            public String of(String sourceId) {
                return sourceId + ".example";
            }
        }, new SearchPlanQueue.Busy() {
            public boolean busy() {
                return false;
            }
        }, new SearchPlanQueue.Listener() {
            public void changed() {
            }

            public void finished(SearchPlanQueue.Outcome outcome) {
                outcomes.add(outcome);
            }

            @Override
            public void queriesRejected(Map<String, String> rejected) {
                rejections.putAll(rejected);
            }
        }, new Executor() {
            public void execute(Runnable command) {
                command.run();
            }
        }, new SearchPlanQueue.Clock() {
            public long now() {
                return ++now;
            }
        }, new SearchPlanCoverage.Caps(3, 4, 60, 0), replanner);
    }

    /** The session's part: a blacklist, and a nearness that objects (old rule on) to queries containing a word. */
    private SearchPlanQueue.QueryGate gate(final List<String> blacklist, final String objectTo) {
        return new SearchPlanQueue.QueryGate() {
            public TypedBlacklist blacklist() {
                return TypedBlacklist.of(blacklist);
            }

            public String nearness(String query) {
                asked.add(query);
                return objectTo != null && (objectTo.isEmpty() || query.contains(objectTo)) ? FENCE : null;
            }
        };
    }

    private void planWithOneNode(String baseQuery) throws Exception {
        ConceptQueryPlan.Node node = new ConceptQueryPlan.Node("a", "Datenschutz",
                Collections.singletonList("Datenschutz"), "", baseQuery);
        SearchPlanMerger.Result result = SearchPlanMerger.merge(store.loadRecord(), store.loadEntries(), "research",
                1, "de", Collections.singletonList(node), Collections.<String, List<String>>emptyMap(),
                new SearchPlanMerger.IdSource() {
                    public String next() {
                        return String.format("plan-%04d", ++ids);
                    }
                }, ++now);
        store.saveAll(result.getChanged());
        store.saveRecord(result.getRecord());
    }

    /** The writer's gap search as the session starts it: the real GapResearch on the real queue. */
    private String researchGap(String sectionId, String sectionTitle, String gap) throws Exception {
        return new GapResearch(new GapResearch.Queue() {
            public String addPriority(String entryId, String phase, String query, String label) throws Exception {
                return queue.addPriority(entryId, phase, query, label);
            }

            public void run() {
                queue.run();
            }
        }, SourcePreference.NONE, BOOK, Collections.<String>emptyList()).research(sectionId, sectionTitle, gap);
    }

    /** A gate that objects to EVERY query: a gap search no longer slips past it. */
    @Test
    public void aWritersGapSearchIsCheckedLikeEveryOtherQuery() throws Exception {
        planWithOneNode(SEED);
        queue = newQueue(null);
        queue.setQueryGate(gate(Collections.<String>emptyList(), ""));

        String entryId = researchGap("sec-3", "Datenschutz", GAP);

        SearchPlanEntry gap = store.loadEntries().get(entryId);
        String gapQuery = gap.getQuery();
        assertTrue(gapQuery, gapQuery.contains("Wiedereingliederungsmanagement"));
        assertEquals(SearchPlanEntry.Origin.PRIORITY, gap.getOrigin());
        assertTrue(entryId, entryId.startsWith(SearchPlanQueue.WRITER_GAP_PREFIX));
        // the gate was asked about the gap search, which never reached the search port ...
        assertTrue(asked.toString(), asked.contains(gapQuery));
        assertEquals(SearchPlanEntry.Status.SKIPPED, gap.getStatus());
        assertEquals(SearchPlanQueue.GATE_REJECTED + FENCE, gap.getNote());
        assertEquals(FENCE, rejections.get(gapQuery));
        // ... and the seed query met the same gate
        assertEquals(FENCE, rejections.get(SEED));
        assertTrue("nothing ran", searched.isEmpty());
    }

    @Test
    public void aWritersGapSearchThatRepeatsAnEarlierSearchIsSkippedWithItsReason() throws Exception {
        String gapQuery = ConceptPlanNodes.gapQuery(GAP, BOOK, Collections.<String>emptyList());
        planWithOneNode(gapQuery);
        queue = newQueue(null);
        queue.setQueryGate(gate(Collections.<String>emptyList(), null));
        queue.run();
        queue.onSearchEvent(requests.get(0), "completed", "", false);

        String entryId = researchGap("sec-3", "Datenschutz", GAP);

        SearchPlanEntry gap = store.loadEntries().get(entryId);
        assertEquals(gapQuery, gap.getQuery());
        assertEquals(SearchPlanEntry.Status.SKIPPED, gap.getStatus());
        assertEquals(SearchPlanQueue.GATE_REJECTED + "repeats the search \"" + gapQuery + "\"", gap.getNote());
        assertEquals(Collections.singletonList(gapQuery), searched);
    }

    @Test
    public void aWritersGapSearchOnTheBlacklistIsSkippedWithItsReason() throws Exception {
        planWithOneNode(SEED);
        queue = newQueue(null);
        // added to the scope after the concept was frozen: the gap's own query builder does not know it
        queue.setQueryGate(gate(Collections.singletonList("Wiedereingliederungsmanagement"), null));
        queue.run();
        queue.onSearchEvent(requests.get(0), "completed", "", false);

        String entryId = researchGap("sec-3", "Datenschutz", GAP);

        SearchPlanEntry gap = store.loadEntries().get(entryId);
        assertEquals(SearchPlanEntry.Status.SKIPPED, gap.getStatus());
        assertEquals(SearchPlanQueue.GATE_REJECTED + "blacklist \"Wiedereingliederungsmanagement\"", gap.getNote());
        assertEquals(Collections.singletonList(SEED), searched);
    }

    @Test
    public void aWritersGapSearchRunsWhenTheHygieneHasNoObjection() throws Exception {
        planWithOneNode(SEED);
        queue = newQueue(null);
        queue.setQueryGate(gate(Collections.<String>emptyList(), null));
        queue.run();
        queue.onSearchEvent(requests.get(0), "completed", "", false);

        String entryId = researchGap("sec-3", "Datenschutz", GAP);

        String gapQuery = store.loadEntries().get(entryId).getQuery();
        assertEquals(java.util.Arrays.asList(SEED, gapQuery), searched);
        assertTrue("its nearness was asked and only logged", asked.contains(gapQuery));
        assertTrue(rejections.toString(), rejections.isEmpty());
    }

    /** The comment of "Request changes" at the gate is the user's own search: unchecked, as before. */
    @Test
    public void theUsersCommentAtTheGateStaysUnchecked() throws Exception {
        planWithOneNode(SEED);
        queue = newQueue(null);
        queue.setQueryGate(gate(Collections.<String>emptyList(), ""));
        String id = queue.addPriority("research", GAP);
        queue.run();

        assertEquals(Collections.singletonList(GAP), searched);
        assertTrue(asked.toString(), !asked.contains(GAP));
        assertEquals(SearchPlanEntry.Status.RUNNING, store.loadEntries().get(id).getStatus());
    }

    /** Control: the very same query from the cycle planner meets the same gate. */
    @Test
    public void theSameQueryFromTheCyclePlannerIsCheckedTheSameWay() throws Exception {
        planWithOneNode(SEED);
        final String gapQuery = ConceptPlanNodes.gapQuery(GAP, BOOK, Collections.<String>emptyList());
        final SearchPlanQueue.Replanned[] pending = new SearchPlanQueue.Replanned[1];
        queue = newQueue(new SearchPlanQueue.Replanner() {
            public void replan(ConceptQueryPlan.Request request, SearchPlanQueue.Replanned callback) {
                pending[0] = callback;
            }
        });
        queue.setQueryGate(gate(Collections.<String>emptyList(), "Wiedereingliederungsmanagement"));
        queue.run();
        queue.onSearchEvent(requests.get(0), "completed", "", false);
        pending[0].done(new ConceptQueryPlan.Result(Collections.singletonList(
                new ConceptQueryPlan.NodePlan("a", "", Collections.singletonList(gapQuery), ""))));

        assertEquals(Collections.singletonList(SEED), searched);
        assertEquals(FENCE, rejections.get(gapQuery));
        assertTrue(asked.toString(), asked.contains(gapQuery));
    }
}
