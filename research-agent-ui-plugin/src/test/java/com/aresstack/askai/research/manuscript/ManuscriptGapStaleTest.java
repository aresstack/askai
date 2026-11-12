package com.aresstack.askai.research.manuscript;

import com.aresstack.askai.research.store.FileManuscriptStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Live run 4, fix 5: the gap searches found sources, and nothing was written again with them. A written section
 * older than the newest source of its gap research becomes stale, so {@code rewrite-stale} picks it up.
 */
public class ManuscriptGapStaleTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ManuscriptWorkspace workspace() {
        return new ManuscriptWorkspace(new FileManuscriptStore(new File(folder.getRoot(), "manuscript")));
    }

    private static SectionRevision.Builder text(String text) {
        return SectionRevision.builder().text(text).author(SectionRevision.AUTHOR_WRITER)
                .citations(Collections.singletonList(new ManuscriptCitation("1", "src-a", "sha-src-a", "Cited words.", "p-src-a", null)));
    }

    private static GapSources gap(String sectionId, long at, String... sources) {
        return new GapSources(sectionId, Arrays.asList(sources), at, Collections.singletonList("Ortung Helm"));
    }

    @Test
    public void aWrittenSectionOlderThanItsNewGapSourcesBecomesStaleOnce() {
        ManuscriptWorkspace manuscript = workspace();
        manuscript.save("s1", "c1", 0, text("Helme orten Beschäftigte [1]."), 1_000L);
        manuscript.save("s2", "c1", 0, text("Westen warnen [1]."), 1_000L);
        manuscript.save("s3", "c1", 0, text("Uhren messen [1]."), 3_000L);
        manuscript.skip("s4", "c1", 1_000L);
        manuscript.markFailed("s5", "c1", "not enough evidence", 1_000L);

        Map<String, String> marked = manuscript.markGapsResearched(Arrays.asList(gap("s1", 2_000L, "n1", "n2"),
                gap("s2", 2_000L), gap("s3", 2_000L, "n3"), gap("s4", 2_000L, "n4"), gap("s5", 2_000L, "n5")),
                4_000L);

        assertEquals(Collections.singleton("s1"), marked.keySet());
        assertEquals("Gap research found 2 new sources for \"Ortung Helm\"", marked.get("s1"));
        assertEquals(SectionStatus.STALE, manuscript.store().section("s1").getStatus());
        assertEquals("no new source", SectionStatus.WRITTEN, manuscript.store().section("s2").getStatus());
        assertEquals("written after the sources came in", SectionStatus.WRITTEN,
                manuscript.store().section("s3").getStatus());
        assertEquals(SectionStatus.SKIPPED, manuscript.store().section("s4").getStatus());
        assertEquals("write-sections writes a failed one", SectionStatus.FAILED,
                manuscript.store().section("s5").getStatus());

        assertTrue("the same sources mark nothing again",
                manuscript.markGapsResearched(Collections.singletonList(gap("s1", 2_000L, "n1", "n2")), 5_000L)
                        .isEmpty());
    }

    @Test
    public void aRewrittenSectionIsNotMarkedAgainButANewerSourceMarksIt() {
        ManuscriptWorkspace manuscript = workspace();
        manuscript.save("s1", "c1", 0, text("Helme orten Beschäftigte [1]."), 1_000L);
        manuscript.markGapsResearched(Collections.singletonList(gap("s1", 2_000L, "n1")), 2_500L);
        manuscript.save("s1", "c1", 1, text("Helme orten Beschäftigte genauer [1]."), 3_000L);
        assertEquals(SectionStatus.WRITTEN, manuscript.store().section("s1").getStatus());
        assertTrue(manuscript.markGapsResearched(Collections.singletonList(gap("s1", 2_000L, "n1")), 3_500L)
                .isEmpty());
        assertEquals(Collections.singleton("s1"), manuscript.markGapsResearched(
                Collections.singletonList(gap("s1", 4_000L, "n1", "n2")), 4_500L).keySet());
    }

    /** Codex on #222: a source accepted during the model call was not in the pack, though the save is newer. */
    @Test
    public void aSourceAcceptedDuringTheModelCallMarksTheSectionSavedAfterIt() {
        ManuscriptWorkspace manuscript = workspace();
        manuscript.save("s1", "c1", 0, text("Helme orten Beschäftigte [1].").evidenceAt(1_000L), 3_000L);
        assertEquals(1_000L, manuscript.store().revision("s1", 1).getEvidenceAt());
        assertEquals(Collections.singleton("s1"), manuscript.markGapsResearched(
                Collections.singletonList(gap("s1", 2_000L, "n1")), 3_500L).keySet());
    }

    @Test
    public void aTextOfTheUserIsNeverMarked() {
        ManuscriptWorkspace manuscript = workspace();
        manuscript.save("s1", "c1", 0, text("Helme orten Beschäftigte [1].").author(SectionRevision.AUTHOR_USER),
                1_000L);
        assertTrue(manuscript.markGapsResearched(Collections.singletonList(gap("s1", 2_000L, "n1")), 2_500L)
                .isEmpty());
    }

    @Test
    public void theMarkIsExemptFromTheWriteGuardLikeTheOtherStaleMarks() {
        ManuscriptWorkspace manuscript = workspace();
        manuscript.save("s1", "c1", 0, text("Helme orten Beschäftigte [1]."), 1_000L);
        manuscript.setWriteGuard(new ManuscriptWorkspace.WriteGuard() {
            public String refusal() {
                return "The document is finished";
            }
        });
        assertEquals(Collections.singleton("s1"), manuscript.markGapsResearched(
                Collections.singletonList(gap("s1", 2_000L, "n1")), 2_500L).keySet());
    }
}
