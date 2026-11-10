package com.aresstack.askai.research.host;

import com.aresstack.askai.research.book.FrozenConcept;
import com.aresstack.askai.research.concept.ConceptIdentity;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;
import com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader;
import com.aresstack.askai.research.knowledge.processing.live.CorpusLimit;
import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineJobGuard;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.outline.generate.ConceptOutlineNode;
import com.aresstack.askai.research.outline.generate.ConceptSeededOutlineBuilder;
import com.aresstack.askai.research.outline.generate.OutlineGenerationInput;
import com.aresstack.askai.research.outline.generate.OutlineProvenanceMerge;
import com.aresstack.askai.research.outline.generate.OutlineSourceRule;
import com.aresstack.askai.research.plan.ConceptPlanNodes;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.store.FileConceptStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Plan PR 22: "Generate outline" in {@code outline/running}. Off the UI thread, with the progress and Cancel of the
 * drawer's {@link OutlineClusteringRun}, under the session's {@link OutlineJobGuard} (so {@code finish-outline} is
 * refused meanwhile): read the eligible corpus (D4), cluster it (PR 20), build the concept-seeded proposal without a
 * model call and merge it into the stored outline by provenance.
 */
public final class OutlineGeneration {

    /** What the generation reads from the session. */
    public interface Inputs {
        OutlineWorkspace workspace();

        /** The Phase-1 record, or {@code null} before the first committed {@code SUBMIT_SCOPE}. */
        FrozenConcept frozenConcept() throws Exception;

        FileConceptStore conceptStore();

        /** The source ids of the approved source set (sources pin); empty before one. */
        Collection<String> pinnedSourceIds() throws Exception;

        Collection<ResearchSourceRecord> sources();

        long now();
    }

    private final Inputs inputs;
    private final ActiveKnowledgeCorpusReader corpus;
    private final EmbeddingPort embeddings;
    /** Read at each start, so a changed threshold or margin applies to the next generation without a restart. */
    private final java.util.function.Supplier<OutlineGenerationInput.Settings> settings;
    private final OutlineClusteringRun run;
    private final Executor executor;
    /** Plan PR 24: the label and brief pass after the model-free proposal; {@code null} = none. */
    private volatile Enrichment enrichment;

    /** Plan PR 24: labels and briefs for a fresh proposal (the session binds the model slots). */
    public interface Enrichment {
        /**
         * Checked before a generation starts: {@code null} when the labels and briefs can run, otherwise why not
         * (issue #152: a broken role declaration must refuse the start instead of failing after "started").
         */
        String refusal();

        Outline enrich(Outline generated, java.util.function.BooleanSupplier cancelled);
    }

    public void setEnrichment(Enrichment enrichment) {
        this.enrichment = enrichment;
    }

    public OutlineGeneration(Inputs inputs, ActiveKnowledgeCorpusReader corpus, EmbeddingPort embeddings,
                             java.util.function.Supplier<OutlineGenerationInput.Settings> settings,
                             OutlineClusteringRun run, Executor executor) {
        this.inputs = inputs;
        this.corpus = corpus;
        this.embeddings = embeddings;
        this.settings = settings;
        this.run = run;
        this.executor = executor;
    }

