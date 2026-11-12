package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.capture.CanonicalUrlSourceIdResolver;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.CaptureStoreSourceCaptureReader;
import com.aresstack.askai.research.knowledge.PassageSegmentation;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;
import com.aresstack.askai.research.knowledge.processing.FilePassageVectorStore;
import com.aresstack.askai.research.knowledge.processing.FileResearchProjectRepository;
import com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue;
import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingRunner;
import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingSettings;
import com.aresstack.askai.research.knowledge.processing.RepositoryIndexableGenerationSource;
import com.aresstack.askai.research.knowledge.processing.ResearchProjectPassageStore;
import com.aresstack.askai.research.knowledge.processing.SourceCaptureReader;
import com.aresstack.askai.research.knowledge.processing.SourceProcessingWorker;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;
import com.aresstack.askai.research.knowledge.lucene.CompositeSemanticKnowledgeIndex;
import com.aresstack.askai.research.sources.ResearchSourceRepository;

import java.io.File;

/**
 * The composition root for the continuous knowledge worker of ONE productive research session (§13): it wires
 * the concrete adapters — HTTP embedding, OpenNLP sentence resolver, PassageSegmentation, the file-backed passage
 * store + canonical vectors, the Lucene+cosine {@link SemanticKnowledgeIndex} and the persisted-generation resume
 * source — into a {@link SourceProcessingWorker}, and hands back a NOT-yet-started {@link KnowledgeProcessingRunner}.
 * No algorithm lives here; the {@code ProductiveResearchBackendFactory} stays a composition root and delegates the
 * knowledge wiring to this small class.
 */
final class KnowledgeProcessingSessionFactory {

    private KnowledgeProcessingSessionFactory() {
    }

    /**
     * Resolves a research language into the session's sentence segmenter — the seam behind which the host NLP
     * snapshot provider (with its documented regex-fallback / hard-failure semantics) stays. Resolved lazily
     * PER LANGUAGE and cached by the factory below, so a job's immutable language snapshot picks its sentence
     * model without re-loading the artifact per capture.
     */
    interface SentenceSegmenterResolver {
        SessionSentenceSegmenter resolve(String languageCode);
    }

    /** Cheap deterministic staleness metadata: is the persisted outline older than its inputs? */
    interface OutlineStalenessCheck {
        /**
         * {@code true} when the persisted projection's corpus fingerprint no longer matches the ACTIVE
         * corpus (new passages, source Save/Exclude/⭐), or when no projection exists yet but the corpus
         * has content. Pure read — never triggers a rebuild.
         */
        boolean isStale();

        /** Whether a persisted projection exists at all (the tab shows "not generated yet" without one). */
        boolean hasPersistedProjection();
    }

    /** Everything the continuous knowledge capability of one session owns: worker + live projection. */
    static final class KnowledgeSession {
        final KnowledgeProcessingRunner worker;
        final com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner projection;
        final com.aresstack.askai.research.knowledge.processing.live.KnowledgeProjectionInvalidator invalidator;
        final OutlineStalenessCheck staleness;
        /** #42: the SHARED phase-neutral topic discovery (phase 1 + outline consume it). */
        final com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery topics;
        /** PR 20: progress, Cancel and outcome of the clustering run, read by the outline drawer. */
        final OutlineClusteringRun outlineRun;
        /**
         * D6: the debounced, single-flight runner of the shared topic analysis (Phase 2 steering). Invalidated after
         * completed processing jobs and at research cycle boundaries; it runs only when the analysis is due.
         */
        com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner analysis;
        /** #28/#39.7: read-only per-source passage inspection over the active corpus. */
        final com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader corpusReader;
        /** Plan PR 22: the session's embedding port (label paths are embedded as queries) and topic minimums. */
        com.aresstack.askai.research.knowledge.EmbeddingPort embeddings;
        KnowledgeProcessingSettings settings;
        /** Plan PR 29: the semantic index, project id and active embedding world of the writer's evidence packs. */
        SemanticKnowledgeIndex index;
        String projectId;
        String embeddingFingerprint;

