package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.knowledge.topic.ConceptAnchor;
import com.aresstack.askai.research.knowledge.topic.ScopeFence;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * D6: what one run of the shared topic analysis reads besides the corpus: the concept nodes below the book frame with
 * their query vectors, the pinned search fence, the eligible sources, each source's domain, the sources still being
 * processed, and the settings. The host assembles it (it owns the concept, the scope and the settings); the
 * discovery owns the corpus, the clustering and the snapshots. Immutable.
 */
public final class TopicAnalysisRequest {

    /**
     * Which passages are material at all: page boilerplate (navigation, contact lines, prices, bare headings) is no
     * topic. The host passes the same prose rule the writer's evidence packs use, so one rule decides what counts.
     */
    public interface PassageFilter {
        /** Every passage counts. */
        PassageFilter ALL = new PassageFilter() {
            public boolean accepts(String text) {
                return true;
            }

            public String key() {
                return "all";
            }
        };

        boolean accepts(String text);

        /** The rule's values, for the snapshot id: a changed rule is a new state. */
        String key();
    }

    private final String bookTitle;
    private final boolean bookFrame;
    private final List<ConceptAnchor> anchors;
    private final long conceptRevision;
    private final ScopeFence fence;
    private final Set<String> eligibleSourceIds;
    private final Map<String, String> domainBySource;
    private final Set<String> pendingSourceIds;
    private final TopicAnalysisSettings settings;
    private final int keyTerms;
    private final int minimumCorpusPassages;
    private final int everyNewPassages;
    private final int corpusLimit;
    private final PassageFilter passageFilter;
    private final String framePath;
    private final com.aresstack.askai.research.knowledge.EmbeddingPort.EmbeddingVector frame;

    /**
     * @param eligibleSourceIds the sources the analysis reads (D4); {@code null} = every source of the corpus filter
     * @param fence             {@code null} when no fence is negotiated or none can be read
     */
    public TopicAnalysisRequest(String bookTitle, boolean bookFrame, List<ConceptAnchor> anchors, long conceptRevision,
                                ScopeFence fence, Set<String> eligibleSourceIds, Map<String, String> domainBySource,
                                Set<String> pendingSourceIds, TopicAnalysisSettings settings, int keyTerms,
                                int minimumCorpusPassages, int everyNewPassages, int corpusLimit) {
        this(bookTitle, bookFrame, anchors, conceptRevision, fence, eligibleSourceIds, domainBySource,
                pendingSourceIds, settings, keyTerms, minimumCorpusPassages, everyNewPassages, corpusLimit,
                PassageFilter.ALL);
    }

    /** @param passageFilter which passages are material; {@code null} = every passage */
    public TopicAnalysisRequest(String bookTitle, boolean bookFrame, List<ConceptAnchor> anchors, long conceptRevision,
                                ScopeFence fence, Set<String> eligibleSourceIds, Map<String, String> domainBySource,
                                Set<String> pendingSourceIds, TopicAnalysisSettings settings, int keyTerms,
                                int minimumCorpusPassages, int everyNewPassages, int corpusLimit,
                                PassageFilter passageFilter) {
        this(bookTitle, bookFrame, anchors, conceptRevision, fence, eligibleSourceIds, domainBySource,
                pendingSourceIds, settings, keyTerms, minimumCorpusPassages, everyNewPassages, corpusLimit,
                passageFilter, "", null);
    }

