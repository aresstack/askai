package com.aresstack.askai.research.assembly;

import com.aresstack.askai.research.assembly.BookQualityReport.Kind;
import com.aresstack.askai.research.knowledge.processing.acronym.AcronymLongForms;
import com.aresstack.askai.research.knowledge.processing.acronym.SourceAcronymRecord;
import com.aresstack.askai.research.knowledge.processing.acronym.TextSpan;
import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Plan PR 30: the deterministic book assembler, its citation registry and its quality report. */
public class BookAssemblerTest {

    private static final long DAY = 1793836800000L; // 2026-11-05 UTC

    private static final String TEXT_A = "Solar panels convert sunlight into electricity. Their efficiency depends on "
            + "the cell material and the temperature of the module.";
    private static final String TEXT_B = "Wind turbines turn the kinetic energy of moving air into electrical power. "
            + "Larger rotors capture more energy at lower wind speeds.";
    private static final String TEXT_C = "Battery storage shifts surplus energy from sunny hours into the evening. "
            + "Lithium cells dominate new installations today.";

    private static OutlineSection section(String id, String parent, int order, String title, int target) {
        return new OutlineSection(id, parent, order, title, new OutlineBrief("on " + title,
                Collections.<String>emptyList(), target), OutlineProvenance.NONE, false, "");
    }

    private static OutlineChapter chapter(String id, int order, String title, OutlineSection... sections) {
        return new OutlineChapter(id, order, title, new OutlineBrief("why " + title, Arrays.asList("k"), 0),
                OutlineProvenance.NONE, false, "", Arrays.asList(sections));
    }

    private static Outline outline() {
        return new Outline(Arrays.asList(
                chapter("c1", 1, "Generation",
                        section("s11", "c1", 1, "Solar power", 12),
                        section("s12", "c1", 2, "Wind power", 12)),
                chapter("c2", 2, "Storage",
                        section("s21", "c2", 1, "Batteries", 12),
                        section("s22", "c2", 2, "Outlook", 12))));
    }

    private static AssemblySource source(String id, String title, String text, boolean late) {
        return new AssemblySource(id, title, "example.org", "https://example.org/" + id, DAY, "sha-" + id, text, late);
    }

    private static ManuscriptCitation cite(String marker, String sourceId, String citedText, String passageId) {
        return new ManuscriptCitation(marker, sourceId, "sha-" + sourceId, citedText, passageId, null);
    }

    private static SectionRevision revision(String text, List<String> gaps, List<String> flags,
                                            ManuscriptCitation... citations) {
        return SectionRevision.builder().text(text).citations(Arrays.asList(citations)).gaps(gaps)
                .claimFlags(flags).modelLabel("gemma4:e2b").author(SectionRevision.AUTHOR_WRITER).build(1, DAY);
    }

    private static SectionRevision revision(String text, ManuscriptCitation... citations) {
        return revision(text, Collections.<String>emptyList(), Collections.<String>emptyList(), citations);
    }

    private static ManuscriptSection written(String id, String chapter) {
        return new ManuscriptSection(id, chapter, SectionStatus.WRITTEN, "", 1, DAY);
    }

    /** Two chapters with two sections each, every sentence cited, nothing to report. */
    private static BookAssemblyInput.Builder clean() {
        return BookAssemblyInput.builder().title("Renewable Energy").assembledAt(DAY, TimeZone.getTimeZone("UTC"))
                .conceptRevision(3).outline(outline(), 2)
                .source(source("a", "Solar basics", TEXT_A, false))
                .source(source("b", "Wind basics", TEXT_B, false))
                .source(source("c", "Storage basics", TEXT_C, false))
                .glossaryTerms(Arrays.asList("energy"))
                .section(written("s11", "c1"), revision(
                        "Solar panels convert sunlight into electricity [1]. Hot modules lose efficiency [1].",
                        cite("1", "a", TEXT_A, "p-a-1")))
                .section(written("s12", "c1"), revision(
                        "Turbines turn moving air into power [1]. Solar panels convert sunlight beside them [2].",
                        cite("1", "b", TEXT_B, "p-b-1"),
                        cite("2", "a", TEXT_A, "p-a-1")))
                .section(written("s21", "c2"), revision(
                        "Battery storage shifts surplus energy into the evening [1].",
                        cite("1", "c", TEXT_C, "p-c-1")))
                .section(written("s22", "c2"), revision(
                        "Lithium cells dominate and larger rotors capture more energy [1, 2].",
                        cite("1", "c", TEXT_C, "p-c-2"),
                        cite("2", "b", TEXT_B, "p-b-2")));
    }

