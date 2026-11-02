package com.aresstack.askai.ui.markdown;

import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Guards the wrapped-paragraph height contract deterministically: after exactly one normal layout pass a
 * paragraph has its full wrapped height and the following heading starts below it — no invalidate/layout
 * loop, i.e. no reliance on a later "self-healing" second pass.
 */
public class WrappingTextPaneLayoutTest {

    private static final String PARAGRAPH =
            "This is a deliberately long paragraph that must wrap onto several lines when the chat bubble "
            + "is narrow, so that its measured height reflects the real number of wrapped lines and not a "
            + "single line height computed before the final width was known to the layout manager.";

    private static final String DOCUMENT =
            "# First Heading\n\n" + PARAGRAPH + "\n\n## Second Heading\n\nShort tail.";

    @Test
    public void wrappedHeightGrowsAsTheWidthShrinks() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                WrappingTextPane pane = new WrappingTextPane();
                pane.setText(PARAGRAPH);
                int wide = pane.heightForWidth(600);
                int narrow = pane.heightForWidth(240);
                assertTrue("narrower width wraps to more lines -> taller (" + narrow + " > " + wide + ")",
                        narrow > wide);
            }
        });
    }

    @Test
    public void preferredHeightForWidthIsWidthAware() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                MarkdownMessageView view = new MarkdownMessageView(MarkdownTheme.fromUiDefaults());
                view.setMarkdown(DOCUMENT);
                assertTrue("narrower render area needs more height",
                        view.preferredHeightForWidth(240) > view.preferredHeightForWidth(620));
            }
        });
    }

    @Test
    public void aSingleLayoutPassGivesTheParagraphItsFullHeightBelowTheHeading() throws Exception {
        onEdt(new Runnable() {
            public void run() {
                MarkdownMessageView view = new MarkdownMessageView(MarkdownTheme.fromUiDefaults());
                view.setMarkdown(DOCUMENT);

                view.setSize(320, 10_000);
                layoutOnce(view); // exactly one top-down layout pass — no invalidate loop

                WrappingTextPane paragraph = paneContaining(view, "deliberately long paragraph");
                WrappingTextPane secondHeading = paneContaining(view, "Second Heading");

                int lineHeight = paragraph.getFontMetrics(paragraph.getFont()).getHeight();
                assertTrue("paragraph is not clipped to one line (h=" + paragraph.getHeight()
                                + ", line=" + lineHeight + ")",
                        paragraph.getHeight() >= lineHeight * 3);

                int paragraphBottom = yRelativeTo(view, paragraph) + paragraph.getHeight();
                assertTrue("second heading starts below the full paragraph",
                        yRelativeTo(view, secondHeading) >= paragraphBottom);
            }
        });
    }

    // --- helpers ---

    /** One normal top-down layout pass (what a single validate() does), NOT an invalidate/layout loop. */
    private static void layoutOnce(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                layoutOnce((Container) child);
            }
        }
    }

    private static WrappingTextPane paneContaining(Component root, String needle) {
        List<WrappingTextPane> panes = new ArrayList<WrappingTextPane>();
        collect(root, panes);
        for (WrappingTextPane pane : panes) {
            if (pane.getText().contains(needle)) {
                return pane;
            }
        }
        throw new AssertionError("no WrappingTextPane containing '" + needle + "'");
    }

    private static void collect(Component component, List<WrappingTextPane> found) {
        if (component instanceof WrappingTextPane) {
            found.add((WrappingTextPane) component);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collect(child, found);
            }
        }
    }

    private static int yRelativeTo(Component ancestor, Component component) {
        int y = 0;
        Component current = component;
        while (current != null && current != ancestor) {
            y += current.getY();
            current = current.getParent();
        }
        return y;
    }

    private static void onEdt(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException) {
                throw (RuntimeException) ex.getCause();
            }
            if (ex.getCause() instanceof Error) {
                throw (Error) ex.getCause();
            }
            throw ex;
        }
    }
}