        KnowledgeSession(KnowledgeProcessingRunner worker,
                         com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner
                                 projection,
                         com.aresstack.askai.research.knowledge.processing.live.KnowledgeProjectionInvalidator
                                 invalidator,
                         OutlineStalenessCheck staleness,
                         com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery topics,
                         com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader
                                 corpusReader,
                         OutlineClusteringRun outlineRun) {
            this.outlineRun = outlineRun;
            this.worker = worker;
            this.projection = projection;
            this.invalidator = invalidator;
            this.staleness = staleness;
            this.topics = topics;
            this.corpusReader = corpusReader;
        }
    }

    /** A hook fired after every persisted projection rebuild (C5d writes the outline artifact here). */
    interface ProjectionListener {
        void onProjectionUpdated(
                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection projection);

        ProjectionListener NONE = new ProjectionListener() {
            public void onProjectionUpdated(
                    com.aresstack.askai.research.knowledge.live.LiveOutlineProjection projection) {
            }
        };
    }

    /**
     * Build (but do not start) the serial worker for a session's authoritative embedding world. The caller starts
     * the runner once construction has fully succeeded and stops it on session close (Variant B lifted only here).
     */
    static KnowledgeSession buildRunner(File projectDir, String projectId,
                                                 EmbeddingEndpointDescriptor descriptor,
                                                 String sessionLanguageCode,
                                                 final SentenceSegmenterResolver segmenterResolver,
                                                 CaptureStore captures,
                                                 final ResearchSourceRepository sourceRepository,
                                                 FileSourceProcessingQueue queue,
                                                 KnowledgeProcessingSettings settings,
                                                 final java.util.List<String> briefQuestions,
                                                 final ProjectionListener projectionListener,
                                                 final Runnable knowledgeChangedNotifier) {
        // Embedding: strict batch /api/embed adapter over the SESSION descriptor (its own timeout).
        final HttpEmbeddingPortAdapter embeddings = new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport((int) descriptor.timeoutMillis));
        // The knowledge pipeline's vector world: documents under the model's prompt scheme (PR 04).
        final String documentFingerprint = embeddings.fingerprint(
                com.aresstack.askai.research.knowledge.EmbeddingPort.Role.DOCUMENT);

        // Sentences: resolved PER JOB LANGUAGE (cached) — a job's immutable language snapshot picks its own
        // sentence model, so the job enqueued after a language switch segments with the NEW language while a
        // running job keeps its instance. Resolution failures surface as that job's stage failure.
        final KnowledgeProcessingSettings s = settings;
        final java.util.Map<String, PassageSegmentation> byLanguage =
                new java.util.concurrent.ConcurrentHashMap<String, PassageSegmentation>();
        final java.util.Map<String, String> descriptionByLanguage =
                new java.util.concurrent.ConcurrentHashMap<String, String>();
        final SourceProcessingWorker.SegmentationFactory segmentationFactory =
                new SourceProcessingWorker.SegmentationFactory() {
                    public PassageSegmentation forLanguage(String languageCode) {
                        String lang = "de".equalsIgnoreCase(languageCode == null ? "" : languageCode.trim())
                                ? "de" : "en";
                        PassageSegmentation cached = byLanguage.get(lang);
                        if (cached == null) {
                            SentenceSegmenterResolver r = segmenterResolver;
                            SessionSentenceSegmenter resolved = r.resolve(lang);
                            cached = new PassageSegmentation(resolved.segmenter, embeddings,
                                    s.segmentationPipelineVersion, lang, s.windowSize, s.boundaryThreshold,
                                    s.minPassageSentences, s.maxPassageSentences);
                            byLanguage.put(lang, cached);
                            descriptionByLanguage.put(lang, resolved.description);
                        }
                        return cached;
                    }
                };
        // Eager fail-fast + the one-time ready line for the SESSION language (the same behavior as before):
        // a broken selected model for the session language still fails the session build, not the first job.
        segmentationFactory.forLanguage(sessionLanguageCode);
        String segmenterDescription = descriptionByLanguage.values().iterator().next();

