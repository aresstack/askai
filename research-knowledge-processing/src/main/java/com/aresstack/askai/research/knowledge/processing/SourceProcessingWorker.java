package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.PassageSegmentation;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates one accepted capture through the CANONICAL knowledge pipeline: read the capture as a domain
 * {@link SourceCapture}, run {@link PassageSegmentation} (sentence segmentation → semantic passages → each
 * passage's own embedding — all inside the pipeline), then persist the sentences and passages via {@link
 * PassageStore}. It owns NO algorithm and NO domain model, and knows nothing of Swing, the state machine or
 * {@code ResearchLoop}. Failures are attributed to a {@link SourceProcessingStage} (§24): a missing capture is
 * permanent; a transient pipeline error is retryable and the job is re-queued at the tail (bounded by
 * {@code maxAttempts}), so one failing job never blocks the FIFO for the others.
 *
 * <p>Variant B (deliberate): the productive daemon is NOT started until the semantic index (C4) exists — a
 * {@code COMPLETED} job then honestly means the whole pipeline ran. This class is exercised behind test doubles
 * meanwhile.</p>
 */
public final class SourceProcessingWorker {

    /**
     * Observation seam (diagnostics §29 / UI events §28). A no-op default keeps the worker pure.
     * {@code sentenceCount} is {@link #RESUMED} when a job was resumed straight at the indexing stage (the
     * sentences were persisted by an earlier attempt and were not recomputed).
     */
    public interface Listener {
        /** Sentinel sentence count reported when a job resumed at indexing (no NLP/embedding recompute). */
        int RESUMED = -1;

        void onStarted(SourceProcessingJob job);

        void onCompleted(SourceProcessingJob job, int sentenceCount, int passageCount);

        void onFailed(SourceProcessingJob job, SourceProcessingFailure failure);

        Listener NONE = new Listener() {
            public void onStarted(SourceProcessingJob job) {
            }

            public void onCompleted(SourceProcessingJob job, int sentenceCount, int passageCount) {
            }

            public void onFailed(SourceProcessingJob job, SourceProcessingFailure failure) {
            }
        };
    }

    /**
     * Builds the {@link PassageSegmentation} for ONE job's immutable language snapshot. A running job keeps
     * the instance it was given for its whole duration; the NEXT job after a language switch gets the new
     * language's sentence model. The neutral seam keeps OpenNLP/host resolution out of this module — the
     * composition root adapts the session's NLP snapshot provider (with its documented regex-fallback /
     * hard-failure semantics) behind it and caches per language.
     */
    public interface SegmentationFactory {
        PassageSegmentation forLanguage(String languageCode);

        /** A language-blind factory over one fixed segmentation (legacy/tests). */
        static SegmentationFactory fixed(final PassageSegmentation segmentation) {
            return new SegmentationFactory() {
                public PassageSegmentation forLanguage(String languageCode) {
                    return segmentation;
                }
            };
        }
    }

    private final SourceProcessingQueue queue;
    private final SourceCaptureReader captureReader;
    private final SegmentationFactory segmentationFactory;
    private final PassageStore passageStore;
    private final SemanticKnowledgeIndex index;
    private final IndexableGenerationSource persistedGenerations;
    private final String projectId;
    private final int maxAttempts;
    /** The embedding-world fingerprint this worker's {@link PassageSegmentation} actually produces (§4.3). */
    private final String activeEmbeddingFingerprint;
    private final Listener listener;

    /** Language-blind legacy form: one fixed segmentation serves every job (tests / pre-language callers). */
    public SourceProcessingWorker(SourceProcessingQueue queue, SourceCaptureReader captureReader,
                                  PassageSegmentation passageSegmentation, PassageStore passageStore,
                                  SemanticKnowledgeIndex index, IndexableGenerationSource persistedGenerations,
                                  String projectId, int maxAttempts, String activeEmbeddingFingerprint,
                                  Listener listener) {
        this(queue, captureReader, SegmentationFactory.fixed(passageSegmentation), passageStore, index,
                persistedGenerations, projectId, maxAttempts, activeEmbeddingFingerprint, listener);
    }

