package com.aresstack.askai.research.assembly;

import com.aresstack.askai.research.assembly.BookQualityReport.Finding;
import com.aresstack.askai.research.assembly.BookQualityReport.Kind;
import com.aresstack.askai.research.knowledge.processing.acronym.AcronymDefinition;
import com.aresstack.askai.research.knowledge.processing.acronym.AcronymExtractor;
import com.aresstack.askai.research.knowledge.processing.acronym.AcronymFinding;
import com.aresstack.askai.research.knowledge.processing.acronym.AcronymLongForms;
import com.aresstack.askai.research.knowledge.processing.acronym.AcronymSettings;
import com.aresstack.askai.research.knowledge.processing.acronym.SentenceSegmenter;
import com.aresstack.askai.research.knowledge.processing.acronym.TextSpan;
import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.writer.ClaimCarryCheck;
import com.aresstack.askai.research.runtime.writer.VoiceCheck;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic book assembler (plan PR 30, D8): renders {@code document.md} from the approved outline and the
 * current section revisions, renumbers the local markers globally by first use through a {@link CitationRegistry},
 * lists open points, references and an "Abbreviations" appendix, and computes the {@link BookQualityReport}. A pure
 * function of its {@link BookAssemblyInput}: the same input always gives the same bytes.
 */
public final class BookAssembler {

    /** Consecutive words of a cited passage, outside quotation marks, that count as a near-copy. */
    static final int NEAR_COPY_WORDS = 12;
    /** Word-shingle size and Jaccard overlap at which two paragraphs of different sections count as repeated. */
    static final int SHINGLE_WORDS = 8;
    static final double REPEAT_OVERLAP = 0.5;

