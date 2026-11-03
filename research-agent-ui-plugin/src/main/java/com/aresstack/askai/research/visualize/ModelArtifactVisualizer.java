package com.aresstack.askai.research.visualize;

import com.aresstack.askai.agent.model.inference.AgentInferencePort;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The model-backed {@link ArtifactVisualizationService}: renders an artifact through the host's neutral
 * {@link AgentInferencePort} (no HTTP, no model names, no transport). The streaming port is bridged to the
 * synchronous service contract with a latch — safe because the visualizer only ever runs on a background
 * worker (the lazy scheduler), never on the EDT. Any failure/timeout degrades to
 * {@link VisualizationResult#none} — a visualization is never critical.
 */
public final class ModelArtifactVisualizer implements ArtifactVisualizationService {

    // Generous on purpose: the visualizer is LOW-priority background work on a local model that also
    // serves the main agent — a cold gemma answer took 35s on an otherwise idle machine, far longer
    // under turn contention. Nothing blocks on this latch except the visualizer's own worker.
    private static final long TIMEOUT_MILLIS = 180000;

    private final AgentInferencePort port;
    private final MermaidValidator validator;

    public ModelArtifactVisualizer(AgentInferencePort port) {
        this(port, new MermaidValidator.Graal());
    }

    public ModelArtifactVisualizer(AgentInferencePort port, MermaidValidator validator) {
        this.port = port;
        this.validator = validator == null ? new MermaidValidator.Graal() : validator;
    }

    /**
     * One inference, one VALIDATION, at most one RETRY: a diagram whose Mermaid fails to render is
     * sent BACK to the model together with the renderer's concrete error, so the retry fixes the
     * actual mistake. A second failure degrades honestly to FAILED with the error attached —
     * broken Mermaid never reaches the UI as a "diagram".
     */
    public VisualizationResult visualize(ArtifactSnapshot snapshot) {
        if (port == null || snapshot == null || snapshot.getContent().trim().isEmpty()) {
            return VisualizationResult.failed("no inference port or empty artifact");
        }
        VisualizationResult result = infer(VisualizationPrompt.user(snapshot));
        if (result.getKind() != VisualizationResult.Kind.DIAGRAM) {
            return result;
        }
        String error = validator.validate(result.getMermaid());
        if (error == null) {
            return result;
        }
        VisualizerDiagnostics.log("render-invalid, retrying with error feedback: " + error);
        VisualizationResult retried = infer(
                VisualizationPrompt.retryUser(snapshot, result.getMermaid(), error));
        if (retried.getKind() != VisualizationResult.Kind.DIAGRAM) {
            return retried; // an honest NONE (or a failure) from the retry stands as-is
        }
        String retryError = validator.validate(retried.getMermaid());
        if (retryError == null) {
            return retried;
        }
        return VisualizationResult.failed("diagram does not render (after retry): " + retryError);
    }

    private VisualizationResult infer(String userPrompt) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> text = new AtomicReference<String>();
        final AtomicReference<String> failure = new AtomicReference<String>();
        AgentInferencePort.Cancellable cancellable = port.generate(
                new AgentInferencePort.InferenceRequest(VisualizationPrompt.system(), userPrompt),
                new AgentInferencePort.Listener() {
                    public void onCompleted(String fullText) {
                        text.set(fullText);
                        done.countDown();
                    }

                    public void onFailed(String reason) {
                        failure.set(reason);
                        done.countDown();
                    }
                });
        try {
            if (!done.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                cancellable.cancel();
                return VisualizationResult.failed("visualizer timed out");
            }
        } catch (InterruptedException interrupted) {
            cancellable.cancel();
            Thread.currentThread().interrupt();
            return VisualizationResult.failed("visualizer interrupted");
        }
        if (text.get() == null) {
            return VisualizationResult.failed(failure.get() == null ? "visualizer failed" : failure.get());
        }
        return VisualizationResultParser.parse(text.get());
    }
}
