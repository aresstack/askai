package com.aresstack.askai.research.acceptance;

import com.aresstack.askai.research.book.BookManifest;
import com.aresstack.askai.research.book.ConceptPin;
import com.aresstack.askai.research.book.ManuscriptPin;
import com.aresstack.askai.research.book.OutlinePin;
import com.aresstack.askai.research.book.SourcesPin;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.store.FileConceptStore;
import com.aresstack.askai.research.store.FileManuscriptStore;
import com.aresstack.askai.research.store.FileOutlineStore;
import com.aresstack.askai.research.store.FileResearchSourceRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan PR 32: the data-integrity level of the first-book acceptance run. */
public class BookIntegrityCheckTest {

    private static final String SOURCE_TEXT = "Die Wärmepumpe nutzt „Umweltwärme“ aus Luft,\nErde oder Wasser. "
            + "Retrieval-Augmented Generation (RAG) ist ein Verfahren.";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final BookManifest.CommitCheck ALL = new BookManifest.CommitCheck() {
        public boolean isCommitted(String transitionId) {
            return true;
        }
    };

    private File dir;
    private BookManifest manifest;
    private FileConceptStore concepts;
    private FileOutlineStore outlines;
    private FileManuscriptStore manuscript;
    private FileResearchSourceRepository sources;

    private void project(String sectionText, ManuscriptCitation... citations) throws Exception {
        dir = folder.newFolder("book");
        manifest = new BookManifest(dir);
        concepts = new FileConceptStore(new File(dir, "concept"));
        outlines = new FileOutlineStore(new File(dir, "outline"));
        manuscript = new FileManuscriptStore(new File(dir, "manuscript"));
        sources = new FileResearchSourceRepository(new File(dir, "sources"));

        concepts.commitWorking("{\"title\":\"Wärmepumpen – Grundlagen\",\"subtitle\":\"\",\"concept\":[]}", 1L);
        FileConceptStore.Approval concept = concepts.approveCurrent(2L);
        manifest.appendConceptPin(new ConceptPin("t-concept", concept.getRevisionNumber(), true,
                FileConceptStore.sha256(concepts.approvedContent(concept.getRevisionNumber())), 1L, 2L,
                "Wärmepumpen", "", "de", Collections.<String>emptyList()));

        sources.put(ResearchSourceRecord.builder("src-1").title("Quelle").url("https://example.org/1")
                .fullText(SOURCE_TEXT).checksum(CaptureStore.sha256(SOURCE_TEXT)).status(SourceStatus.ACCEPTED)
                .revision(1L).build());
        manifest.appendSourcesPin(new SourcesPin("t-sources", 3L, Collections.singletonList(
                new SourcesPin.Entry("src-1", CaptureStore.sha256(SOURCE_TEXT), "ACCEPTED"))));

        OutlineSection section = new OutlineSection("", "", 1, "Grundlagen",
                new OutlineBrief("on it", Collections.<String>emptyList(), 200), OutlineProvenance.NONE, false, "");
        FileOutlineStore.Commit committed = outlines.commit(new Outline(Collections.singletonList(new OutlineChapter("", 1, "Einführung",
                OutlineBrief.EMPTY, OutlineProvenance.NONE, false, "", Arrays.asList(section)))), 0L, 4L);
        OutlineChapter chapter = committed.outline.getChapters().get(0);
        String sectionId = chapter.getSections().get(0).getId();
        FileOutlineStore.Approval outline = outlines.approveCurrent(5L);
        manifest.appendOutlinePin(new OutlinePin("t-outline", outline.revisionNumber, true, outline.contentHash,
                outline.workingRevision, 5L));

        FileManuscriptStore.Write write = manuscript.save(sectionId, chapter.getId(), 0, SectionRevision.builder()
                .text(sectionText).citations(Arrays.asList(citations)), 6L);
        manifest.appendManuscriptPin(new ManuscriptPin("t-final", outline.revisionNumber, 7L,
                Collections.singletonList(new ManuscriptPin.Entry(sectionId, write.section.getStatus().name(),
                        write.revision.getNumber(), write.revision.textHash(), "llama3.1:8b"))));
    }

    private BookIntegrityReport check() throws Exception {
        return new BookIntegrityCheck(new BookManifest(dir), ALL, concepts, outlines, manuscript, sources).run();
    }

    private static ManuscriptCitation citation(String marker, String source, String checksum, String text) {
        return new ManuscriptCitation(marker, source, checksum, text, "p-1", null);
    }

