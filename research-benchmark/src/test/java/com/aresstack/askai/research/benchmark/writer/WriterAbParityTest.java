package com.aresstack.askai.research.benchmark.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.askai.research.drafting.EvidenceAssignments;
import com.aresstack.askai.research.drafting.ProductiveSectionContexts;
import com.aresstack.askai.research.drafting.SectionWriteRequest;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.writer.SectionAssignment;
import com.aresstack.askai.research.runtime.writer.SectionWriter;
import com.aresstack.askai.research.store.FileManuscriptStore;
import com.aresstack.askai.research.writer.EvidencePassageLookup;
import com.aresstack.askai.research.writer.SectionContextBuilder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Stage 0 of the writer proposal: the writer A/B sends the writer exactly what the app sends it. The app path is the
 * app's {@link EvidenceAssignments} over its pack builder; the A/B path exports that pack ({@link WriterAbExport}),
 * goes through the JSON file and builds the assignment with {@link WriterAbPipeline}. Both assignments are written
 * by the same writer on a recording model, and every request it sends (first call, top-ups, per part) must be equal.
 */
public class WriterAbParityTest {

    static final String FP = "fp-test";
    static final String MODEL = "gemma4:12b";

    /** A semantic index and passage lookup over fixed passages, best score first. */
    static final class Corpus implements SemanticKnowledgeIndex, EvidencePassageLookup {
        final Map<String, PassageSearchHit> byId = new LinkedHashMap<String, PassageSearchHit>();

        void add(String passageId, String sourceId, String text, double score) {
            byId.put(passageId, new PassageSearchHit(passageId, "cap-" + passageId, sourceId, text, "", score));
        }

        public PassageSearchHit passage(String projectId, String passageId) {
            return byId.get(passageId);
        }

        public List<PassageSearchHit> semanticSearch(String projectId, PassageSemanticQuery query) {
            List<PassageSearchHit> hits = new ArrayList<PassageSearchHit>(byId.values());
            Collections.sort(hits, (a, b) -> Double.compare(b.getScore(), a.getScore()));
            return new ArrayList<PassageSearchHit>(hits.subList(0, Math.min(hits.size(), query.getMaxResults())));
        }

        public void indexPassages(String projectId, Collection<PassageIndexDocument> passages) {
            throw new AssertionError("read only");
        }

        public void replacePassagesForCapture(String projectId, String fp, String captureId,
                                              Collection<PassageIndexDocument> passages) {
            throw new AssertionError("read only");
        }

        public List<PassageSearchHit> keywordSearch(String projectId, PassageTextQuery query) {
            throw new AssertionError("no keyword search");
        }

        public void rebuild(String projectId, Collection<PassageIndexDocument> passages) {
            throw new AssertionError("read only");
        }

        public void removeProject(String projectId) {
            throw new AssertionError("read only");
        }
    }

