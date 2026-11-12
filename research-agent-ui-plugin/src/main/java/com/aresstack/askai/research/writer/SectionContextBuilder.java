package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;

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
 * section's provenance followed by the hits of a semantic search on its brief, which is embedded once as a
 * {@link EmbeddingPort.Role#QUERY query}; no passage is embedded again. A provenance of more than one passage is
 * ranked by each passage's similarity to the brief: the pack takes only a few passages, and in the provenance's id
 * order the DGUV guidance of live run 4 (section 2.1, 361 provenance passages) never reached it. Both parts follow
 * the mission's source preference ({@link EvidenceSourceSet#preferenceShift}): a passage of a preferred publisher
 * moves up, a vendor page down, by the places the Rank shift setting gives. Only passages of sources the
 * {@link EvidenceSourceSet} admits count, and only prose: navigation, footer and heading passages fail the pack's
 * {@linkplain WriterPackSettings#getProse() prose criteria} and never reach the writer; passages are deduplicated by
 * id and by text, at most {@linkplain WriterPackSettings#getPassagesPerSource() passages per source} each, cut at
 * sentence boundaries to the preset's passage size, and numbered {@code [1]..[k]} in that order. The same store
 * state always gives the same pack.
 */
public final class SectionContextBuilder {

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
        // Several provenance passages compete for the pack's few places, so every one of them needs its score: the
        // first window then covers the whole index (the vector index scores every passage anyway).
        boolean rank = provenance.size() > 1;
        List<EvidencePassage> chosen = select(weighed(provenance, sources), sources, pack);
        float[] query = rank || chosen.size() < pack.getPassageCount() ? embedBrief(request) : null;
        // Admission, deduplication and the per-source cap apply after retrieval, so the window grows until the
        // pack is full or the index has no further hits.
        for (int limit = rank ? MAX_SEARCH_WINDOW : pack.getPassageCount() * SEARCH_FACTOR;
             query != null && (rank || chosen.size() < pack.getPassageCount()); limit *= 2) {
            List<PassageSearchHit> hits = search(request, query, limit);
            List<PassageSearchHit> candidates = weighed(byScore(provenance, hits), sources);
            candidates.addAll(weighed(outside(hits, provenance), sources));
            chosen = select(candidates, sources, pack);
            rank = false;
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
        ProsePassageFilter prose = new ProsePassageFilter(pack.getProse());
        for (PassageSearchHit hit : candidates) {
            if (chosen.size() >= pack.getPassageCount()) {
                break;
            }
            String sourceId = hit.getSourceId();
            if (!sources.admits(sourceId) || !seenIds.add(hit.getPassageId())) {
                continue;
            }
            Integer used = perSource.get(sourceId);
            if (used != null && used >= pack.getPassagesPerSource()) {
                continue;
            }
            String text = TokenBudget.cutAtSentence(hit.getText(), pack.getPassageTokens());
            if (text.isEmpty() || !prose.isProse(text) || !seenTexts.add(text)) {
                continue;
            }
            perSource.put(sourceId, used == null ? 1 : used + 1);
            chosen.add(new EvidencePassage(chosen.size() + 1, hit.getPassageId(), sourceId,
                    sources.checksum(sourceId), text));
        }
        return chosen;
    }

    /** The provenance by score among the hits, best first; a passage the hits miss keeps its place after them. */
    static List<PassageSearchHit> byScore(List<PassageSearchHit> provenance, List<PassageSearchHit> hits) {
        final Map<String, Double> scores = new HashMap<String, Double>();
        for (PassageSearchHit hit : hits) {
            scores.put(hit.getPassageId(), hit.getScore());
        }
        List<PassageSearchHit> ranked = new ArrayList<PassageSearchHit>(provenance);
        Collections.sort(ranked, new Comparator<PassageSearchHit>() { // stable: ties keep the provenance order
            @Override
            public int compare(PassageSearchHit a, PassageSearchHit b) {
                Double first = scores.get(a.getPassageId());
                Double second = scores.get(b.getPassageId());
                if (first == null || second == null) {
                    return first == null ? (second == null ? 0 : 1) : -1;
                }
                return Double.compare(second, first);
            }
        });
        return ranked;
    }

    /** The hits that are not part of the provenance, in their order. */
    private static List<PassageSearchHit> outside(List<PassageSearchHit> hits, List<PassageSearchHit> provenance) {
        Set<String> ids = new HashSet<String>();
        for (PassageSearchHit hit : provenance) {
            ids.add(hit.getPassageId());
        }
        List<PassageSearchHit> out = new ArrayList<PassageSearchHit>();
        for (PassageSearchHit hit : hits) {
            if (!ids.contains(hit.getPassageId())) {
                out.add(hit);
            }
        }
        return out;
    }

    /**
     * The candidates under the mission's source preference: each moves by its source's shift, a passage of a
     * preferred publisher up, a vendor page down; ties keep the given order.
     */
    static List<PassageSearchHit> weighed(List<PassageSearchHit> ordered, EvidenceSourceSet sources) {
        if (ordered.size() < 2 || !sources.hasPreferenceShifts()) {
            return new ArrayList<PassageSearchHit>(ordered);
        }
        final int[] weight = new int[ordered.size()];
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < ordered.size(); i++) {
            weight[i] = i + sources.preferenceShift(ordered.get(i).getSourceId());
            order.add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int byWeight = Integer.compare(weight[a], weight[b]);
                return byWeight != 0 ? byWeight : Integer.compare(a, b);
            }
        });
        List<PassageSearchHit> out = new ArrayList<PassageSearchHit>(ordered.size());
        for (Integer i : order) {
            out.add(ordered.get(i));
        }
        return out;
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