    private static final String GOLDEN = "# Renewable Energy\n"
            + "\n"
            + "- Writer models: gemma4:e2b\n"
            + "- Assembled: 2026-11-05\n"
            + "- Sources: 3\n"
            + "- Approved concept revision: 3\n"
            + "- Approved outline revision: 2\n"
            + "- Book status: complete\n"
            + "- Support checked for numbers and quotes, and that every cited passage is prose sharing the "
            + "sentence's content words; meaning is not checked.\n"
            + "\n"
            + "## Contents\n"
            + "\n"
            + "- 1 Generation\n"
            + "  - 1.1 Solar power\n"
            + "  - 1.2 Wind power\n"
            + "- 2 Storage\n"
            + "  - 2.1 Batteries\n"
            + "  - 2.2 Outlook\n"
            + "\n"
            + "## 1 Generation\n"
            + "\n"
            + "### 1.1 Solar power\n"
            + "\n"
            + "Solar panels convert sunlight into electricity [1]. Hot modules lose efficiency [1].\n"
            + "\n"
            + "### 1.2 Wind power\n"
            + "\n"
            + "Turbines turn moving air into power [2]. Solar panels convert sunlight beside them [1].\n"
            + "\n"
            + "## 2 Storage\n"
            + "\n"
            + "### 2.1 Batteries\n"
            + "\n"
            + "Battery storage shifts surplus energy into the evening [3].\n"
            + "\n"
            + "### 2.2 Outlook\n"
            + "\n"
            + "Lithium cells dominate and larger rotors capture more energy [3][2].\n"
            + "\n"
            + "## References\n"
            + "\n"
            + "[1] Solar basics. example.org. <https://example.org/a>. Accessed 2026-11-05.\n"
            + "[2] Wind basics. example.org. <https://example.org/b>. Accessed 2026-11-05.\n"
            + "[3] Storage basics. example.org. <https://example.org/c>. Accessed 2026-11-05.\n";

    @Test
    public void goldenTwoChaptersTwoSectionsRenderExactlyAndComplete() {
        AssembledBook book = new BookAssembler().assemble(clean().build());
        assertTrue(book.getReport().getFindings().toString(), book.getReport().getFindings().isEmpty());
        assertEquals(GOLDEN, book.getMarkdown());
        assertEquals(BookQualityReport.Status.COMPLETE, book.getReport().getStatus());
        assertTrue(book.getReport().getFindings().toString(), book.getReport().getFindings().isEmpty());
        assertEquals(GOLDEN, new BookAssembler().assemble(clean().build()).getMarkdown());
    }