        // Canonical persistence (source of truth) + rebuildable index projection.
        FileResearchProjectRepository repository = new FileResearchProjectRepository(projectDir);
        FilePassageVectorStore vectorStore = new FilePassageVectorStore(projectDir);
        ResearchProjectPassageStore passageStore =
                new ResearchProjectPassageStore(repository, projectId, vectorStore);
        SemanticKnowledgeIndex index = new CompositeSemanticKnowledgeIndex(projectDir, projectId);
        RepositoryIndexableGenerationSource generations =
                new RepositoryIndexableGenerationSource(repository, vectorStore, projectId);
        retireSupersededCaptures(repository, index, projectId, documentFingerprint);

        // The DURABLE fallback (issue #29): when the bounded in-memory capture store no longer holds the
        // capture (restart, eviction), the reader rebuilds it from the persisted source record's full text —
        // a delayed, user-triggered segmentation never depends on in-memory state.
        // The knowledge project's own persisted capture comes FIRST: a re-read page refreshes its source record in
        // place, so only the persisted original still holds an older capture's exact input.
        SourceCaptureReader reader = new com.aresstack.askai.research.knowledge.processing
                .PersistedFirstSourceCaptureReader(repository, new CaptureStoreSourceCaptureReader(captures,
                        new CanonicalUrlSourceIdResolver(sourceRepository), sourceRepository));

        // One-time readiness line for the live gate: language, the resolved segmenter (model id/version/artifact
        // name — never an absolute path — or the regex reason), and the embedding world.
        System.err.println("[research-knowledge] worker ready project=" + projectId
                + " language=" + sessionLanguageCode
                + " segmenter=" + segmenterDescription
                + " embeddingModel=" + descriptor.modelId
                + " fingerprint=" + documentFingerprint
                + " promptScheme=" + embeddings.scheme().id()
                + " dimension=" + descriptor.embeddingDimension
                + " endpoint=" + descriptor.endpointUrl());

