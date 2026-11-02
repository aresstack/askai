package com.aresstack.askai.ui.markdown;

import java.awt.image.BufferedImage;
import java.util.logging.Logger;

/**
 * Fault-tolerant rendering layer for LLM-generated Mermaid.
 *
 * <p>The original diagram code is never mutated — {@link MermaidDiagramPanel} keeps it verbatim for
 * "Copy Mermaid code". Only the <em>rendering input</em> is compatibilized: when
 * {@link MermaidRenderingSourceNormalizer} produces a changed copy (i.e. it found clearly-repairable
 * unquoted flowchart rectangle labels) that copy is rendered instead.
 *
 * <p>Because the underlying library performs an expensive GraalJS render and reports parse errors as a
 * {@code null} image rather than a typed exception, this layer normalizes proactively (avoiding a wasted
 * first render of code we already know is broken). If the normalized copy still fails to render, it falls
 * back to the original source exactly once so the real failure — not the repaired variant — surfaces in
 * the UI. There is at most one fallback render; there is never a retry loop.
 */
final class NormalizingMermaidImageRenderer implements MermaidImageRenderer {

    private static final Logger LOG = Logger.getLogger(NormalizingMermaidImageRenderer.class.getName());

    private final MermaidImageRenderer delegate;
    private final MermaidRenderingSourceNormalizer normalizer;

    NormalizingMermaidImageRenderer(MermaidImageRenderer delegate, MermaidRenderingSourceNormalizer normalizer) {
        if (delegate == null || normalizer == null) {
            throw new IllegalArgumentException("delegate and normalizer must not be null");
        }
        this.delegate = delegate;
        this.normalizer = normalizer;
    }

    @Override
    public BufferedImage render(String diagramCode, int width) {
        String normalized = normalizer.normalize(diagramCode);
        boolean unchanged = normalized == null ? diagramCode == null : normalized.equals(diagramCode);
        if (unchanged) {
            return delegate.render(diagramCode, width); // nothing to fix: render the original exactly once
        }

        LOG.info("[MermaidRenderer] Retrying render with normalized flowchart labels.");
        BufferedImage rendered = delegate.render(normalized, width);
        if (rendered != null) {
            return rendered;
        }

        // The normalized copy still failed; fall back once to the original so the underlying error surfaces.
        LOG.warning("[MermaidRenderer] Normalized flowchart render failed; falling back to the original source.");
        return delegate.render(diagramCode, width);
    }
}