    @Test
    public void liveRun3Section13CitingAFooterIsReportedAsNotCarried() {
        String footer = "Steigern Sie Ihr Geschäft mit Überwachung Bauwerksüberwachung in Echtzeit "
                + "Referenzkundenberichte Fangen Sie an Vertriebsteam kontaktieren Alle Produkte durchsuchen Globale "
                + "Standorte Support erhalten Starten Sie Ihre Karriere Entdecken Sie Hexagon Wer wir sind "
                + "Führungsteam Nachhaltigkeit Investor Relations Erfahren Sie mehr Im Einsatz bei Kunden Nächste "
                + "Veranstaltungen Newsroom Ethik und Compliance Rechtliches Datenschutz Nutzungsbedingungen "
                + "Impressum Problem mit der Website melden Cookie-Einstellungen © 2026 Hexagon AB und/oder seine "
                + "Tochtergesellschaften. Alle Rechte vorbehalten. Deutsch";
        // The first paragraph of section 1.3 as llama3.1:8b wrote it in live run 3 (verbatim, marker [6]).
        String written = "Die rechtlichen Rahmenbedingungen für die Erfassung von Mitarbeiterdaten durch Wearables "
                + "auf Baustellen sind eng mit dem Datenschutz und den Rechten der Arbeitnehmer verbunden [6]. Die "
                + "Verarbeitung von Daten, die durch Wearables erfasst werden, unterliegt den Vorschriften der "
                + "Datenschutz-Grundverordnung (DSGVO) und des Bundesdatenschutzgesetzes (BDSG) [6]. Diese Gesetze "
                + "legen fest, wie personenbezogene Daten zu erheben, zu speichern und zu verarbeiten sind [6].";
        AssembledBook book = new BookAssembler().assemble(clean()
                .source(source("h", "Bauwerksüberwachung | Hexagon", footer, false))
                .section(written("s21", "c2"), revision(written, cite("6", "h", footer, "cap-12#p101")))
                .build());
        assertEquals(3, book.getReport().count(Kind.UNCARRIED_CLAIM));
        assertTrue(book.getReport().findings(Kind.UNCARRIED_CLAIM).get(0).getDetail(),
                book.getReport().findings(Kind.UNCARRIED_CLAIM).get(0).getDetail().contains("[6] is no prose"));
        assertEquals(BookQualityReport.Status.INCOMPLETE_DRAFT, book.getReport().getStatus());
    }

    @Test
    public void everyNumberHasExactlyOneReferenceAndNoReferenceIsUnused() {
        AssembledBook book = new BookAssembler().assemble(clean().build());
        Set<Integer> used = usedNumbers(book.getMarkdown());
        int references = book.getCitations().references().size();
        assertEquals(references, used.size());
        for (int n = 1; n <= references; n++) {
            assertTrue(used.contains(n));
            assertNotNull(book.getCitations().sourceOf(n));
        }
    }

    @Test
    public void rewriteWithSameSourcesInSameOrderKeepsNumbersOtherRewriteRenumbersDeterministically() {
        BookAssembler assembler = new BookAssembler();
        AssembledBook before = assembler.assemble(clean().build());
        AssembledBook same = assembler.assemble(clean().section(written("s11", "c1"), revision(
                "Modules generate current from light [1].",
                cite("1", "a", "Their efficiency depends on the cell material", "p-a-2"))).build());
        for (String id : Arrays.asList("a", "b", "c")) {
            assertEquals(before.getCitations().numberOf(id), same.getCitations().numberOf(id));
        }
        AssembledBook other = assembler.assemble(clean().section(written("s11", "c1"), revision(
                "Storage matters for solar [1].",
                cite("1", "c", "Battery storage shifts surplus energy", "p-c-1"))).build());
        assertEquals(1, other.getCitations().numberOf("c"));
        assertEquals(2, other.getCitations().numberOf("b"));
        assertEquals(3, other.getCitations().numberOf("a"));
        assertEquals(other.getMarkdown(), assembler.assemble(clean().section(written("s11", "c1"), revision(
                "Storage matters for solar [1].",
                cite("1", "c", "Battery storage shifts surplus energy", "p-c-1"))).build()).getMarkdown());
        assertEquals(3, usedNumbers(other.getMarkdown()).size());
        assertTrue(other.getCitations().issues().isEmpty());
    }