    private static final Pattern MARKER_GROUP = Pattern.compile("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)\\]");
    private static final Pattern MARKER = Pattern.compile("\\[\\d+(?:\\s*[,;]\\s*\\d+)*\\]");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[.,'’-][\\p{L}\\p{N}]+)*");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|„[^“”]*[“”]|“[^”]*”|«[^»]*»|»[^«]*«|‚[^‘’]*[‘’]");
    private static final String SUPPORT_NOTE = "Support checked for numbers and quotes, and that every cited "
            + "passage is prose sharing the sentence's content words; meaning is not checked.";

    private final SentenceSegmenter sentences;
    private final AcronymExtractor acronymExtractor;
    private final ClaimCarryCheck carry;
    private final VoiceCheck voice = new VoiceCheck();
    private final TopicDriftCheck.Criteria drift;

    public BookAssembler() {
        this(SentenceSegmenter.splitter(), ClaimCarryCheck.Criteria.defaults(), TopicDriftCheck.Criteria.defaults());
    }

    /** With the claim-check stage 2 and topic-drift thresholds of the settings. */
    public BookAssembler(ClaimCarryCheck.Criteria carry, TopicDriftCheck.Criteria drift) {
        this(SentenceSegmenter.splitter(), carry, drift);
    }

    public BookAssembler(SentenceSegmenter sentences) {
        this(sentences, ClaimCarryCheck.Criteria.defaults(), TopicDriftCheck.Criteria.defaults());
    }

    public BookAssembler(SentenceSegmenter sentences, ClaimCarryCheck.Criteria carry,
                         TopicDriftCheck.Criteria drift) {
        this.sentences = sentences;
        this.acronymExtractor = new AcronymExtractor(sentences);
        this.carry = new ClaimCarryCheck(carry);
        this.drift = drift == null ? TopicDriftCheck.Criteria.defaults() : drift;
    }

    public AssembledBook assemble(BookAssemblyInput input) {
        CitationRegistry registry = new CitationRegistry();
        List<Finding> findings = new ArrayList<Finding>();
        List<String> openPoints = new ArrayList<String>();
        List<ParagraphShingles> shingles = new ArrayList<ParagraphShingles>();
        Set<String> models = new TreeSet<String>();
        StringBuilder contents = new StringBuilder();
        StringBuilder body = new StringBuilder();
        StringBuilder plainBook = new StringBuilder();
        Map<String, String> chapterPlain = new LinkedHashMap<String, String>();
        Counts counts = new Counts();
        TopicDriftCheck topics = new TopicDriftCheck(drift, input.getTitle(), input.getOutline(),
                input.getGlossaryTerms());

        int chapterNo = 0;
        for (OutlineChapter chapter : input.getOutline().getChapters()) {
            chapterNo++;
            contents.append("- ").append(chapterNo).append(' ').append(chapter.getTitle()).append('\n');
            body.append("## ").append(chapterNo).append(' ').append(chapter.getTitle()).append("\n\n");
            StringBuilder chapterText = new StringBuilder(chapter.getTitle()).append("\n\n");
            int sectionNo = 0;
            for (OutlineSection section : chapter.getSections()) {
                sectionNo++;
                counts.sectionsTotal++;
                String number = chapterNo + "." + sectionNo;
                String label = number + " " + section.getTitle();
                contents.append("  - ").append(label).append('\n');
                body.append("### ").append(label).append("\n\n");
                counts.target += section.getBrief().getWordTarget();
                String text = sectionText(input, section, label, findings, openPoints);
                if (text == null) {
                    continue;
                }
                SectionRevision revision = input.revision(section.getId());
                if (!revision.getModelLabel().isEmpty()) {
                    models.add(revision.getModelLabel());
                }
                Map<String, ManuscriptCitation> byMarker = new HashMap<String, ManuscriptCitation>();
                for (ManuscriptCitation c : revision.getCitations()) {
                    byMarker.put(c.getMarker(), c);
                }
                body.append(renumber(text, section.getId(), byMarker, registry, input)).append("\n\n");
                String plain = stripMarkers(text);
                chapterText.append(plain).append("\n\n");
                checkSection(section, label, text, plain, revision, byMarker, findings, shingles, counts, topics);
            }
            chapterPlain.put(chapterNo + " " + chapter.getTitle(), chapterText.toString());
            plainBook.append(chapterText);
        }

        if (counts.sectionsTotal == 0) {
            findings.add(new Finding(Kind.NO_OUTLINE, "", "the approved outline has no sections"));
        }
        for (CitationRegistry.Issue issue : registry.issues()) {
            findings.add(new Finding(Kind.UNRESOLVED_CITATION, issue.getSectionId(),
                    label(input, issue.getSectionId()) + " [" + issue.getMarker() + "]: "
                            + issue.getProblem().label()));
        }
        repeated(input, shingles, findings);
        for (String node : input.getUncoveredConceptNodes()) {
            findings.add(new Finding(Kind.UNCOVERED_CONCEPT_NODE, "", node));
        }
        for (AssemblySource source : input.getSources()) {
            if (source.isAcceptedAfterApproval()) {
                findings.add(new Finding(Kind.LATE_SOURCE, "", source.getTitle().isEmpty()
                        ? source.getSourceId() : source.getTitle()));
            }
        }
        String lowerBook = plainBook.toString().toLowerCase(Locale.ROOT);
        for (String term : input.getGlossaryTerms()) {
            if (term != null && !term.trim().isEmpty()
                    && !lowerBook.contains(term.trim().toLowerCase(Locale.ROOT))) {
                findings.add(new Finding(Kind.UNUSED_GLOSSARY_TERM, "", term.trim()));
            }
        }
        String appendix = acronyms(input, plainBook.toString(), chapterPlain, registry, findings);

        BookQualityReport report = new BookQualityReport(findings, counts.sentences, counts.paragraphs,
                counts.words, counts.target);
        StringBuilder md = new StringBuilder();
        md.append("# ").append(input.getTitle()).append("\n\n");
        md.append("- Writer models: ").append(models.isEmpty() ? "none" : join(models)).append('\n');
        md.append("- Assembled: ").append(date(input.getAssembledAt(), input)).append('\n');
        md.append("- Sources: ").append(registry.references().size()).append('\n');
        md.append("- Approved concept revision: ").append(input.getConceptRevision()).append('\n');
        md.append("- Approved outline revision: ").append(input.getOutlineRevision()).append('\n');
        md.append("- Book status: ").append(report.getStatus().label()).append('\n');
        md.append("- ").append(SUPPORT_NOTE).append("\n\n");
        md.append("## Contents\n\n").append(contents).append('\n');
        md.append(body);
        if (!openPoints.isEmpty()) {
            md.append("## Open points\n\n");
            for (String point : openPoints) {
                md.append("- ").append(point).append('\n');
            }
            md.append('\n');
        }
        md.append("## References\n\n");
        if (registry.references().isEmpty()) {
            md.append("No sources cited.\n\n");
        }
        int n = 0;
        for (AssemblySource source : registry.references()) {
            md.append('[').append(++n).append("] ").append(reference(source, input)).append('\n');
        }
        if (n > 0) {
            md.append('\n');
        }
        md.append(appendix);
        String markdown = md.toString().replaceAll("\\n{3,}", "\n\n").replaceAll("\\s+$", "") + "\n";
        return new AssembledBook(markdown, registry, report);
    }

    /** The section text to assemble, or {@code null} when it has none (recorded as a finding and open point). */
    private static String sectionText(BookAssemblyInput input, OutlineSection section, String label,
                                      List<Finding> findings, List<String> openPoints) {
        ManuscriptSection ms = input.section(section.getId());
        SectionRevision revision = input.revision(section.getId());
        SectionStatus status = ms == null ? SectionStatus.PENDING : ms.getStatus();
        if (input.isThin(section.getId()) || section.isThin()) {
            findings.add(new Finding(Kind.THIN_SECTION, section.getId(), label));
            openPoints.add(label + ": few sources cover this section.");
        }
        if (status == SectionStatus.SKIPPED) {
            findings.add(new Finding(Kind.SKIPPED_SECTION, section.getId(), label));
            openPoints.add(label + ": skipped.");
            return null;
        }
        boolean hasText = revision != null && !revision.getText().trim().isEmpty();
        if (!hasText || status == SectionStatus.PENDING || status == SectionStatus.FAILED
                || status == SectionStatus.ORPHAN) {
            findings.add(new Finding(Kind.MISSING_SECTION, section.getId(), label));
            openPoints.add(label + ": not written yet.");
            return null;
        }
        if (status == SectionStatus.STALE) {
            findings.add(new Finding(Kind.STALE_SECTION, section.getId(), label
                    + (ms.getReason().isEmpty() ? "" : " (" + ms.getReason() + ")")));
        }
        for (String gap : revision.getGaps()) {
            if (gap != null && !gap.trim().isEmpty()) {
                findings.add(new Finding(Kind.OPEN_GAP, section.getId(), label + ": " + gap.trim()));
                openPoints.add(label + ": " + gap.trim());
            }
        }
        return revision.getText().trim();
    }

    private static String renumber(String text, String sectionId, Map<String, ManuscriptCitation> byMarker,
                                   CitationRegistry registry, BookAssemblyInput input) {
        Matcher m = MARKER_GROUP.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            Set<String> numbers = new LinkedHashSet<String>();
            boolean unresolved = false;
            for (String local : m.group(1).split("\\s*[,;]\\s*")) {
                ManuscriptCitation citation = byMarker.get(local);
                int global = registry.resolve(sectionId, local, citation, input);
                if (global > 0) {
                    numbers.add("[" + global + "]");
                } else {
                    unresolved = true; // an unknown marker stays visible as well
                }
            }
            StringBuilder replacement = new StringBuilder();
            for (String number : numbers) {
                replacement.append(number);
            }
            if (unresolved) {
                replacement.append("[?]");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        m.appendTail(out);
        return out.toString().replaceAll(" +([.,;:!?])", "$1").replaceAll(" {2,}", " ");
    }

    private void checkSection(OutlineSection section, String label, String text, String plain,
                              SectionRevision revision, Map<String, ManuscriptCitation> byMarker,
                              List<Finding> findings, List<ParagraphShingles> shingles, Counts counts,
                              TopicDriftCheck topics) {
        String id = section.getId();
        int paragraphNo = 0;
        List<String> sectionSentences = new ArrayList<String>();
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            if (paragraph.trim().isEmpty()) {
                continue;
            }
            paragraphNo++;
            counts.paragraphs++;
            List<ManuscriptCitation> cited = new ArrayList<ManuscriptCitation>();
            List<String> parts = sentences.sentences(paragraph.trim());
            List<String> voices = voice.firstPersonWords(parts); // a quotation can span sentences
            for (int s = 0; s < parts.size(); s++) {
                String sentence = parts.get(s);
                if (sentence.trim().isEmpty()) {
                    continue;
                }
                counts.sentences++;
                sectionSentences.add(stripMarkers(sentence).trim());
                String we = voices.get(s);
                if (!we.isEmpty()) {
                    findings.add(new Finding(Kind.FIRST_PERSON_VOICE, id, label + ": "
                            + stripMarkers(sentence).trim() + " (" + we + ")"));
                }
                List<ManuscriptCitation> own = citationsIn(sentence, byMarker);
                if (own.isEmpty()) {
                    findings.add(new Finding(Kind.UNCITED_SENTENCE, id, label + ": " + sentence.trim()));
                } else {
                    String why = uncarried(sentence, own);
                    if (!why.isEmpty()) {
                        findings.add(new Finding(Kind.UNCARRIED_CLAIM, id, label + ": "
                                + stripMarkers(sentence).trim() + " (" + why + ")"));
                    }
                }
                cited.addAll(own);
            }
            if (cited.isEmpty()) {
                findings.add(new Finding(Kind.UNCITED_PARAGRAPH, id, label + ", paragraph " + paragraphNo));
            }
            List<String> words = words(QUOTED.matcher(stripMarkers(paragraph)).replaceAll(" "));
            Set<ManuscriptCitation> distinct = new LinkedHashSet<ManuscriptCitation>(cited);
            for (ManuscriptCitation citation : distinct) {
                if (sharesRun(words, words(citation.getCitedText()), NEAR_COPY_WORDS)) {
                    findings.add(new Finding(Kind.NEAR_COPY, id, label + ", paragraph " + paragraphNo
                            + " copies [" + citation.getMarker() + "]"));
                }
            }
            Set<String> set = shingles(words(stripMarkers(paragraph)));
            if (!set.isEmpty()) {
                shingles.add(new ParagraphShingles(id, label, paragraphNo, set));
            }
        }
        Set<String> flags = new LinkedHashSet<String>(revision.getClaimFlags());
        for (ManuscriptCitation citation : revision.getCitations()) {
            flags.addAll(citation.getFlags());
        }
        for (String flag : flags) {
            String lower = flag.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith("unsupported number")) {
                findings.add(new Finding(Kind.UNSUPPORTED_NUMBER, id, label + ": " + detail(flag)));
            } else if (lower.startsWith("unsupported quote")) {
                findings.add(new Finding(Kind.UNSUPPORTED_QUOTE, id, label + ": " + detail(flag)));
            }
        }
        for (String subject : topics.foreignSubjects(sectionSentences)) {
            findings.add(new Finding(Kind.TOPIC_DRIFT, id, label + ": " + subject));
        }
        int written = words(plain).size();
        int target = section.getBrief().getWordTarget();
        counts.words += written;
        if (target > 0 && (written * 2 < target || written * 2 > target * 3)) {
            findings.add(new Finding(Kind.WORDS_OFF_TARGET, id, label + ": " + written + " of " + target + " words"));
        }
    }

