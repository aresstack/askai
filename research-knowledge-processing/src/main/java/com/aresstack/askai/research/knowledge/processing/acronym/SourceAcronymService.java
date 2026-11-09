package com.aresstack.askai.research.knowledge.processing.acronym;

import com.aresstack.askai.research.domain.Passage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Abbreviations and long forms from the sources (PR 38). Runs the rule-based {@link AcronymExtractor} once per
 * accepted source on its write-once text and keeps the findings in a {@link SourceAcronymStore}; a source is
 * extracted again only when its checksum, the extractor version or the segmenter fingerprint changed, never because
 * of a re-embedding.
 *
 * <p>Every read takes the set of sources an evidence pack may use at that moment (PR 27: the approved set plus later
 * accepted sources, minus excluded sources and sources hit by the recorded blacklist); findings of any other source
 * never reach a glossary block, the abbreviation list or the conflicts.</p>
 */
public final class SourceAcronymService {

    /** Glossary entries an evidence pack adds at most. */
    public static final int GLOSSARY_MAX_ENTRIES = 10;
    /** Token budget of the evidence pack's glossary block. */
    public static final int GLOSSARY_TOKEN_BUDGET = 200;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final AcronymExtractor extractor;
    private final String segmenterFingerprint;
    private final SourceAcronymStore store;
    private final Map<String, String> passageLinks = new ConcurrentHashMap<String, String>();

    public SourceAcronymService(SentenceSegmenter segmenter, SourceAcronymStore store) {
        if (segmenter == null || store == null) {
            throw new IllegalArgumentException("segmenter and store are required");
        }
        this.extractor = new AcronymExtractor(segmenter);
        this.segmenterFingerprint = segmenter.id();
        this.store = store;
    }

    /**
     * Brings the findings of these sources up to date; returns the ids of the sources that were extracted (again).
     */
    public List<String> refresh(Collection<SourceText> sources) {
        List<String> extracted = new ArrayList<String>();
        for (SourceText source : sources) {
            SourceAcronymRecord stored = store.load(source.sourceId());
            if (stored != null && !stored.isStaleFor(source, AcronymExtractor.VERSION, segmenterFingerprint)) {
                continue;
            }
            AcronymAnalysis analysis = extractor.analyze(source.text(), AcronymSettings.defaults());
            store.save(SourceAcronymRecord.from(source, analysis, segmenterFingerprint));
            extracted.add(source.sourceId());
        }
        return extracted;
    }

