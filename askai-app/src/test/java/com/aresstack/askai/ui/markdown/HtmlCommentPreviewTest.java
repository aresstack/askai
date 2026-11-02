package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Internal {@code <!-- askai:... -->} metadata comments stay in the Markdown source (the batch writer
 * needs them for structured upserts) but must not produce any visible component in the Swing preview.
 * Other unknown nodes keep the generic fallback.
 */
public class HtmlCommentPreviewTest {

    private FlexmarkSwingRenderer renderer() {
        return new FlexmarkSwingRenderer(MarkdownTheme.fromUiDefaults(),
                DesktopLinkOpener.systemDefault(), new FakeMermaidImageRenderer());
    }

    @Test
    public void modelMetadataCommentIsInvisibleInThePreview() {
        Component rendered = renderer().render(
                "# gemma4:e2b\n\n<!-- askai:model-id=gemma4:e2b -->\n\nBody text.", true);
        assertFalse("metadata comment must not be visible",
                visibleText(rendered).contains("askai:model-id"));
        assertTrue("surrounding content stays visible", visibleText(rendered).contains("Body text."));
        assertTrue(visibleText(rendered).contains("gemma4:e2b")); // the heading, not the comment
    }

    @Test
    public void profileMetadataCommentIsInvisibleInThePreview() {
        Component rendered = renderer().render(
                "## Audio profile: Off\n\n<!-- askai:profile-id=off -->\n\nTranscribed words.", true);
        String text = visibleText(rendered);
        assertFalse(text.contains("askai:profile-id"));
        assertFalse(text.contains("<!--"));
        assertTrue(text.contains("Audio profile: Off"));
        assertTrue(text.contains("Transcribed words."));
    }

    @Test
    public void textBeforeAndAfterACommentStaysVisible() {
        Component rendered = renderer().render(
                "Before the comment.\n\n<!-- askai:profile-id=default-speech -->\n\nAfter the comment.", true);
        String text = visibleText(rendered);
        assertTrue(text.contains("Before the comment."));
        assertTrue(text.contains("After the comment."));
        assertFalse(text.contains("askai:"));
    }

    @Test
    public void theCommentSurvivesInTheStoredMarkdown() throws Exception {
        final String source = "# m\n\n<!-- askai:model-id=m -->\n\ntext";
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                MarkdownMessageView view = new MarkdownMessageView(MarkdownTheme.fromUiDefaults());
                view.setMarkdown(source);
                assertEquals("the source is never rewritten by the preview", source, view.getMarkdown());
            }
        });
    }

    @Test
    public void otherUnknownHtmlKeepsTheGenericFallback() {
        // A non-comment HTML block has no dedicated branch and must still fall back to visible raw text —
        // hiding is strictly limited to comment nodes.
        Component rendered = renderer().render("<div>raw html block</div>", true);
        assertTrue("non-comment unknown nodes keep their fallback",
                visibleText(rendered).contains("raw html block"));
    }

    // --- helpers ---

    /** All user-visible text in the rendered tree (labels + text components). */
    private static String visibleText(Component root) {
        StringBuilder text = new StringBuilder();
        List<JLabel> labels = MarkdownTestSupport.collect(root, JLabel.class);
        for (JLabel label : labels) {
            text.append(label.getText()).append('\n');
        }
        List<JTextComponent> panes = MarkdownTestSupport.collect(root, JTextComponent.class);
        for (JTextComponent pane : panes) {
            text.append(pane.getText()).append('\n');
        }
        return text.toString();
    }
}
