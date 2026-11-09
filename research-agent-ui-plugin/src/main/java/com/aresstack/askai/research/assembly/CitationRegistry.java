package com.aresstack.askai.research.assembly;

import com.aresstack.askai.research.manuscript.ManuscriptCitation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global citation numbers of one assembly (plan PR 30): one number per source, given in order of first use, so a
 * rewrite that cites the same sources in the same order keeps every number. A citation resolves only through its
 * source id, the source checksum and its cited text occurring in the stored source text; the passage id is never
 * consulted, so a re-embedding that renames or re-cuts passages changes nothing. A mismatch is recorded as an
 * {@link Issue} and never re-pointed to another source.
 */
public final class CitationRegistry {

    /** Why a citation did not resolve, or a marker had no citation. */
    public enum Problem {
        UNKNOWN_MARKER("marker without a citation"),
        UNKNOWN_SOURCE("source not in the store"),
        CHECKSUM_MISMATCH("source checksum differs"),
        TEXT_NOT_FOUND("cited text not found in the source");

        private final String label;

        Problem(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One citation that did not resolve. */
    public static final class Issue {
        private final String sectionId;
        private final String marker;
        private final String sourceId;
        private final Problem problem;

        Issue(String sectionId, String marker, String sourceId, Problem problem) {
            this.sectionId = sectionId;
            this.marker = marker;
            this.sourceId = sourceId == null ? "" : sourceId;
            this.problem = problem;
        }

        public String getSectionId() {
            return sectionId;
        }

        public String getMarker() {
            return marker;
        }

        public String getSourceId() {
            return sourceId;
        }

        public Problem getProblem() {
            return problem;
        }
    }

    private final Map<String, Integer> numbers = new LinkedHashMap<String, Integer>();
    private final List<AssemblySource> references = new ArrayList<AssemblySource>();
    private final List<Issue> issues = new ArrayList<Issue>();

    CitationRegistry() {
    }

    /**
     * The global number of a citation, or {@code -1} when it does not resolve (the issue is recorded).
     *
     * @param citation the citation map entry of the local marker, {@code null} for an unknown marker
     */
    int resolve(String sectionId, String marker, ManuscriptCitation citation, BookAssemblyInput input) {
        if (citation == null) {
            issues.add(new Issue(sectionId, marker, "", Problem.UNKNOWN_MARKER));
            return -1;
        }
        Problem problem = check(citation, input.source(citation.getSourceId()));
        if (problem != null) {
            issues.add(new Issue(sectionId, marker, citation.getSourceId(), problem));
            return -1;
        }
        Integer number = numbers.get(citation.getSourceId());
        if (number == null) {
            number = numbers.size() + 1;
            numbers.put(citation.getSourceId(), number);
            references.add(input.source(citation.getSourceId()));
        }
        return number;
    }

    /** {@code null} when the citation resolves to its source, else the problem. */
    static Problem check(ManuscriptCitation citation, AssemblySource source) {
        if (source == null) {
            return Problem.UNKNOWN_SOURCE;
        }
        if (!source.getChecksum().equals(citation.getSourceChecksum())) {
            return Problem.CHECKSUM_MISMATCH;
        }
        if (!normalize(source.getText()).contains(normalize(citation.getCitedText()))) {
            return Problem.TEXT_NOT_FOUND;
        }
        return null;
    }

    /** Whitespace collapsed, so a passage cut at other line breaks is still found in the source. */
    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** The number of a source, or {@code -1} when the book does not cite it. */
    public int numberOf(String sourceId) {
        Integer n = numbers.get(sourceId);
        return n == null ? -1 : n;
    }

    /** The source behind {@code [n]}, or {@code null}; what {@code DocumentPageView} resolves a badge with. */
    public AssemblySource sourceOf(int number) {
        return number >= 1 && number <= references.size() ? references.get(number - 1) : null;
    }

    /** The cited sources, index {@code n - 1} for {@code [n]}. */
    public List<AssemblySource> references() {
        return Collections.unmodifiableList(references);
    }

    public List<Issue> issues() {
        return Collections.unmodifiableList(issues);
    }
}
