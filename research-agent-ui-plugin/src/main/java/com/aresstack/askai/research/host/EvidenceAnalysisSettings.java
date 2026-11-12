package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;

/**
 * Phase 2/3: the settings of the shared topic analysis (D6): the topic-analysis knobs, the key terms per node or
 * area, and when the background run repeats during the research (a minimum corpus, then every n new passages; the
 * debounce is the knowledge projection's). Each value is read
 * from the session's state store under {@code research.evidence.*} with the default of its owner; the Outline
 * settings group edits them. Immutable.
 */
public final class EvidenceAnalysisSettings {

    public static final String KEY_DUPLICATE_DISTANCE = "research.evidence.duplicateDistance";
    public static final String KEY_SUBTOPIC_DISTANCE = "research.evidence.subtopicDistance";
    public static final String KEY_TOPIC_GROUP_DISTANCE = "research.evidence.topicGroupDistance";
    public static final String KEY_CONCEPT_THRESHOLD = "research.evidence.conceptThreshold";
    public static final String KEY_CONCEPT_MARGIN = "research.evidence.conceptMargin";
    public static final String KEY_MINIMUM_PASSAGES = "research.evidence.minimumPassages";
    public static final String KEY_MINIMUM_SOURCES = "research.evidence.minimumSources";
    public static final String KEY_MINIMUM_DOMAINS = "research.evidence.minimumDomains";
    public static final String KEY_MINIMUM_CHILD_COVERAGE = "research.evidence.minimumChildCoverage";
    public static final String KEY_MAXIMUM_DOMINANT_SHARE = "research.evidence.maximumDominantShare";
    public static final String KEY_MAJOR_TOPIC_SHARE = "research.evidence.majorTopicShare";
    public static final String KEY_REPRESENTATIVES = "research.evidence.representatives";
    public static final String KEY_TOPIC_DISTANCE = "research.evidence.topicDistance";
    public static final String KEY_KEY_TERMS = "research.evidence.keyTerms";
    public static final String KEY_MINIMUM_CORPUS = "research.evidence.minimumCorpusPassages";
    public static final String KEY_EVERY_NEW_PASSAGES = "research.evidence.everyNewPassages";

    /** Key terms per concept node or research area. */
    public static final int DEFAULT_KEY_TERMS = 5;
    /** The Sources phase analyses the evidence from this corpus size on ... */
    public static final int DEFAULT_MINIMUM_CORPUS = 50;
    /** ... and again after this many new passages. */
    public static final int DEFAULT_EVERY_NEW_PASSAGES = 100;

    private final TopicAnalysisSettings analysis;
    private final int keyTerms;
    private final int minimumCorpusPassages;
    private final int everyNewPassages;
    private final int corpusLimit;
    private final ProsePassageFilter.Criteria prose;

    public EvidenceAnalysisSettings(TopicAnalysisSettings analysis, int keyTerms, int minimumCorpusPassages,
                                    int everyNewPassages, int corpusLimit) {
        this(analysis, keyTerms, minimumCorpusPassages, everyNewPassages, corpusLimit,
                ProsePassageFilter.Criteria.defaults());
    }

    /**
     * @param prose which passages are material for the analysis: the writer's prose criteria (live run 3, fix 2), so
     *              navigation, contact and price lines are neither evidence nor topics (live run 4)
     */
    public EvidenceAnalysisSettings(TopicAnalysisSettings analysis, int keyTerms, int minimumCorpusPassages,
                                    int everyNewPassages, int corpusLimit, ProsePassageFilter.Criteria prose) {
        this.prose = prose == null ? ProsePassageFilter.Criteria.defaults() : prose;
        this.analysis = analysis == null ? TopicAnalysisSettings.defaults() : analysis;
        this.keyTerms = Math.max(1, keyTerms);
        this.minimumCorpusPassages = Math.max(2, minimumCorpusPassages);
        this.everyNewPassages = Math.max(1, everyNewPassages);
        this.corpusLimit = Math.max(2, corpusLimit);
    }

