package com.aresstack.askai.research.assembly;

import com.aresstack.askai.research.knowledge.processing.acronym.AcronymLongForms;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.Outline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Everything one assembly reads (plan PR 30), as an immutable snapshot so the assembler stays a pure function: the
 * approved outline, the manuscript sections with their current revisions, the sources, the concept labels (the
 * glossary), the acronym long forms of the sources an evidence pack may use (PR 38), the thin sections and the
 * concept nodes left uncovered at the Sources approval.
 */
public final class BookAssemblyInput {

    private final String title;
    private final long assembledAt;
    private final TimeZone timeZone;
    private final int conceptRevision;
    private final int outlineRevision;
    private final Outline outline;
    private final Map<String, ManuscriptSection> sections;
    private final Map<String, SectionRevision> revisions;
    private final Map<String, AssemblySource> sources;
    private final List<String> glossaryTerms;
    private final List<AcronymLongForms> acronyms;
    private final Set<String> thinSectionIds;
    private final List<String> uncoveredConceptNodes;

    private BookAssemblyInput(Builder b) {
        this.title = b.title == null || b.title.trim().isEmpty() ? "Untitled book" : b.title.trim();
        this.assembledAt = b.assembledAt;
        this.timeZone = b.timeZone == null ? TimeZone.getTimeZone("UTC") : b.timeZone;
        this.conceptRevision = b.conceptRevision;
        this.outlineRevision = b.outlineRevision;
        this.outline = b.outline == null ? Outline.EMPTY : b.outline;
        this.sections = Collections.unmodifiableMap(new LinkedHashMap<String, ManuscriptSection>(b.sections));
        this.revisions = Collections.unmodifiableMap(new LinkedHashMap<String, SectionRevision>(b.revisions));
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<String, AssemblySource>(b.sources));
        this.glossaryTerms = Collections.unmodifiableList(new ArrayList<String>(b.glossaryTerms));
        this.acronyms = Collections.unmodifiableList(new ArrayList<AcronymLongForms>(b.acronyms));
        this.thinSectionIds = Collections.unmodifiableSet(new LinkedHashSet<String>(b.thinSectionIds));
        this.uncoveredConceptNodes = Collections.unmodifiableList(new ArrayList<String>(b.uncoveredConceptNodes));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTitle() {
        return title;
    }

    public long getAssembledAt() {
        return assembledAt;
    }

    public TimeZone getTimeZone() {
        return timeZone;
    }

    public int getConceptRevision() {
        return conceptRevision;
    }

    public int getOutlineRevision() {
        return outlineRevision;
    }

    public Outline getOutline() {
        return outline;
    }

    public ManuscriptSection section(String sectionId) {
        return sections.get(sectionId);
    }

    public SectionRevision revision(String sectionId) {
        return revisions.get(sectionId);
    }

    public AssemblySource source(String sourceId) {
        return sources.get(sourceId);
    }

    public Collection<AssemblySource> getSources() {
        return sources.values();
    }

    public List<String> getGlossaryTerms() {
        return glossaryTerms;
    }

    public List<AcronymLongForms> getAcronyms() {
        return acronyms;
    }

    public boolean isThin(String sectionId) {
        return thinSectionIds.contains(sectionId);
    }

    public List<String> getUncoveredConceptNodes() {
        return uncoveredConceptNodes;
    }

    public static final class Builder {
        private String title;
        private long assembledAt;
        private TimeZone timeZone;
        private int conceptRevision;
        private int outlineRevision;
        private Outline outline;
        private final Map<String, ManuscriptSection> sections = new LinkedHashMap<String, ManuscriptSection>();
        private final Map<String, SectionRevision> revisions = new LinkedHashMap<String, SectionRevision>();
        private final Map<String, AssemblySource> sources = new LinkedHashMap<String, AssemblySource>();
        private final List<String> glossaryTerms = new ArrayList<String>();
        private final List<AcronymLongForms> acronyms = new ArrayList<AcronymLongForms>();
        private final Set<String> thinSectionIds = new LinkedHashSet<String>();
        private final List<String> uncoveredConceptNodes = new ArrayList<String>();

        private Builder() {
        }

        public Builder title(String value) {
            title = value;
            return this;
        }

        public Builder assembledAt(long epochMillis, TimeZone zone) {
            assembledAt = epochMillis;
            timeZone = zone;
            return this;
        }

        public Builder conceptRevision(int value) {
            conceptRevision = value;
            return this;
        }

        public Builder outline(Outline value, int revision) {
            outline = value;
            outlineRevision = revision;
            return this;
        }

        /** A manuscript section and its current revision ({@code null} when it has none). */
        public Builder section(ManuscriptSection section, SectionRevision current) {
            sections.put(section.getSectionId(), section);
            if (current != null) {
                revisions.put(section.getSectionId(), current);
            }
            return this;
        }

        public Builder source(AssemblySource source) {
            sources.put(source.getSourceId(), source);
            return this;
        }

        public Builder glossaryTerms(Collection<String> values) {
            glossaryTerms.clear();
            if (values != null) {
                glossaryTerms.addAll(values);
            }
            return this;
        }

        public Builder acronyms(Collection<AcronymLongForms> values) {
            acronyms.clear();
            if (values != null) {
                acronyms.addAll(values);
            }
            return this;
        }

        public Builder thinSections(Collection<String> ids) {
            thinSectionIds.clear();
            if (ids != null) {
                thinSectionIds.addAll(ids);
            }
            return this;
        }

        public Builder uncoveredConceptNodes(Collection<String> labels) {
            uncoveredConceptNodes.clear();
            if (labels != null) {
                uncoveredConceptNodes.addAll(labels);
            }
            return this;
        }

        public BookAssemblyInput build() {
            return new BookAssemblyInput(this);
        }
    }
}