    /** Per acronym, the long forms the eligible sources state; sorted by acronym. */
    public List<AcronymLongForms> longForms(Collection<String> eligibleSourceIds) {
        Map<String, List<AcronymLongForms.Statement>> byAcronym =
                new java.util.TreeMap<String, List<AcronymLongForms.Statement>>();
        for (String sourceId : new LinkedHashSet<String>(eligibleSourceIds)) {
            SourceAcronymRecord record = store.load(sourceId);
            if (record == null) {
                continue;
            }
            for (SourceAcronymRecord.Term term : record.terms()) {
                if (term.kind() != TermKind.ACRONYM) {
                    continue;
                }
                List<AcronymLongForms.Statement> list = byAcronym.get(term.normalized());
                if (list == null) {
                    list = new ArrayList<AcronymLongForms.Statement>();
                    byAcronym.put(term.normalized(), list);
                }
                for (SourceAcronymRecord.LongForm form : term.longForms()) {
                    list.add(new AcronymLongForms.Statement(sourceId, form));
                }
            }
        }
        List<AcronymLongForms> result = new ArrayList<AcronymLongForms>();
        for (Map.Entry<String, List<AcronymLongForms.Statement>> entry : byAcronym.entrySet()) {
            result.add(new AcronymLongForms(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** The long forms of one acronym in the eligible sources, or {@code null} if no eligible source names it. */
    public AcronymLongForms longFormsOf(String acronym, Collection<String> eligibleSourceIds) {
        for (AcronymLongForms forms : longForms(eligibleSourceIds)) {
            if (forms.acronym().equals(acronym)) {
                return forms;
            }
        }
        return null;
    }

    /**
     * The glossary lines an evidence pack adds for its passages: up to {@value #GLOSSARY_MAX_ENTRIES} acronyms that
     * occur in the passage texts and have a long form in an eligible source, in order of first occurrence, as
     * {@code RAG: Retrieval-Augmented Generation} (conflicting long forms joined by {@code " / "}), cut so the lines
     * stay within {@code tokenBudget} estimated tokens.
     */
    public List<String> glossaryLines(List<String> passageTexts, Collection<String> eligibleSourceIds,
                                      int tokenBudget) {
        Map<String, AcronymLongForms> known = new LinkedHashMap<String, AcronymLongForms>();
        for (AcronymLongForms forms : longForms(eligibleSourceIds)) {
            if (!forms.statements().isEmpty()) {
                known.put(forms.acronym(), forms);
            }
        }
        Map<Integer, String> firstOccurrence = new java.util.TreeMap<Integer, String>();
        StringBuilder joined = new StringBuilder();
        for (String text : passageTexts) {
            joined.append(text == null ? "" : text).append('\n');
        }
        String all = joined.toString();
        for (TermScanner.Token token : TermScanner.tokens(all)) {
            List<TermScanner.Token> candidates = new ArrayList<TermScanner.Token>();
            candidates.add(token);
            candidates.addAll(TermScanner.parts(token));
            for (TermScanner.Token candidate : candidates) {
                if (AcronymForms.classify(candidate.text(), AcronymSettings.defaults().maxLetters())
                        != TermKind.ACRONYM) {
                    continue;
                }
                String acronym = AcronymForms.normalize(candidate.text());
                if (known.containsKey(acronym) && !firstOccurrence.containsValue(acronym)) {
                    firstOccurrence.put(candidate.span().start(), acronym);
                }
            }
        }
        List<String> lines = new ArrayList<String>();
        int used = 0;
        for (String acronym : firstOccurrence.values()) {
            if (lines.size() >= GLOSSARY_MAX_ENTRIES) {
                break;
            }
            String line = acronym + ": " + join(known.get(acronym).distinctLongForms(), " / ");
            int cost = estimateTokens(line);
            if (used + cost > tokenBudget) {
                break;
            }
            lines.add(line);
            used += cost;
        }
        return lines;
    }

    /**
     * The passage, among the current passages of the statement's source, that states this long form; looked up from
     * the span in the write-once text, cached only for the given embedding fingerprint and segmentation version.
     * Returns {@code null} when no current passage contains the statement.
     */
    public Passage statingPassage(AcronymLongForms.Statement statement, SourceText source,
                                  List<Passage> currentPassages) {
        if (!statement.sourceId().equals(source.sourceId()) || currentPassages.isEmpty()) {
            return null;
        }
        String fingerprint = String.valueOf(currentPassages.get(0).getEmbeddingFingerprint());
        String segmentation = String.valueOf(currentPassages.get(0).getSegmentationPipelineVersion());
        TextSpan definition = statement.longForm().definitionSpan();
        String key = source.sourceId() + '|' + source.checksum() + '|' + definition + '|' + fingerprint + '|'
                + segmentation;
        String cached = passageLinks.get(key);
        if (cached != null) {
            for (Passage passage : currentPassages) {
                if (passage.getPassageId().equals(cached)) {
                    return passage;
                }
            }
        }
        if (definition.end() > source.text().length()) {
            return null;
        }
        String needle = normalize(definition.of(source.text()));
        for (Passage passage : currentPassages) {
            if (normalize(passage.getText()).contains(needle)) {
                passageLinks.put(key, passage.getPassageId());
                return passage;
            }
        }
        return null;
    }

    /** Rough token estimate (four characters per token, rounded up), as used for prompt budgets. */
    static int estimateTokens(String text) {
        return (text.length() + 3) / 4;
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(text == null ? "" : text).replaceAll(" ").trim();
    }

    private static String join(Collection<String> parts, String separator) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(part);
        }
        return out.toString();
    }
}
