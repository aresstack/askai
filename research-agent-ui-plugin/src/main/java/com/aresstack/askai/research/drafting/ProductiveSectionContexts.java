package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.book.SourcesFreezeHook;
import com.aresstack.askai.research.book.SourcesPin;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
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
 * moment (the approved set plus sources a gap search accepted later), the active passages by id for the
 * provenance lookup, and the digest of the previous written section. The passage map is read once per writing
 * run ({@link #refresh()}), so a pack never reads the whole corpus per section.
 */
public final class ProductiveSectionContexts implements EvidenceAssignments.Contexts, EvidencePassageLookup {

    /** The accepted sources (id -> checksum) right now. */
    public interface AcceptedSources {
        Map<String, String> checksums();
    }

    static final int DIGEST_CHARS = 1200;

    private final String projectId;
    private final String embeddingFingerprint;
    private final ActiveKnowledgeCorpusReader corpus;
    private final AcceptedSources sources;
    private final FileManuscriptStore manuscript;
    private final String bookLanguage;
    private volatile Map<String, PassageSearchHit> passages;

    public ProductiveSectionContexts(String projectId, String embeddingFingerprint, ActiveKnowledgeCorpusReader corpus,
                                     AcceptedSources sources, FileManuscriptStore manuscript, String bookLanguage) {
        this.projectId = projectId;
        this.embeddingFingerprint = embeddingFingerprint;
        this.corpus = corpus;
        this.sources = sources;
        this.manuscript = manuscript;
        this.bookLanguage = bookLanguage == null ? "" : bookLanguage;
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
        };
    }

    /** Read the active passages again (call at the start of each writing run). */
    public void refresh() {
        passages = null;
    }

    public SectionContextRequest requestFor(SectionWriteRequest request) {
        EvidenceSourceSet set = new EvidenceSourceSet(sources.checksums(), Collections.<String>emptyList(),
                Collections.<String>emptyList());
        return new SectionContextRequest(projectId, embeddingFingerprint, request.getChapter(), request.getSection(),
                set, request.getChapter().getBrief().getPurpose(), previousDigest(request),
                Collections.<String>emptyList(), bookLanguage);
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