    @Test
    public void findingsFixtureReportsEachAndIsAnIncompleteDraft() {
        String repeated = "Grid operators balance supply and demand every second of every day across the whole "
                + "network [1].";
        BookAssemblyInput input = clean()
                .section(new ManuscriptSection("s22", "c2", SectionStatus.SKIPPED, "", 1, DAY), null)
                .section(written("s11", "c1"), revision("Efficiency reaches 15 % in tests [1].\n\n" + repeated,
                        Collections.<String>emptyList(), Arrays.asList("unsupported number: 15 %"),
                        cite("1", "a", "Solar panels convert sunlight into electricity.", "p-a-1")))
                .section(written("s21", "c2"), revision(
                        "Battery storage shifts surplus energy from sunny hours into the evening. Lithium cells "
                                + "dominate new installations [1].\n\n" + repeated,
                        cite("1", "c", TEXT_C, "p-c-1")))
                .build();
        AssembledBook book = new BookAssembler().assemble(input);
        BookQualityReport report = book.getReport();
        assertEquals(1, report.count(Kind.SKIPPED_SECTION));
        assertEquals(1, report.count(Kind.UNSUPPORTED_NUMBER));
        assertEquals(1, report.count(Kind.NEAR_COPY));
        assertEquals(1, report.count(Kind.REPEATED_PASSAGE));
        assertEquals(BookQualityReport.Status.INCOMPLETE_DRAFT, report.getStatus());
        assertTrue(book.getMarkdown().contains("- Book status: incomplete draft"));
        assertTrue(book.getMarkdown().contains("## Open points\n\n- 2.2 Outlook: skipped."));
        assertTrue(report.warnings().contains("1 x skipped section"));
        assertTrue(report.toMarkdown().contains("15 %"));
    }

    /** Live run 4, books 2 and 3: a manufacturer's "unsere tragbaren Geräte" and "implementieren wir" stood in the book. */
    @Test
    public void aSourcesWeInTheStoredTextIsReportedAndKeepsTheBookADraft() {
        AssembledBook book = new BookAssembler().assemble(clean()
                .section(written("s21", "c2"), revision(
                        "Our battery storage shifts surplus energy into the evening [1].",
                        cite("1", "c", TEXT_C, "p-c-1")))
                .section(written("s22", "c2"), revision(
                        "The vendor says \"we lead the market\" while lithium cells dominate [1]. It adds: "
                                + "\"We test every cell [1]. Our warranty covers ten years [1].\"",
                        cite("1", "c", TEXT_C, "p-c-2")))
                .build());
        assertEquals("a quoted we is the quoted author's, also over several sentences", 1,
                book.getReport().count(Kind.FIRST_PERSON_VOICE));
        assertEquals("2.1 Batteries: Our battery storage shifts surplus energy into the evening. (Our)",
                book.getReport().findings(Kind.FIRST_PERSON_VOICE).get(0).getDetail());
        assertEquals(BookQualityReport.Status.INCOMPLETE_DRAFT, book.getReport().getStatus());
    }

    @Test
    public void gapsAndUncitedParagraphsBlockCompleteness() {
        AssembledBook book = new BookAssembler().assemble(clean()
                .section(written("s21", "c2"), revision("Storage needs no citation here.",
                        Arrays.asList("No numbers on home storage in Germany."), Collections.<String>emptyList()))
                .build());
        assertEquals(1, book.getReport().count(Kind.UNCITED_PARAGRAPH));
        assertEquals(1, book.getReport().count(Kind.UNCITED_SENTENCE));
        assertEquals(1, book.getReport().count(Kind.OPEN_GAP));
        assertFalse(book.getReport().isComplete());
        assertTrue(book.getMarkdown().contains("- 2.1 Batteries: No numbers on home storage in Germany."));
    }

    @Test
    public void sourceAcceptedAfterApprovalIsMarkedInReferences() {
        AssembledBook book = new BookAssembler().assemble(clean()
                .source(source("c", "Storage basics", TEXT_C, true)).build());
        assertTrue(book.getMarkdown().contains(
                "[3] Storage basics. example.org. <https://example.org/c>. Accessed 2026-11-05. "
                        + "Accepted after the Sources approval."));
        assertEquals(1, book.getReport().count(Kind.LATE_SOURCE));
    }

    /**
     * Live run 4, book 6: the source had a no-break space between two sentences, the passage (its sentences joined
     * by one space) a plain space, and four citations showed "[?]".
     */
    @Test
    public void aCitationResolvesWhenItsSourceHasANoBreakSpaceBetweenTwoSentences() {
        String withNoBreakSpace = TEXT_C.replace("evening. Lithium", "evening. \u00A0Lithium");
        AssembledBook book = new BookAssembler().assemble(clean()
                .source(source("c", "Storage basics", withNoBreakSpace, false)).build());
        assertTrue(book.getCitations().issues().toString(), book.getCitations().issues().isEmpty());
        assertEquals(3, book.getCitations().numberOf("c"));
        assertFalse(book.getMarkdown().contains("[?]"));
    }