    /**
     * Stage 2 at assembly, on the stored cited text, so a revision written before stage 2 existed (live run 3) is
     * judged by the same rule; the writer's own stage-2 flags are therefore not counted again.
     */
    private String uncarried(String sentence, List<ManuscriptCitation> own) {
        List<String> markers = new ArrayList<String>();
        List<String> texts = new ArrayList<String>();
        for (ManuscriptCitation citation : new LinkedHashSet<ManuscriptCitation>(own)) {
            markers.add(citation.getMarker());
            texts.add(citation.getCitedText());
        }
        return carry.uncarried(stripMarkers(sentence), markers, texts);
    }

    private static String detail(String flag) {
        int colon = flag.indexOf(':');
        return colon < 0 ? flag.trim() : flag.substring(colon + 1).trim();
    }

    private static List<ManuscriptCitation> citationsIn(String sentence, Map<String, ManuscriptCitation> byMarker) {
        List<ManuscriptCitation> out = new ArrayList<ManuscriptCitation>();
        Matcher m = MARKER_GROUP.matcher(sentence);
        while (m.find()) {
            for (String local : m.group(1).split("\\s*[,;]\\s*")) {
                ManuscriptCitation c = byMarker.get(local);
                if (c != null) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static void repeated(BookAssemblyInput input, List<ParagraphShingles> all, List<Finding> findings) {
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                ParagraphShingles a = all.get(i);
                ParagraphShingles b = all.get(j);
                if (a.sectionId.equals(b.sectionId)) {
                    continue;
                }
                Set<String> common = new HashSet<String>(a.shingles);
                common.retainAll(b.shingles);
                Set<String> union = new HashSet<String>(a.shingles);
                union.addAll(b.shingles);
                if (!common.isEmpty() && common.size() >= REPEAT_OVERLAP * union.size()) {
                    findings.add(new Finding(Kind.REPEATED_PASSAGE, b.sectionId, b.label + ", paragraph "
                            + b.paragraph + " repeats " + a.label + ", paragraph " + a.paragraph));
                }
            }
        }
    }

    private String acronyms(BookAssemblyInput input, String book, Map<String, String> chapters,
                            CitationRegistry registry, List<Finding> findings) {
        AcronymSettings settings = AcronymSettings.defaults();
        Map<String, AcronymLongForms> known = new HashMap<String, AcronymLongForms>();
        for (AcronymLongForms forms : input.getAcronyms()) {
            known.put(forms.acronym(), forms);
        }
        Set<String> used = new TreeSet<String>();
        for (AcronymFinding finding : acronymExtractor.analyze(book, settings).acronyms()) {
            used.add(finding.normalized());
        }
        if (used.isEmpty()) {
            return "";
        }
        StringBuilder md = new StringBuilder("## Abbreviations\n\n");
        for (String acronym : used) {
            AcronymLongForms forms = known.get(acronym);
            if (forms == null || !forms.hasLongForm()) {
                findings.add(new Finding(Kind.ACRONYM_WITHOUT_LONG_FORM, "", acronym));
                md.append("- **").append(acronym).append("**: no long form in the sources\n");
                continue;
            }
            if (forms.conflicting()) {
                findings.add(new Finding(Kind.ACRONYM_CONFLICT, "", acronym + ": "
                        + join(forms.distinctLongForms(), " / ")));
            }
            md.append("- **").append(acronym).append("**: ").append(longForms(forms, input, registry))
                    .append(forms.conflicting() ? " (the sources disagree)" : "").append('\n');
        }
        for (Map.Entry<String, String> chapter : chapters.entrySet()) {
            for (AcronymFinding finding : acronymExtractor.analyze(chapter.getValue(), settings).acronyms()) {
                if (!finding.occurrences().isEmpty() && !definedAtFirstUse(finding)) {
                    findings.add(new Finding(Kind.ACRONYM_FIRST_USE_WITHOUT_LONG_FORM, "",
                            chapter.getKey() + ": " + finding.normalized()));
                }
            }
        }
        return md.append('\n').toString();
    }

    private static boolean definedAtFirstUse(AcronymFinding finding) {
        TextSpan first = null;
        for (TextSpan span : finding.occurrences()) {
            if (first == null || span.compareTo(first) < 0) {
                first = span;
            }
        }
        for (AcronymDefinition definition : finding.definitions()) {
            if (definition.span().overlaps(first) || definition.shortSpan().overlaps(first)) {
                return true;
            }
        }
        return false;
    }

    /** Each distinct long form with the sources that state it, as reference numbers or titles. */
    private static String longForms(AcronymLongForms forms, BookAssemblyInput input, CitationRegistry registry) {
        Map<String, Set<String>> sourcesByForm = new TreeMap<String, Set<String>>();
        Map<String, String> display = new HashMap<String, String>();
        for (AcronymLongForms.Statement statement : forms.statements()) {
            String form = statement.longForm().longForm().trim();
            String key = form.toLowerCase(Locale.ROOT);
            if (!display.containsKey(key)) {
                display.put(key, form);
            }
            Set<String> names = sourcesByForm.get(key);
            if (names == null) {
                names = new TreeSet<String>();
                sourcesByForm.put(key, names);
            }
            int number = registry.numberOf(statement.sourceId());
            AssemblySource source = input.source(statement.sourceId());
            names.add(number > 0 ? "[" + number + "]"
                    : source != null && !source.getTitle().isEmpty() ? source.getTitle() : statement.sourceId());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Set<String>> entry : sourcesByForm.entrySet()) {
            sb.append(sb.length() == 0 ? "" : "; ").append(display.get(entry.getKey()))
                    .append(" (").append(join(entry.getValue())).append(')');
        }
        return sb.toString();
    }

    private static String reference(AssemblySource source, BookAssemblyInput input) {
        StringBuilder sb = new StringBuilder(source.getTitle().isEmpty() ? source.getSourceId() : source.getTitle());
        sb.append('.');
        if (!source.getSite().isEmpty()) {
            sb.append(' ').append(source.getSite()).append('.');
        }
        if (!source.getUrl().isEmpty()) {
            sb.append(" <").append(source.getUrl()).append(">.");
        }
        if (source.getAccessedAt() > 0) {
            sb.append(" Accessed ").append(date(source.getAccessedAt(), input)).append('.');
        }
        if (source.isAcceptedAfterApproval()) {
            sb.append(" Accepted after the Sources approval.");
        }
        return sb.toString();
    }

    private static String label(BookAssemblyInput input, String sectionId) {
        int c = 0;
        for (OutlineChapter chapter : input.getOutline().getChapters()) {
            c++;
            int s = 0;
            for (OutlineSection section : chapter.getSections()) {
                s++;
                if (section.getId().equals(sectionId)) {
                    return c + "." + s + " " + section.getTitle();
                }
            }
        }
        return sectionId;
    }

    static String stripMarkers(String text) {
        return MARKER.matcher(text).replaceAll("").replaceAll(" +([.,;:!?])", "$1").replaceAll(" {2,}", " ");
    }

    static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        Matcher m = WORD.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean sharesRun(List<String> a, List<String> b, int n) {
        if (a.size() < n || b.size() < n) {
            return false;
        }
        Set<String> grams = new HashSet<String>();
        for (int i = 0; i + n <= b.size(); i++) {
            grams.add(join(b.subList(i, i + n), " "));
        }
        for (int i = 0; i + n <= a.size(); i++) {
            if (grams.contains(join(a.subList(i, i + n), " "))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> shingles(List<String> words) {
        Set<String> out = new HashSet<String>();
        for (int i = 0; i + SHINGLE_WORDS <= words.size(); i++) {
            out.add(join(words.subList(i, i + SHINGLE_WORDS), " "));
        }
        return out;
    }

    private static String date(long millis, BookAssemblyInput input) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(input.getTimeZone());
        return format.format(new Date(millis));
    }

    private static String join(Iterable<String> values) {
        return join(values, ", ");
    }

    private static String join(Iterable<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            sb.append(sb.length() == 0 ? "" : separator).append(value);
        }
        return sb.toString();
    }

    private static final class Counts {
        int sentences;
        int paragraphs;
        int words;
        int target;
        int sectionsTotal;
    }

    private static final class ParagraphShingles {
        final String sectionId;
        final String label;
        final int paragraph;
        final Set<String> shingles;

        ParagraphShingles(String sectionId, String label, int paragraph, Set<String> shingles) {
            this.sectionId = sectionId;
            this.label = label;
            this.paragraph = paragraph;
            this.shingles = shingles;
        }
    }
}