    public SourceProcessingWorker(SourceProcessingQueue queue, SourceCaptureReader captureReader,
                                  SegmentationFactory segmentationFactory, PassageStore passageStore,
                                  SemanticKnowledgeIndex index, IndexableGenerationSource persistedGenerations,
                                  String projectId, int maxAttempts, String activeEmbeddingFingerprint,
                                  Listener listener) {
        if (activeEmbeddingFingerprint == null || activeEmbeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("activeEmbeddingFingerprint must be the resolved world "
                    + "fingerprint of this worker's embedding pipeline");
        }
        if (index == null || persistedGenerations == null) {
            throw new IllegalArgumentException("a semantic index and a persisted-generation source are "
                    + "required — a job is COMPLETED only after a successful index update");
        }
        this.queue = queue;
        this.captureReader = captureReader;
        this.segmentationFactory = segmentationFactory;
        this.passageStore = passageStore;
        this.index = index;
        this.persistedGenerations = persistedGenerations;
        this.projectId = projectId;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.activeEmbeddingFingerprint = activeEmbeddingFingerprint.trim();
        this.listener = listener == null ? Listener.NONE : listener;
    }

    /** Process the next QUEUED job. @return true if one was handled (completed/failed), false if none. */
    public boolean processOne() {
        SourceProcessingJob job = queue.takeNext();
        if (job == null) {
            return false;
        }
        // Embedding-world guard (§4.3): a job created for a DIFFERENT vector world (e.g. queued under model A,
        // the session now runs model B) must NEVER be run with this worker's pipeline and stored under the
        // old-world key. Retire it unprocessed and re-enqueue the capture for the ACTIVE world instead — the
        // capture is still derived, honestly, for the world the session actually embeds in.
        if (!activeEmbeddingFingerprint.equals(job.getRequest().getEmbeddingModelFingerprint())) {
            SourceProcessingRequest r = job.getRequest();
            // The job's LANGUAGE snapshot survives the re-enqueue: only the embedding world is re-targeted.
            queue.enqueue(new SourceProcessingRequest(r.getCaptureId(), r.getSourceId(),
                    r.getSegmentationPipelineVersion(), activeEmbeddingFingerprint, r.getLanguageCode()));
            queue.markSuperseded(job);
            return true;
        }
        // Idempotency short-circuit (§4.3): this exact processing already completed → done, no recomputation.
        if (queue.isAlreadyCompleted(job.getRequest().idempotencyKey())) {
            queue.markCompleted(job);
            return true;
        }
        listener.onStarted(job);
        try {
            PipelineOutcome outcome = runPipeline(job);
            queue.markCompleted(job);
            listener.onCompleted(job, outcome.sentenceCount, outcome.passageCount);
        } catch (StageFailure sf) {
            handleFailure(job, sf);
        } catch (RuntimeException unexpected) {
            handleFailure(job, new StageFailure(SourceProcessingStage.EXTRACTION,
                    String.valueOf(unexpected.getMessage()), true));
        }
        return true;
    }

    /** Drain the queue until empty; @return the number of jobs handled. Serial — no parallelism (§23). */
    public int drain() {
        int handled = 0;
        while (processOne()) {
            handled++;
        }
        return handled;
    }

    private PipelineOutcome runPipeline(SourceProcessingJob job) {
        SourceProcessingRequest req = job.getRequest();
        String captureId = req.getCaptureId();
        String segVersion = req.getSegmentationPipelineVersion();
        String fingerprint = req.getEmbeddingModelFingerprint();
        String languageCode = req.getLanguageCode();

        // RESUME (§2, §11): if this generation's passages + vectors are ALREADY persisted (a previous attempt
        // got past PASSAGE_PERSISTENCE and only INDEXING failed), skip OpenNLP/embedding entirely and re-index
        // from the durable data — no expensive NLP/embedding recompute for a transient index error.
        List<PassageIndexDocument> documents = loadPersisted(captureId, segVersion, fingerprint, languageCode);
        int sentenceCount = Listener.RESUMED;
        String sourceId = documents.isEmpty() ? req.getSourceId() : documents.get(0).getSourceId();
        if (documents.isEmpty()) {
            SourceCapture capture = readCapture(captureId, req.getSourceId());
            sourceId = capture.getSourceId();
            PassageSegmentation.Result result = segment(capture, languageCode);
            storePassages(capture, result); // passages + vectors durable BEFORE the index (which is a projection)
            documents = toIndexDocuments(capture, result, segVersion, fingerprint);
            sentenceCount = result.getSentences().size();
        }
        // INDEXING is the LAST stage: only after a successful index update may the job be COMPLETED. A capture's
        // passages are replaced atomically so a retry never duplicates and a new generation supersedes the old.
        indexPassages(captureId, fingerprint, documents);
        // One active capture per source: the passages of every capture a newer capture of this source replaced
        // (possibly this very one, when it lost) leave the index, so no search sees a source twice.
        retireSupersededCaptures(sourceId, fingerprint);
        return new PipelineOutcome(sentenceCount, documents.size());
    }

