package com.aresstack.comiccontrols.control;

import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JViewport;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays a settings page out at a given window size (headless, no peer needed) and reports every
 * component that sticks out of its parent or of a viewport that must not scroll sideways. Every tab of
 * every tabbed pane is visited. Used by the tests that keep ALL settings pages inside the window.
 */
public final class ComicLayoutAudit {

    private static final int TOLERANCE = 1;

    private ComicLayoutAudit() {
    }

    /** The overflows of {@code root} laid out at {@code width} x {@code height}; empty = fits. */
    public static List<String> overflows(Component root, int width, int height) {
        List<String> problems = new ArrayList<String>();
        root.setSize(width, height);
        layout(root);
        collect(root, "", problems);
        return problems;
    }

    private static void layout(Component component) {
        // Twice: width-dependent preferred sizes (wrapping hints, wrap rows) see their final width.
        for (int pass = 0; pass < 3; pass++) {
            layoutTree(component);
        }
    }

    private static void layoutTree(Component component) {
        if (!(component instanceof Container)) {
            return;
        }
        Container container = (Container) component;
        container.doLayout();
        for (Component child : container.getComponents()) {
            layoutTree(child);
        }
    }

    private static void collect(Component component, String path, List<String> problems) {
        if (!(component instanceof Container)) {
            return;
        }
        Container container = (Container) component;
        if (container instanceof JTabbedPane) {
            JTabbedPane tabs = (JTabbedPane) container;
            int selected = tabs.getSelectedIndex();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                tabs.setSelectedIndex(i);
                Component tab = tabs.getComponentAt(i);
                layout(tabs);
                String tabPath = path + "/[" + tabs.getTitleAt(i) + "]";
                check(tabs, tab, tabPath, problems);
                if (visibleLeaves(tab) == 0) {
                    problems.add(tabPath + " shows nothing");
                }
                collect(tab, tabPath, problems);
            }
            if (selected >= 0) {
                tabs.setSelectedIndex(selected);
                layout(tabs);
            }
            return;
        }
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            String childPath = path + "/" + name(child);
            check(container, child, childPath, problems);
            collect(child, childPath, problems);
        }
    }

    private static int visibleLeaves(Component component) {
        if (!component.isVisible() || component.getWidth() <= 0 || component.getHeight() <= 0) {
            return 0;
        }
        if (!(component instanceof Container) || ((Container) component).getComponentCount() == 0) {
            return 1;
        }
        int count = 0;
        for (Component child : ((Container) component).getComponents()) {
            count += visibleLeaves(child);
        }
        return count;
    }

    private static void check(Container parent, Component child, String path, List<String> problems) {
        if (parent instanceof JViewport) {
            Component scroll = parent.getParent();
            boolean sideways = scroll instanceof JScrollPane && ((JScrollPane) scroll)
                    .getHorizontalScrollBarPolicy() != JScrollPane.HORIZONTAL_SCROLLBAR_NEVER;
            if (!sideways && child.getWidth() > parent.getWidth() + TOLERANCE) {
                problems.add(path + " is " + child.getWidth() + "px wide in a " + parent.getWidth()
                        + "px viewport without a horizontal scroll bar");
            }
            return; // a scrolling view may be placed anywhere inside its viewport
        }
        if (child instanceof javax.swing.JTextArea && ((javax.swing.JTextArea) child).getLineWrap()
                && child.getWidth() > 0 && child.getPreferredSize().height > child.getHeight() + TOLERANCE) {
            problems.add(path + " cuts its wrapped text off: " + child.getHeight() + "px of "
                    + child.getPreferredSize().height + "px shown");
        }
        if (child.getWidth() <= 0 || parent.getWidth() <= 0) {
            return;
        }
        if (child.getX() < -TOLERANCE || child.getX() + child.getWidth() > parent.getWidth() + TOLERANCE) {
            problems.add(path + " spans x=" + child.getX() + ".." + (child.getX() + child.getWidth())
                    + " outside its " + parent.getWidth() + "px parent");
        }
    }

    private static String name(Component component) {
        String name = component.getName();
        String type = component.getClass().getSimpleName();
        if (type.isEmpty()) {
            type = component.getClass().getName();
        }
        if (component instanceof javax.swing.JLabel) {
            return type + "'" + ((javax.swing.JLabel) component).getText() + "'";
        }
        if (component instanceof javax.swing.AbstractButton) {
            return type + "'" + ((javax.swing.AbstractButton) component).getText() + "'";
        }
        return name == null ? type : type + "#" + name;
    }
}
