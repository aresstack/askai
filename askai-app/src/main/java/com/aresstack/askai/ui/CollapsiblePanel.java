package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.theme.ThemeColor;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;

/**
 * A lightweight disclosure section: a full-width header button that toggles a content component in
 * and out of view. Collapsed by default, so technical or advanced controls (e.g. "Chat settings",
 * "Technical details") stay out of the way until the user asks for them.
 *
 * <p>No animation and no dependency on a look-and-feel: the header shows a ▸/▾ marker plus the title,
 * and toggling simply flips the content's visibility and revalidates. The parent layout should let
 * the content take its preferred height (e.g. BorderLayout NORTH/SOUTH or a vertical BoxLayout).</p>
 */
public final class CollapsiblePanel extends JPanel {

    private final JButton header = new ComicButton();
    private final JComponent content;
    private final String title;
    private boolean expanded;

    public CollapsiblePanel(String title, JComponent content) {
        this(title, content, false);
    }

    public CollapsiblePanel(String title, JComponent content, boolean expanded) {
        super(new BorderLayout(0, 0));
        this.title = title;
        this.content = content;
        this.expanded = expanded;
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        header.setHorizontalAlignment(JButton.LEFT);
        header.setFocusPainted(false);
        header.setBorderPainted(false);
        header.setContentAreaFilled(false);
        header.setForeground(ThemeColor.of("app.collapsibleHeaderText"));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        header.addActionListener(event -> setExpanded(!this.expanded));

        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        applyState();
    }

    /** Programmatically expand or collapse the section. */
    public void setExpanded(boolean value) {
        this.expanded = value;
        applyState();
    }

    public boolean isExpanded() {
        return expanded;
    }

    private void applyState() {
        header.setText((expanded ? "▾  " : "▸  ") + title);
        content.setVisible(expanded);
        revalidate();
        repaint();
    }
}
