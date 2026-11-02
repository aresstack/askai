package com.aresstack.askai.ui.markdown;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.StyledDocument;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Open custom StyledDocument links because JTextPane hyperlink events require an HTML document. */
final class LinkMouseListener extends MouseAdapter {

    private final JTextPane pane;
    private final DesktopLinkOpener linkOpener;

    LinkMouseListener(JTextPane pane, DesktopLinkOpener linkOpener) {
        this.pane = pane;
        this.linkOpener = linkOpener;
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        pane.setCursor(findLink(event) == null
                ? Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public void mouseClicked(MouseEvent event) {
        if (event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        String link = findLink(event);
        if (link != null) {
            linkOpener.open(link);
        }
    }

    private String findLink(MouseEvent event) {
        int position = pane.viewToModel(event.getPoint());
        if (position < 0) {
            return null;
        }
        StyledDocument document = pane.getStyledDocument();
        Element element = document.getCharacterElement(position);
        AttributeSet attributes = element.getAttributes();
        Object value = attributes.getAttribute("markdown.href");
        return value == null ? null : String.valueOf(value);
    }
}