    private void retireSupersededCaptures(String sourceId, String fingerprint) {
        if (sourceId == null || sourceId.isEmpty()) {
            return;
        }
        for (String superseded : passageStore.supersededCaptureIds(sourceId)) {
            indexPassages(superseded, fingerprint, new ArrayList<PassageIndexDocument>());
        }
    }

    /** The counts reported to the listener on completion; sentenceCount is {@link Listener#RESUMED} on resume. */
    private static final class PipelineOutcome {
        final int sentenceCount;
        final int passageCount;

        PipelineOutcome(int sentenceCount, int passageCount) {
            this.sentenceCount = sentenceCount;
            this.passageCount = passageCount;
        }
    }

    private List<PassageIndexDocument> loadPersisted(String captureId, String segVersion, String fingerprint,
                                                     String languageCode) {
        try {
            List<PassageIndexDocument> docs =
                    persistedGenerations.loadPersisted(captureId, segVersion, fingerprint, languageCode);
            return docs == null ? new ArrayList<PassageIndexDocument>() : docs;
        } catch (RuntimeException ex) {
            // A failure to read the durable data is treated as "not persisted" → run the full pipeline.
            return new ArrayList<PassageIndexDocument>();
        }
    }

    private List<PassageIndexDocument> toIndexDocuments(SourceCapture capture, PassageSegmentation.Result result,
                                                       String segVersion, String fingerprint) {
        Map<String, EmbeddingPort.EmbeddingVector> vectors = result.getPassageVectors();
        List<PassageIndexDocument> docs = new ArrayList<PassageIndexDocument>();
        for (Passage passage : result.getPassages()) {
            EmbeddingPort.EmbeddingVector vector = vectors.get(passage.getPassageId());
            if (vector == null) {
                continue; // no vector → not indexable (should not happen: the pipeline embeds every passage)
            }
            docs.add(new PassageIndexDocument(passage.getPassageId(), capture.getCaptureId(),
                    capture.getSourceId(), passage.getText(), passage.getHeadingPath(), segVersion,
                    fingerprint, vector.getValues()));
        }
        return docs;
    }

    private void indexPassages(String captureId, String fingerprint, List<PassageIndexDocument> documents) {
        try {
            // Capture-scoped replace = idempotent upsert + supersession of the previous generation.
            index.replacePassagesForCapture(projectId, fingerprint, captureId, documents);
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.INDEXING, message(ex), true);
        }
    }

    private SourceCapture readCapture(String captureId, String sourceId) {
        SourceCapture capture;
        try {
            capture = captureReader.read(captureId, sourceId);
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.EXTRACTION, message(ex), true);
        }
        if (capture == null) {
            throw new StageFailure(SourceProcessingStage.EXTRACTION, "unknown capture: " + captureId, false);
        }
        return capture;
    }

    private PassageSegmentation.Result segment(SourceCapture capture, String languageCode) {
        try {
            // The job's IMMUTABLE language snapshot resolves the segmentation (sentence model) for exactly
            // this job — a mid-session language switch affects the NEXT job, never a running one.
            // PassageSegmentation bundles sentence detection + per-passage embedding; the embedding call is the
            // dominant transient failure, so a thrown segmentation error is treated as a retryable EMBEDDING one.
            return segmentationFactory.forLanguage(languageCode).segment(capture);
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.EMBEDDING, message(ex), true);
        }
    }

    private void storePassages(SourceCapture capture, PassageSegmentation.Result result) {
        try {
            passageStore.store(capture, result.getSentences(), result.getPassages(),
                    result.getPassageVectors());
        } catch (RuntimeException ex) {
            throw new StageFailure(SourceProcessingStage.PASSAGE_PERSISTENCE, message(ex), true);
        }
    }

    private void handleFailure(SourceProcessingJob job, StageFailure sf) {
        if (!sf.retryable || job.getAttempts() >= maxAttempts) {
            SourceProcessingFailure failure = SourceProcessingFailure.permanent(sf.stage, sf.reason);
            queue.markFailed(job, failure);
            listener.onFailed(job, failure);
            return;
        }
        SourceProcessingFailure failure = SourceProcessingFailure.retryable(sf.stage, sf.reason);
        queue.requeue(job.failed(failure)); // back to the tail for another attempt — never blocks the FIFO
        listener.onFailed(job, failure);
    }

    private static String message(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /** Internal control-flow carrier: a stage failed with a stage id, a short reason and a retryable flag. */
    private static final class StageFailure extends RuntimeException {
        final SourceProcessingStage stage;
        final String reason;
        final boolean retryable;

        StageFailure(SourceProcessingStage stage, String reason, boolean retryable) {
            super(reason);
            this.stage = stage;
            this.reason = reason == null ? "" : reason;
            this.retryable = retryable;
        }
    }
}
