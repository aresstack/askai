package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.book.SourcesFreezeHook;
import com.aresstack.askai.research.book.SourcesPin;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.rerank.SourcePreferenceRanking;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.store.FileManuscriptStore;
import com.aresstack.askai.research.writer.EvidencePassageLookup;
import com.aresstack.askai.research.writer.EvidenceSourceSet;
import com.aresstack.askai.research.writer.SectionContextRequest;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The productive side of a section's evidence pack (plan PR 29 wiring of PR 27/28): the accepted sources of this
 * moment (the approved set plus sources a gap search accepted later) with the weight the mission's source
 * preference gives each of them, the active passages by id for the provenance lookup, and the digest of the
 * previous written section. The passage map is read once per writing run ({@link #refresh()}), so a pack never
 * reads the whole corpus per section.
 */
public final class ProductiveSectionContexts implements EvidenceAssignments.Contexts, EvidencePassageLookup {

    /** The accepted sources (id -> checksum) right now. */
    public interface AcceptedSources {
        Map<String, String> checksums();

        /** The URLs of the accepted sources (id -> URL), which the source preference judges. */
        default Map<String, String> urls() {
            return Collections.emptyMap();
        }
    }

    static final int DIGEST_CHARS = 1200;

    private final String projectId;
    private final String embeddingFingerprint;
    private final ActiveKnowledgeCorpusReader corpus;
    private final AcceptedSources sources;
    private final FileManuscriptStore manuscript;
    private final java.util.function.Supplier<String> bookLanguage;
    private final java.util.function.Supplier<SourcePreferenceRanking> preference;
    private volatile Map<String, PassageSearchHit> passages;

    public ProductiveSectionContexts(String projectId, String embeddingFingerprint, ActiveKnowledgeCorpusReader corpus,
                                     AcceptedSources sources, FileManuscriptStore manuscript,
                                     java.util.function.Supplier<String> bookLanguage) {
        this(projectId, embeddingFingerprint, corpus, sources, manuscript, bookLanguage, null);
    }

    /**
     * @param preference the mission's source preference, read for every section, so a changed setting applies to
     *                   the next section written; {@code null} keeps the relevance order
     */
    public ProductiveSectionContexts(String projectId, String embeddingFingerprint, ActiveKnowledgeCorpusReader corpus,
                                     AcceptedSources sources, FileManuscriptStore manuscript,
                                     java.util.function.Supplier<String> bookLanguage,
                                     java.util.function.Supplier<SourcePreferenceRanking> preference) {
        this.preference = preference;
        this.projectId = projectId;
        this.embeddingFingerprint = embeddingFingerprint;
        this.corpus = corpus;
        this.sources = sources;
        this.manuscript = manuscript;
        this.bookLanguage = bookLanguage;
    }

    /** The accepted sources of a repository, as {@link SourcesFreezeHook#approvedSet} lists them. */
    public static AcceptedSources of(final com.aresstack.askai.research.sources.ResearchSourceRepository repository) {
        return new AcceptedSources() {
            public Map<String, String> checksums() {
                Map<String, String> checksums = new LinkedHashMap<String, String>();
                for (SourcesPin.Entry entry : SourcesFreezeHook.approvedSet(repository)) {
                    checksums.put(entry.getSourceId(), entry.getChecksum());
                }
                return checksums;
            }

            public Map<String, String> urls() {
                Map<String, String> urls = new HashMap<String, String>();
                for (ResearchSourceRecord record : repository.find(
                        com.aresstack.askai.research.sources.SourceQuery.all())) {
                    urls.put(record.getSourceId(), record.getUrl() == null ? "" : record.getUrl());
                }
                return urls;
            }
        };
    }

    /** Read the active passages again (call at the start of each writing run). */
    public void refresh() {
        passages = null;
    }

    public SectionContextRequest requestFor(SectionWriteRequest request) {
        Map<String, String> checksums = sources.checksums();
        EvidenceSourceSet set = new EvidenceSourceSet(checksums, Collections.<String>emptyList(),
                Collections.<String>emptyList(), preferenceShifts(checksums.keySet()));
        return new SectionContextRequest(projectId, embeddingFingerprint, request.getChapter(), request.getSection(),
                set, request.getChapter().getBrief().getPurpose(), previousDigest(request),
                Collections.<String>emptyList(), language());
    }

    /** The places each accepted source moves in the pack under the mission's source preference; only shifts != 0. */
    private Map<String, Integer> preferenceShifts(java.util.Set<String> accepted) {
        SourcePreferenceRanking ranking = preference == null ? null : preference.get();
        Map<String, Integer> shifts = new HashMap<String, Integer>();
        if (ranking == null || ranking.isNone()) {
            return shifts;
        }
        for (Map.Entry<String, String> source : sources.urls().entrySet()) {
            int shift = accepted.contains(source.getKey()) ? ranking.shiftOf(source.getValue()) : 0;
            if (shift != 0) {
                shifts.put(source.getKey(), shift);
            }
        }
        return shifts;
    }

    /** The approved concept's language at write time ("" lets the writer fall back). */
    private String language() {
        String value = bookLanguage == null ? null : bookLanguage.get();
        return value == null ? "" : value;
    }

    public PassageSearchHit passage(String project, String passageId) {
        return passageMap().get(passageId);
    }

    private Map<String, PassageSearchHit> passageMap() {
        Map<String, PassageSearchHit> map = passages;
        if (map == null) {
            map = new HashMap<String, PassageSearchHit>();
            ActiveKnowledgeCorpusReader.Corpus read = corpus.read(ActiveKnowledgeCorpusReader.SourceFilter.ALL);
            for (Passage passage : read.getPassages()) {
                map.put(passage.getPassageId(), new PassageSearchHit(passage.getPassageId(), passage.getCaptureId(),
                        read.sourceIdOf(passage.getPassageId()), passage.getText(), passage.getHeadingPath(), 1.0));
            }
            passages = map;
        }
        return map;
    }

    /** The beginning of the previous section's current text in the same chapter, or "". */
    private String previousDigest(SectionWriteRequest request) {
        OutlineChapter chapter = request.getChapter();
        List<OutlineSection> sections = chapter.getSections();
        for (int i = 1; i < sections.size(); i++) {
            if (sections.get(i).getId().equals(request.getSection().getId())) {
                SectionRevision previous = manuscript.current(sections.get(i - 1).getId());
                if (previous == null) {
                    return "";
                }
                String text = previous.getText();
                return text.length() <= DIGEST_CHARS ? text : text.substring(0, DIGEST_CHARS);
            }
        }
        return "";
    }
}
