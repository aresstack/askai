package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.knowledge.processing.acronym.FileSourceAcronymStore;
import com.aresstack.askai.research.knowledge.processing.acronym.SentenceSegmenter;
import com.aresstack.askai.research.knowledge.processing.acronym.SourceAcronymService;
import com.aresstack.askai.research.knowledge.processing.acronym.SourceText;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** PR 38 in the evidence pack: acronym lines from admitted sources only, within the glossary budget. */
public class SectionContextAcronymTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void glossaryAddsAcronymsOfAdmittedSourcesOnlyWithinBudget() {
        SourceAcronymService service = new SourceAcronymService(SentenceSegmenter.splitter(),
                new FileSourceAcronymStore(folder.getRoot().toPath()));
        service.refresh(Arrays.asList(
                new SourceText("src-a", "sum-a",
                        "Assistenten nutzen Retrieval-Augmented Generation (RAG) heute. Dabei hilft RAG."),
                new SourceText("src-black", "sum-black",
                        "Hier steht Random Access Graph (RAG) und Large Language Model (LLM)."),
                new SourceText("src-excluded", "sum-excluded",
                        "Ein Natural Language Processing (NLP) Text.")));

        SectionContextBuilderTest.Corpus corpus = new SectionContextBuilderTest.Corpus();
        corpus.add("p-a1", "src-a", "Dabei hilft RAG. Auch LLM und NLP werden in diesem Abschnitt genannt.", 0.9);

        Map<String, String> accepted = new HashMap<String, String>();
        accepted.put("src-a", "sum-a");
        accepted.put("src-black", "sum-black");
        accepted.put("src-excluded", "sum-excluded");
        EvidenceSourceSet sources = new EvidenceSourceSet(accepted, Collections.singleton("src-excluded"),
                Collections.singleton("src-black"));
        OutlineSection section = new OutlineSection("sec-1", "ch-1", 0, "Begriffe",
                new OutlineBrief("Begriffe", Arrays.asList("RAG"), 450),
                new OutlineProvenance("node-1", Collections.<String>emptyList(), Arrays.asList("p-a1")),
                false, "");
        OutlineChapter chapter = new OutlineChapter("ch-1", 0, "Grundlagen", OutlineBrief.EMPTY,
                OutlineProvenance.NONE, false, "", Collections.<OutlineSection>emptyList());
        SectionContextRequest request = new SectionContextRequest("proj", "fp-rec", chapter, section, sources,
                "Synopse.", "", Arrays.asList("Grundlagen"), "de");

        EvidencePack pack = new SectionContextBuilder(new SectionContextBuilderTest.RecordingEmbeddings(), corpus,
                corpus, AcronymGlossarySource.of(service)).build(request, WriterPackSettings.SMALL);

        assertEquals(Arrays.asList("Grundlagen", "RAG: Retrieval-Augmented Generation"), pack.getGlossary());
        for (String line : pack.getGlossary()) {
            assertFalse(line.contains("Random Access") || line.startsWith("LLM") || line.startsWith("NLP"));
        }
        int chars = 0;
        for (String line : pack.getGlossary()) {
            chars += line.length() + 2;
        }
        assertTrue(chars <= SectionContextBuilder.GLOSSARY_TOKENS * TokenBudget.CHARS_PER_TOKEN);
    }
}
