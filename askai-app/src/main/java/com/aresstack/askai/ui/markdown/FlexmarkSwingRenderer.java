package com.aresstack.askai.ui.markdown;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.Arrays;

/** Map Flexmark block nodes to native Swing components. */
final class FlexmarkSwingRenderer {

    private final Parser parser;
    private final MarkdownTheme theme;
    private final InlineMarkdownRenderer inlineRenderer;
    private final MermaidImageRenderer mermaidImageRenderer;

    FlexmarkSwingRenderer(MarkdownTheme theme, DesktopLinkOpener linkOpener,
                          MermaidImageRenderer mermaidImageRenderer) {
        this.theme = theme;
        this.inlineRenderer = new InlineMarkdownRenderer(theme, linkOpener);
        this.mermaidImageRenderer = mermaidImageRenderer;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create()));
        this.parser = Parser.builder(options).build();
    }

    JPanel render(String markdown, boolean renderMermaid) {
        Node document = parser.parse(markdown == null ? "" : markdown);
        return renderContainer(document, renderMermaid, true);
    }

    private JPanel renderContainer(Node container, boolean renderMermaid, boolean allowUnwrap) {
        JPanel panel = verticalPanel();
        Node child = container.getFirstChild();
        while (child != null) {
            JComponent component = renderBlock(child, renderMermaid, allowUnwrap);
            if (component != null) {
                component.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(component);
                panel.add(Box.createVerticalStrut(blockGap(child)));
            }
            child = child.getNext();
        }
        return panel;
    }

    private JComponent renderBlock(Node node, boolean renderMermaid, boolean allowUnwrap) {
        if (node instanceof com.vladsch.flexmark.ast.HtmlCommentBlock) {
            // Internal metadata such as <!-- askai:model-id=... --> stays in the Markdown source but must
            // not paint a visible component. No HTML is rendered or executed; only comments are hidden —
            // every other unknown node keeps the generic fallback below.
            return null;
        }
        if (node instanceof Heading) {
            return renderHeading((Heading) node);
        }
        if (node instanceof Paragraph) {
            return inlineRenderer.render(node, theme.getBodyFont());
        }
        if (node instanceof FencedCodeBlock) {
            return renderFencedCode((FencedCodeBlock) node, renderMermaid, allowUnwrap);
        }
        if (node instanceof IndentedCodeBlock) {
            String code = ((IndentedCodeBlock) node).getContentChars().toString();
            return code.trim().isEmpty() ? null : new CodeBlockPanel("", code, theme);
        }
        if (node instanceof BulletList) {
            return renderList(node, false, 1, renderMermaid, allowUnwrap);
        }
        if (node instanceof OrderedList) {
            return renderList(node, true, ((OrderedList) node).getStartNumber(), renderMermaid, allowUnwrap);
        }
        if (node instanceof BlockQuote) {
            return renderQuote(node, renderMermaid, allowUnwrap);
        }
        if (node instanceof TableBlock) {
            return new MarkdownTablePanel((TableBlock) node, theme);
        }
        if (node instanceof ThematicBreak) {
            JSeparator separator = new JSeparator();
            separator.setForeground(theme.getSeparatorColor());
            return separator;
        }
        if (node.hasChildren()) {
            return renderContainer(node, renderMermaid, allowUnwrap);
        }
        JLabel fallback = new JLabel(node.getChars().toString());
        fallback.setFont(theme.getBodyFont());
        fallback.setForeground(theme.getForeground());
        return fallback;
    }

    private JComponent renderHeading(Heading heading) {
        int level = Math.max(1, Math.min(6, heading.getLevel()));
        int bodySize = theme.getBodyFont().getSize();
        int size = Math.max(bodySize + 1, bodySize + (7 - level) * 2);
        Font font = theme.getBodyFont().deriveFont(Font.BOLD, size);
        return inlineRenderer.render(heading, font);
    }

    private JComponent renderFencedCode(FencedCodeBlock block, boolean renderMermaid, boolean allowUnwrap) {
        String language = block.getInfo().toString().trim();
        String code = block.getContentChars().toString();
        if ("mermaid".equalsIgnoreCase(language) && renderMermaid) {
            return new MermaidDiagramPanel(code, theme, mermaidImageRenderer);
        }
        // A model sometimes wraps a real Mermaid fence inside an extra ```markdown block. Unwrap that one
        // container (at most a single extra level, and only once the answer is complete) so the diagram
        // renders — but never re-interpret an ordinary code block (java/json/…) or a mere documentation
        // snippet that has no complete inner Mermaid fence.
        if (allowUnwrap && renderMermaid && isContainerLanguage(language) && containsMermaidFence(code)) {
            return renderContainer(parser.parse(code), renderMermaid, false);
        }
        if (code.trim().isEmpty()) {
            return null; // don't paint a big empty box for a blank fence
        }
        return new CodeBlockPanel(language, code, theme);
    }

    private static boolean isContainerLanguage(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase();
        return normalized.equals("markdown") || normalized.equals("md")
                || normalized.equals("commonmark") || normalized.equals("gfm");
    }

    /** @return true when the fence content, parsed as Markdown, yields at least one direct Mermaid fence. */
    private boolean containsMermaidFence(String content) {
        Node document = parser.parse(content == null ? "" : content);
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof FencedCodeBlock
                    && "mermaid".equalsIgnoreCase(((FencedCodeBlock) child).getInfo().toString().trim())) {
                return true;
            }
        }
        return false;
    }

    private JComponent renderList(Node list, boolean ordered, int startNumber, boolean renderMermaid,
                                 boolean allowUnwrap) {
        JPanel listPanel = verticalPanel();
        listPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        Node child = list.getFirstChild();
        int number = startNumber;
        while (child != null) {
            if (child instanceof ListItem) {
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(false);
                JLabel marker = new JLabel(ordered ? number + "." : "•");
                marker.setFont(theme.getBodyFont());
                marker.setForeground(theme.getForeground());
                marker.setVerticalAlignment(JLabel.TOP);
                row.add(marker, BorderLayout.WEST);
                row.add(renderContainer(child, renderMermaid, allowUnwrap), BorderLayout.CENTER);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                listPanel.add(row);
                listPanel.add(Box.createVerticalStrut(2));
                number++;
            }
            child = child.getNext();
        }
        return listPanel;
    }

    private JComponent renderQuote(Node quote, boolean renderMermaid, boolean allowUnwrap) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, theme.getQuoteBorder()),
                BorderFactory.createEmptyBorder(1, 10, 1, 0)));
        wrapper.add(renderContainer(quote, renderMermaid, allowUnwrap), BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel verticalPanel() {
        // A deterministic vertical stack (not BoxLayout): each block is measured at the real width during
        // layout, so wrapping paragraphs get their correct height on the first pass. See MarkdownStackPanel.
        return new MarkdownStackPanel();
    }

    private int blockGap(Node node) {
        if (node instanceof Heading) {
            return 6;
        }
        if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof BlockQuote) {
            return 10;
        }
        return 5;
    }
}