    /**
     * Start a generation.
     * @return {@code null} when it was started, otherwise the reason it was refused
     */
    public String start() {
        final OutlineWorkspace workspace = inputs.workspace();
        if (workspace == null) {
            return "This session has no outline store.";
        }
        String refusal = workspace.writeRefusal();
        if (refusal != null) {
            return "Generate outline runs only while the outline phase is open. " + refusal;
        }
        Enrichment labelsAndBriefs = enrichment;
        if (labelsAndBriefs != null) {
            String notReady = labelsAndBriefs.refusal();
            if (notReady != null) {
                return "The outline generation cannot start: " + notReady;
            }
        }
        final OutlineJobGuard.Job job;
        try {
            job = workspace.jobs().begin("the outline generation");
        } catch (IllegalStateException busy) {
            return busy.getMessage();
        }
        run.started();
        try {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        generate(workspace, run);
                    } finally {
                        job.close();
                    }
                }
            });
        } catch (RuntimeException rejected) {
            job.close();
            run.failed(rejected.getMessage());
            return "The outline generation could not start: " + rejected.getMessage();
        }
        return null;
    }

    /** One generation on the calling thread; reports its outcome through {@code monitor}. */
    void generate(OutlineWorkspace workspace, OutlineClusteringRun monitor) {
        try {
            long expected = workspace.workingRevision();
            Outline stored = workspace.current();
            Prepared prepared = prepare(monitor);
            ConceptSeededOutlineBuilder.Result result =
                    new ConceptSeededOutlineBuilder(embeddings).build(prepared.input, monitor);
            if (monitor.isCancelled()) {
                throw new ClusteringMonitor.ClusteringCancelledException();
            }
            Outline proposal = result.outline;
            Enrichment labelsAndBriefs = enrichment;
            if (labelsAndBriefs != null) {
                monitor.progress("Writing chapter labels and briefs", 0, 1);
                proposal = labelsAndBriefs.enrich(proposal, monitor::isCancelled);
                if (monitor.isCancelled()) {
                    throw new ClusteringMonitor.ClusteringCancelledException();
                }
            }
            workspace.save(OutlineProvenanceMerge.merge(stored, proposal), expected, inputs.now());
            System.err.println("[research-outline] generated chapters=" + result.outline.getChapters().size()
                    + " assigned=" + result.assignedPassages + " unassigned=" + result.unassignedPassages
                    + " (below threshold " + result.belowThreshold + ", margin too small " + result.marginTooSmall
                    + ", median best similarity " + String.format(java.util.Locale.ROOT, "%.3f",
                            result.medianBestSimilarity) + ", median margin " + String.format(
                            java.util.Locale.ROOT, "%.3f", result.medianMargin) + ") title=\""
                    + prepared.input.getBookTitle() + "\""
                    + " candidates=" + result.outline.getCandidates().size()
                    + " omitted=" + prepared.omitted);
            monitor.finished(prepared.omitted);
        } catch (ClusteringMonitor.ClusteringCancelledException cancelled) {
            monitor.cancelled();
        } catch (Exception failed) {
            String message = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
            System.err.println("[research-outline] generation failed: " + message);
            monitor.failed(message);
        }
    }

    private static final class Prepared {
        final OutlineGenerationInput input;
        final int omitted;

        Prepared(OutlineGenerationInput input, int omitted) {
            this.input = input;
            this.omitted = omitted;
        }
    }

    /**
     * The approved concept's first-level nodes with their stable ids (the frozen working revision's identity); the
     * outline generation and the evidence analysis (D6) read the concept through this one method.
     */
    public static List<ConceptOutlineNode> conceptNodes(FrozenConcept frozen, FileConceptStore store)
            throws Exception {
        long workingRevision = frozen.getPin().getWorkingRevision();
        String identityJson = store.identityHistoryContent(workingRevision);
        String workingDocument = store.workingHistoryContent(workingRevision);
        ConceptIdentity identity = identityJson == null ? null : ConceptIdentity.parse(identityJson);
        JsonElement identityRoot = workingDocument == null ? null : JsonParser.parseString(workingDocument);
        return ConceptOutlineNode.firstLevel(frozen.getEffectiveDocument(), identity, identityRoot);
    }

    private Prepared prepare(ClusteringMonitor monitor) throws Exception {
        FrozenConcept frozen = inputs.frozenConcept();
        FileConceptStore store = inputs.conceptStore();
        if (frozen == null || store == null) {
            throw new IllegalStateException("There is no approved concept to build the outline from.");
        }
        List<ConceptOutlineNode> chapters = conceptNodes(frozen, store);

        final Set<String> eligible = OutlineSourceRule.eligible(inputs.pinnedSourceIds(), inputs.sources(),
                TypedBlacklist.of(frozen.getPin().getBlacklist()));
        ActiveKnowledgeCorpusReader.Corpus whole = corpus.read(new ActiveKnowledgeCorpusReader.SourceFilter() {
            public boolean includeSource(String sourceId) {
                return eligible.contains(sourceId);
            }

            public boolean isUserRelevant(String sourceId) {
                return false;
            }
        });
        ActiveKnowledgeCorpusReader.Corpus limited = CorpusLimit.apply(whole,
                SharedTopicDiscovery.DEFAULT_CORPUS_LIMIT);
        List<LiveTopicProjection> topics = new LiveOutlineProjectionBuilder()
                .discoverTopics(limited.getPassages(), limited.getVectors(), monitor);
        List<OutlineGenerationInput.Cluster> clusters = new ArrayList<OutlineGenerationInput.Cluster>();
        for (LiveTopicProjection topic : topics) {
            clusters.add(new OutlineGenerationInput.Cluster(topic.getClusterId(), topic.getTitle(),
                    topic.getMemberPassageIds(), topic.getRepresentativePassageIds()));
        }
        // Assignment covers the whole eligible corpus; only clustering is limited.
        List<OutlineGenerationInput.CorpusPassage> passages = new ArrayList<OutlineGenerationInput.CorpusPassage>();
        for (Passage passage : whole.getPassages()) {
            passages.add(new OutlineGenerationInput.CorpusPassage(passage.getPassageId(),
                    whole.sourceIdOf(passage.getPassageId()), passage.getText(),
                    whole.getVectors().get(passage.getPassageId())));
        }
        String mission = frozen.getMission();
        return new Prepared(new OutlineGenerationInput(bookTitle(mission), mission, frozen.getDeliverable(),
                chapters, passages, clusters, settings.get()),
                whole.getPassages().size() - limited.getPassages().size());
    }

    /**
     * The first part of every anchor's label path: the book title the mission quotes („…“, "…", »…«), or nothing.
     * Live run 3 (2026-11-10) used the whole mission here, so every anchor path started with the same long
     * paragraph, the anchors embedded to nearly the same vector and the margin rule left 2063 of 2068 passages
     * unassigned. A mission without a quoted title gets no prefix: the concept's own labels are the anchors.
     */
    static String bookTitle(String mission) {
        String title = ConceptPlanNodes.quotedTitle(mission == null ? "" : mission);
        return title == null ? "" : title;
    }
}
