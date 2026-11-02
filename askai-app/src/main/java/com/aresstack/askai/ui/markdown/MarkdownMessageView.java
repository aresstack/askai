package com.aresstack.askai.ui.markdown;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;

/** Provide a reusable assistant-message component with debounced streaming updates. */
public final class MarkdownMessageView extends JPanel implements WidthAwareHeight {

    private static final int STREAM_RENDER_DELAY_MILLIS = 90;

    private final StringBuilder markdown = new StringBuilder();
    private final FlexmarkSwingRenderer renderer;
    private final Timer renderTimer;
    private boolean streaming;

    public MarkdownMessageView() {
        this(MarkdownTheme.fromUiDefaults(), DesktopLinkOpener.systemDefault(),
                CachingMermaidImageRenderer.shared());
    }

    public MarkdownMessageView(MarkdownTheme theme) {
        this(theme, DesktopLinkOpener.systemDefault(), CachingMermaidImageRenderer.shared());
    }

    public MarkdownMessageView(MarkdownTheme theme, DesktopLinkOpener linkOpener) {
        this(theme, linkOpener, CachingMermaidImageRenderer.shared());
    }

    public MarkdownMessageView(MarkdownTheme theme, DesktopLinkOpener linkOpener,
                               MermaidImageRenderer mermaidImageRenderer) {
        this.renderer = new FlexmarkSwingRenderer(theme, linkOpener, mermaidImageRenderer);
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        this.renderTimer = new Timer(STREAM_RENDER_DELAY_MILLIS, event -> renderNow());
        this.renderTimer.setRepeats(false);
        renderNow();
    }

    public void setMarkdown(String value) {
        assertEventDispatchThread();
        streaming = false;
        renderTimer.stop();
        markdown.setLength(0);
        if (value != null) {
            markdown.append(value);
        }
        renderNow();
    }

    public void startStreaming() {
        assertEventDispatchThread();
        streaming = true;
        markdown.setLength(0);
        renderNow();
    }

    public void appendMarkdownDelta(String delta) {
        assertEventDispatchThread();
        if (delta == null || delta.isEmpty()) {
            return;
        }
        markdown.append(delta);
        if (streaming) {
            renderTimer.restart();
        } else {
            renderNow();
        }
    }

    public void finishStreaming() {
        assertEventDispatchThread();
        streaming = false;
        renderTimer.stop();
        renderNow();
    }

    public String getMarkdown() {
        return markdown.toString();
    }

    /**
     * Deterministically compute the height this view needs at the given width, so a host that knows the
     * target width (a chat bubble/row) gets the correct height on the first measurement — no reliance on a
     * later Swing layout pass to correct a wrapped paragraph.
     */
    @Override
    public int preferredHeightForWidth(int width) {
        java.awt.Insets insets = getInsets();
        java.awt.Component content = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
        int contentHeight = MarkdownHeights.forWidth(content, width - insets.left - insets.right);
        return contentHeight + insets.top + insets.bottom;
    }

    private void renderNow() {
        removeAll();
        boolean complete = !streaming;
        String normalized = MarkdownResponseNormalizer.normalize(markdown.toString(), complete);
        add(renderer.render(normalized, complete), BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void assertEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Update MarkdownMessageView on the Swing EDT.");
        }
    }
}
