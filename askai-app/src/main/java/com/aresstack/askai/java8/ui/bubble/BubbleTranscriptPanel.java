package com.aresstack.askai.java8.ui.bubble;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.java8.ui.markdown.MarkdownMessageView;
import com.aresstack.askai.java8.ui.markdown.MarkdownTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host normal chat bubbles and temporary agent-activity animations in one scrollable transcript.
 *
 * <p>Call every mutating method on the Swing Event Dispatch Thread. Keep agent activities
 * temporary: finishing an activity plays the burst/result animation and removes its row.</p>
 */
public final class BubbleTranscriptPanel extends JPanel {

    private BubblePalette palette;
    private final JPanel messageList;
    private final JScrollPane scrollPane;
    private final SummaryOverlay overlay;
    private final Map<AnimatedThoughtBubblePanel, BubbleMessageRow> activityRows;
    // The streaming assistant answer renders as native Markdown (headings, lists, code, tables, links,
    // Mermaid) inside a speech bubble. Thinking/tool/user messages keep the plain speech bubble.
    private MarkdownMessageView activeAssistantView;
    // Exactly ONE action bar is ever visible — the CURRENT possibilities. A new bar replaces this one and a
    // consumed bar clears it, so a live chat looks like a restored chat: no stack of stale grayed buttons.
    private BubbleMessageRow currentActionsRow;

    public BubbleTranscriptPanel() {
        this(BubblePalette.themed());
    }

