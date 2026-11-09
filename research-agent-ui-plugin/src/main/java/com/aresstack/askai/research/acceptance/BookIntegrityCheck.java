package com.aresstack.askai.research.acceptance;

import com.aresstack.askai.research.book.BookManifest;
import com.aresstack.askai.research.book.ConceptPin;
import com.aresstack.askai.research.book.ManuscriptPin;
import com.aresstack.askai.research.book.OutlinePin;
import com.aresstack.askai.research.book.SourcesPin;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.store.FileConceptStore;
import com.aresstack.askai.research.store.FileManuscriptStore;
import com.aresstack.askai.research.store.FileOutlineStore;
import com.aresstack.askai.research.store.FileResearchSourceRepository;
import com.aresstack.askai.research.transition.TransitionJournal;
import com.google.gson.JsonObject;

import java.io.File;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan PR 32, level "data integrity" of the first-book acceptance run: reads a book project after its restarts and
 * checks that
 * <ul>
 * <li>every committed concept, outline and sources pin and the committed manuscript pin match the content they
 * pinned (recomputed from the stored text, not from the recorded hash);</li>
 * <li>every citation of a current section revision resolves to a known source with the checksum the writer saw,
 * and its stored text is found in that source's write-once text;</li>
 * <li>every {@code [n]} marker in a section text resolves to a citation of that revision;</li>
 * <li>no stored text carries a replacement character or UTF-8 read as Latin-1 (umlauts and typographic quotes
 * intact).</li>
 * </ul>
 * The check only reads. It never repairs a project (broken projects stay as they are).
 */
public final class BookIntegrityCheck {