    private TopicAnalysisRequest(String bookTitle, boolean bookFrame, List<ConceptAnchor> anchors,
                                 long conceptRevision, ScopeFence fence, Set<String> eligibleSourceIds,
                                 Map<String, String> domainBySource, Set<String> pendingSourceIds,
                                 TopicAnalysisSettings settings, int keyTerms, int minimumCorpusPassages,
                                 int everyNewPassages, int corpusLimit, PassageFilter passageFilter,
                                 String framePath,
                                 com.aresstack.askai.research.knowledge.EmbeddingPort.EmbeddingVector frame) {
        this.framePath = framePath == null ? "" : framePath;
        this.frame = frame;
        this.passageFilter = passageFilter == null ? PassageFilter.ALL : passageFilter;
        this.bookTitle = bookTitle == null ? "" : bookTitle.trim();
        this.bookFrame = bookFrame;
        this.anchors = Collections.unmodifiableList(new ArrayList<ConceptAnchor>(
                anchors == null ? Collections.<ConceptAnchor>emptyList() : anchors));
        this.conceptRevision = conceptRevision;
        this.fence = fence;
        this.eligibleSourceIds = eligibleSourceIds == null ? null
                : Collections.unmodifiableSet(new TreeSet<String>(eligibleSourceIds));
        this.domainBySource = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                domainBySource == null ? Collections.<String, String>emptyMap() : domainBySource));
        this.pendingSourceIds = Collections.unmodifiableSet(new TreeSet<String>(
                pendingSourceIds == null ? Collections.<String>emptySet() : pendingSourceIds));
        this.settings = settings == null ? TopicAnalysisSettings.defaults() : settings;
        this.keyTerms = Math.max(1, keyTerms);
        this.minimumCorpusPassages = Math.max(2, minimumCorpusPassages);
        this.everyNewPassages = Math.max(1, everyNewPassages);
        if (corpusLimit < 2) {
            throw new IllegalArgumentException("corpus limit must be at least 2: " + corpusLimit);
        }
        this.corpusLimit = corpusLimit;
    }

    /**
     * The same request with the book frame: the prefix every anchor path shares and its query vector (see
     * {@link com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings#DEFAULT_CONCEPT_FRAME_LEAD}).
     */
    public TopicAnalysisRequest withFrame(String path,
                                          com.aresstack.askai.research.knowledge.EmbeddingPort.EmbeddingVector vector) {
        return new TopicAnalysisRequest(bookTitle, bookFrame, anchors, conceptRevision, fence, eligibleSourceIds,
                domainBySource, pendingSourceIds, settings, keyTerms, minimumCorpusPassages, everyNewPassages,
                corpusLimit, passageFilter, path, vector);
    }

    /** The vector of the book frame, or {@code null} when the anchor paths share no prefix. */
    public com.aresstack.askai.research.knowledge.EmbeddingPort.EmbeddingVector getFrame() {
        return frame;
    }

    public String getBookTitle() {
        return bookTitle;
    }

    /** Whether the concept's single first-level node is the book frame (its children are the main themes). */
    public boolean hasBookFrame() {
        return bookFrame;
    }

    public List<ConceptAnchor> getAnchors() {
        return anchors;
    }

    public long getConceptRevision() {
        return conceptRevision;
    }

    public ScopeFence getFence() {
        return fence;
    }

    /** {@code null} = every source the corpus filter lets through. */
    public Set<String> getEligibleSourceIds() {
        return eligibleSourceIds;
    }

    /** The domain of a source ("" when unknown: the analysis then counts the source as its own domain). */
    public String domainOf(String sourceId) {
        String domain = domainBySource.get(sourceId);
        return domain == null ? "" : domain;
    }

    public Set<String> getPendingSourceIds() {
        return pendingSourceIds;
    }

    public TopicAnalysisSettings getSettings() {
        return settings;
    }

    public int getKeyTerms() {
        return keyTerms;
    }

    /** A background run starts from this many eligible passages ... */
    public int getMinimumCorpusPassages() {
        return minimumCorpusPassages;
    }

    /** ... and repeats after this many new ones. */
    public int getEveryNewPassages() {
        return everyNewPassages;
    }

    public int getCorpusLimit() {
        return corpusLimit;
    }

    public PassageFilter getPassageFilter() {
        return passageFilter;
    }

    /** Every value that changes the result besides corpus and embedding world, for the snapshot id. */
    String fingerprint() {
        StringBuilder key = new StringBuilder();
        key.append(bookTitle).append('|').append(bookFrame).append('|').append(conceptRevision).append('|');
        for (ConceptAnchor anchor : anchors) {
            key.append(anchor.getId()).append('=').append(anchor.getPath()).append(';');
        }
        key.append("|frame=").append(frame == null ? "" : framePath);
        key.append('|').append(fence == null ? "no-fence" : "fence-" + fence.getRevision() + "-"
                + fence.getAnchors().size());
        TopicAnalysisSettings s = settings;
        key.append('|').append(s.getDuplicateDistance()).append('/').append(s.getSubtopicDistance()).append('/')
                .append(s.getTopicGroupDistance()).append('/').append(s.getConceptThreshold()).append('/')
                .append(s.getConceptMargin()).append('/').append(s.getConceptFrameLead()).append('/')
                .append(s.getMinimumPassages()).append('/')
                .append(s.getMinimumSources()).append('/').append(s.getMinimumDomains()).append('/')
                .append(s.getMinimumChildCoverage()).append('/').append(s.getMaximumDominantShare()).append('/')
                .append(s.getMajorTopicShare()).append('/').append(s.getRepresentatives()).append('/')
                .append(s.getTopicDistance()).append('/')
                .append(keyTerms).append('/').append(corpusLimit);
        // a queue change is a new state: the snapshot must not report "settled" while sources are still pending
        key.append("|passages=").append(passageFilter.key());
        key.append("|pending=").append(new java.util.TreeSet<String>(pendingSourceIds));
        return key.toString();
    }
}
