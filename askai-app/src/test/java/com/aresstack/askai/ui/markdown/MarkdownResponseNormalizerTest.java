package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The normalizer unwraps a single outer Markdown container but never touches real code fences. */
public class MarkdownResponseNormalizerTest {

    private static String complete(String raw) {
        return MarkdownResponseNormalizer.normalize(raw, true);
    }

    private static String streaming(String raw) {
        return MarkdownResponseNormalizer.normalize(raw, false);
    }

    @Test
    public void unwrapsAnOuterMarkdownFence() {
        String raw = "```markdown\n# Heading\n\nNormal **text**\n```";
        assertEquals("# Heading\n\nNormal **text**", complete(raw));
    }

    @Test
    public void unwrapsOuterMdCommonmarkAndGfmFences() {
        assertEquals("# H", complete("```md\n# H\n```"));
        assertEquals("# H", complete("```commonmark\n# H\n```"));
        assertEquals("# H", complete("```gfm\n# H\n```"));
    }

    @Test
    public void keepsARealJavaCodeFence() {
        String raw = "```java\nSystem.out.println(\"Hallo\");\n```";
        assertEquals(raw, complete(raw));
    }

    @Test
    public void keepsAMermaidFence() {
        String raw = "```mermaid\ngraph TD\n  A --> B\n```";
        assertEquals(raw, complete(raw));
    }

    @Test
    public void leavesAnInnerCodeBlockInsideNormalMarkdownUntouched() {
        String raw = "Here is Java code:\n\n```java\nSystem.out.println(1);\n```";
        assertEquals(raw, complete(raw));
    }

    @Test
    public void doesNotStripWhenContentFollowsTheClosingFence() {
        String raw = "```markdown\n# H\n```\nTrailing text after the fence.";
        assertEquals(raw, complete(raw));
    }

    @Test
    public void unwrapsAnOuterFenceThatItselfContainsCodeFences() {
        String raw = "```markdown\n# Title\n\n```java\nint x = 1;\n```\n```";
        String expected = "# Title\n\n```java\nint x = 1;\n```";
        assertEquals(expected, complete(raw));
    }

    @Test
    public void streamingStripsAStillOpenOuterContainerWithoutLosingContent() {
        String raw = "```markdown\n# Heading\n\nStill streaming";
        assertEquals("# Heading\n\nStill streaming", streaming(raw));
    }

    @Test
    public void completeLeavesAnUnclosedOuterFenceUntouched() {
        String raw = "```markdown\n# Heading\n\nNo closing fence yet";
        assertEquals(raw, complete(raw));
    }

    @Test
    public void afterUnwrappingHeadingsRenderAsMarkdownNotACodeBlock() {
        String wrapped = "```markdown\n# Heading\n\nNormal **text**\n```";
        String normalized = complete(wrapped);
        FlexmarkSwingRenderer renderer = new FlexmarkSwingRenderer(
                MarkdownTheme.fromUiDefaults(), DesktopLinkOpener.systemDefault(),
                new FakeMermaidImageRenderer());
        java.awt.Component rendered = renderer.render(normalized, true);
        assertTrue("headings/paragraphs must render as text panes",
                MarkdownTestSupport.containsType(rendered, WrappingTextPane.class));
        assertFalse("the answer must not be one big code block",
                MarkdownTestSupport.containsType(rendered, CodeBlockPanel.class));
    }

    @Test
    public void blankAndNullAnswersAreHandled() {
        assertEquals("", complete(null));
        assertEquals("   \n  ", complete("   \n  "));
    }
}