    @Test
    public void citationsResolveAfterReEmbeddingAndMismatchIsReportedNotRepointed() {
        BookAssemblyInput.Builder reembedded = clean().section(written("s21", "c2"), revision(
                "Battery storage shifts surplus energy into the evening [1].",
                cite("1", "c", TEXT_C, "bge-m3:seg-v2:c#7")));
        AssembledBook book = new BookAssembler().assemble(reembedded.build());
        assertTrue(book.getCitations().issues().isEmpty());
        assertEquals(GOLDEN, book.getMarkdown());

        AssembledBook changed = new BookAssembler().assemble(clean()
                .source(new AssemblySource("c", "Storage basics", "example.org", "https://example.org/c", DAY,
                        "sha-other", TEXT_C, false)).build());
        assertEquals(2, changed.getReport().count(Kind.UNRESOLVED_CITATION));
        assertEquals(-1, changed.getCitations().numberOf("c"));
        assertTrue(changed.getMarkdown().contains("Battery storage shifts surplus energy into the evening [?]."));
        assertFalse(changed.getReport().isComplete());
    }

    @Test
    public void conflictingLongFormsShowInReportAndAppendixWhichListsOnlyUsedAcronyms() {
        String ragA = "Retrieval-Augmented Generation (RAG) combines search and writing.";
        String ragB = "Retrieval-Assisted Generation (RAG) is another name.";
        BookAssemblyInput input = clean()
                .source(source("a", "Solar basics", TEXT_A + " " + ragA, false))
                .source(source("b", "Wind basics", TEXT_B + " " + ragB, false))
                .section(written("s11", "c1"), revision(
                        "Photovoltaic modules turn light into current, and RAG helps to explain them [1].",
                        cite("1", "a", "Solar panels convert sunlight into electricity.", "p-a-1")))
                .acronyms(Arrays.asList(
                        longForms("RAG", statement("a", "Retrieval-Augmented Generation"),
                                statement("b", "Retrieval-Assisted Generation")),
                        longForms("LLM", statement("a", "Large Language Model"))))
                .build();
        AssembledBook book = new BookAssembler().assemble(input);
        assertEquals(1, book.getReport().count(Kind.ACRONYM_CONFLICT));
        assertEquals(1, book.getReport().count(Kind.ACRONYM_FIRST_USE_WITHOUT_LONG_FORM));
        String md = book.getMarkdown();
        assertTrue(md, md.contains("## Abbreviations\n\n- **RAG**: Retrieval-Assisted Generation ([2]); "
                + "Retrieval-Augmented Generation ([1]) (the sources disagree)"));
        assertFalse(md.contains("LLM"));
    }

    /**
     * Live run 4, book 6: the appendix named sources by raw id ("source-47") and page title, and listed the soft-
     * hyphenated "Bun\u00ADdes\u00ADda\u00ADten\u00ADschutz\u00ADge\u00ADsetz" as a second, conflicting long form.
     */
    @Test
    public void theAppendixNamesSourcesByNumberOnlyAndIgnoresSoftHyphens() {
        BookAssemblyInput input = clean()
                .section(written("s11", "c1"), revision(
                        "Photovoltaic modules follow the BDSG when they log data [1].",
                        cite("1", "a", "Solar panels convert sunlight into electricity.", "p-a-1")))
                .acronyms(Arrays.asList(longForms("BDSG",
                        statement("a", "Bundesdatenschutzgesetz"),
                        statement("source-47", "Bundesdatenschutzgesetz"),
                        statement("b", "Bun\u00ADdes\u00ADda\u00ADten\u00ADschutz\u00ADge\u00ADsetz"),
                        statement("source-54", "Bundesdatenschutzgesetzbuch"))))
                .source(source("source-54", "Datenschutz-Seite", TEXT_C, false))
                .build();
        AssembledBook book = new BookAssembler().assemble(input);
        String md = book.getMarkdown();
        assertTrue(md, md.contains("- **BDSG**: Bundesdatenschutzgesetz ([1], [2]); Bundesdatenschutzgesetzbuch "
                + "(the sources disagree)"));
        assertFalse(md, md.contains("source-47"));
        assertFalse(md, md.contains("Datenschutz-Seite"));
        assertFalse(md, md.contains("\u00AD"));
    }

