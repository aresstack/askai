package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The compatibility renderer normalizes only the rendering input, keeps the original untouched, performs
 * at most one fallback render, and cooperates with the cache (successful results cached under the
 * original code). No GraalJS is exercised — a recording test double stands in for the real library.
 */
public class NormalizingMermaidImageRendererTest {

    private final MermaidRenderingSourceNormalizer normalizer = new MermaidRenderingSourceNormalizer();

    @Test
    public void validCodeIsRenderedOnceAndUnchanged() {
        RecordingRenderer delegate = new RecordingRenderer();
        MermaidImageRenderer renderer = new NormalizingMermaidImageRenderer(delegate, normalizer);

        renderer.render("graph TD\nA-->B", 480);

        assertEquals(1, delegate.codes.size());
        assertEquals("graph TD\nA-->B", delegate.codes.get(0));
    }

    @Test
    public void repairableCodeReachesTheDelegateNormalized() {
        RecordingRenderer delegate = new RecordingRenderer();
        MermaidImageRenderer renderer = new NormalizingMermaidImageRenderer(delegate, normalizer);

        renderer.render("graph TD\nA[foo (x)]", 480);

        assertEquals("normalized copy rendered once", 1, delegate.codes.size());
        assertEquals("graph TD\nA[\"foo (x)\"]", delegate.codes.get(0));
    }

    @Test
    public void aFailedNormalizedRenderFallsBackToTheOriginalExactlyOnce() {
        RecordingRenderer delegate = new RecordingRenderer();
        delegate.returnNull = true; // simulate the library returning null on a parse error
        MermaidImageRenderer renderer = new NormalizingMermaidImageRenderer(delegate, normalizer);

        BufferedImage result = renderer.render("graph TD\nA[foo (x)]", 480);

        assertNull(result);
        assertEquals("normalized then original: one retry, no loop", 2, delegate.codes.size());
        assertEquals("graph TD\nA[\"foo (x)\"]", delegate.codes.get(0));
        assertEquals("graph TD\nA[foo (x)]", delegate.codes.get(1));
    }

    @Test
    public void nullCodeIsPassedThroughWithoutARetry() {
        RecordingRenderer delegate = new RecordingRenderer();
        MermaidImageRenderer renderer = new NormalizingMermaidImageRenderer(delegate, normalizer);

        renderer.render(null, 480);

        assertEquals(1, delegate.codes.size());
        assertNull(delegate.codes.get(0));
    }

    @Test
    public void cacheStoresTheSuccessfulRenderUnderTheOriginalCode() {
        RecordingRenderer delegate = new RecordingRenderer();
        CachingMermaidImageRenderer cache = new CachingMermaidImageRenderer(
                new NormalizingMermaidImageRenderer(delegate, normalizer), 8);

        cache.render("graph TD\nA[foo (x)]", 480);
        cache.render("graph TD\nA[foo (x)]", 480); // identical original -> cache hit

        assertEquals("rendered once, then served from cache", 1, delegate.codes.size());
        assertEquals("delegate saw the normalized copy", "graph TD\nA[\"foo (x)\"]", delegate.codes.get(0));
    }

    @Test
    public void panelKeepsOriginalForCopyWhileRenderingPathGetsNormalized() throws Exception {
        CapturingRenderer capturing = new CapturingRenderer();
        MermaidImageRenderer renderer = new NormalizingMermaidImageRenderer(capturing, normalizer);
        FlexmarkSwingRenderer flexmark = new FlexmarkSwingRenderer(
                MarkdownTheme.fromUiDefaults(), DesktopLinkOpener.systemDefault(), renderer);

        Component rendered = flexmark.render("```mermaid\ngraph TD\nA[foo (x)]\n```", true);

        List<MermaidDiagramPanel> panels = MarkdownTestSupport.collect(rendered, MermaidDiagramPanel.class);
        assertEquals(1, panels.size());
        String copySource = panels.get(0).getDiagramCode();
        assertTrue("Copy keeps the original unquoted label", copySource.contains("A[foo (x)]"));
        assertFalse("Copy is never the normalized variant", copySource.contains("A[\"foo (x)\"]"));

        assertTrue("renderer was invoked", capturing.firstCall.await(5, TimeUnit.SECONDS));
        assertTrue("the rendering path received the normalized copy",
                capturing.lastCode.contains("A[\"foo (x)\"]"));
    }

    // --- test doubles ---

    /** Synchronous recorder: captures each code passed and optionally returns null (parse failure). */
    private static final class RecordingRenderer implements MermaidImageRenderer {
        final List<String> codes = new ArrayList<String>();
        boolean returnNull;

        @Override
        public BufferedImage render(String diagramCode, int width) {
            codes.add(diagramCode);
            return returnNull ? null : new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        }
    }

    /** Captures the last code rendered off the EDT so the async panel path can be asserted. */
    private static final class CapturingRenderer implements MermaidImageRenderer {
        final CountDownLatch firstCall = new CountDownLatch(1);
        volatile String lastCode;

        @Override
        public BufferedImage render(String diagramCode, int width) {
            lastCode = diagramCode;
            firstCall.countDown();
            return new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        }
    }
}