        // C5: the live projection stack — active corpus (persisted passages + vectors, source-filtered) →
        // deterministic clustering → LiveOutlineProjection → rebuildable store. NEVER re-embeds; a corrupt or
        // missing persisted projection only costs a rebuild and never blocks the session start.
        final com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader corpusReader =
                new com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader(
                        repository, vectorStore, projectId, documentFingerprint);
        final com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader.SourceFilter
                sourceFilter = new com.aresstack.askai.research.knowledge.processing.live
                        .ActiveKnowledgeCorpusReader.SourceFilter() {
                    public boolean includeSource(String sourceId) {
                        com.aresstack.askai.research.sources.ResearchSourceRecord record =
                                sourceRepository.get(sourceId);
                        if (record == null) {
                            return true; // unknown → include (canonical data decides, never a silent drop)
                        }
                        com.aresstack.askai.research.sources.SourceStatus status = record.getStatus();
                        return status != com.aresstack.askai.research.sources.SourceStatus.EXCLUDED
                                && status != com.aresstack.askai.research.sources.SourceStatus.DUPLICATE
                                && status != com.aresstack.askai.research.sources.SourceStatus.SUPERSEDED;
                    }

                    public boolean isUserRelevant(String sourceId) {
                        com.aresstack.askai.research.sources.ResearchSourceRecord record =
                                sourceRepository.get(sourceId);
                        return record != null && record.isUserRelevant();
                    }
                };
        final com.aresstack.askai.research.knowledge.processing.live.FileLiveOutlineProjectionStore
                projectionStore = new com.aresstack.askai.research.knowledge.processing.live
                        .FileLiveOutlineProjectionStore(projectDir);
        final com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder projectionBuilder =
                new com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder();
        // #42: the shared, phase-neutral topic discovery over the SAME corpus/filter/builder.
        final com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery sharedTopics =
                new com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery(
                        new com.aresstack.askai.research.knowledge.processing.live
                                .SharedTopicDiscovery.CorpusSource() {
                            public com.aresstack.askai.research.knowledge.processing.live
                                    .ActiveKnowledgeCorpusReader.Corpus read() {
                                return corpusReader.read(sourceFilter);
                            }
                        },
                        projectionBuilder,
                        new com.aresstack.askai.research.knowledge.processing.live
                                .FileTopicSnapshotStore(projectDir),
                        documentFingerprint);
        final String fingerprint = documentFingerprint;
        final OutlineClusteringRun outlineRun = new OutlineClusteringRun();
        final com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner projection =
                new com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner(
                        new com.aresstack.askai.research.knowledge.processing.live
                                .LiveKnowledgeProjectionRunner.RebuildStep() {
                            public void rebuild() {
                                // EXPLICIT two-stage pipeline (issue #29): topic discovery and outline
                                // building are separate, visibly orchestrated stages — the outline never
                                // hides an implicit cluster run. This step only runs after the user's
                                // explicit "Inhaltsverzeichnis erzeugen" action (nothing invalidates it
                                // automatically anymore).
                                // #42: BOTH consumers (this outline build and phase 1's
                                // concept ideas) run through the ONE shared discovery — the
                                // snapshot is persisted here, then the outline builds on it.
                                // refreshPinned reads the corpus INSIDE the discovery lock
                                // and returns it with the topics: one coherent pair, and no
                                // concurrent refresh can persist an older corpus's snapshot
                                // over a newer one.
                                // PR 20: progress, Cancel and failures reach the outline drawer.
                                outlineRun.started();
                                com.aresstack.askai.research.knowledge.processing.live
                                        .SharedTopicDiscovery.Pinned pinned;
                                try {
                                    pinned = sharedTopics.refreshPinned(System.currentTimeMillis(), outlineRun);
                                } catch (com.aresstack.askai.research.knowledge.ClusteringMonitor
                                        .ClusteringCancelledException cancelled) {
                                    outlineRun.cancelled();
                                    System.err.println("[research-knowledge] clustering cancelled");
                                    return;
                                } catch (RuntimeException failed) {
                                    outlineRun.failed(failed.getMessage());
                                    throw failed;
                                } catch (OutOfMemoryError tooLarge) {
                                    outlineRun.failed("not enough memory for the similarity matrix; "
                                            + "lower the corpus limit");
                                    return;
                                }
                                com.aresstack.askai.research.knowledge.processing.live
                                        .ActiveKnowledgeCorpusReader.Corpus corpus = pinned.corpus;
                                com.aresstack.askai.research.knowledge.processing.live
                                        .FileTopicSnapshotStore.TopicSnapshot topicSnapshot =
                                        pinned.snapshot;
                                java.util.List<com.aresstack.askai.research.knowledge.live
                                        .LiveTopicProjection> topics = topicSnapshot.topics;
                                System.err.println("[research-knowledge] topics discovered passages="
                                        + corpus.getPassages().size() + " topics=" + topics.size()
                                        + " snapshotRevision=" + topicSnapshot.revision);
                                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection previous =
                                        projectionStore.load();
                                long nextRevision = (previous == null ? 0L
                                        : previous.getProjectionRevision()) + 1L;
                                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection next =
                                        projectionBuilder.buildOutline(nextRevision, fingerprint,
                                                System.currentTimeMillis(), corpus.getPassages(),
                                                topics, briefQuestions,
                                                topicSnapshot.corpusFingerprint);
                                try {
                                    projectionStore.save(next);
                                } catch (RuntimeException failed) {
                                    outlineRun.failed(failed.getMessage());
                                    throw failed;
                                }
                                outlineRun.finished(pinned.omittedPassages);
                                System.err.println("[research-knowledge] outline rebuilt revision="
                                        + next.getProjectionRevision()
                                        + " topics=" + next.getTopics().size()
                                        + " sections=" + next.getSections().size());
                                (projectionListener == null ? ProjectionListener.NONE : projectionListener)
                                        .onProjectionUpdated(next);
                            }
                        }, "knowledge-projection-" + projectId, 1500L);

