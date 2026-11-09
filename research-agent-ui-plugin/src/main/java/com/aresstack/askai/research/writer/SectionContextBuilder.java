package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the evidence pack of one section without a model call (plan PR 27). Candidates are the passages of the
 * section's provenance (in provenance order) followed by the hits of a semantic search on its brief, which is
 * embedded once as a {@link EmbeddingPort.Role#QUERY query}; no passage is embedded again. Only passages of sources
 * the {@link EvidenceSourceSet} admits count; passages are deduplicated by id and by text, at most two per source,
 * cut at sentence boundaries to the preset's passage size, and numbered {@code [1]..[k]} in that order. The same
 * store state always gives the same pack.
 */
public final class SectionContextBuilder {

    static final int MAX_PER_SOURCE = 2;
    static final int SYNOPSIS_TOKENS = 300;
    static final int DIGEST_TOKENS = 300;
    static final int GLOSSARY_TOKENS = 200;
    /** Semantic hits fetched per pack slot, so that filtered and capped sources still leave enough candidates. */
    static final int SEARCH_FACTOR = 4;
    /** The largest retrieval window; the window doubles up to it while filtering leaves the pack short. */
    static final int MAX_SEARCH_WINDOW = 4096;

    private final EmbeddingPort embeddings;
    private final SemanticKnowledgeIndex index;
    private final EvidencePassageLookup passages;
    private final AcronymGlossarySource acronyms;

    public SectionContextBuilder(EmbeddingPort embeddings, SemanticKnowledgeIndex index,
                                 EvidencePassageLookup passages) {
        this(embeddings, index, passages, AcronymGlossarySource.NONE);
    }

    /** With acronym lines from the sources (PR 38) appended to the glossary block, within its budget. */
    public SectionContextBuilder(EmbeddingPort embeddings, SemanticKnowledgeIndex index,
                                 EvidencePassageLookup passages, AcronymGlossarySource acronyms) {
        this.acronyms = acronyms == null ? AcronymGlossarySource.NONE : acronyms;
        if (embeddings == null || index == null || passages == null) {
            throw new IllegalArgumentException("the section context builder needs embeddings, index and passages");
        }
        this.embeddings = embeddings;
        this.index = index;
        this.passages = passages;
    }

    public EvidencePack build(SectionContextRequest request, WriterPackSettings settings) {
        WriterPackSettings pack = settings == null ? WriterPackSettings.SMALL : settings;
        OutlineSection section = request.getSection();
        EvidenceSourceSet sources = request.getSources();

        List<PassageSearchHit> provenance = new ArrayList<PassageSearchHit>();
        for (String passageId : section.getProvenance().getPassageIds()) {
            PassageSearchHit hit = passages.passage(request.getProjectId(), passageId);
            if (hit != null) {
                provenance.add(hit);
            }
        }
        List<EvidencePassage> chosen = select(provenance, sources, pack);
        float[] query = chosen.size() < pack.getPassageCount() ? embedBrief(request) : null;
        // Admission, deduplication and the per-source cap apply after retrieval, so the window grows until the
        // pack is full or the index has no further hits.
        for (int limit = pack.getPassageCount() * SEARCH_FACTOR;
             query != null && chosen.size() < pack.getPassageCount(); limit *= 2) {
            List<PassageSearchHit> hits = search(request, query, limit);
            List<PassageSearchHit> candidates = new ArrayList<PassageSearchHit>(provenance);
            candidates.addAll(hits);
            chosen = select(candidates, sources, pack);
            if (hits.size() < limit || limit >= MAX_SEARCH_WINDOW) {
                break;
            }
        }

        return new EvidencePack(section.getId(), pack, chosen,
                TokenBudget.cutAtSentence(request.getSynopsis(), SYNOPSIS_TOKENS),
                TokenBudget.cutAtSentence(request.getPreviousSectionDigest(), DIGEST_TOKENS),
                glossaryWithAcronyms(request.getGlossary(), chosen, sources), request.getBookLanguage());
    }

    private static List<EvidencePassage> select(List<PassageSearchHit> candidates, EvidenceSourceSet sources,
                                                WriterPackSettings pack) {
        List<EvidencePassage> chosen = new ArrayList<EvidencePassage>();
        Set<String> seenIds = new HashSet<String>();
        Set<String> seenTexts = new HashSet<String>();
        Map<String, Integer> perSource = new HashMap<String, Integer>();
        for (PassageSearchHit hit : candidates) {
            if (chosen.size() >= pack.getPassageCount()) {
                break;
            }
            String sourceId = hit.getSourceId();
            if (!sources.admits(sourceId) || !seenIds.add(hit.getPassageId())) {
                continue;
            }
            Integer used = perSource.get(sourceId);
            if (used != null && used >= MAX_PER_SOURCE) {
                continue;
            }
            String text = TokenBudget.cutAtSentence(hit.getText(), pack.getPassageTokens());
            if (text.isEmpty() || !seenTexts.add(text)) {
                continue;
            }
            perSource.put(sourceId, used == null ? 1 : used + 1);
            chosen.add(new EvidencePassage(chosen.size() + 1, hit.getPassageId(), sourceId,
                    sources.checksum(sourceId), text));
        }
        return chosen;
    }

    /** The brief as one query vector of the active world, or {@code null} when the brief is empty. */
    private float[] embedBrief(SectionContextRequest request) {
        String query = briefQuery(request.getChapter(), request.getSection());
        if (query.isEmpty()) {
            return null;
        }
        List<EmbeddingPort.EmbeddingVector> vectors = embeddings.embedQueries(Collections.singletonList(query));
        if (vectors.size() != 1) {
            throw new IllegalStateException("the embedding port returned " + vectors.size() + " vectors for one brief");
        }
        EmbeddingPort.EmbeddingVector vector = vectors.get(0);
        if (!request.getEmbeddingFingerprint().equals(vector.getModelFingerprint())) {
            throw new IllegalStateException("the brief was embedded in world " + vector.getModelFingerprint()
                    + ", the passages live in " + request.getEmbeddingFingerprint());
        }
        return vector.getValues();
    }

    private List<PassageSearchHit> search(SectionContextRequest request, float[] query, int limit) {
        List<PassageSearchHit> hits = new ArrayList<PassageSearchHit>(index.semanticSearch(request.getProjectId(),
                new PassageSemanticQuery(request.getEmbeddingFingerprint(), query, limit)));
        Collections.sort(hits, new Comparator<PassageSearchHit>() {
            @Override
            public int compare(PassageSearchHit a, PassageSearchHit b) {
                int byScore = Double.compare(b.getScore(), a.getScore());
                return byScore != 0 ? byScore : a.getPassageId().compareTo(b.getPassageId());
            }
        });
        return hits;
    }

    /** The text embedded as the section's query: chapter title, section title, purpose and key points. */
    static String briefQuery(OutlineEntry chapter, OutlineSection section) {
        StringBuilder out = new StringBuilder();
        if (chapter != null) {
            append(out, chapter.getTitle());
        }
        append(out, section.getTitle());
        OutlineBrief brief = section.getBrief();
        append(out, brief.getPurpose());
        for (String keyPoint : brief.getKeyPoints()) {
            append(out, keyPoint);
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(text);
        }
    }

    /** Concept labels first, then acronym lines of the chosen passages in the budget the labels leave. */
    private List<String> glossaryWithAcronyms(List<String> labels, List<EvidencePassage> chosen,
                                              EvidenceSourceSet sources) {
        List<String> glossary = glossary(labels);
        int chars = 0;
        for (String label : glossary) {
            chars += label.length() + 2;
        }
        int remainingTokens = GLOSSARY_TOKENS - (chars + TokenBudget.CHARS_PER_TOKEN - 1) / TokenBudget.CHARS_PER_TOKEN;
        if (remainingTokens <= 0 || chosen.isEmpty()) {
            return glossary;
        }
        List<String> texts = new ArrayList<String>();
        for (EvidencePassage passage : chosen) {
            texts.add(passage.getText());
        }
        for (String line : acronyms.lines(texts, sources.admittedSourceIds(), remainingTokens)) {
            if (!glossary.contains(line)) {
                glossary.add(line);
            }
        }
        return glossary;
    }

    /** Distinct labels in their given order, within the glossary budget. */
    static List<String> glossary(List<String> labels) {
        Map<String, Boolean> distinct = new LinkedHashMap<String, Boolean>();
        int chars = 0;
        for (String label : labels) {
            String value = label == null ? "" : label.trim();
            if (value.isEmpty() || distinct.containsKey(value)) {
                continue;
            }
            if (chars + value.length() + 2 > GLOSSARY_TOKENS * TokenBudget.CHARS_PER_TOKEN) {
                break;
            }
            chars += value.length() + 2;
            distinct.put(value, Boolean.TRUE);
        }
        return new ArrayList<String>(distinct.keySet());
    }
}