    static final class Embeddings implements EmbeddingPort {
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new EmbeddingVector("test", FP, new float[]{1f, 0f}));
            }
            return out;
        }
    }

    /** Records every request and answers with a short cited text, so top-ups follow. */
    static final class RecordingChat implements MainModelChat {
        final List<String> requests = new ArrayList<String>();

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            StringBuilder request = new StringBuilder("max=" + maxOutputTokens + " t=" + temperature + "\n");
            for (ChatMessage m : messages) {
                request.append(m.getRole().wire()).append(": ").append(m.getContent()).append("\n---\n");
            }
            requests.add(request.toString());
            return MainModelChatResult.ok("Sensoren auf Baustellen messen Herzfrequenz und Temperatur der "
                    + "Beschäftigten im Jahr 2024 [1]. Die Daten gehen an eine zentrale Plattform [2].\n\nGAP: Kosten");
        }

        public String modelName() {
            return MODEL;
        }
    }

    private static String prose(String topic, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            out.append(topic).append(" auf der Baustelle betrifft im Jahr 2024 genau ").append(n * 10 + i)
                    .append(" Prozent der Beschäftigten, wie die Erhebung der Berufsgenossenschaft zeigt. ");
        }
        return out.toString().trim();
    }

    private static Corpus corpus() {
        Corpus corpus = new Corpus();
        String[] topics = {"Sensorik", "Herzfrequenz", "Datenschutz", "Sturzerkennung", "Hitzestress",
                "Ortung", "Ermüdung", "Lärm"};
        int n = 0;
        for (String topic : topics) {
            for (int source = 0; source < 4; source++) {
                n++;
                corpus.add("p" + n, "src-" + (n % 9), prose(topic, n), 0.95 - n * 0.005);
            }
        }
        return corpus;
    }

    private static OutlineChapter chapter() {
        List<OutlineSection> sections = new ArrayList<OutlineSection>();
        String[][] specs = {{"s1", "Sensorik am Körper", "Sensorik;Herzfrequenz;Sturzerkennung"},
                {"s2", "Belastung messen", "Hitzestress;Ermüdung;Lärm"},
                {"s3", "Daten schützen", "Datenschutz;Ortung"}};
        for (String[] spec : specs) {
            sections.add(new OutlineSection(spec[0], "c1", sections.size() + 1, spec[1],
                    new OutlineBrief("Wie " + spec[1] + " auf Baustellen funktioniert",
                            Arrays.asList(spec[2].split(";")), 1100), OutlineProvenance.NONE, false, ""));
        }
        return new OutlineChapter("c1", 1, "Wearables auf Baustellen",
                new OutlineBrief("Das Kapitel zeigt, was Wearables auf Baustellen messen.",
                        new ArrayList<String>(), 0), OutlineProvenance.NONE, false, "", sections);
    }

    private static ProductiveSectionContexts contexts(FileManuscriptStore manuscript, Map<String, String> sources) {
        return new ProductiveSectionContexts("p", FP, null, () -> sources, manuscript, () -> "de");
    }

    @Test
    public void theAbSendsTheWriterWhatTheAppSends() throws Exception {
        Corpus corpus = corpus();
        Map<String, String> sources = new LinkedHashMap<String, String>();
        for (PassageSearchHit hit : corpus.byId.values()) {
            sources.put(hit.getSourceId(), "sum-" + hit.getSourceId());
        }
        OutlineChapter chapter = chapter();
        SectionContextBuilder packs = new SectionContextBuilder(new Embeddings(), corpus, corpus);
        // the app's defaults, with the top-up and the cap of 40 words per passage
        EvidenceAssignments.Targets targets = new WriterAbVariant(WriterAbVariant.APP,
                Collections.<String, String>emptyMap()).targets(0);
        assertTrue(targets.getTopUp().getMaxCalls() > 0);
        assertEquals(40, targets.getWordsPerPassage());

        // the export: every section's pack as the app's builder makes it, through the JSON file
        FileManuscriptStore exportStore = new FileManuscriptStore(Files.createTempDirectory("export").toFile());
        List<WriterAbInput.Section> exported = new ArrayList<WriterAbInput.Section>();
        for (OutlineSection section : chapter.getSections()) {
            exported.add(WriterAbExport.section(packs, contexts(exportStore, sources), chapter, section, targets));
        }
        WriterAbInput input = WriterAbInput.parse(WriterAbInput.of("de", chapter.getId(), chapter.getTitle(),
                chapter.getBrief().getPurpose(), exported, "test").toJson());
        assertTrue("a pack of several parts holds more than one call's passages",
                input.sections.get(0).passages.size() > targets.getPack().getPassageCount());

        // the same manuscript state on both sides: section 1 written, section 2 next
        File appDir = Files.createTempDirectory("app").toFile();
        File abDir = Files.createTempDirectory("ab").toFile();
        String written = "Wearables erfassen Herzfrequenz und Temperatur [1]. Sie melden Stürze an die Leitstelle "
                + "[2].\n\nDie Messung läuft während der ganzen Schicht [3].";
        for (File dir : Arrays.asList(appDir, abDir)) {
            new FileManuscriptStore(dir).save("s1", "c1", 0, SectionRevision.builder().text(written), 1L);
        }
        FileManuscriptStore appStore = new FileManuscriptStore(appDir);
        FileManuscriptStore abStore = new FileManuscriptStore(abDir);
        EvidenceAssignments app = new EvidenceAssignments(packs, contexts(appStore, sources), () -> targets,
                () -> MODEL);
        WriterAbPipeline ab = new WriterAbPipeline(input, targets, MODEL);

        for (OutlineSection section : chapter.getSections().subList(1, 3)) {
            SectionWriteRequest request = SectionWriteRequest.draft(chapter, section);
            SectionAssignment fromApp = app.assignmentFor(request);
            SectionAssignment fromAb = ab.assignments(abStore).assignmentFor(
                    SectionWriteRequest.draft(input.outlineChapter(), sectionOf(input, section.getId())));
            assertEquals(1, fromApp.getWrittenSections().size());
            assertFalse(section.getId().equals("s2") && fromApp.getPreviousDigest().isEmpty());
            assertEquals(prompts(fromApp), prompts(fromAb));
        }
    }

    @Test
    public void aSectionAloneHasNeitherDigestNorOtherSections() throws Exception {
        Corpus corpus = corpus();
        Map<String, String> sources = new LinkedHashMap<String, String>();
        for (PassageSearchHit hit : corpus.byId.values()) {
            sources.put(hit.getSourceId(), "sum");
        }
        OutlineChapter chapter = chapter();
        EvidenceAssignments.Targets targets = new WriterAbVariant("x", Collections.<String, String>emptyMap())
                .targets(0);
        SectionContextBuilder packs = new SectionContextBuilder(new Embeddings(), corpus, corpus);
        FileManuscriptStore store = new FileManuscriptStore(Files.createTempDirectory("e").toFile());
        List<WriterAbInput.Section> exported = new ArrayList<WriterAbInput.Section>();
        for (OutlineSection section : chapter.getSections()) {
            exported.add(WriterAbExport.section(packs, contexts(store, sources), chapter, section, targets));
        }
        WriterAbInput input = WriterAbInput.of("de", "c1", "K", "Zweck", exported, "");
        SectionAssignment alone = new WriterAbPipeline(input, targets, MODEL).alone("s2",
                new FileManuscriptStore(Files.createTempDirectory("a").toFile()));
        assertTrue(alone.getPreviousDigest().isEmpty());
        assertTrue(alone.getWrittenSections().isEmpty());
        assertEquals("de", alone.getBookLanguage());
        assertEquals("Zweck", alone.getSynopsis());
        assertEquals(450, alone.getWordsPerCall());
        assertEquals(targets.getPack().getPassageCount(), alone.getPassagesPerCall());
    }

    private static OutlineSection sectionOf(WriterAbInput input, String id) {
        for (OutlineSection section : input.outlineChapter().getSections()) {
            if (section.getId().equals(id)) {
                return section;
            }
        }
        throw new AssertionError(id);
    }

    /** Every request the writer sends for an assignment, as text. */
    private static List<String> prompts(SectionAssignment assignment) {
        RecordingChat chat = new RecordingChat();
        new SectionWriter(chat, null).write(assignment);
        assertTrue(chat.requests.size() > 1);
        return chat.requests;
    }
}