    public static EvidenceAnalysisSettings defaults() {
        return new EvidenceAnalysisSettings(TopicAnalysisSettings.defaults(), DEFAULT_KEY_TERMS,
                DEFAULT_MINIMUM_CORPUS, DEFAULT_EVERY_NEW_PASSAGES, SharedTopicDiscovery.DEFAULT_CORPUS_LIMIT);
    }

    /** The stored values; an unreadable or inconsistent value falls back to its default. */
    public static EvidenceAnalysisSettings load(WorkspaceStateStore store) {
        TopicAnalysisSettings analysis;
        try {
            analysis = new TopicAnalysisSettings(
                    ResearchRuntimeSettings.loadDouble(store, KEY_DUPLICATE_DISTANCE,
                            TopicAnalysisSettings.DEFAULT_DUPLICATE_DISTANCE),
                    ResearchRuntimeSettings.loadDouble(store, KEY_SUBTOPIC_DISTANCE,
                            TopicAnalysisSettings.DEFAULT_SUBTOPIC_DISTANCE),
                    ResearchRuntimeSettings.loadDouble(store, KEY_TOPIC_GROUP_DISTANCE,
                            TopicAnalysisSettings.DEFAULT_TOPIC_GROUP_DISTANCE),
                    ResearchRuntimeSettings.loadDouble(store, KEY_CONCEPT_THRESHOLD,
                            TopicAnalysisSettings.DEFAULT_CONCEPT_THRESHOLD),
                    ResearchRuntimeSettings.loadDouble(store, KEY_CONCEPT_MARGIN,
                            TopicAnalysisSettings.DEFAULT_CONCEPT_MARGIN),
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_MINIMUM_PASSAGES,
                            TopicAnalysisSettings.DEFAULT_MINIMUM_PASSAGES),
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_MINIMUM_SOURCES,
                            TopicAnalysisSettings.DEFAULT_MINIMUM_SOURCES),
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_MINIMUM_DOMAINS,
                            TopicAnalysisSettings.DEFAULT_MINIMUM_DOMAINS),
                    ResearchRuntimeSettings.loadDouble(store, KEY_MINIMUM_CHILD_COVERAGE,
                            TopicAnalysisSettings.DEFAULT_MINIMUM_CHILD_COVERAGE),
                    ResearchRuntimeSettings.loadDouble(store, KEY_MAXIMUM_DOMINANT_SHARE,
                            TopicAnalysisSettings.DEFAULT_MAXIMUM_DOMINANT_SHARE),
                    ResearchRuntimeSettings.loadDouble(store, KEY_MAJOR_TOPIC_SHARE,
                            TopicAnalysisSettings.DEFAULT_MAJOR_TOPIC_SHARE),
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_REPRESENTATIVES,
                            TopicAnalysisSettings.DEFAULT_REPRESENTATIVES),
                    ResearchRuntimeSettings.loadDouble(store, KEY_TOPIC_DISTANCE,
                            TopicAnalysisSettings.DEFAULT_TOPIC_DISTANCE));
        } catch (IllegalArgumentException inconsistent) {
            System.err.println("[evidence-analysis] settings ignored: " + inconsistent.getMessage());
            analysis = TopicAnalysisSettings.defaults();
        }
        return new EvidenceAnalysisSettings(analysis,
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_KEY_TERMS, DEFAULT_KEY_TERMS),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_MINIMUM_CORPUS, DEFAULT_MINIMUM_CORPUS),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_EVERY_NEW_PASSAGES, DEFAULT_EVERY_NEW_PASSAGES),
                SharedTopicDiscovery.DEFAULT_CORPUS_LIMIT, ResearchRuntimeSettings.loadWriterProseCriteria(store));
    }

    public TopicAnalysisSettings getAnalysis() {
        return analysis;
    }

    public int getKeyTerms() {
        return keyTerms;
    }

    public int getMinimumCorpusPassages() {
        return minimumCorpusPassages;
    }

    public int getEveryNewPassages() {
        return everyNewPassages;
    }

    public int getCorpusLimit() {
        return corpusLimit;
    }

    /** The prose criteria a passage must meet to be material for the analysis (the writer's, one rule). */
    public ProsePassageFilter.Criteria getProse() {
        return prose;
    }

}
