package com.aresstack.askai.research.knowledge.processing.acronym;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The stored acronym findings of one source (PR 38): short forms, technical identifiers and the long forms the source
 * states, each long form with the character spans of its short form, its long form and its sentence in the source's
 * write-once text ({@link SourceText}). Keyed by source id, source checksum, extractor version and segmenter
 * fingerprint; a record whose key no longer matches is stale and extracted again. Never points at a passage.
 */
public final class SourceAcronymRecord {

    /** One stated long form with its spans. */
    public static final class LongForm {
        private final String longForm;
        private final TextSpan shortSpan;
        private final TextSpan longSpan;
        private final TextSpan sentenceSpan;

        public LongForm(String longForm, TextSpan shortSpan, TextSpan longSpan, TextSpan sentenceSpan) {
            if (longForm == null || longForm.trim().isEmpty()) {
                throw new IllegalArgumentException("longForm must not be blank");
            }
            if (shortSpan == null || longSpan == null || sentenceSpan == null) {
                throw new IllegalArgumentException("spans must not be null");
            }
            this.longForm = longForm;
            this.shortSpan = shortSpan;
            this.longSpan = longSpan;
            this.sentenceSpan = sentenceSpan;
        }

        public String longForm() {
            return longForm;
        }

        public TextSpan shortSpan() {
            return shortSpan;
        }

        public TextSpan longSpan() {
            return longSpan;
        }

        public TextSpan sentenceSpan() {
            return sentenceSpan;
        }

        /** Short and long form together: the region that states the long form. */
        public TextSpan definitionSpan() {
            return new TextSpan(Math.min(shortSpan.start(), longSpan.start()),
                    Math.max(shortSpan.end(), longSpan.end()));
        }
    }

    /** One short form (acronym or technical identifier) of the source. */
    public static final class Term {
        private final String normalized;
        private final TermKind kind;
        private final List<LongForm> longForms;

        public Term(String normalized, TermKind kind, List<LongForm> longForms) {
            if (normalized == null || normalized.isEmpty() || kind == null) {
                throw new IllegalArgumentException("normalized and kind are required");
            }
            this.normalized = normalized;
            this.kind = kind;
            this.longForms = Collections.unmodifiableList(new ArrayList<LongForm>(
                    longForms == null ? Collections.<LongForm>emptyList() : longForms));
        }

        public String normalized() {
            return normalized;
        }

        public TermKind kind() {
            return kind;
        }

        public List<LongForm> longForms() {
            return longForms;
        }
    }

    private final String sourceId;
    private final String sourceChecksum;
    private final String extractorVersion;
    private final String segmenterFingerprint;
    private final List<Term> terms;

    public SourceAcronymRecord(String sourceId, String sourceChecksum, String extractorVersion,
                               String segmenterFingerprint, List<Term> terms) {
        if (sourceId == null || sourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        this.sourceId = sourceId;
        this.sourceChecksum = nonNull(sourceChecksum);
        this.extractorVersion = nonNull(extractorVersion);
        this.segmenterFingerprint = nonNull(segmenterFingerprint);
        this.terms = Collections.unmodifiableList(new ArrayList<Term>(
                terms == null ? Collections.<Term>emptyList() : terms));
    }

    /** The record of one extraction. */
    public static SourceAcronymRecord from(SourceText source, AcronymAnalysis analysis, String segmenterFingerprint) {
        List<Term> terms = new ArrayList<Term>();
        for (AcronymFinding finding : analysis.findings()) {
            List<LongForm> longForms = new ArrayList<LongForm>();
            for (AcronymDefinition definition : finding.definitions()) {
                longForms.add(new LongForm(definition.longForm(), definition.shortSpan(), definition.longSpan(),
                        definition.sentenceSpan()));
            }
            terms.add(new Term(finding.normalized(), finding.kind(), longForms));
        }
        return new SourceAcronymRecord(source.sourceId(), source.checksum(), AcronymExtractor.VERSION,
                segmenterFingerprint, terms);
    }

    /** {@code true} when this record was made from another text, extractor or segmenter. */
    public boolean isStaleFor(SourceText source, String extractorVersion, String segmenterFingerprint) {
        return !sourceChecksum.equals(source.checksum())
                || !this.extractorVersion.equals(nonNull(extractorVersion))
                || !this.segmenterFingerprint.equals(nonNull(segmenterFingerprint));
    }

    public String sourceId() {
        return sourceId;
    }

    public String sourceChecksum() {
        return sourceChecksum;
    }

    public String extractorVersion() {
        return extractorVersion;
    }

    public String segmenterFingerprint() {
        return segmenterFingerprint;
    }

    public List<Term> terms() {
        return terms;
    }

    /** The term with this normalized form, or {@code null}. */
    public Term term(String normalized) {
        for (Term term : terms) {
            if (term.normalized().equals(normalized)) {
                return term;
            }
        }
        return null;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
