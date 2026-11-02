package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;

import java.io.IOException;
import java.util.Arrays;

/**
 * A5b: the host adapter that turns AskAI's ALREADY-STARTED local model runtime into the NEUTRAL
 * {@link RerankerConfigurationDocument} the research agent consumes. This is the only place that
 * knows the local runtime speaks the {@code /api/rerank} dialect with RAW_LOGIT scores; it hands the
 * agent nothing but a base URL, a virtual model name and a selection policy — never a path, a process
 * handle, or any knowledge of win-directml-java.
 *
 * <p>The descriptor DESCRIBES an endpoint; it never starts the runtime itself. The caller starts the
 * runtime (the manager's {@code ensureStarted()} returns the base URL) and then publishes the
 * resulting snapshot with {@link LocalRerankerConfigurationSnapshotWriter}.
 */
public final class LocalRerankerEndpointDescriptorFactory {

    /**
     * A conservative default request timeout. A local cross-encoder scores a handful of short search
     * snippets in well under a second on cold start; 15s leaves generous headroom without letting the
     * research loop hang on a wedged runtime.
     */
    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L;

    /**
     * The default number of survivors handed to the browser navigation stage. Reranking exists to
     * open FEWER, better pages; Top-10 keeps a full first-page worth of candidates while discarding
     * the long organic tail. Relative/absolute score cut-offs are intentionally LEFT ABSENT here —
     * RAW_LOGIT scores are unbounded and model-specific, so a global threshold would be a guess.
     */
    public static final int DEFAULT_MAXIMUM_SELECTED_CANDIDATES = 10;

    private LocalRerankerEndpointDescriptorFactory() {
    }

    /**
     * Build a current-schema configuration document for the local reranker served by the started
     * runtime.
     *
     * @param manager              the local model runtime manager (must be startable)
     * @param virtualModelName     the {@code local/<repo>:latest} name of the installed reranker
     * @param configurationRevision monotonic revision of the host's reranker settings (0 = initial)
     * @throws IOException if the local runtime cannot be started
     */
    public static RerankerConfigurationDocument forLocalReranker(LocalModelRuntimeManager manager,
                                                                 String virtualModelName,
                                                                 long configurationRevision)
            throws IOException {
        return forLocalReranker(manager, virtualModelName, configurationRevision,
                DEFAULT_MAXIMUM_SELECTED_CANDIDATES, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    /** As {@link #forLocalReranker(LocalModelRuntimeManager, String, long)} with explicit policy. */
    public static RerankerConfigurationDocument forLocalReranker(LocalModelRuntimeManager manager,
                                                                 String virtualModelName,
                                                                 long configurationRevision,
                                                                 int maximumSelectedCandidates,
                                                                 long requestTimeoutMillis)
            throws IOException {
        if (virtualModelName == null || !LocalModelNames.isLocalModelName(virtualModelName)) {
            throw new IllegalArgumentException(
                    "reranker model name must be a local/<repo>:latest name: " + virtualModelName);
        }
        String baseUrl = manager.ensureStarted();
        RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(
                RerankerProvider.ASKAI_LOCAL, baseUrl, virtualModelName,
                Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT,
                requestTimeoutMillis,
                RerankerSelectionConfiguration.topN(maximumSelectedCandidates));
        return RerankerConfigurationDocument.current(configurationRevision, descriptor);
    }
}
