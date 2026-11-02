package com.aresstack.askai.ui.markdown;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.MailLink;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.util.ast.Node;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Cursor;
import java.awt.Font;

/** Convert inline Flexmark nodes into StyledDocument attributes. */
final class InlineMarkdownRenderer {

    private final MarkdownTheme theme;
    private final DesktopLinkOpener linkOpener;

    InlineMarkdownRenderer(MarkdownTheme theme, DesktopLinkOpener linkOpener) {
        this.theme = theme;
        this.linkOpener = linkOpener;
    }

    WrappingTextPane render(Node container, Font font) {
        WrappingTextPane pane = new WrappingTextPane();
        pane.setFont(font);
        pane.setForeground(theme.getForeground());
        renderChildren(container, pane.getStyledDocument(), attributes(font));
        installLinkHandling(pane);
        return pane;
    }

    private void renderChildren(Node parent, StyledDocument document, SimpleAttributeSet inherited) {
        Node node = parent.getFirstChild();
        while (node != null) {
            renderNode(node, document, inherited);
            node = node.getNext();
        }
    }

    private void renderNode(Node node, StyledDocument document, SimpleAttributeSet inherited) {
        if (node instanceof Text) {
            append(document, node.getChars().toString(), inherited);
            return;
        }
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            append(document, "\n", inherited);
            return;
        }
        if (node instanceof Code) {
            SimpleAttributeSet code = copy(inherited);
            StyleConstants.setFontFamily(code, theme.getCodeFont().getFamily());
            StyleConstants.setBackground(code, theme.getCodeBackground());
            append(document, ((Code) node).getText().toString(), code);
            return;
        }
        if (node instanceof StrongEmphasis) {
            SimpleAttributeSet bold = copy(inherited);
            StyleConstants.setBold(bold, true);
            renderChildren(node, document, bold);
            return;
        }
        if (node instanceof Emphasis) {
            SimpleAttributeSet italic = copy(inherited);
            StyleConstants.setItalic(italic, true);
            renderChildren(node, document, italic);
            return;
        }
        if (node instanceof Strikethrough) {
            SimpleAttributeSet strike = copy(inherited);
            StyleConstants.setStrikeThrough(strike, true);
            renderChildren(node, document, strike);
            return;
        }
        if (node instanceof Link) {
            Link link = (Link) node;
            renderLink(link, link.getUrl().toString(), document, inherited);
            return;
        }
        if (node instanceof AutoLink) {
            AutoLink link = (AutoLink) node;
            renderStandaloneLink(link.getText().toString(), link.getUrl().toString(), document, inherited);
            return;
        }
        if (node instanceof MailLink) {
            MailLink link = (MailLink) node;
            String address = link.getText().toString();
            renderStandaloneLink(address, "mailto:" + address, document, inherited);
            return;
        }
        if (node.hasChildren()) {
            renderChildren(node, document, inherited);
            return;
        }
        append(document, node.getChars().toString(), inherited);
    }


    private void renderLink(Node link, String url, StyledDocument document, SimpleAttributeSet inherited) {
        SimpleAttributeSet linkAttributes = linkAttributes(inherited, url);
        if (link.hasChildren()) {
            renderChildren(link, document, linkAttributes);
        } else {
            append(document, url, linkAttributes);
        }
    }

    private void renderStandaloneLink(String text, String url, StyledDocument document,
                                      SimpleAttributeSet inherited) {
        append(document, text, linkAttributes(inherited, url));
    }

    private SimpleAttributeSet linkAttributes(SimpleAttributeSet inherited, String url) {
        SimpleAttributeSet attributes = copy(inherited);
        StyleConstants.setForeground(attributes, theme.getLinkForeground());
        StyleConstants.setUnderline(attributes, true);
        attributes.addAttribute("markdown.href", url);
        return attributes;
    }

    private SimpleAttributeSet attributes(Font font) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attributes, font.getFamily());
        StyleConstants.setFontSize(attributes, font.getSize());
        StyleConstants.setForeground(attributes, theme.getForeground());
        return attributes;
    }

    private SimpleAttributeSet copy(SimpleAttributeSet source) {
        return new SimpleAttributeSet(source);
    }

    private void append(StyledDocument document, String text, SimpleAttributeSet attributes) {
        try {
            document.insertString(document.getLength(), text, attributes);
        } catch (BadLocationException ignored) {
            // Keep rendering remaining nodes when one insertion fails.
        }
    }

    private void installLinkHandling(final JTextPane pane) {
        pane.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        LinkMouseListener listener = new LinkMouseListener(pane, linkOpener);
        pane.addMouseListener(listener);
        pane.addMouseMotionListener(listener);
    }
}