    private static ManuscriptCitation good() {
        return citation("1", "src-1", CaptureStore.sha256(SOURCE_TEXT),
                "„Umweltwärme“ aus Luft, Erde oder Wasser.");
    }

    @Test
    public void anIntactBookPassesEveryCheck() throws Exception {
        project("Eine Wärmepumpe nutzt „Umweltwärme“ [1].", good());

        BookIntegrityReport report = check();

        assertTrue(report.findings().toString(), report.passed());
        assertEquals("concept, outline, source and section pins", 4,
                report.examined(IntegrityFinding.Check.PIN_HASH));
        assertEquals(1, report.examined(IntegrityFinding.Check.CITATION_SOURCE));
        assertEquals(1, report.examined(IntegrityFinding.Check.CITATION_TEXT));
        assertEquals(1, report.examined(IntegrityFinding.Check.CITATION_MARKER));
        assertTrue(report.markdown().contains("Result: **met**"));
    }

    @Test
    public void aChangedApprovedOutlineBreaksItsPin() throws Exception {
        project("Text [1].", good());
        File revision = new File(new File(new File(dir, "outline"), "revisions"), "0001.json");
        String json = new String(Files.readAllBytes(revision.toPath()), Charset.forName("UTF-8"));
        Files.write(revision.toPath(), json.replace("Einführung", "Einleitung").getBytes(Charset.forName("UTF-8")));

        BookIntegrityReport report = check();

        assertEquals(1, report.findings(IntegrityFinding.Check.PIN_HASH).size());
        assertEquals("outline r1", report.findings(IntegrityFinding.Check.PIN_HASH).get(0).getSubject());
        assertTrue(report.markdown().contains("Result: **not met**"));
    }

    @Test
    public void citationsMustResolveToSourceChecksumAndText() throws Exception {
        project("Erstens [1], zweitens [2], drittens [3; 4].", good(),
                citation("2", "src-1", "other-checksum", "Erde oder Wasser"),
                citation("3", "src-9", "x", "egal"),
                citation("4", "src-1", CaptureStore.sha256(SOURCE_TEXT), "Geothermie ist billig."));

        BookIntegrityReport report = check();

        assertEquals(2, report.findings(IntegrityFinding.Check.CITATION_SOURCE).size());
        assertEquals(1, report.findings(IntegrityFinding.Check.CITATION_TEXT).size());
        assertTrue(report.findings(IntegrityFinding.Check.CITATION_TEXT).get(0).getSubject().endsWith("[4]"));
        assertTrue(report.findings(IntegrityFinding.Check.CITATION_MARKER).isEmpty());
    }

    @Test
    public void aMarkerWithoutCitationIsAMiss() throws Exception {
        project("Belegt [1] und unbelegt [2].", good());

        BookIntegrityReport report = check();

        assertEquals(1, report.findings(IntegrityFinding.Check.CITATION_MARKER).size());
        assertTrue(report.findings(IntegrityFinding.Check.CITATION_MARKER).get(0).getMessage().contains("[2]"));
    }

    @Test
    public void damagedUmlautsAreFound() throws Exception {
        project("Die WÃ¤rmepumpe nutzt â€žUmweltwÃ¤rmeâ€œ [1].", good());

        BookIntegrityReport report = check();

        assertEquals(1, report.findings(IntegrityFinding.Check.TEXT_ENCODING).size());
        assertFalse(BookIntegrityCheck.damagedEncoding("Grüße „Zitat“ – ß 🙂 São Paulo ÃO"));
        assertTrue(BookIntegrityCheck.damagedEncoding("Fehler �"));
    }

    @Test
    public void uncommittedPinsAreNotChecked() throws Exception {
        project("Text [1].", good());
        BookIntegrityReport report = new BookIntegrityCheck(new BookManifest(dir),
                new BookManifest.CommitCheck() {
                    public boolean isCommitted(String transitionId) {
                        return false;
                    }
                }, concepts, outlines, manuscript, sources).run();
        assertEquals(0, report.examined(IntegrityFinding.Check.PIN_HASH));
        assertFalse("a project without committed pins never passes", report.passed());
    }

    @Test
    public void anEmptyDirectoryNeverPasses() throws Exception {
        BookIntegrityReport report = BookIntegrityCheck.forProject(folder.newFolder("empty")).run();
        assertFalse(report.passed());
        assertEquals("project", report.findings().get(0).getSubject());
    }
}