    public BubbleTranscriptPanel(BubblePalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.palette = palette;
        this.messageList = createMessageList();
        this.scrollPane = createScrollPane(messageList);
        this.overlay = new SummaryOverlay();
        this.activityRows = new IdentityHashMap<AnimatedThoughtBubblePanel, BubbleMessageRow>();
        buildUi();
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    /** The message list's base padding — the top-inset (sky) adds onto the base top value. */
    private static final int BASE_PADDING_TOP = 8;
    private static final int BASE_PADDING_BOTTOM = 12;
    private int topInset;

    /**
     * Extra top room INSIDE the scroll geometry (e.g. under the out-of-scope sky overlay): scrolled
     * fully up, the first message starts below a covering visual layer instead of hiding behind it.
     * Part of the scrollable content, not of the overlay; {@code 0} restores the plain padding.
     */
    public void setTopInset(int pixels) {
        int inset = Math.max(0, pixels);
        if (inset == topInset) {
            return;
        }
        topInset = inset;
        messageList.setBorder(BorderFactory.createEmptyBorder(
                BASE_PADDING_TOP + topInset, 0, BASE_PADDING_BOTTOM, 0));
        messageList.revalidate();
        messageList.repaint();
    }

    /**
     * Swaps the color palette. The transcript/background colors update immediately; new bubbles use the new
     * user/assistant colors. Existing bubbles keep the colors they were drawn with (a chat restart or the
     * next messages pick up the change), which keeps the swap cheap and avoids re-styling live bubbles.
     */
    public void applyPalette(BubblePalette newPalette) {
        requireEventDispatchThread();
        if (newPalette == null) {
            return;
        }
        this.palette = newPalette;
        setBackground(newPalette.getTranscriptBackground());
        messageList.setBackground(newPalette.getTranscriptBackground());
        scrollPane.getViewport().setBackground(newPalette.getTranscriptBackground());
        refreshTranscript();
    }

    public void clear() {
        requireEventDispatchThread();
        stopAllActivityAnimations();
        messageList.removeAll();
        activityRows.clear();
        activeAssistantView = null;
        currentActionsRow = null;
        refreshTranscript();
    }

    public boolean isEmpty() {
        return messageList.getComponentCount() == 0;
    }

    public SpeechBubblePanel appendUserMessage(String text) {
        requireEventDispatchThread();
        SpeechBubblePanel bubble = new SpeechBubblePanel(
                BubbleSide.RIGHT,
                palette.getUserBackground(),
                palette.getUserForeground(),
                "You",
                text);
        bubble.setHeaderTimestamp(System.currentTimeMillis());
        addBubbleRow(bubble, BubbleSide.RIGHT);
        return bubble;
    }

    /** Add a right-aligned row of image previews under the user's message (the images just sent). */
    public void appendUserImages(java.util.List<com.aresstack.askai.java8.vision.ImageAttachment> attachments) {
        requireEventDispatchThread();
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        JPanel thumbs = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        thumbs.setOpaque(false);
        for (com.aresstack.askai.java8.vision.ImageAttachment attachment : attachments) {
            thumbs.add(new com.aresstack.askai.java8.ui.ImageThumbnail(attachment, 72));
        }
        addBubbleRow(thumbs, BubbleSide.RIGHT);
    }

    /**
     * Appends a Partying-mode group message as a left-aligned bubble with the sender's display
     * name as the header.
     *
     * <p>The message content is passed as plain text (not prefixed with {@code **@name**}) so that
     * sender semantics are carried by the structured header, not baked into the Markdown body.
     * This makes it possible to apply per-participant colors and metadata cleanly in later slices.</p>
     *
     * @param senderName    the sender's display name to show as the bubble header
     * @param participantId the sender's stable participant ID (reserved for future color mapping)
     * @param markdown      the message body as raw Markdown
     */
    public void appendPartyMessage(String senderName, String participantId, String markdown) {
        appendPartyMessage(senderName, participantId, markdown, null, false);
    }

    /**
     * Appends a Partying-mode group message with the sender's replicated participant color.
     *
     * @param senderName  the sender's display name shown as the bubble header
     * @param participantId the sender's stable participant ID
     * @param markdown    the message body as raw Markdown
     * @param headerColor the sender's palette color (theme-matched variant), or {@code null}
     * @param local       {@code true} for the local participant's own messages (right-aligned)
     */
    public void appendPartyMessage(String senderName, String participantId, String markdown,
                                   java.awt.Color headerColor, boolean local) {
        appendPartyMessage(senderName, participantId, markdown, headerColor, local, 0L);
    }

    /**
     * Appends a Partying-mode group message; a non-zero {@code createdAtMillis} renders as a
     * small stacked date/time block next to the sender name.
     */
    public void appendPartyMessage(String senderName, String participantId, String markdown,
                                   java.awt.Color headerColor, boolean local, long createdAtMillis) {
        requireEventDispatchThread();
        String header = senderName != null && !senderName.trim().isEmpty() ? senderName : "?";
        BubbleSide side = local ? BubbleSide.RIGHT : BubbleSide.LEFT;
        SpeechBubblePanel bubble = new SpeechBubblePanel(
                side,
                local ? palette.getUserBackground() : palette.getAssistantBackground(),
                local ? palette.getUserForeground() : palette.getAssistantForeground(),
                header,
                markdown);
        if (headerColor != null) {
            bubble.setHeaderColor(headerColor);
        }
        if (createdAtMillis > 0) {
            bubble.setHeaderTimestamp(createdAtMillis);
        }
        addBubbleRow(bubble, side);
    }

    /**
     * Starts a streaming assistant answer rendered as native Markdown inside a speech bubble. While
     * streaming the text re-renders debounced; a Mermaid fence stays a code block until
     * {@link #finishAssistantMessage()} turns it into a diagram.
     */
    public void startAssistantMessage(String header) {
        requireEventDispatchThread();
        finishAssistantMessage();
        MarkdownTheme theme = MarkdownTheme.forBubble(
                palette.getAssistantBackground(), palette.getAssistantForeground());
        activeAssistantView = new MarkdownMessageView(theme);
        activeAssistantView.startStreaming();
        addAssistantMarkdownRow(header, activeAssistantView);
    }

    public void appendAssistantDelta(String delta) {
        requireEventDispatchThread();
        if (activeAssistantView == null) {
            startAssistantMessage("Assistant");
        }
        activeAssistantView.appendMarkdownDelta(delta);
        refreshTranscript();
    }

    public void finishAssistantMessage() {
        requireEventDispatchThread();
        if (activeAssistantView != null) {
            activeAssistantView.finishStreaming();
        }
        activeAssistantView = null;
    }

    /**
     * Receives the index of the pressed action button of one card row.
     * @return {@code true} when the card is CONSUMED by this action (the whole row gets disabled),
     *         {@code false} when the card stays active (navigation, rejected or failed actions).
     */
    public interface ActionInvoker {
        boolean invoke(int index);
    }

    /**
     * A left-aligned row of REAL action buttons under an assistant card. Navigation buttons (per-index
     * flag) never consume the card — pressing "view sources" must not kill "continue research". Decision
     * buttons guard against double-fire by disabling the row DURING the action; the row stays disabled
     * only when the invoker reports the card as consumed, otherwise it is re-enabled.
     */
    public void appendActionButtons(java.util.List<String> labels, final java.util.List<Boolean> navigation,
                                    final ActionInvoker invoker) {
        requireEventDispatchThread();
        // Keep exactly ONE action bar: a new bar replaces the previous possibilities. The card TEXT above
        // stays as history; only the buttons are deduped to the current decision.
        if (currentActionsRow != null) {
            removeRowAndFollowingSpacer(currentActionsRow);
            currentActionsRow = null;
        }
        final JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        final BubbleMessageRow[] holder = new BubbleMessageRow[1];
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            final boolean isNavigation = navigation != null && index < navigation.size()
                    && Boolean.TRUE.equals(navigation.get(index));
            final javax.swing.JButton button = new ComicButton(labels.get(i));
            button.addActionListener(event -> {
                if (isNavigation) {
                    // Navigation shows something; the card (incl. its decisions) stays fully usable.
                    button.setEnabled(false);
                    try {
                        if (invoker != null) {
                            invoker.invoke(index);
                        }
                    } finally {
                        button.setEnabled(true);
                    }
                    return;
                }
                // Decision: block double-fire during the action.
                for (java.awt.Component component : row.getComponents()) {
                    component.setEnabled(false);
                }
                boolean consumed = false;
                try {
                    consumed = invoker != null && invoker.invoke(index);
                } finally {
                    if (consumed) {
                        // Consumed: drop the whole bar — no lingering grayed buttons (uniform with restore).
                        if (holder[0] != null) {
                            removeRowAndFollowingSpacer(holder[0]);
                            if (currentActionsRow == holder[0]) {
                                currentActionsRow = null;
                            }
                            refreshTranscript();
                        }
                    } else {
                        for (java.awt.Component component : row.getComponents()) {
                            component.setEnabled(true);
                        }
                    }
                }
            });
            row.add(button);
        }
        holder[0] = addBubbleRow(row, BubbleSide.LEFT);
        currentActionsRow = holder[0];
    }