        // D6: the shared topic analysis runs in the background during the research (Phase 2): a completed job
        // only marks it stale; the runner coalesces bursts (the projection debounce setting) and runs one analysis
        // at a time, and SharedTopicDiscovery.analyzeIfDue decides whether the grown corpus is due at all.
        final com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner analysis =
                new com.aresstack.askai.research.knowledge.processing.live.LiveKnowledgeProjectionRunner(
                        new com.aresstack.askai.research.knowledge.processing.live
                                .LiveKnowledgeProjectionRunner.RebuildStep() {
                            public void rebuild() {
                                try {
                                    sharedTopics.analyzeIfDue();
                                } catch (com.aresstack.askai.research.knowledge.ClusteringMonitor
                                        .ClusteringCancelledException cancelled) {
                                    System.err.println("[topic-analysis] cancelled");
                                } catch (OutOfMemoryError tooLarge) {
                                    System.err.println("[topic-analysis] not enough memory for the similarity "
                                            + "matrix; lower the corpus limit");
                                } catch (Exception failed) {
                                    throw new IllegalStateException("topic analysis failed: "
                                            + failed.getMessage(), failed);
                                }
                            }
                        }, "knowledge-analysis-" + projectId, settings.projectionDebounceMillis);
        sharedTopics.setAnalysisTrigger(analysis);

        // Issue #29: a COMPLETED job no longer rebuilds the projection. It only NOTIFIES (cheap staleness
        // metadata for the open Outline tab); recalculation is an explicit user action. PR 20: the background
        // topic runner of #42 is gone; clustering runs only on request in outline/running.
        final SourceProcessingWorker worker = new SourceProcessingWorker(queue, reader, segmentationFactory,
                passageStore, index, generations, projectId, settings.maxProcessingAttempts,
                documentFingerprint,
                notifying(diagnosticListener(projectId, descriptionByLanguage), new Runnable() {
                    public void run() {
                        if (knowledgeChangedNotifier != null) {
                            knowledgeChangedNotifier.run();
                        }
                        analysis.knowledgeChanged();
                    }
                }));

