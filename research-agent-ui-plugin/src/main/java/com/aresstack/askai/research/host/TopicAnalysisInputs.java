package com.aresstack.askai.research.host;

import com.aresstack.askai.research.book.FrozenConcept;
import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.knowledge.processing.live.TopicAnalysisRequest;
import com.aresstack.askai.research.knowledge.topic.ConceptAnchor;
import com.aresstack.askai.research.knowledge.topic.ScopeFence;
import com.aresstack.askai.research.outline.generate.ConceptFrame;
import com.aresstack.askai.research.outline.generate.OutlineSourceRule;
import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.store.FileConceptStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D6: the session's side of the shared topic analysis. It reads what the session owns (the approved concept below its
 * book frame, the eligible sources, the pinned search fence, the sources still being processed and the
 * {@code research.evidence.*} settings) and hands one {@link TopicAnalysisRequest} to {@link SharedTopicDiscovery};
 * corpus, clustering, measuring and snapshots stay there. The concept label paths are the only thing embedded (as
 * queries), once per path and vector world.
 */
public final class TopicAnalysisInputs implements SharedTopicDiscovery.AnalysisInputs {

    /** What the inputs read from the session. */
    public interface Source {
        /** The Phase-1 record, or {@code null} before the first committed {@code SUBMIT_SCOPE}. */
        FrozenConcept frozenConcept() throws Exception;

        FileConceptStore conceptStore();

        /** The approved title, "" when the concept has none (the mission is used then). */
        String approvedTitle(FrozenConcept frozen);

        /** The approved source set (sources pin); empty before one. */
        Collection<String> pinnedSourceIds() throws Exception;

        Collection<ResearchSourceRecord> sources();

        /** The pinned search fence, or {@code null} without one (the topics are then UNCLASSIFIED). */
        ScopeFence fence();

        /** Accepted sources still in the processing queue. */
        Set<String> pendingSourceIds();

        EvidenceAnalysisSettings settings();

        /** The embedding world of the stored passage vectors (the query cache key). */
        String embeddingFingerprint();
    }

    private final Source source;
    private final EmbeddingPort embeddings;
    private final Map<String, EmbeddingPort.EmbeddingVector> queryCache =
            new HashMap<String, EmbeddingPort.EmbeddingVector>();

    public TopicAnalysisInputs(Source source, EmbeddingPort embeddings) {
        this.source = source;
        this.embeddings = embeddings;
    }

    public TopicAnalysisRequest request() throws Exception {
        FrozenConcept frozen = source.frozenConcept();
        FileConceptStore store = source.conceptStore();
        if (frozen == null || store == null) {
            return null;
        }
        String title = source.approvedTitle(frozen);
        if (title == null || title.trim().isEmpty()) {
            title = anchorTitle(frozen.getMission());
        }
        ConceptFrame frame = ConceptFrame.of(title, OutlineGeneration.conceptNodes(frozen, store));
        Collection<ResearchSourceRecord> records = source.sources();
        Set<String> eligible = OutlineSourceRule.eligible(source.pinnedSourceIds(), records,
                TypedBlacklist.of(frozen.getPin().getBlacklist()));
        Map<String, String> domains = new LinkedHashMap<String, String>();
        for (ResearchSourceRecord record : records) {
            domains.put(record.getSourceId(), host(record.getUrl()));
        }
        EvidenceAnalysisSettings settings = source.settings();
        return new TopicAnalysisRequest(title, frame.hasFrame(), anchors(frame),
                frozen.getPin().getWorkingRevision(), source.fence(), eligible, domains, source.pendingSourceIds(),
                settings.getAnalysis(), settings.getKeyTerms(), settings.getMinimumCorpusPassages(),
                settings.getEveryNewPassages(), settings.getCorpusLimit(), proseOnly(settings.getProse()))
                .withFrame(frame.getFramePath(), frameVector(frame));
    }

    /** The query vector of the prefix every anchor path shares, or {@code null} when there is none. */
    private synchronized EmbeddingPort.EmbeddingVector frameVector(ConceptFrame frame) {
        String path = frame.getFramePath();
        if (path.isEmpty() || frame.nodes().isEmpty()) {
            return null;
        }
        String key = source.embeddingFingerprint() + "\n" + path;
        if (!queryCache.containsKey(key)) {
            List<EmbeddingPort.EmbeddingVector> vectors = embeddings.embedQueries(Collections.singletonList(path));
            if (vectors == null || vectors.size() != 1) {
                throw new IllegalStateException("The embedding model returned no vector for the book frame.");
            }
            queryCache.put(key, vectors.get(0));
        }
        return queryCache.get(key);
    }

    /**
     * Live run 4 (2026-11-12): navigation, contact, price and heading passages ("Email*", "+49 5251 60-0 Rechtliches
     * Impressum …", "CHF 69") reached the topic corpus and became topics of their own; the writer's evidence packs had
     * dropped them since live run 3. One rule decides for both: a passage that carries no statement is no material.
     */
    static TopicAnalysisRequest.PassageFilter proseOnly(ProsePassageFilter.Criteria criteria) {
        final ProsePassageFilter prose = new ProsePassageFilter(criteria);
        return new TopicAnalysisRequest.PassageFilter() {
            public boolean accepts(String text) {
                return prose.isProse(text);
            }

            @Override
            public java.util.Set<Integer> rejectedOnPage(List<String> page) {
                return prose.captions(page);
            }

            public String key() {
                return "prose(" + prose.getCriteria() + ")";
            }
        };
    }

    /** The concept nodes below the frame with their query vectors; each label path is embedded once. */
    private synchronized List<ConceptAnchor> anchors(ConceptFrame frame) {
        String world = source.embeddingFingerprint();
        List<String> missing = new ArrayList<String>();
        for (ConceptFrame.Node node : frame.nodes()) {
            if (!queryCache.containsKey(world + "\n" + node.getPath()) && !missing.contains(node.getPath())) {
                missing.add(node.getPath());
            }
        }
        if (!missing.isEmpty()) {
            List<EmbeddingPort.EmbeddingVector> vectors = embeddings.embedQueries(missing);
            if (vectors == null || vectors.size() != missing.size()) {
                throw new IllegalStateException("The embedding model returned "
                        + (vectors == null ? 0 : vectors.size()) + " vectors for " + missing.size()
                        + " concept label paths.");
            }
            for (int i = 0; i < missing.size(); i++) {
                queryCache.put(world + "\n" + missing.get(i), vectors.get(i));
            }
        }
        List<ConceptAnchor> anchors = new ArrayList<ConceptAnchor>();
        for (ConceptFrame.Node node : frame.nodes()) {
            anchors.add(new ConceptAnchor(node.getId(), node.getLabel(), node.getPath(), node.getDepth(),
                    node.getParentId(), queryCache.get(world + "\n" + node.getPath())));
        }
        return anchors;
    }

    /**
     * Without an approved title, the book title the mission quotes („…“, "…", »…«), or nothing. Live run 3
     * (2026-11-10) started every anchor path with the whole mission, so the anchors embedded to nearly the same
     * vector and almost no passage could be assigned; without a quoted title the concept's own labels are the anchors.
     */
    static String anchorTitle(String mission) {
        String title = com.aresstack.askai.research.plan.ConceptPlanNodes.quotedTitle(mission == null ? "" : mission);
        return title == null ? "" : title;
    }

    /** The host of a URL, without "www."; "" when it has none. */
    public static String host(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        try {
            String host = new java.net.URI(url.trim()).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (java.net.URISyntaxException invalid) {
            return "";
        }
    }
}
