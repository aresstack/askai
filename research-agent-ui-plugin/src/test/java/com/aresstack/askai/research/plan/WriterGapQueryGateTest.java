package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins what the code does today (found 2026-11-21): a writer's gap search is NOT asked about at the query gate
 * (blacklist and Weidezaun), although the class comment of {@link SearchPlanQueue} says that gap searches pass it.
 * The queue asks the gate only for entries that are neither {@code USER} nor {@code PRIORITY}, and a gap search
 * enters through {@code addPriority} like a comment of the user.
 * <p>The real {@link GapResearch} adds its query to the real {@link SearchPlanQueue} exactly as
 * {@code ResearchAgentSession.researchGap} wires it ({@code queue.addPriority}, then {@code queue.run}); the gate
 * records every query it is asked about. The control sends the same query through the cycle planner, where the
 * gate rejects it.
 * <p>This is a description, not a wish: once gap searches are put behind the gate, the first test has to assert
 * the opposite.
 */
public class WriterGapQueryGateTest {

    private static final String FENCE = "outside the Weidezaun: near no concept node of the mind map";
    private static final String BOOK = "Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz";
    /** The off-subject gap of a live run that the query gate was built against. */
    private static final String GAP = "Datenschutz beim betrieblichen Wiedereingliederungsmanagement";

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

    private void planWithOneNode() throws Exception {
        ConceptQueryPlan.Node node = new ConceptQueryPlan.Node("a", "Datenschutz",
                Collections.singletonList("Datenschutz"), "", "Wearables Baustelle Datenschutz");
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

    /** A gate that lets NOTHING through: whatever still reaches the search port never passed the gate. */
    @Test
    public void aWritersGapSearchRunsAlthoughTheGateRejectsEveryQuery() throws Exception {
        planWithOneNode();
        queue = newQueue(null);
        queue.setQueryGate(new SearchPlanQueue.QueryGate() {
            public String rejection(String query) {
                asked.add(query);
                return FENCE;
            }
        });

        String entryId = researchGap("sec-3", "Datenschutz", GAP);

        SearchPlanEntry gap = store.loadEntries().get(entryId);
        String gapQuery = gap.getQuery();
        assertTrue(gapQuery, gapQuery.contains("Wiedereingliederungsmanagement"));
        assertEquals(SearchPlanEntry.Origin.PRIORITY, gap.getOrigin());
        // the off-subject gap query went to the search port ...
        assertEquals(Collections.singletonList(gapQuery), searched);
        assertEquals(SearchPlanEntry.Status.RUNNING, gap.getStatus());
        // ... and the gate was never asked about it
        assertFalse(asked.toString(), asked.contains(gapQuery));
        assertFalse(rejections.toString(), rejections.containsKey(gapQuery));

        // the seed query of the plan, in contrast, is asked about and rejected once the gap search has ended
        queue.onSearchEvent(requests.get(0), "completed", "", false);
        assertEquals("nothing else ran", Collections.singletonList(gapQuery), searched);
        assertEquals(FENCE, rejections.get("Wearables Baustelle Datenschutz"));
        assertTrue(asked.toString(), asked.contains("Wearables Baustelle Datenschutz"));
    }

    /** Control: the very same query from the cycle planner is rejected by the same kind of gate. */
    @Test
    public void theSameQueryFromTheCyclePlannerIsRejected() throws Exception {
        planWithOneNode();
        final String gapQuery = ConceptPlanNodes.gapQuery(GAP, BOOK, Collections.<String>emptyList());
        final SearchPlanQueue.Replanned[] pending = new SearchPlanQueue.Replanned[1];
        queue = newQueue(new SearchPlanQueue.Replanner() {
            public void replan(ConceptQueryPlan.Request request, SearchPlanQueue.Replanned callback) {
                pending[0] = callback;
            }
        });
        queue.setQueryGate(new SearchPlanQueue.QueryGate() {
            public String rejection(String query) {
                asked.add(query);
                return query.contains("Wiedereingliederungsmanagement") ? FENCE : null;
            }
        });
        queue.run();
        queue.onSearchEvent(requests.get(0), "completed", "", false);
        pending[0].done(new ConceptQueryPlan.Result(Collections.singletonList(
                new ConceptQueryPlan.NodePlan("a", "", Collections.singletonList(gapQuery), ""))));

        assertEquals(Collections.singletonList("Wearables Baustelle Datenschutz"), searched);
        assertEquals(FENCE, rejections.get(gapQuery));
        assertTrue(asked.toString(), asked.contains(gapQuery));
    }
}