        KnowledgeProcessingRunner workerRunner = new KnowledgeProcessingRunner(
                new KnowledgeProcessingRunner.ProcessingStep() {
                    public boolean processOne() {
                        return worker.processOne();
                    }
                }, "knowledge-processing-" + projectId, 1000L, 5000L);
        OutlineStalenessCheck staleness = new OutlineStalenessCheck() {
            public boolean isStale() {
                com.aresstack.askai.research.knowledge.live.LiveOutlineProjection persisted =
                        projectionStore.load();
                com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader.Corpus
                        corpus = corpusReader.read(sourceFilter);
                java.util.List<String> ids = new java.util.ArrayList<String>();
                for (com.aresstack.askai.research.domain.Passage p : corpus.getPassages()) {
                    ids.add(p.getPassageId());
                }
                String currentFingerprint = com.aresstack.askai.research.knowledge.live
                        .LiveOutlineProjection.corpusFingerprintOf(ids);
                if (persisted == null) {
                    return !ids.isEmpty(); // nothing generated yet but there IS content to project
                }
                return !currentFingerprint.equals(persisted.getCorpusFingerprint())
                        || !fingerprint.equals(persisted.getEmbeddingFingerprint());
            }

            public boolean hasPersistedProjection() {
                return projectionStore.load() != null;
            }
        };
        KnowledgeSession session = new KnowledgeSession(workerRunner, projection, projection, staleness,
                sharedTopics, corpusReader, outlineRun);
        session.analysis = analysis;
        session.embeddings = embeddings;
        session.settings = settings;
        session.index = index;
        session.projectId = projectId;
        session.embeddingFingerprint = fingerprint;
        return session;
    }

    /**
     * One active capture per source: loading the project retires every older capture of a source that was
     * captured again (also in projects written before the rule), and their passages leave the semantic index
     * here, so the writer's evidence never sees a source twice. A failure never blocks the session start.
     */
    private static void retireSupersededCaptures(FileResearchProjectRepository repository,
                                                 SemanticKnowledgeIndex index, String projectId,
                                                 String documentFingerprint) {
        try {
            repository.load(projectId);
            java.util.Set<String> superseded = repository.supersededCaptureIds();
            for (String captureId : superseded) {
                index.replacePassagesForCapture(projectId, documentFingerprint, captureId,
                        java.util.Collections.<com.aresstack.askai.research.knowledge.processing.index
                                .PassageIndexDocument>emptyList());
            }
            if (!superseded.isEmpty()) {
                System.err.println("[research-knowledge] " + superseded.size()
                        + " superseded capture(s) kept out of the active corpus and the index");
            }
        } catch (RuntimeException failed) {
            System.err.println("[research-knowledge] retiring superseded captures failed: "
                    + failed.getMessage());
        }
    }

    /** Wrap the diagnostics listener so a COMPLETED job NOTIFIES the UI (staleness re-check) — no rebuild. */
    private static SourceProcessingWorker.Listener notifying(
            final SourceProcessingWorker.Listener delegate, final Runnable knowledgeChangedNotifier) {
        return new SourceProcessingWorker.Listener() {
            public void onStarted(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job) {
                delegate.onStarted(job);
            }

            public void onCompleted(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job,
                    int sentenceCount, int passageCount) {
                delegate.onCompleted(job, sentenceCount, passageCount);
                if (knowledgeChangedNotifier != null) {
                    try {
                        knowledgeChangedNotifier.run(); // new passages exist → the outline tab shows STALE
                    } catch (RuntimeException never) {
                        // a UI notification must never fail the worker
                    }
                }
            }

            public void onFailed(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job,
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingFailure failure) {
                delegate.onFailed(job, failure);
            }
        };
    }

    /**
     * Per-job diagnostics to the app console (the live-gate evidence: captureId, sentence/passage counts,
     * embedding fingerprint, COMPLETED / failure stage). Cheap and side-effect free.
     */
    private static SourceProcessingWorker.Listener diagnosticListener(
            final String projectId, final java.util.Map<String, String> descriptionByLanguage) {
        return new SourceProcessingWorker.Listener() {
            public void onStarted(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job) {
                // PER-JOB language + segmenter: a mixed language state in a live run is immediately visible.
                String jobLanguage = job.getRequest().getLanguageCode();
                String segmenter = descriptionByLanguage.get(jobLanguage);
                System.err.println("[research-knowledge] processing captureId=" + job.getRequest().getCaptureId()
                        + " project=" + projectId + " language=" + jobLanguage
                        + " segmenter=" + (segmenter == null ? "(resolved on first use)" : segmenter)
                        + " fingerprint=" + job.getRequest().getEmbeddingModelFingerprint());
            }

            public void onCompleted(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job,
                    int sentenceCount, int passageCount) {
                System.err.println("[research-knowledge] COMPLETED captureId=" + job.getRequest().getCaptureId()
                        + " sentences=" + (sentenceCount == RESUMED ? "resumed" : Integer.toString(sentenceCount))
                        + " passages=" + passageCount
                        + " fingerprint=" + job.getRequest().getEmbeddingModelFingerprint());
            }

            public void onFailed(
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingJob job,
                    com.aresstack.askai.research.knowledge.processing.SourceProcessingFailure failure) {
                System.err.println("[research-knowledge] " + failure.getStage() + "_FAILED captureId="
                        + job.getRequest().getCaptureId() + " retryable=" + failure.isRetryable()
                        + " reason=" + failure.getReason());
            }
        };
    }
}