    /** Info lines addressable by id, so a terminal status (check/retry) can find its line later. */
    private final java.util.Map<String, JLabel> infoLinesById =
            new java.util.HashMap<String, JLabel>();

    public void appendInfo(String infoId, String text) {
        JLabel label = appendInfoInternal(text);
        if (infoId != null && !infoId.trim().isEmpty()) {
            infoLinesById.put(infoId, label);
        }
    }

    /**
     * Mark an info line (by id) with its terminal outcome: a green comic check for success, or a red
     * circling-arrow RETRY control for failure — clicking the failed line runs the retry.
     */
    public void markInfoStatus(String infoId, boolean success, final Runnable retry) {
        requireEventDispatchThread();
        JLabel label = infoId == null ? null : infoLinesById.get(infoId);
        if (label == null) {
            return; // restored/unknown line: nothing to decorate
        }
        String base = escapeHtml(String.valueOf(label.getClientProperty("info.plainText")));
        label.putClientProperty(INFO_MARK, Boolean.valueOf(success));
        label.setText(markedInfoHtml(base, success));
        if (!success) {
            if (retry != null) {
                label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
                label.setToolTipText("Suche fehlgeschlagen — klicken: erneut versuchen");
                label.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseClicked(java.awt.event.MouseEvent event) {
                        retry.run();
                    }
                });
            }
        }
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        refreshTranscript();
    }

    /** The client property remembering an info line's outcome mark (re-rendered on a theme switch). */
    private static final String INFO_MARK = "info.mark";

    /** The info line with its outcome mark; the mark color comes from the theme when the HTML is built. */
    private static String markedInfoHtml(String base, boolean success) {
        String color = ThemeColor.toHtmlHex(
                ThemeColor.of(success ? "chat.infoLineSuccessMark" : "chat.infoLineRetryMark"));
        return "<html><i>" + base + "</i>&nbsp;&nbsp;<b style='color:" + color + ";'>"
                + (success ? "✔" : "⟳") + "</b></html>";
    }

    /** A theme switch (updateComponentTreeUI) re-renders the HTML outcome marks in the new theme colors. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (infoLinesById == null) {
            return; // called from the JPanel constructor, before the fields exist
        }
        for (JLabel label : infoLinesById.values()) {
            Object mark = label.getClientProperty(INFO_MARK);
            if (mark instanceof Boolean) {
                label.setText(markedInfoHtml(
                        escapeHtml(String.valueOf(label.getClientProperty("info.plainText"))), (Boolean) mark));
            }
        }
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void appendInfo(String text) {
        appendInfoInternal(text);
    }

    private JLabel appendInfoInternal(String text) {
        requireEventDispatchThread();
        final JLabel label = new JLabel(text == null ? "" : text, SwingConstants.CENTER);
        Font font = UIManager.getFont("Label.font");
        if (font != null) {
            label.setFont(font.deriveFont(Font.ITALIC, Math.max(11f, font.getSize2D() - 1f)));
        }
        label.setForeground(palette.getInfoForeground());
        label.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        // The label centers its own TEXT (SwingConstants.CENTER) and fills the width like every other entry.
        // Its alignmentX must not differ from the rows' — see addToMessageList.
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        // A "Websuche: <query>" line is a repeatable ACTION, not only a breadcrumb: clicking it offers
        // the ready-made /search command for the composer (works for restored lines too — they render
        // through this same method).
        final String searchCommand = searchCommandOf(text);
        if (searchCommand != null) {
            label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            label.setToolTipText("Klicken: Suchbefehl kopieren — " + searchCommand);
            label.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                    javax.swing.JMenuItem copy = new javax.swing.JMenuItem(
                            "Suchbefehl kopieren: " + searchCommand);
                    copy.addActionListener(new java.awt.event.ActionListener() {
                        public void actionPerformed(java.awt.event.ActionEvent action) {
                            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                                    new java.awt.datatransfer.StringSelection(searchCommand), null);
                        }
                    });
                    menu.add(copy);
                    menu.show(label, event.getX(), event.getY());
                }
            });
        }
        label.putClientProperty("info.plainText", text == null ? "" : text);
        addToMessageList(label);
        refreshTranscript();
        return label;
    }

    /**
     * The composer command that repeats the search a "Websuche: <query>" info line reports, or null when
     * the line is no search breadcrumb. Package-visible for tests.
     */
    static String searchCommandOf(String text) {
        String line = text == null ? "" : text.trim();
        if (!line.startsWith("Websuche: ")) {
            return null;
        }
        String query = line.substring("Websuche: ".length()).trim();
        return query.isEmpty() ? null : "/search " + query;
    }

    /** Optional consumer of clicks on amber activity bubbles (e.g. read the search tag aloud). */
    public interface AgentActivityClickListener {
        void agentActivityClicked(String title, String explanation);
    }

    private AgentActivityClickListener agentActivityClickListener;

    /** Register (or clear with null) the click consumer for ALL amber activity bubbles. */
    public void setAgentActivityClickListener(AgentActivityClickListener listener) {
        this.agentActivityClickListener = listener;
    }

    /** Opaque handle to one amber tool-/agent-activity bubble; the panel keeps the component internally. */
    public static final class AgentActivityHandle {
        private final AgentActivityBubblePanel bubble;
        private volatile String currentTitle = "";
        private volatile String currentExplanation = "";

        private AgentActivityHandle(AgentActivityBubblePanel bubble) {
            this.bubble = bubble;
        }
    }

    public AgentActivityHandle startAgentActivity(String title, String explanation) {
        requireEventDispatchThread();
        AgentActivityBubblePanel activity = new AgentActivityBubblePanel(BubbleSide.LEFT, palette, title, explanation);
        activity.setHeaderTimestamp(System.currentTimeMillis());
        addThoughtBubble(activity);
        final AgentActivityHandle handle = new AgentActivityHandle(activity);
        handle.currentTitle = title == null ? "" : title;
        handle.currentExplanation = explanation == null ? "" : explanation;
        // A click hands the bubble's CURRENT text to the registered consumer (read-aloud etc.).
        // Attached RECURSIVELY: the bubble is full of children (title label, explanation text
        // area, …) that swallow the press before it ever reaches the bubble panel — a listener
        // on the panel alone never fired. Children with their OWN mouse function (links,
        // toggles) are skipped so they keep their meaning.
        java.awt.event.MouseAdapter click = new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                AgentActivityClickListener listener = agentActivityClickListener;
                if (listener != null) {
                    listener.agentActivityClicked(handle.currentTitle, handle.currentExplanation);
                }
            }
        };
        attachActivityClickRecursively(activity, click);
        return handle;
    }

    public void updateAgentActivity(AgentActivityHandle handle, String title, String explanation) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.currentTitle = title == null ? "" : title;
        handle.currentExplanation = explanation == null ? "" : explanation;
        handle.bubble.updateActivity(title, explanation);
        refreshTranscript();
    }

    /**
     * Attach the activity click to the bubble AND its descendants. Skipped: components that
     * already carry their own mouse function (browse links, history toggles) — EXCEPT text
     * components, whose UI-installed caret listeners are internal, not a user-facing action.
     */
    private static void attachActivityClickRecursively(java.awt.Component component,
                                                       java.awt.event.MouseAdapter click) {
        boolean hasOwnFunction = component.getMouseListeners().length > 0
                && !(component instanceof javax.swing.text.JTextComponent);
        if (!hasOwnFunction) {
            component.addMouseListener(click);
        }
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                attachActivityClickRecursively(child, click);
            }
        }
    }

    public void completeAgentActivity(AgentActivityHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.completeSuccessfully(summary, createActivityRemoval(handle.bubble));
    }

    public void failAgentActivity(AgentActivityHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.completeWithFailure(summary, createActivityRemoval(handle.bubble));
    }

    public void cancelAgentActivity(AgentActivityHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.cancel(summary, createActivityRemoval(handle.bubble));
    }

    // ------------------------------------------------------------------ assistant thinking

    /** Opaque handle to one assistant-thinking bubble; the panel keeps the Swing component internally. */
    public static final class ThinkingHandle {
        private final AssistantThinkingBubblePanel bubble;

        private ThinkingHandle(AssistantThinkingBubblePanel bubble) {
            this.bubble = bubble;
        }
    }

    /** Starts a green assistant-thinking bubble and begins its animation. */
    public ThinkingHandle startAssistantThinking(String modelName) {
        requireEventDispatchThread();
        String header = modelName == null || modelName.trim().isEmpty() ? "Thinking" : modelName.trim();
        AssistantThinkingBubblePanel bubble = new AssistantThinkingBubblePanel(BubbleSide.LEFT, palette, header, "");
        addThoughtBubble(bubble);
        return new ThinkingHandle(bubble);
    }

    /** Streams a reasoning delta into the thinking bubble. */
    public void appendAssistantThinkingDelta(ThinkingHandle handle, String delta) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.appendBodyText(delta);
        refreshTranscript();
    }

    /** Replaces the thinking bubble's body with the full current text (agent status bubbles). */
    public void replaceAssistantThinkingText(ThinkingHandle handle, String text) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.setBodyText(text);
        refreshTranscript();
    }

    /** Finishes thinking: the bubble bursts and the summary rises over the transcript, then the row is removed. */
    public void completeAssistantThinking(ThinkingHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.completeSuccessfully(summary, createActivityRemoval(handle.bubble));
    }

    public void cancelAssistantThinking(ThinkingHandle handle, String summary) {
        requireEventDispatchThread();
        requireKnownActivity(handle == null ? null : handle.bubble);
        handle.bubble.cancel(summary, createActivityRemoval(handle.bubble));
    }

    /**
     * Adds an animated thought bubble (activity or thinking) as a left row and wires its finished summary
     * to rise on the transcript-wide overlay instead of being clipped to its own row.
     */
    private void addThoughtBubble(final AnimatedThoughtBubblePanel bubble) {
        bubble.setSummaryFloatHandler(new AnimatedThoughtBubblePanel.SummaryFloatHandler() {
            public void floatSummary(AnimatedThoughtBubblePanel source, String text, Color accent, Font font) {
                Point anchor = SwingUtilities.convertPoint(source, source.getWidth() / 2, 0, overlay);
                overlay.floatSummary(anchor.x, anchor.y, text, accent, font);
            }
        });
        BubbleMessageRow row = addBubbleRow(bubble, BubbleSide.LEFT);
        activityRows.put(bubble, row);
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(palette.getTranscriptBackground());
        add(createLayeredContent(), BorderLayout.CENTER);
    }

    /** Stack the scrolling transcript under a transparent overlay that can rise past the row bounds. */
    private JLayeredPane createLayeredContent() {
        JLayeredPane layered = new JLayeredPane() {
            @Override
            public void doLayout() {
                for (Component child : getComponents()) {
                    child.setBounds(0, 0, getWidth(), getHeight());
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return scrollPane.getPreferredSize();
            }
        };
        layered.add(scrollPane, JLayeredPane.DEFAULT_LAYER);
        layered.add(overlay, JLayeredPane.MODAL_LAYER);
        return layered;
    }

    private JPanel createMessageList() {
        JPanel panel = new WidthTrackingList();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(palette.getTranscriptBackground());
        panel.setBorder(BorderFactory.createEmptyBorder(BASE_PADDING_TOP, 0, BASE_PADDING_BOTTOM, 0));
        return panel;
    }

    /**
     * The transcript body always matches the viewport width instead of driving its own from the rows, so
     * bubbles reflow (and never trigger a horizontal scrollbar) when the window is made narrower — the row
     * widths are derived from this width, so it must be led by the viewport, not by the rows.
     */
    private static final class WidthTrackingList extends JPanel implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private JScrollPane createScrollPane(JPanel content) {
        JScrollPane scroll = new JScrollPane(
                content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(palette.getTranscriptBackground());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private BubbleMessageRow addBubbleRow(JComponent bubble, BubbleSide side) {
        BubbleMessageRow row = new BubbleMessageRow(bubble, side);
        addToMessageList(row);
        refreshTranscript();
        return row;
    }

    /**
     * THE one way anything enters the transcript list, and it exists for one reason: every entry must carry
     * the SAME alignmentX.
     * <p>
     * A BoxLayout on the Y axis lines its children up on a shared alignment point. Mix the values and it
     * splits the container at that point — entries aligned LEFT (0.0) then receive only the part right of
     * it. That is what happened to every NEW chat: it opens with a CENTER-aligned "New conversation…" hint,
     * so every following row was laid out at half the width in the right half, while a restored chat (which
     * has no such hint) was fine. No width, ratio or resize could have fixed it, because the split is
     * proportional.
     */
    private void addToMessageList(JComponent entry) {
        entry.setAlignmentX(LEFT_ALIGNMENT);
        messageList.add(entry);
        Component spacer = Box.createVerticalStrut(2);
        ((JComponent) spacer).setAlignmentX(LEFT_ALIGNMENT);
        messageList.add(spacer);
    }

    /** Adds the assistant answer inside a left speech bubble, capped in width and reflowing on resize. */
    private void addAssistantMarkdownRow(String header, MarkdownMessageView view) {
        AssistantMarkdownBubble bubble = new AssistantMarkdownBubble(
                BubbleSide.LEFT, palette, header == null || header.length() == 0 ? "Assistant" : header, view);
        bubble.setHeaderTimestamp(System.currentTimeMillis());
        // The SAME row type as every other bubble: one geometry, one alignment behaviour. A second row
        // implementation for this bubble is what let the streamed answer be laid out differently from a
        // restored one.
        addBubbleRow(bubble, BubbleSide.LEFT);
    }

    private Runnable createActivityRemoval(final AnimatedThoughtBubblePanel activity) {
        return new Runnable() {
            public void run() {
                BubbleMessageRow row = activityRows.remove(activity);
                if (row != null) {
                    removeRowAndFollowingSpacer(row);
                    refreshTranscript();
                }
            }
        };
    }

    private void removeRowAndFollowingSpacer(BubbleMessageRow row) {
        int index = findComponentIndex(row);
        if (index < 0) {
            return;
        }
        messageList.remove(index);
        if (index < messageList.getComponentCount()) {
            messageList.remove(index);
        }
    }

    private int findComponentIndex(JComponent component) {
        for (int index = 0; index < messageList.getComponentCount(); index++) {
            if (messageList.getComponent(index) == component) {
                return index;
            }
        }
        return -1;
    }

    private void stopAllActivityAnimations() {
        for (AnimatedThoughtBubblePanel activity : activityRows.keySet()) {
            activity.stopAnimation();
        }
    }

    private void requireKnownActivity(AnimatedThoughtBubblePanel activity) {
        if (activity == null || !activityRows.containsKey(activity)) {
            throw new IllegalArgumentException("activity is not part of this transcript");
        }
    }

    private void refreshTranscript() {
        messageList.revalidate();
        messageList.repaint();
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                // SECOND layout pass after the first one settled (scrollbar appearance narrows the
                // viewport; look-and-feel fonts can wrap one line differently than the pre-layout
                // measurement). The height math converges, so this pass removes any residual
                // last-line clipping instead of leaving it to a later resize.
                messageList.revalidate();
                messageList.repaint();
                int maximum = scrollPane.getVerticalScrollBar().getMaximum();
                scrollPane.getVerticalScrollBar().setValue(maximum);
                traceGeometry();
            }
        });
    }

    /**
     * One line per appended message with the ACTUAL widths of the whole chain. A layout defect that only
     * appears in a running window cannot be reasoned about from a screenshot: this says plainly which
     * component is the narrow one. OFF by default (it fired for every message); switch it on with
     * {@code -Daskai.debug.bubbleGeometry=true} when hunting a layout defect.
     */
    private void traceGeometry() {
        if (!Boolean.getBoolean("askai.debug.bubbleGeometry")) {
            return;
        }
        Component last = null;
        for (Component child : messageList.getComponents()) {
            if (child instanceof BubbleMessageRow) {
                last = child;
            }
        }
        if (last == null) {
            return;
        }
        JComponent bubble = ((BubbleMessageRow) last).getBubble();
        System.err.println("[bubble-geom] transcript=" + getWidth()
                + " scroll=" + scrollPane.getWidth()
                + " viewport=" + scrollPane.getViewport().getWidth()
                + " list=" + messageList.getWidth()
                + " row=" + last.getWidth() + "x" + last.getHeight()
                + " rowX=" + last.getX()
                + " bubble=" + bubble.getClass().getSimpleName()
                + " x=" + bubble.getX() + " w=" + bubble.getWidth() + " h=" + bubble.getHeight());
    }

    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Call transcript mutations on the Swing Event Dispatch Thread");
        }
    }

    /**
     * A transparent, click-through layer that animates a finished activity summary rising from the burst
     * position all the way to the top edge, over everything below it, then fades out.
     */
    private static final class SummaryOverlay extends JComponent {

        private static final int DURATION_MILLIS = 1500;
        private static final int TICK_MILLIS = 33;
        private static final int TOP_MARGIN = 8;

        private final Timer timer;
        private boolean active;
        private String text = "";
        private Color accent = ThemeColor.of("chat.floatingSummaryAccent");
        private Font font;
        private int anchorX;
        private double startY;
        private long startedAt;

        private SummaryOverlay() {
            setOpaque(false);
            this.timer = new Timer(TICK_MILLIS, new ActionListener() {
                public void actionPerformed(ActionEvent event) {
                    tick();
                }
            });
        }

        /** Let clicks fall through to the transcript underneath. */
        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        void floatSummary(int anchorX, int startY, String text, Color accent, Font font) {
            this.anchorX = anchorX;
            this.startY = startY;
            this.text = text == null ? "" : text;
            this.accent = accent == null ? ThemeColor.of("chat.floatingSummaryAccent") : accent;
            this.font = font;
            this.startedAt = System.currentTimeMillis();
            this.active = true;
            setVisible(true);
            timer.restart();
            repaint();
        }

        private void tick() {
            if (!active || System.currentTimeMillis() - startedAt >= DURATION_MILLIS) {
                active = false;
                timer.stop();
                setVisible(false);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!active) {
                return;
            }
            double progress = Math.min(1.0d, (System.currentTimeMillis() - startedAt) / (double) DURATION_MILLIS);
            double eased = 1.0d - Math.pow(1.0d - progress, 3.0d);
            int y = (int) Math.round(startY + ((TOP_MARGIN - startY) * eased));
            float alpha = progress < 0.6d
                    ? 1.0f
                    : (float) Math.max(0.0d, 1.0d - ((progress - 0.6d) / 0.4d));

            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                copy.setComposite(AlphaComposite.SrcOver.derive(alpha));
                if (font != null) {
                    copy.setFont(font);
                }
                copy.setColor(accent);
                paintRisingText(copy, y);
            } finally {
                copy.dispose();
            }
        }

        private void paintRisingText(Graphics2D graphics, int topY) {
            FontMetrics metrics = graphics.getFontMetrics();
            int maximumWidth = Math.max(120, getWidth() - 24);
            List<String> lines = wrap(text, metrics, maximumWidth);
            int lineHeight = metrics.getHeight();
            int y = topY + metrics.getAscent();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                int width = metrics.stringWidth(line);
                int x = anchorX - (width / 2);
                x = Math.max(8, Math.min(getWidth() - width - 8, x));
                graphics.drawString(line, x, y);
                y += lineHeight;
            }
        }

        private static List<String> wrap(String text, FontMetrics metrics, int maximumWidth) {
            List<String> lines = new ArrayList<String>();
            String[] words = (text == null ? "" : text).trim().split("\\s+");
            StringBuilder current = new StringBuilder();
            for (int index = 0; index < words.length; index++) {
                String candidate = current.length() == 0 ? words[index] : current + " " + words[index];
                if (current.length() > 0 && metrics.stringWidth(candidate) > maximumWidth) {
                    lines.add(current.toString());
                    current.setLength(0);
                    current.append(words[index]);
                } else {
                    current.setLength(0);
                    current.append(candidate);
                }
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
            if (lines.isEmpty()) {
                lines.add("");
            }
            return lines;
        }
    }
}