    @Test
    public void acronymWithoutLongFormIsReported() {
        AssembledBook book = new BookAssembler().assemble(clean().section(written("s21", "c2"), revision(
                "Storage moves daytime energy into the evening, says the IEA [1].",
                cite("1", "c", "Battery storage shifts surplus energy", "p-c-1"))).build());
        assertEquals(1, book.getReport().count(Kind.ACRONYM_WITHOUT_LONG_FORM));
        assertTrue(book.getMarkdown().contains("- **IEA**: no long form in the sources"));
    }

    @Test
    public void emptyOutlineIsNeverComplete() {
        AssembledBook book = new BookAssembler().assemble(BookAssemblyInput.builder().title("Empty")
                .assembledAt(DAY, TimeZone.getTimeZone("UTC"))
                .outline(new Outline(java.util.Collections.<OutlineChapter>emptyList()), 0).build());
        assertEquals(BookQualityReport.Status.NOT_STARTED, book.getReport().getStatus());
        assertFalse(book.getReport().isComplete());
        assertEquals(1, book.getReport().count(Kind.NO_OUTLINE));
        assertTrue(book.getMarkdown().contains("not started"));
        assertFalse(book.getMarkdown().contains("Book status: complete"));
    }

    @Test
    public void outlineWithoutWrittenSectionsIsNotStarted() {
        AssembledBook book = new BookAssembler().assemble(BookAssemblyInput.builder().title("Unwritten")
                .assembledAt(DAY, TimeZone.getTimeZone("UTC")).outline(outline(), 1).build());
        assertEquals(BookQualityReport.Status.NOT_STARTED, book.getReport().getStatus());
        assertEquals(0, book.getReport().count(Kind.NO_OUTLINE));
    }

    @Test
    public void unusedGlossaryTermIsReported() {
        AssembledBook book = new BookAssembler().assemble(clean()
                .glossaryTerms(Arrays.asList("energy", "hydrogen")).build());
        assertEquals(1, book.getReport().count(Kind.UNUSED_GLOSSARY_TERM));
        assertTrue(book.getReport().isComplete());
    }

    @Test
    public void unknownMarkerStaysVisibleAndUnwrittenSectionsCountTowardTheTarget() {
        AssembledBook book = new BookAssembler().assemble(clean()
                .section(new ManuscriptSection("s22", "c2", SectionStatus.SKIPPED, "", 1, DAY), null)
                .section(written("s21", "c2"), revision("Storage moves daytime energy into the evening [1][2].",
                        cite("1", "c", "Battery storage shifts surplus energy", "p-c-1"))).build());
        assertTrue(book.getMarkdown().contains("into the evening [3][?]."));
        assertEquals(1, book.getReport().count(Kind.UNRESOLVED_CITATION));
        assertEquals(48, book.getReport().getWordsTarget());
    }

    private static AcronymLongForms.Statement statement(String sourceId, String longForm) {
        TextSpan span = new TextSpan(0, 1);
        return new AcronymLongForms.Statement(sourceId, new SourceAcronymRecord.LongForm(longForm, span, span, span));
    }

    private static AcronymLongForms longForms(String acronym, AcronymLongForms.Statement... statements) {
        return new AcronymLongForms(acronym, Arrays.asList(statements));
    }

    private static Set<Integer> usedNumbers(String markdown) {
        String body = markdown.substring(0, markdown.indexOf("## References"));
        Set<Integer> used = new HashSet<Integer>();
        Matcher m = Pattern.compile("\\[(\\d+)\\]").matcher(body);
        while (m.find()) {
            used.add(Integer.parseInt(m.group(1)));
        }
        return used;
    }

    @SuppressWarnings("unused")
    private static List<String> list(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }
}
