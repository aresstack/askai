package com.aresstack.askai.research.knowledge.processing.acronym;

import com.aresstack.askai.research.domain.Passage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** PR 38 acceptance: findings per source, staleness, spans into the write-once text, eligibility, glossary budget. */
public class SourceAcronymServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String GERMAN = "Moderne Assistenten nutzen Retrieval-Augmented Generation (RAG) für Antworten. "
            + "Dabei liefert RAG passende Textstellen. Der Puffer heißt MAX_SIZE im Code.";
    private static final String OTHER = "Ein anderer Text definiert Random Access Graph (RAG) ganz anders.";

    private final SourceText german = new SourceText("src-de", "sha-de", GERMAN);
    private final SourceText other = new SourceText("src-x", "sha-x", OTHER);

    private SourceAcronymStore store() {
        return new FileSourceAcronymStore(folder.getRoot().toPath());
    }

    private static final class CountingStore implements SourceAcronymStore {
        final SourceAcronymStore delegate;
        int saves;

        CountingStore(SourceAcronymStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public SourceAcronymRecord load(String sourceId) {
            return delegate.load(sourceId);
        }

        @Override
        public void save(SourceAcronymRecord record) {
            saves++;
            delegate.save(record);
        }
    }

    @Test
    public void germanSourceYieldsRagWithLongFormSpansAndStatingPassage() {
        SourceAcronymService service = new SourceAcronymService(SentenceSegmenter.splitter(), store());
        service.refresh(Collections.singletonList(german));

        AcronymLongForms rag = service.longFormsOf("RAG", Collections.singleton("src-de"));
        assertNotNull(rag);
        assertEquals(Collections.singletonList("Retrieval-Augmented Generation"), rag.distinctLongForms());
        AcronymLongForms.Statement statement = rag.statements().get(0);
        assertEquals("RAG", statement.longForm().shortSpan().of(GERMAN));
        assertEquals("Retrieval-Augmented Generation", statement.longForm().longSpan().of(GERMAN));
        assertTrue(statement.longForm().sentenceSpan().of(GERMAN).startsWith("Moderne Assistenten"));
        assertFalse(rag.conflicting());

        List<Passage> passages = Arrays.asList(
                passage("p1", "Moderne Assistenten nutzen Retrieval-Augmented Generation (RAG) für Antworten.", "emb-a"),
                passage("p2", "Dabei liefert RAG passende Textstellen.", "emb-a"));
        assertEquals("p1", service.statingPassage(statement, german, passages).getPassageId());
    }

    @Test
    public void technicalIdentifierIsNoAcronym() {
        SourceAcronymStore store = store();
        new SourceAcronymService(SentenceSegmenter.splitter(), store).refresh(Collections.singletonList(german));
        SourceAcronymRecord record = store.load("src-de");
        assertEquals(TermKind.TECHNICAL_IDENTIFIER, record.term("MAX_SIZE").kind());
        assertNull(new SourceAcronymService(SentenceSegmenter.splitter(), store)
                .longFormsOf("MAX_SIZE", Collections.singleton("src-de")));
    }

    @Test
    public void newExtractorVersionOrSegmenterReRunsOnlyStaleSources() {
        SourceAcronymStore base = store();
        SourceAcronymRecord old = new SourceAcronymRecord("src-x", "sha-x", "0", "regex",
                Collections.<SourceAcronymRecord.Term>emptyList());
        new SourceAcronymService(SentenceSegmenter.splitter(), base).refresh(Collections.singletonList(german));
        base.save(old);

        CountingStore counting = new CountingStore(base);
        List<String> extracted = new SourceAcronymService(SentenceSegmenter.splitter(), counting)
                .refresh(Arrays.asList(german, other));
        assertEquals(Collections.singletonList("src-x"), extracted);
        assertEquals(1, counting.saves);

        assertTrue(new SourceAcronymService(SentenceSegmenter.splitter(), counting)
                .refresh(Arrays.asList(german, other)).isEmpty());

        SentenceSegmenter model = new SentenceSegmenter() {
            @Override
            public List<String> sentences(String paragraph) {
                return SentenceSegmenter.splitter().sentences(paragraph);
            }

            @Override
            public String id() {
                return "opennlp-abc";
            }
        };
        assertEquals(Arrays.asList("src-de", "src-x"),
                new SourceAcronymService(model, counting).refresh(Arrays.asList(german, other)));
    }

    @Test
    public void reEmbeddingRenamesAndReCutsPassagesWithoutNewExtraction() {
        CountingStore counting = new CountingStore(store());
        SourceAcronymService service = new SourceAcronymService(SentenceSegmenter.splitter(), counting);
        service.refresh(Collections.singletonList(german));
        AcronymLongForms.Statement statement =
                service.longFormsOf("RAG", Collections.singleton("src-de")).statements().get(0);
        assertEquals("a1", service.statingPassage(statement, german, Collections.singletonList(
                passage("a1", GERMAN, "emb-a"))).getPassageId());

        List<Passage> reEmbedded = Arrays.asList(
                passage("b1", "Moderne Assistenten nutzen", "emb-b"),
                passage("b2", "Retrieval-Augmented\nGeneration (RAG) für Antworten. Dabei liefert RAG", "emb-b"),
                passage("b3", "passende Textstellen.", "emb-b"));
        assertTrue(service.refresh(Collections.singletonList(german)).isEmpty());
        assertEquals(1, counting.saves);
        assertEquals("b2", service.statingPassage(statement, german, reEmbedded).getPassageId());
    }

    @Test
    public void excludedOrBlacklistedSourceReachesNeitherGlossaryNorAssembler() {
        SourceAcronymService service = new SourceAcronymService(SentenceSegmenter.splitter(), store());
        service.refresh(Arrays.asList(german, other));
        List<String> passages = Collections.singletonList("RAG liefert Textstellen.");

        assertTrue(service.longFormsOf("RAG", Arrays.asList("src-de", "src-x")).conflicting());

        List<String> onlyGerman = Collections.singletonList("src-de");
        assertEquals(Collections.singletonList("RAG: Retrieval-Augmented Generation"),
                service.glossaryLines(passages, onlyGerman, SourceAcronymService.GLOSSARY_TOKEN_BUDGET));
        AcronymLongForms forAssembler = service.longFormsOf("RAG", onlyGerman);
        assertFalse(forAssembler.conflicting());
        for (AcronymLongForms.Statement statement : forAssembler.statements()) {
            assertEquals("src-de", statement.sourceId());
        }
        assertTrue(service.glossaryLines(passages, Collections.<String>emptyList(), 200).isEmpty());
    }

    @Test
    public void glossaryStaysWithinTenEntriesAndTokenBudget() {
        StringBuilder text = new StringBuilder();
        List<String> usages = new ArrayList<String>();
        String[] words = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India",
                "Juliett", "Kilo", "Lima"};
        for (int i = 0; i < words.length; i++) {
            String a = words[i].substring(0, 1);
            String b = words[(i + 1) % words.length].substring(0, 1);
            String c = words[(i + 2) % words.length].substring(0, 1);
            text.append("Wir nutzen ").append(words[i]).append(' ').append(words[(i + 1) % words.length])
                    .append(' ').append(words[(i + 2) % words.length]).append(" (").append(a).append(b).append(c)
                    .append(") heute. ");
            usages.add("Hier steht " + a + b + c + ".");
        }
        SourceText many = new SourceText("src-many", "sha-m", text.toString());
        SourceAcronymService service = new SourceAcronymService(SentenceSegmenter.splitter(), store());
        service.refresh(Collections.singletonList(many));

        List<String> lines = service.glossaryLines(usages, Collections.singleton("src-many"), 200);
        assertEquals(SourceAcronymService.GLOSSARY_MAX_ENTRIES, lines.size());
        int tokens = 0;
        for (String line : lines) {
            tokens += SourceAcronymService.estimateTokens(line);
        }
        assertTrue(tokens <= 200);

        List<String> tight = service.glossaryLines(usages, Collections.singleton("src-many"), 12);
        int tightTokens = 0;
        for (String line : tight) {
            tightTokens += SourceAcronymService.estimateTokens(line);
        }
        assertTrue(tight.size() < lines.size());
        assertTrue(tightTokens <= 12);
    }

    @Test
    public void storeRoundTripsEscapedFields() {
        SourceAcronymStore store = store();
        SourceAcronymRecord record = new SourceAcronymRecord("https://x/a b", "c\tx", "1", "regex",
                Collections.singletonList(new SourceAcronymRecord.Term("AB", TermKind.ACRONYM,
                        Collections.singletonList(new SourceAcronymRecord.LongForm("Alpha\\Beta",
                                new TextSpan(1, 3), new TextSpan(5, 15), new TextSpan(0, 20))))));
        store.save(record);
        SourceAcronymRecord loaded = store.load("https://x/a b");
        assertEquals("c\tx", loaded.sourceChecksum());
        assertEquals("Alpha\\Beta", loaded.term("AB").longForms().get(0).longForm());
        assertEquals(new TextSpan(5, 15), loaded.term("AB").longForms().get(0).longSpan());
    }

    private static Passage passage(String id, String text, String fingerprint) {
        return new Passage(id, "cap", Collections.<String>emptyList(), "", text, fingerprint, "seg-v2");
    }
}