    /** The marker grammar of the section writer ({@code CitationValidator}): {@code [1]}, {@code [1, 2]}. */
    static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\]");

    private static final Pattern REFERENCE_ENTRY = Pattern.compile("(?m)^\\[(\\d{1,3})\\] ");

    /** UTF-8 bytes decoded as Latin-1/CP-1252: "Ã¤" for ä, "â€ž" for „, and the replacement character. */
    private static final Pattern MOJIBAKE = Pattern.compile("�|Ã[\u0080-¿¤¶¼Ÿ„–—]|â€");

    private final BookManifest manifest;
    private final BookManifest.CommitCheck commits;
    private final FileConceptStore concepts;
    private final FileOutlineStore outlines;
    private final FileManuscriptStore manuscript;
    private final ResearchSourceRepository sources;
    private final String document;

    public BookIntegrityCheck(BookManifest manifest, BookManifest.CommitCheck commits, FileConceptStore concepts,
                              FileOutlineStore outlines, FileManuscriptStore manuscript,
                              ResearchSourceRepository sources) {
        this.manifest = manifest;
        this.commits = commits;
        this.concepts = concepts;
        this.outlines = outlines;
        this.manuscript = manuscript;
        this.sources = sources;
        this.document = null;
    }

    private BookIntegrityCheck(BookIntegrityCheck base, String document) {
        this.manifest = base.manifest;
        this.commits = base.commits;
        this.concepts = base.concepts;
        this.outlines = base.outlines;
        this.manuscript = base.manuscript;
        this.sources = base.sources;
        this.document = document;
    }

    /** The same check that also resolves every {@code [n]} of the generated {@code document.md} (plan PR 30). */
    public BookIntegrityCheck withDocument(String documentMarkdown) {
        return new BookIntegrityCheck(this, documentMarkdown);
    }

    /**
     * The check over a book project directory as the app lays it out ({@code concept}, {@code outline},
     * {@code manuscript}, {@code sources}, {@code book.json}); a pin counts when its transition is committed in
     * {@code state/transitions.jsonl}.
     */
    public static BookIntegrityCheck forProject(File projectDirectory) throws Exception {
        final Set<String> committed = new HashSet<String>();
        File journal = new File(new File(projectDirectory, "state"), TransitionJournal.FILE_NAME);
        if (journal.isFile()) {
            for (JsonObject record : new TransitionJournal(journal).read()) {
                if (record.has("kind") && "committed".equals(record.get("kind").getAsString())
                        && record.has("id")) {
                    committed.add(record.get("id").getAsString());
                }
            }
        }
        BookIntegrityCheck check = new BookIntegrityCheck(new BookManifest(projectDirectory), new BookManifest.CommitCheck() {
            public boolean isCommitted(String transitionId) {
                return committed.contains(transitionId);
            }
        }, new FileConceptStore(new File(projectDirectory, "concept")),
                new FileOutlineStore(new File(projectDirectory, "outline")),
                new FileManuscriptStore(new File(projectDirectory, "manuscript")),
                new FileResearchSourceRepository(new File(projectDirectory, "sources")));
        File document = new File(new File(projectDirectory, "artifacts"), "document.md");
        if (!document.isFile()) {
            return check;
        }
        return check.withDocument(new String(java.nio.file.Files.readAllBytes(document.toPath()),
                java.nio.charset.Charset.forName("UTF-8")));
    }

    public BookIntegrityReport run() throws Exception {
        Run run = new Run();
        checkConceptPins(run);
        checkOutlinePins(run);
        checkSourcesPins(run);
        checkManuscriptPin(run);
        checkSections(run);
        if (document != null) {
            checkDocument(run, document);
        }
        Integer pins = run.examined.get(IntegrityFinding.Check.PIN_HASH);
        if (pins == null || pins == 0) {
            run.miss(IntegrityFinding.Check.PIN_HASH, "project",
                    "no committed pin found: wrong project path, missing book.json or lost transition journal");
        }
        return new BookIntegrityReport(run.examined, run.findings);
    }

    private void checkConceptPins(Run run) throws Exception {
        for (ConceptPin pin : manifest.conceptPins()) {
            if (!commits.isCommitted(pin.getTransitionId())) {
                continue;
            }
            String subject = "concept r" + pin.getApprovedRevision();
            String content = concepts.approvedContent(pin.getApprovedRevision());
            run.examine(IntegrityFinding.Check.PIN_HASH);
            if (content == null) {
                run.miss(IntegrityFinding.Check.PIN_HASH, subject, "the pinned revision is missing");
                continue;
            }
            if (!pin.getContentHash().equals(FileConceptStore.sha256(content))) {
                run.miss(IntegrityFinding.Check.PIN_HASH, subject, "the stored text differs from the pinned hash");
            }
            run.encoding(subject, content);
        }
    }

    private void checkOutlinePins(Run run) throws Exception {
        for (OutlinePin pin : manifest.outlinePins()) {
            if (!commits.isCommitted(pin.getTransitionId())) {
                continue;
            }
            String subject = "outline r" + pin.getApprovedRevision();
            String content = outlines.approvedContent(pin.getApprovedRevision());
            run.examine(IntegrityFinding.Check.PIN_HASH);
            if (content == null) {
                run.miss(IntegrityFinding.Check.PIN_HASH, subject, "the pinned revision is missing");
                continue;
            }
            if (!pin.getContentHash().equals(FileOutlineStore.sha256(content))) {
                run.miss(IntegrityFinding.Check.PIN_HASH, subject, "the stored text differs from the pinned hash");
            }
            run.encoding(subject, content);
        }
    }

    private void checkSourcesPins(Run run) throws Exception {
        for (SourcesPin pin : manifest.sourcesPins()) {
            if (!commits.isCommitted(pin.getTransitionId())) {
                continue;
            }
            for (SourcesPin.Entry entry : pin.getSources()) {
                String subject = "source " + entry.getSourceId();
                run.examine(IntegrityFinding.Check.PIN_HASH);
                ResearchSourceRecord record = sources.get(entry.getSourceId());
                if (record == null) {
                    run.miss(IntegrityFinding.Check.PIN_HASH, subject, "the pinned source is missing");
                    continue;
                }
                if (!entry.getChecksum().equals(record.getChecksum())) {
                    run.miss(IntegrityFinding.Check.PIN_HASH, subject,
                            "the source checksum changed after the sources approval");
                } else if (!entry.getChecksum().isEmpty()
                        && !entry.getChecksum().equals(CaptureStore.sha256(record.getFullText()))) {
                    run.miss(IntegrityFinding.Check.PIN_HASH, subject,
                            "the stored text differs from its checksum");
                }
            }
        }
    }

    private void checkManuscriptPin(Run run) throws Exception {
        ManuscriptPin pin = manifest.validManuscriptPin(commits);
        if (pin == null) {
            return;
        }
        for (ManuscriptPin.Entry entry : pin.getSections()) {
            if (entry.getRevision() <= 0) {
                continue;
            }
            String subject = "section " + entry.getSectionId() + " r" + entry.getRevision();
            run.examine(IntegrityFinding.Check.PIN_HASH);
            SectionRevision revision = manuscript.revision(entry.getSectionId(), entry.getRevision());
            if (revision == null) {
                run.miss(IntegrityFinding.Check.PIN_HASH, subject, "the pinned revision is missing");
            } else if (!entry.getTextHash().equals(revision.textHash())) {
                run.miss(IntegrityFinding.Check.PIN_HASH, subject, "the stored text differs from the pinned hash");
            }
        }
    }

    private void checkSections(Run run) {
        for (ManuscriptSection section : manuscript.sections()) {
            if (!section.hasText()) {
                continue;
            }
            SectionRevision revision = manuscript.revision(section.getSectionId(), section.getRevision());
            if (revision == null) {
                continue;
            }
            String subject = "section " + section.getSectionId() + " r" + revision.getNumber();
            run.encoding(subject, revision.getText());
            Set<String> markers = new HashSet<String>();
            for (ManuscriptCitation citation : revision.getCitations()) {
                markers.add(citation.getMarker());
                checkCitation(run, subject + " [" + citation.getMarker() + "]", citation);
            }
            Matcher matcher = MARKER.matcher(revision.getText());
            while (matcher.find()) {
                for (String number : matcher.group(1).split("\\s*[,;]\\s*")) {
                    run.examine(IntegrityFinding.Check.CITATION_MARKER);
                    if (!markers.contains(number.trim())) {
                        run.miss(IntegrityFinding.Check.CITATION_MARKER, subject,
                                "[" + number.trim() + "] has no citation");
                    }
                }
            }
        }
    }

    /**
     * The assembled book: every {@code [n]} before "## References" names an entry of that list, and the assembler
     * left no {@code [?]} for an unresolved citation.
     */
    static void checkDocument(Run run, String markdown) {
        int split = markdown.lastIndexOf("\n## References");
        String body = split < 0 ? markdown : markdown.substring(0, split);
        Set<String> references = new HashSet<String>();
        if (split >= 0) {
            Matcher entry = REFERENCE_ENTRY.matcher(markdown.substring(split));
            while (entry.find()) {
                references.add(entry.group(1));
            }
        }
        run.encoding("document.md", markdown);
        Matcher matcher = MARKER.matcher(body);
        while (matcher.find()) {
            for (String number : matcher.group(1).split("\\s*[,;]\\s*")) {
                run.examine(IntegrityFinding.Check.REFERENCE);
                if (!references.contains(number.trim())) {
                    run.miss(IntegrityFinding.Check.REFERENCE, "document.md",
                            "[" + number.trim() + "] has no reference entry");
                }
            }
        }
        int unresolved = body.split("\\[\\?\\]", -1).length - 1;
        for (int i = 0; i < unresolved; i++) {
            run.examine(IntegrityFinding.Check.REFERENCE);
            run.miss(IntegrityFinding.Check.REFERENCE, "document.md", "[?] marks a citation that did not resolve");
        }
    }

    private void checkCitation(Run run, String subject, ManuscriptCitation citation) {
        run.examine(IntegrityFinding.Check.CITATION_SOURCE);
        ResearchSourceRecord record = sources.get(citation.getSourceId());
        if (record == null) {
            run.miss(IntegrityFinding.Check.CITATION_SOURCE, subject,
                    "source " + citation.getSourceId() + " is unknown");
            return;
        }
        if (!citation.getSourceChecksum().equals(record.getChecksum())) {
            run.miss(IntegrityFinding.Check.CITATION_SOURCE, subject,
                    "source " + citation.getSourceId() + " has another checksum than the writer saw");
            return;
        }
        run.examine(IntegrityFinding.Check.CITATION_TEXT);
        if (!normalize(record.getFullText()).contains(normalize(citation.getCitedText()))) {
            run.miss(IntegrityFinding.Check.CITATION_TEXT, subject,
                    "the cited text is not found in source " + citation.getSourceId());
        }
        run.encoding(subject, citation.getCitedText());
    }

    /** Whitespace runs collapse to one space, so a passage cut across a line break still matches its source. */
    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** {@code true} when {@code text} shows a replacement character or UTF-8 decoded as Latin-1. */
    static boolean damagedEncoding(String text) {
        return text != null && MOJIBAKE.matcher(text).find();
    }

    static final class Run {
        final Map<IntegrityFinding.Check, Integer> examined =
                new EnumMap<IntegrityFinding.Check, Integer>(IntegrityFinding.Check.class);
        final List<IntegrityFinding> findings = new ArrayList<IntegrityFinding>();

        void examine(IntegrityFinding.Check check) {
            Integer count = examined.get(check);
            examined.put(check, count == null ? 1 : count + 1);
        }

        void miss(IntegrityFinding.Check check, String subject, String message) {
            findings.add(new IntegrityFinding(check, subject, message));
        }

        void encoding(String subject, String text) {
            examine(IntegrityFinding.Check.TEXT_ENCODING);
            if (damagedEncoding(text)) {
                miss(IntegrityFinding.Check.TEXT_ENCODING, subject, "the text shows damaged umlauts or quotes");
            }
        }
    }
}
