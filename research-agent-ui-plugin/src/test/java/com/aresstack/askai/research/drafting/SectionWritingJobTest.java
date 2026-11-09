package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.orchestrator.InMemoryTaskBoard;
import com.aresstack.askai.research.store.FileManuscriptStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SectionWritingJobTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private InMemoryTaskBoard board;
    private ManuscriptWorkspace manuscript;
    private ScriptedWriter writer;
    private final List<String> progress = new ArrayList<String>();
    private Outline outline;

    /** A simulated app kill inside the writer: nothing below it runs. */
    private static final class Kill extends Error {
    }

    private static final class ScriptedWriter implements SectionWriter {
        final List<String> calls = new ArrayList<String>();
        final Set<String> failAlways = new HashSet<String>();
        String killAt;
        String stopAt;
        final java.util.Map<String, Runnable> during = new java.util.HashMap<String, Runnable>();
        SectionWritingJob job;
        final List<SectionWriteRequest> requests = new ArrayList<SectionWriteRequest>();

        public SectionRevision.Builder write(SectionWriteRequest request, Cancellation cancellation) {
            String id = request.getSection().getId();
            calls.add(id);
            requests.add(request);
            Runnable side = during.remove(id);
            if (side != null) {
                side.run();
            }
            if (id.equals(killAt)) {
                killAt = null;
                throw new Kill();
            }
            if (id.equals(stopAt)) {
                job.stop();
                if (cancellation.isCancelled()) {
                    throw new Cancelled();
                }
            }
            if (failAlways.contains(id)) {
                throw new IllegalStateException("model timed out");
            }
            String text = request.isRewrite()
                    ? request.getCurrentText() + "\nRevised: " + request.getComment()
                    : "Text of " + request.getSection().getTitle() + ".";
            return SectionRevision.builder().text(text).author(SectionRevision.AUTHOR_WRITER)
                    .modelLabel("scripted");
        }
    }

    private static OutlineSection section(String id, String parent, int order) {
        return new OutlineSection(id, parent, order, "Section " + id, new OutlineBrief("on " + id,
                Collections.<String>emptyList(), 200), OutlineProvenance.NONE, false, "");
    }

    private static OutlineChapter chapter(String id, int order, OutlineSection... sections) {
        return new OutlineChapter(id, order, "Chapter " + id, new OutlineBrief("why " + id, Arrays.asList("k"), 400),
                OutlineProvenance.NONE, false, "", Arrays.asList(sections));
    }

    @Before
    public void setUp() {
        board = new InMemoryTaskBoard();
        manuscript = new ManuscriptWorkspace(new FileManuscriptStore(new File(folder.getRoot(), "manuscript")));
        writer = new ScriptedWriter();
        outline = new Outline(Arrays.asList(
                chapter("c1", 1, section("s1", "c1", 1), section("s2", "c1", 2), section("s3", "c1", 3)),
                chapter("c2", 2, section("s4", "c2", 1), section("s5", "c2", 2), section("s6", "c2", 3)),
                chapter("c3", 3, section("s7", "c3", 1), section("s8", "c3", 2))));
    }

    private SectionWritingJob job() {
        SectionWritingJob job = new SectionWritingJob(board, manuscript, writer, new SectionWritingJob.Progress() {
            public void progress(String text) {
                progress.add(text);
            }
        }, null);
        writer.job = job;
        return job;
    }

    private int revisionOf(String id) {
        return manuscript.store().section(id).getRevision();
    }

    @Test
    public void writesEverySectionInOrderAndOffersFinish() {
        WritingOutcome outcome = job().writeRemaining(outline);
        assertEquals(Arrays.asList("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8"), writer.calls);
        assertEquals("Writing 3/8: Section s3", progress.get(2));
        assertEquals(WritingOutcome.ACTION_FINISH, outcome.offeredAction());
        assertTrue(outcome.getRemaining().isEmpty());
        for (ResearchTask task : board.tasks()) {
            assertEquals(TaskState.COMPLETED, task.getState());
        }
    }

    @Test
    public void afterAKillWhileSectionSixIsWrittenOnlySixAndLaterAreWritten() {
        writer.killAt = "s6";
        try {
            job().writeRemaining(outline);
            fail("killed");
        } catch (Kill expected) {
            // the app died while section 6 was written
        }
        for (String id : Arrays.asList("s1", "s2", "s3", "s4", "s5")) {
            assertEquals(1, revisionOf(id));
        }
        writer.calls.clear();
        SectionWritingJob reopened = job();
        assertEquals(1, reopened.resume());
        WritingOutcome outcome = reopened.writeRemaining(outline);
        assertEquals(Arrays.asList("s6", "s7", "s8"), writer.calls);
        for (String id : Arrays.asList("s1", "s2", "s3", "s4", "s5", "s6")) {
            assertEquals(1, revisionOf(id));
        }
        assertEquals(WritingOutcome.ACTION_FINISH, outcome.offeredAction());
    }

    @Test
    public void aSectionThatFailsTwiceIsListedAndTheJobCompletes() {
        writer.failAlways.add("s4");
        WritingOutcome outcome = job().writeRemaining(outline);
        assertEquals(2, java.util.Collections.frequency(writer.calls, "s4"));
        assertTrue(writer.calls.contains("s8"));
        assertEquals(Collections.singletonList("Section s4"), new ArrayList<String>(outcome.getFailed().keySet()));
        assertEquals(WritingOutcome.ACTION_WRITE_REMAINING, outcome.offeredAction());
        assertEquals(SectionStatus.FAILED, manuscript.store().section("s4").getStatus());
        assertTrue(outcome.text().contains("Write remaining sections"));

        writer.failAlways.clear();
        writer.calls.clear();
        WritingOutcome again = job().writeRemaining(outline);
        assertEquals(Collections.singletonList("s4"), writer.calls);
        assertEquals(WritingOutcome.ACTION_FINISH, again.offeredAction());
    }

    @Test
    public void stopLeavesTheSectionInFlightPending() {
        writer.stopAt = "s3";
        WritingOutcome outcome = job().writeRemaining(outline);
        assertTrue(outcome.isStopped());
        assertEquals(Arrays.asList("s1", "s2", "s3"), writer.calls);
        assertTrue(manuscript.store().section("s3") == null
                || manuscript.store().section("s3").getStatus() == SectionStatus.PENDING);
        writer.stopAt = null;
        writer.calls.clear();
        job().writeRemaining(outline);
        assertEquals(Arrays.asList("s3", "s4", "s5", "s6", "s7", "s8"), writer.calls);
    }

    @Test
    public void rewriteSectionTouchesExactlyOneSectionAndShowsTheDifference() {
        job().writeRemaining(outline);
        writer.calls.clear();
        WritingOutcome outcome = job().rewriteSection(outline, "s5", "more examples");
        assertEquals(Collections.singletonList("s5"), writer.calls);
        assertEquals("more examples", writer.requests.get(writer.requests.size() - 1).getComment());
        assertEquals(2, revisionOf("s5"));
        for (String id : Arrays.asList("s1", "s2", "s3", "s4", "s6", "s7", "s8")) {
            assertEquals(1, revisionOf(id));
        }
        assertTrue(outcome.getDifferences().get("s5").contains("+ Revised: more examples"));
    }

    @Test
    public void rewriteChapterTouchesOnlyThatChapter() {
        job().writeRemaining(outline);
        writer.calls.clear();
        job().rewriteChapter(outline, "c2", "shorter");
        assertEquals(Arrays.asList("s4", "s5", "s6"), writer.calls);
        for (String id : Arrays.asList("s1", "s2", "s3", "s7", "s8")) {
            assertEquals(1, revisionOf(id));
        }
        for (String id : Arrays.asList("s4", "s5", "s6")) {
            assertEquals(2, revisionOf(id));
        }
    }

    @Test
    public void staleSectionsAreListedAndRewrittenOnRequest() {
        job().writeRemaining(outline);
        manuscript.store().mark("s2", null, SectionStatus.STALE, "changed", 1L);
        assertEquals(Collections.singletonList("Section s2"), job().staleSections(outline));
        writer.calls.clear();
        job().writeRemaining(outline);
        assertTrue(writer.calls.isEmpty());
        job().rewriteStale(outline);
        assertEquals(Collections.singletonList("s2"), writer.calls);
        assertEquals(SectionStatus.WRITTEN, manuscript.store().section("s2").getStatus());
    }

    @Test
    public void theWriteGuardRefusesTheJob() {
        manuscript.setWriteGuard(new ManuscriptWorkspace.WriteGuard() {
            public String refusal() {
                return "The manuscript is read-only after finish-document.";
            }
        });
        WritingOutcome outcome = job().writeRemaining(outline);
        assertTrue(writer.calls.isEmpty());
        assertFalse(outcome.getRefusal().isEmpty());
    }

    @Test
    public void finishDocumentIsRefusedWhileSectionsAreOpenAndNamesStaleOnes() {
        writer.failAlways.add("s2");
        SectionWritingJob job = job();
        job.writeRemaining(outline);
        assertTrue(FinishDocumentCheck.refusal(job.openSections(outline)).contains("Section s2"));
        manuscript.skip("s2", "c1", 2L);
        assertEquals(null, FinishDocumentCheck.refusal(job.openSections(outline)));
        manuscript.store().mark("s7", null, SectionStatus.STALE, "changed", 3L);
        assertTrue(FinishDocumentCheck.confirmation(job.staleSections(outline)).contains("Section s7"));
    }

    @Test
    public void researchThisGapAddsOnePriorityEntryAndRunsIt() throws Exception {
        final List<String> added = new ArrayList<String>();
        final int[] runs = {0};
        GapResearch gaps = new GapResearch(new GapResearch.Queue() {
            public String addPriority(String id, String phase, String query, String label) {
                added.add(phase + "|" + query + "|" + label);
                return id;
            }

            public void run() {
                runs[0]++;
            }
        });
        String id = gaps.research("s3", "Soil moisture", "no field data for 2024");
        assertTrue(id.startsWith("gap-s3-"));
        assertEquals(Collections.singletonList("draft|Soil moisture no field data for 2024|Section: Soil moisture"),
                added);
        assertEquals(1, runs[0]);
    }

    @Test
    public void aSectionTheUserSavesOrSkipsWhileTheJobRunsIsLeftAsTheUserMadeIt() {
        writer.during.put("s3", new Runnable() {
            public void run() {
                manuscript.save("s5", "c2", 0, SectionRevision.builder().text("My own text.")
                        .author(SectionRevision.AUTHOR_USER), 1L);
                manuscript.skip("s6", "c2", 1L);
            }
        });
        writer.during.put("s7", new Runnable() {
            public void run() {
                manuscript.skip("s7", "c3", 2L); // skipped during its own model call
            }
        });
        WritingOutcome outcome = job().writeRemaining(outline);
        assertFalse(writer.calls.contains("s5"));
        assertFalse(writer.calls.contains("s6"));
        assertEquals("My own text.", manuscript.store().current("s5").getText());
        assertEquals(SectionStatus.SKIPPED, manuscript.store().section("s6").getStatus());
        assertEquals(SectionStatus.SKIPPED, manuscript.store().section("s7").getStatus());
        assertTrue(outcome.getFailed().isEmpty());
        assertEquals(WritingOutcome.ACTION_FINISH, outcome.offeredAction());
    }
}
