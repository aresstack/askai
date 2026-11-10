package com.aresstack.askai.research.host;

import com.aresstack.askai.research.book.FrozenConcept;
import com.aresstack.askai.research.concept.ConceptIdentity;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.knowledge.processing.live.TopicAnalysisSnapshot;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineJobGuard;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.outline.generate.ConceptOutlineNode;
import com.aresstack.askai.research.outline.generate.OutlineProvenanceMerge;
import com.aresstack.askai.research.outline.synthesis.OutlineAssembler;
import com.aresstack.askai.research.outline.synthesis.OutlineProposer;
import com.aresstack.askai.research.outline.synthesis.OutlineQualityCheck;
import com.aresstack.askai.research.outline.synthesis.OutlineSynthesisSettings;
import com.aresstack.askai.research.outline.synthesis.OutlineSynthesizer;
import com.aresstack.askai.research.outline.synthesis.SynthesisBrief;
import com.aresstack.askai.research.outline.synthesis.SynthesisContext;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.store.FileConceptStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * "Generate outline" in {@code outline/running} (Phase 3, D6/D6a). Off the UI thread, with the progress and Cancel
 * of the drawer's {@link OutlineClusteringRun}, under the session's {@link OutlineJobGuard} (so {@code finish-outline}
 * is refused meanwhile):
 * <ol>
 * <li>take ONE topic-analysis snapshot from the shared analysis ({@link SharedTopicDiscovery#analysisFor}); Phase 3
 * never segments, embeds or clusters itself;</li>
 * <li>the compact brief from the snapshot and the approved concept ({@link SynthesisBrief});</li>
 * <li>the synthesis on {@code phase.outline} with the quality check between the rounds
 * ({@link OutlineSynthesizer}, {@link OutlineQualityCheck});</li>
 * <li>the safe fixes, the final review and the outline proposal with word budgets, provenance and the report
 * ({@link OutlineAssembler});</li>
 * <li>labels and briefs ({@link Enrichment}), then the merge into the stored outline by passage overlap
 * ({@link OutlineProvenanceMerge}): edited entries are kept and marked when their research changed.</li>
 * </ol>
 */
public final class OutlineGeneration {

    /** What the generation reads from the session. */
    public interface Inputs {
        OutlineWorkspace workspace();

        /** The Phase-1 record, or {@code null} before the first committed {@code SUBMIT_SCOPE}. */
        FrozenConcept frozenConcept() throws Exception;

        /** The approved book title (or the mission without one). */
        String bookTitle(FrozenConcept frozen);

        /** The shared topic analysis, or {@code null} without the knowledge pipeline. */
        SharedTopicDiscovery discovery();

        OutlineGenerationSettings settings();

        /** The chat on {@code phase.outline}, or {@code null} without a model (the plan then comes from the analysis). */
        MainModelChat synthesisChat(OutlineSynthesisSettings settings);

        long now();
    }

    private final Inputs inputs;
    private final OutlineClusteringRun run;
    private final Executor executor;
    /** Plan PR 24: the label and brief pass after the proposal; {@code null} = none. */
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

    public OutlineGeneration(Inputs inputs, OutlineClusteringRun run, Executor executor) {
        this.inputs = inputs;
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
            Proposal proposal = propose(monitor);
            Outline outline = proposal.outline;
            Enrichment labelsAndBriefs = enrichment;
            if (labelsAndBriefs != null) {
                monitor.progress("Writing chapter labels and briefs", 0, 1);
                outline = labelsAndBriefs.enrich(outline, monitor::isCancelled);
                checkCancel(monitor);
            }
            workspace.save(OutlineProvenanceMerge.merge(stored, outline, proposal.settings.getMergeOverlap()),
                    expected, inputs.now());
            System.err.println("[research-outline] generated chapters=" + proposal.outline.getChapters().size()
                    + " snapshot=" + proposal.snapshot.getId() + " gaps="
                    + proposal.outline.getReport().getGaps().size() + " findings="
                    + proposal.outline.getReport().getFindings().size() + " fromAnalysisOnly="
                    + proposal.outline.getReport().isFromAnalysisOnly()
                    + " omitted=" + proposal.snapshot.getOmittedPassages());
            monitor.finished(proposal.snapshot.getOmittedPassages());
        } catch (ClusteringMonitor.ClusteringCancelledException cancelled) {
            monitor.cancelled();
        } catch (Exception failed) {
            String message = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
            System.err.println("[research-outline] generation failed: " + message);
            monitor.failed(message);
        }
    }

    /** The proposal of one generation, before labels, briefs and the merge. */
    static final class Proposal {
        final TopicAnalysisSnapshot snapshot;
        final OutlineGenerationSettings settings;
        final Outline outline;

        Proposal(TopicAnalysisSnapshot snapshot, OutlineGenerationSettings settings, Outline outline) {
            this.snapshot = snapshot;
            this.settings = settings;
            this.outline = outline;
        }
    }

    Proposal propose(ClusteringMonitor monitor) throws Exception {
        FrozenConcept frozen = inputs.frozenConcept();
        if (frozen == null) {
            throw new IllegalStateException("There is no approved concept to build the outline from.");
        }
        SharedTopicDiscovery discovery = inputs.discovery();
        if (discovery == null) {
            throw new IllegalStateException("The knowledge pipeline is not available, so there is no topic analysis "
                    + "to build the outline from.");
        }
        monitor.progress("Taking the topic analysis", 0, 1);
        TopicAnalysisSnapshot snapshot = discovery.analysisFor(monitor);
        checkCancel(monitor);
        OutlineGenerationSettings settings = inputs.settings();
        SynthesisContext context = new SynthesisContext(inputs.bookTitle(frozen), frozen.getMission(),
                frozen.getDeliverable(), frozen.getLanguage(), frozen.getPin().getBlacklist());
        final TopicAnalysisSnapshot texts = snapshot;
        SynthesisBrief brief = SynthesisBrief.of(context, snapshot.analysis(), new SynthesisBrief.Texts() {
            public String text(String passageId) {
                return texts.text(passageId);
            }
        }, settings.getSynthesis());
        List<String> notes = new ArrayList<String>();
        if (!snapshot.isSettled()) {
            notes.add(snapshot.getPendingSourceIds().size() + " sources were still being processed when the topic "
                    + "analysis was taken; generate again once they are done to include them.");
        }
        Outline outline = new OutlineProposer(inputs.synthesisChat(settings.getSynthesis()), settings.getSynthesis(),
                settings.getLength(), new OutlineSynthesizer.Telemetry() {
                    public void call(String step, String model, int budget, int attempt, boolean ok, String detail) {
                        System.err.println("[outline-synthesis] step=" + step + " model=" + model + " budget="
                                + budget + " attempt=" + attempt + " ok=" + ok + " " + detail);
                    }
                }).propose(brief, snapshot.getId(), notes, monitor);
        return new Proposal(snapshot, settings, outline);
    }

    private static void checkCancel(ClusteringMonitor monitor) {
        if (monitor.isCancelled()) {
            throw new ClusteringMonitor.ClusteringCancelledException();
        }
    }

    /**
     * The approved concept's first-level nodes with their stable ids (the frozen working revision's identity); the
     * topic analysis (D6) reads the concept through this one method.
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
}
