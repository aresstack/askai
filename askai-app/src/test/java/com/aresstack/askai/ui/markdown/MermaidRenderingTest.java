package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Verify Mermaid detection, off-EDT rendering, error isolation and caching. */
public class MermaidRenderingTest {

    private FlexmarkSwingRenderer renderer(MermaidImageRenderer mermaid) {
        return new FlexmarkSwingRenderer(MarkdownTheme.fromUiDefaults(),
                DesktopLinkOpener.systemDefault(), mermaid);
    }

    @Test
    public void closedMermaidFenceProducesADiagramPanel() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertTrue(MarkdownTestSupport.containsType(
                renderer.render("```mermaid\ngraph LR\nA-->B\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void mermaidLanguageIsDetectedCaseInsensitively() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertTrue(MarkdownTestSupport.containsType(
                renderer.render("```MERMAID\ngraph LR\nA-->B\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void plainCodeFenceIsNotAMermaidDiagram() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertFalse(MarkdownTestSupport.containsType(
                renderer.render("```java\nint x = 1;\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void extraInfoStringDoesNotTriggerMermaid() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        assertFalse(MarkdownTestSupport.containsType(
                renderer.render("```mermaid diagram\ngraph LR\nA-->B\n```", true), MermaidDiagramPanel.class));
    }

    @Test
    public void mermaidIsNotRenderedWhileStreaming() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        // renderMermaid = false models the streaming phase; the fence stays a code block.
        assertFalse(MarkdownTestSupport.containsType(
                renderer.render("```mermaid\ngraph LR\nA-->B\n```", false), MermaidDiagramPanel.class));
        assertTrue(MarkdownTestSupport.containsType(
                renderer.render("```mermaid\ngraph LR\nA-->B\n```", false), CodeBlockPanel.class));
    }

    @Test
    public void rendererRunsOffTheEventDispatchThread() throws Exception {
        FakeMermaidImageRenderer fake = new FakeMermaidImageRenderer();
        renderer(fake).render("```mermaid\ngraph LR\nA-->B\n```", true);
        assertTrue("renderer invoked", fake.firstCall.await(5, TimeUnit.SECONDS));
        assertTrue("render ran off the EDT", !fake.lastCallOnEventDispatchThread);
    }

    @Test
    public void aFailingRendererDoesNotBreakTheMessage() {
        FakeMermaidImageRenderer fake = new FakeMermaidImageRenderer();
        fake.fail = true;
        // render() returns synchronously; the diagram failure happens on the worker and is contained.
        java.awt.Component panel = renderer(fake)
                .render("Intro text.\n\n```mermaid\ngraph LR\nA-->B\n```\n\nOutro text.", true);
        assertNotNull(panel);
        assertTrue("other content survives", MarkdownTestSupport.containsType(panel, WrappingTextPane.class));
        assertTrue("diagram area still present", MarkdownTestSupport.containsType(panel, MermaidDiagramPanel.class));
    }

    @Test
    public void cacheReusesIdenticalRenders() {
        final AtomicInteger delegateCalls = new AtomicInteger();
        MermaidImageRenderer counting = new MermaidImageRenderer() {
            @Override
            public BufferedImage render(String diagramCode, int width) {
                delegateCalls.incrementAndGet();
                return new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            }
        };
        CachingMermaidImageRenderer cache = new CachingMermaidImageRenderer(counting, 8);
        cache.render("graph LR\nA-->B", 480);
        cache.render("graph LR\nA-->B", 480);
        assertEquals("identical code+width rendered once", 1, delegateCalls.get());
        cache.render("graph LR\nA-->B", 900);
        assertEquals("different width rendered again", 2, delegateCalls.get());
    }

    @Test
    public void mermaidWrappedInAMarkdownFenceStillRendersADiagram() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        String wrapped = "```markdown\n```mermaid\ngraph TD\nA-->B\n```\n```";
        java.awt.Component rendered = renderer.render(wrapped, true);
        assertTrue("nested mermaid must render a diagram",
                MarkdownTestSupport.containsType(rendered, MermaidDiagramPanel.class));
        assertFalse("the markdown wrapper must not remain a code block",
                MarkdownTestSupport.containsType(rendered, CodeBlockPanel.class));
    }

    @Test
    public void introTextPlusMermaidRendersBothParagraphAndDiagram() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        String answer = "Here is a diagram:\n\n```mermaid\ngraph TD\nA-->B\n```";
        java.awt.Component rendered = renderer.render(answer, true);
        assertTrue(MarkdownTestSupport.containsType(rendered, WrappingTextPane.class));
        assertTrue(MarkdownTestSupport.containsType(rendered, MermaidDiagramPanel.class));
    }

    @Test
    public void aMarkdownFenceWithoutMermaidStaysACodeBlock() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        String docExample = "```markdown\n# Heading\n\nSome **text**\n```";
        java.awt.Component rendered = renderer.render(docExample, true);
        assertTrue("a plain markdown demo stays code",
                MarkdownTestSupport.containsType(rendered, CodeBlockPanel.class));
        assertFalse(MarkdownTestSupport.containsType(rendered, MermaidDiagramPanel.class));
    }

    @Test
    public void aJavaFenceIsNeverReinterpreted() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        String java = "```java\nSystem.out.println(1);\n```";
        java.awt.Component rendered = renderer.render(java, true);
        assertTrue(MarkdownTestSupport.containsType(rendered, CodeBlockPanel.class));
        assertFalse(MarkdownTestSupport.containsType(rendered, MermaidDiagramPanel.class));
    }

    @Test
    public void nestedMarkdownWrapperStaysCodeWhileStreaming() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        String wrapped = "```markdown\n```mermaid\ngraph TD\nA-->B\n```\n```";
        java.awt.Component rendered = renderer.render(wrapped, false); // streaming
        assertFalse("no diagram while streaming",
                MarkdownTestSupport.containsType(rendered, MermaidDiagramPanel.class));
        assertTrue(MarkdownTestSupport.containsType(rendered, CodeBlockPanel.class));
    }

    @Test
    public void doublyNestedMarkdownDoesNotRecurseBeyondOneLevel() {
        FlexmarkSwingRenderer renderer = renderer(new FakeMermaidImageRenderer());
        // Outer markdown wraps an inner markdown that wraps mermaid: only one level is unwrapped, so the
        // inner markdown stays a code block and no diagram is produced (and it must not loop forever).
        String doublyNested = "````markdown\n```markdown\n```mermaid\ngraph TD\nA-->B\n```\n```\n````";
        java.awt.Component rendered = renderer.render(doublyNested, true);
        assertTrue(MarkdownTestSupport.containsType(rendered, CodeBlockPanel.class));
        assertFalse(MarkdownTestSupport.containsType(rendered, MermaidDiagramPanel.class));
    }

    @Test
    public void diagramPanelExposesItsSourceForCopying() {
        MermaidDiagramPanel panel = new MermaidDiagramPanel(
                "graph LR\nA-->B", MarkdownTheme.fromUiDefaults(), new FakeMermaidImageRenderer());
        assertEquals("graph LR\nA-->B", panel.getDiagramCode());
    }

    @Test
    public void imageTransferableCarriesTheRenderedImage() throws Exception {
        BufferedImage rendered = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
        Transferable transferable = MermaidDiagramPanel.imageTransferable(rendered);
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.imageFlavor));
        assertSame(rendered, transferable.getTransferData(DataFlavor.imageFlavor));
    }
}
