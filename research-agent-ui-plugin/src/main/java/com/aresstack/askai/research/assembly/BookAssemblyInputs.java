package com.aresstack.askai.research.assembly;

import com.aresstack.askai.research.book.SourcesPin;
import com.aresstack.askai.research.knowledge.processing.acronym.AcronymLongForms;
import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.store.FileManuscriptStore;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Reads one {@link BookAssemblyInput} from the stores (plan PR 30): the approved outline, the current section
 * revisions of {@link FileManuscriptStore}, the cited and late-accepted sources of {@code FileResearchSourceRepository}
 * (marked late when the valid sources pin lacks them), the concept title and labels, and the acronym long forms.
 */
public final class BookAssemblyInputs {

    private BookAssemblyInputs() {
    }

    /** The concept facts the assembler prints: title, approved revision and labels (the glossary). */
    public static final class Concept {
        final String title;
        final int revision;
        final Collection<String> labels;

        public Concept(String title, int revision, Collection<String> labels) {
            this.title = title;
            this.revision = revision;
            this.labels = labels;
        }
    }

    public static BookAssemblyInput read(Outline outline, int outlineRevision, FileManuscriptStore manuscript,
                                         ResearchSourceRepository sources, SourcesPin pin, Concept concept,
                                         List<AcronymLongForms> acronyms, long nowMillis, TimeZone zone) {
        BookAssemblyInput.Builder b = BookAssemblyInput.builder()
                .title(concept == null ? null : concept.title)
                .conceptRevision(concept == null ? 0 : concept.revision)
                .glossaryTerms(concept == null ? null : concept.labels)
                .outline(outline, outlineRevision)
                .assembledAt(nowMillis, zone)
                .acronyms(acronyms);
        Set<String> sourceIds = new LinkedHashSet<String>();
        for (ManuscriptSection section : manuscript.sections()) {
            SectionRevision current = manuscript.current(section.getSectionId());
            b.section(section, current);
            if (current != null) {
                for (ManuscriptCitation citation : current.getCitations()) {
                    sourceIds.add(citation.getSourceId());
                }
            }
        }
        if (sources == null) {
            return b.build();
        }
        if (pin != null) {
            for (ResearchSourceRecord record : sources.find(SourceQuery.all())) {
                if (record.getStatus() == SourceStatus.ACCEPTED && !pin.contains(record.getSourceId())) {
                    sourceIds.add(record.getSourceId());
                }
            }
        }
        for (String id : sourceIds) {
            ResearchSourceRecord record = sources.get(id);
            if (record != null) {
                b.source(new AssemblySource(id, record.getTitle(), site(record.getUrl()), record.getUrl(),
                        record.getCapturedAt(), record.getChecksum(), record.getFullText(),
                        pin != null && record.getStatus() == SourceStatus.ACCEPTED && !pin.contains(id)));
            }
        }
        return b.build();
    }

    /** The host of a URL without {@code www.}, or "" when it has none. */
    static String site(String url) {
        try {
            String host = url == null || url.trim().isEmpty() ? null : URI.create(url.trim()).getHost();
            return host == null ? "" : host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }
}
