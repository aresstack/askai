package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineCandidate;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineJobGuard;
import com.aresstack.askai.research.outline.OutlineJson;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConceptSeededOutlineBuilderTest {

    private static final String FP = "fp-test";
    private static final List<String> DIMS = Arrays.asList("Energy sources", "Solar", "Wind", "Storage", "Policy",
            "Panels", "Unrelated");

    /** The recording embedding port of PR 04: one-hot vector of the LAST label of a path. */
    static final class RecordingPort implements EmbeddingPort {
        final List<Role> roles = new ArrayList<Role>();
        final List<String> texts = new ArrayList<String>();

        public List<EmbeddingVector> embed(Role role, List<String> input) {
            List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>();
            for (String text : input) {
                roles.add(role);
                texts.add(text);
                String[] parts = text.split(" > ");
                vectors.add(oneHot(parts[parts.length - 1]));
            }
            return vectors;
        }
    }

    static EmbeddingPort.EmbeddingVector oneHot(String dim) {
        float[] values = new float[DIMS.size()];
        int index = DIMS.indexOf(dim);
        if (index >= 0) {
            values[index] = 1f;
        }
        return new EmbeddingPort.EmbeddingVector("test", FP, values);
    }

    static ConceptOutlineNode node(String id, String label, ConceptOutlineNode... children) {
        return new ConceptOutlineNode(id, label, Arrays.asList(children));
    }

    static OutlineGenerationInput.CorpusPassage passage(String id, String source, String dim) {
        return new OutlineGenerationInput.CorpusPassage(id, source,
                "Passage " + id + " talks about " + dim + " in some detail. A second sentence follows.", oneHot(dim));
    }

    static List<ConceptOutlineNode> concept() {
        return Arrays.asList(
                node("n-energy", "Energy sources",
                        node("n-solar", "Solar", node("n-panels", "Panels")),
                        node("n-wind", "Wind")),
                node("n-storage", "Storage"),
                node("n-policy", "Policy"));
    }

    static OutlineGenerationInput input(String deliverable) {
        List<OutlineGenerationInput.CorpusPassage> passages = Arrays.asList(
                passage("p10", "s2", "Unrelated"), passage("p01", "s1", "Solar"), passage("p02", "s2", "Solar"),
                passage("p03", "s1", "Panels"), passage("p04", "s3", "Wind"), passage("p05", "s1", "Storage"),
                passage("p06", "s2", "Storage"), passage("p07", "s1", "Storage"), passage("p08", "s1", "Unrelated"),
                passage("p09", "s2", "Unrelated"));
        List<OutlineGenerationInput.Cluster> clusters = Arrays.asList(
                new OutlineGenerationInput.Cluster("c-storage", "storage", Arrays.asList("p05", "p06", "p07"),
                        Arrays.asList("p05")),
                new OutlineGenerationInput.Cluster("c-other", "other topic", Arrays.asList("p08", "p09", "p10"),
                        Arrays.asList("p08", "p09")));
        return new OutlineGenerationInput("Book", "Explain renewable energy", deliverable, concept(), passages,
                clusters, new OutlineGenerationInput.Settings(0.35, 0.02, 2, 2));
    }

    private static Outline build(String deliverable, RecordingPort port) {
        return new ConceptSeededOutlineBuilder(port).build(input(deliverable)).outline;
    }

    @Test
    public void sameInputGivesByteIdenticalOutlineInConceptOrder() {
        String first = OutlineJson.write(build("", new RecordingPort()));
        String second = OutlineJson.write(build("", new RecordingPort()));
        assertEquals(first, second);
        Outline outline = build("", new RecordingPort());
        assertEquals(Arrays.asList("Energy sources", "Storage", "Policy"), titles(outline.getChapters()));
        assertEquals(Arrays.asList("Solar", "Wind"), sectionTitles(outline.getChapters().get(0)));
    }

    @Test
    public void labelPathsAreEmbeddedAsQueriesAndNoPassageIsEmbeddedAgain() {
        RecordingPort port = new RecordingPort();
        build("", port);
        assertEquals(Arrays.asList("Book > Energy sources", "Book > Energy sources > Solar",
                "Book > Energy sources > Solar > Panels", "Book > Energy sources > Wind", "Book > Storage",
                "Book > Policy"), port.texts);
        for (EmbeddingPort.Role role : port.roles) {
            assertEquals(EmbeddingPort.Role.QUERY, role);
        }
    }

    @Test
    public void thirdLevelNodeIsAKeyPointAndItsPassagesCountForTheSection() {
        OutlineSection solar = build("", new RecordingPort()).getChapters().get(0).getSections().get(0);
        assertEquals("Panels", solar.getBrief().getKeyPoints().get(0));
        assertEquals(Arrays.asList("p01", "p02", "p03"), solar.getProvenance().getPassageIds());
        assertEquals("n-solar", solar.getProvenance().getConceptNodeId());
        assertFalse(solar.isThin());
    }

    @Test
    public void thinSectionsAreFlagged() {
        OutlineSection wind = build("", new RecordingPort()).getChapters().get(0).getSections().get(1);
        assertTrue("one source only", wind.isThin());
    }

    @Test
    public void chapterWithoutSecondLevelGetsUntitledSubClusterSections() {
        OutlineChapter storage = build("", new RecordingPort()).getChapters().get(1);
        assertEquals(1, storage.getSections().size());
        OutlineSection part = storage.getSections().get(0);
        assertEquals("Storage, part 1", part.getTitle());
        assertTrue(part.isUntitled());
        assertEquals(Collections.singletonList("c-storage"), part.getProvenance().getClusterIds());
        assertEquals("n-storage", part.getProvenance().getConceptNodeId());
        assertTrue("no passage, no section", build("", new RecordingPort()).getChapters().get(2)
                .getSections().isEmpty());
    }

    @Test
    public void leftoverClustersAreListedNotInserted() {
        Outline outline = build("", new RecordingPort());
        assertEquals(1, outline.getCandidates().size());
        OutlineCandidate candidate = outline.getCandidates().get(0);
        assertEquals("c-other", candidate.getClusterId());
        assertEquals(Arrays.asList("p08", "p09", "p10"), candidate.getPassageIds());
        assertEquals(2, candidate.getSourceCount());
        assertEquals(3, outline.getChapters().size());
        assertEquals(outline, OutlineJson.read(OutlineJson.write(outline)));
    }

    @Test
    public void wordTargetsFollowTheDeliverableOrQ7() {
        Outline free = build("", new RecordingPort());
        for (OutlineChapter chapter : free.getChapters()) {
            for (OutlineSection section : chapter.getSections()) {
                int words = section.getBrief().getWordTarget();
                assertTrue(words >= 400 && words <= 1100);
            }
        }
        Outline sized = build("10 pages", new RecordingPort());
        int total = 0;
        for (OutlineChapter chapter : sized.getChapters()) {
            for (OutlineSection section : chapter.getSections()) {
                total += section.getBrief().getWordTarget();
            }
        }
        assertEquals(3000, total, 100);
        assertEquals(25000, ConceptSeededOutlineBuilder.targetWords("20000-30000 words"));
        assertEquals(0, ConceptSeededOutlineBuilder.targetWords(""));
    }

    @Test
    public void renamedSectionKeepsItsTitleAfterRegeneration() {
        Outline first = placed(build("", new RecordingPort()));
        OutlineChapter energy = first.getChapters().get(0);
        OutlineSection renamed = energy.getSections().get(0).withTitle("Photovoltaics").withUserEdited(true);
        List<OutlineSection> sections = new ArrayList<OutlineSection>(energy.getSections());
        sections.set(0, renamed);
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(first.getChapters());
        chapters.set(0, energy.withSections(sections));
        Outline edited = first.withChapters(chapters);

        Outline merged = OutlineProvenanceMerge.merge(edited, build("", new RecordingPort()));
        OutlineSection after = merged.getChapters().get(0).getSections().get(0);
        assertEquals("Photovoltaics", after.getTitle());
        assertEquals(renamed.getId(), after.getId());
        assertEquals("ids stay stable", first.allIds(), merged.allIds());
    }

    @Test
    public void editedSectionSurvivesWhenItsGeneratedChapterDisappears() {
        Outline first = placed(build("", new RecordingPort()));
        OutlineChapter energy = first.getChapters().get(0);
        List<OutlineSection> sections = new ArrayList<OutlineSection>(energy.getSections());
        sections.set(1, sections.get(1).withTitle("My wind notes").withUserEdited(true));
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(first.getChapters());
        chapters.set(0, energy.withSections(sections));

        Outline merged = OutlineProvenanceMerge.merge(first.withChapters(chapters), Outline.EMPTY);
        assertEquals(1, merged.getChapters().size());
        assertEquals(energy.getId(), merged.getChapters().get(0).getId());
        assertEquals(Collections.singletonList("My wind notes"), sectionTitles(merged.getChapters().get(0)));
    }

    @Test
    public void onlyArrayValuedPropertiesAreConceptCards() {
        List<ConceptOutlineNode> nodes = ConceptOutlineNode.firstLevel(
                "{\"concept\":[{\"Energy\":[\"a scalar note\",{\"Solar\":[]},{\"note\":\"x\"}]},"
                        + "{\"Storage\":[]}]}", null, null);
        assertEquals(2, nodes.size());
        assertEquals("Energy", nodes.get(0).getLabel());
        assertEquals(1, nodes.get(0).getChildren().size());
        assertEquals("Solar", nodes.get(0).getChildren().get(0).getLabel());
        assertEquals("path:Energy/Solar", nodes.get(0).getChildren().get(0).getId());
    }

    @Test
    public void excludedSourceContributesNothingWhileALaterBlacklistEntryDoesNot() {
        List<ResearchSourceRecord> records = Arrays.asList(
                record("s1", SourceStatus.ACCEPTED, "Solar basics"),
                record("s2", SourceStatus.EXCLUDED, "Wind farms"),
                record("s3", SourceStatus.ACCEPTED, "Coal lobby report"),
                record("s4", SourceStatus.ACCEPTED, "Tidal energy"),
                record("s5", SourceStatus.NEW, "Unreviewed"));
        // "coal" was recorded at SUBMIT_SCOPE; "tidal" was typed later and only filters future searches.
        Set<String> eligible = OutlineSourceRule.eligible(Arrays.asList("s1", "s2"), records,
                TypedBlacklist.of(Collections.singletonList("coal")));
        assertEquals(new java.util.TreeSet<String>(Arrays.asList("s1", "s4")), eligible);
    }

    @Test
    public void finishOutlineIsBlockedWhileAJobRuns() {
        OutlineJobGuard guard = new OutlineJobGuard();
        OutlineJobGuard.Job job = guard.begin("the outline generation");
        assertTrue(guard.isBusy());
        try {
            guard.begin("another");
            fail("a second job must be refused");
        } catch (IllegalStateException busy) {
            assertTrue(busy.getMessage().contains("outline generation"));
        }
        job.close();
        assertFalse(guard.isBusy());
        assertNotNull(guard.begin("again"));
    }

    private static ResearchSourceRecord record(String id, SourceStatus status, String title) {
        return ResearchSourceRecord.builder(id).title(title).url("https://example.org/" + id).status(status)
                .build();
    }

    private static Outline placed(Outline draft) {
        final int[] next = {0};
        return draft.placedAgainst(Outline.EMPTY, new Outline.IdSource() {
            public String next(String prefix) {
                return prefix + (++next[0]);
            }
        });
    }

    private static List<String> titles(List<OutlineChapter> chapters) {
        List<String> titles = new ArrayList<String>();
        for (OutlineChapter chapter : chapters) {
            titles.add(chapter.getTitle());
        }
        return titles;
    }

    private static List<String> sectionTitles(OutlineChapter chapter) {
        List<String> titles = new ArrayList<String>();
        for (OutlineSection section : chapter.getSections()) {
            titles.add(section.getTitle());
        }
        return titles;
    }
}
