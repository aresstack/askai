package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicButton;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;

/**
 * The uniform container of the full-window screens (settings pages, Help > Actions, Help > About): the screen's
 * content, and below it ONE footer in three equal thirds — "Back" on the left, an optional component exactly
 * centered (e.g. "Copy diagnostics"), an optional one right-aligned (e.g. the copyright).
 */
public final class FooterScreen extends JPanel {

    private static final long serialVersionUID = 1L;
    /** The footer's side/bottom padding (as the About screen's border). */
    static final int FOOTER_PADDING = 16;

    private final JPanel center = third(FlowLayout.CENTER);
    private final JPanel trailing = new JPanel(new GridBagLayout());
    private final JButton backButton;

    public FooterScreen(JComponent content, final Runnable back) {
        super(new BorderLayout());
        add(content, BorderLayout.CENTER);
        JPanel footer = new JPanel(new GridLayout(1, 3));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(8, FOOTER_PADDING, FOOTER_PADDING, FOOTER_PADDING));
        JPanel left = third(FlowLayout.LEFT);
        backButton = new ComicButton("Back", ChatComposerPanel.createBackGlyphIcon());
        backButton.setIconTextGap(8);
        backButton.setFocusable(false);
        backButton.addActionListener(event -> back.run());
        left.add(backButton);
        footer.add(left);
        footer.add(center);
        trailing.setOpaque(false);
        footer.add(trailing);
        add(footer, BorderLayout.SOUTH);
    }

    /** A component exactly centered in the footer. */
    public FooterScreen withCenter(JComponent component) {
        center.add(component);
        return this;
    }

    /** A component right-aligned in the footer. */
    public FooterScreen withTrailing(JComponent component) {
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.anchor = GridBagConstraints.EAST;
        trailing.add(component, c);
        return this;
    }

    JButton backButton() {
        return backButton;
    }

    private static JPanel third(int alignment) {
        JPanel panel = new JPanel(new FlowLayout(alignment, 0, 0));
        panel.setOpaque(false);
        return panel;
    }
}
