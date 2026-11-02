package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import javax.swing.Icon;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Verify Flexmark block nodes map to native Swing components (no HTML execution, no remote images). */
public class MarkdownRenderingTest {

    private final FlexmarkSwingRenderer renderer = new FlexmarkSwingRenderer(
            MarkdownTheme.fromUiDefaults(), DesktopLinkOpener.systemDefault(), new FakeMermaidImageRenderer());

    @Test
    public void paragraphRendersAsAWrappingTextPane() {
        JPanel panel = renderer.render("Just a paragraph.", true);
        List<WrappingTextPane> panes = MarkdownTestSupport.collect(panel, WrappingTextPane.class);
        assertFalse(panes.isEmpty());
        assertTrue(panes.get(0).getText().contains("Just a paragraph."));
    }

    @Test
    public void headingsUseGradedFontSizes() {
        WrappingTextPane h1 = MarkdownTestSupport.collect(renderer.render("# Big", true),
                WrappingTextPane.class).get(0);
        WrappingTextPane h2 = MarkdownTestSupport.collect(renderer.render("## Small", true),
                WrappingTextPane.class).get(0);
        assertTrue("h1 larger than h2: " + h1.getFont().getSize() + " vs " + h2.getFont().getSize(),
                h1.getFont().getSize() > h2.getFont().getSize());
    }

    @Test
    public void boldTextProducesABoldRun() {
        WrappingTextPane pane = MarkdownTestSupport.collect(renderer.render("normal **bold** normal", true),
                WrappingTextPane.class).get(0);
        assertTrue("a bold character run exists", hasBoldRun(pane.getStyledDocument()));
    }

    @Test
    public void javaCodeBlockRendersAsCodeBlockPanel() {
        JPanel panel = renderer.render("```java\nint x = 1;\n```", true);
        assertTrue(MarkdownTestSupport.containsType(panel, CodeBlockPanel.class));
        assertFalse(MarkdownTestSupport.containsType(panel, MermaidDiagramPanel.class));
    }

    @Test
    public void tableRendersAsTablePanel() {
        JPanel panel = renderer.render("| A | B |\n|---|---|\n| 1 | 2 |\n", true);
        assertTrue(MarkdownTestSupport.containsType(panel, MarkdownTablePanel.class));
    }

    @Test
    public void rawHtmlIsShownLiterallyNotExecuted() {
        JPanel panel = renderer.render("<b>not bold html</b>\n\n<script>alert('x')</script>", true);
        // Model output is never rendered through an HTML content type / HTMLEditorKit.
        for (JEditorPane pane : MarkdownTestSupport.collect(panel, JEditorPane.class)) {
            assertFalse("no HTML content type", "text/html".equals(pane.getContentType()));
        }
        // The raw HTML tag survives verbatim as inert text — shown, not interpreted/executed.
        assertTrue("raw HTML kept as literal text", treeTextContains(panel, "<b>not bold html</b>"));
    }

    @Test
    public void remoteImagesAreNotLoaded() {
        JPanel panel = renderer.render("![alt text](http://example.invalid/pic.png)", true);
        for (JLabel label : MarkdownTestSupport.collect(panel, JLabel.class)) {
            Icon icon = label.getIcon();
            assertTrue("no image icon loaded from a URL", icon == null);
        }
        // The alt text is shown instead of fetching the image.
        assertTrue(treeTextContains(panel, "alt text"));
    }

    private static boolean hasBoldRun(StyledDocument document) {
        for (int i = 0; i < document.getLength(); i++) {
            Element element = document.getCharacterElement(i);
            if (StyleConstants.isBold(element.getAttributes())) {
                return true;
            }
        }
        return false;
    }

    private static boolean treeTextContains(java.awt.Component root, String needle) {
        for (WrappingTextPane pane : MarkdownTestSupport.collect(root, WrappingTextPane.class)) {
            if (pane.getText() != null && pane.getText().contains(needle)) {
                return true;
            }
        }
        for (JLabel label : MarkdownTestSupport.collect(root, JLabel.class)) {
            if (label.getText() != null && label.getText().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
