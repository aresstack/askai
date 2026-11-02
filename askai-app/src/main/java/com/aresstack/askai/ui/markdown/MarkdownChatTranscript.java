package com.aresstack.askai.ui.markdown;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/** Provide a scrollable chat transcript with one native Swing component per message. */
public final class MarkdownChatTranscript {

    private static final int MESSAGE_GAP = 10;
    private static final int MESSAGE_PADDING = 10;

    private final MarkdownTheme theme;
    private final JPanel messagesPanel;
    private final JScrollPane scrollPane;
    private MarkdownMessageView activeAssistantView;

    public MarkdownChatTranscript() {
        this(MarkdownTheme.fromUiDefaults());
    }

    public MarkdownChatTranscript(MarkdownTheme theme) {
        this.theme = theme;
        this.messagesPanel = createMessagesPanel();
        this.scrollPane = new JScrollPane(messagesPanel);
        this.scrollPane.setBorder(null);
        this.scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        this.scrollPane.getViewport().setOpaque(false);
        this.scrollPane.setOpaque(false);
    }

    public JComponent getComponent() {
        return scrollPane;
    }

    public void clear() {
        assertEventDispatchThread();
        activeAssistantView = null;
        messagesPanel.removeAll();
        refreshTranscript();
    }

    public boolean isEmpty() {
        return messagesPanel.getComponentCount() == 0;
    }

    public void appendUser(String text) {
        assertEventDispatchThread();
        addMessage(createUserMessage(text));
    }

    public void appendInfo(String text) {
        assertEventDispatchThread();
        JLabel label = new JLabel(toHtml(text), SwingConstants.CENTER);
        label.setFont(theme.getBodyFont().deriveFont(Font.ITALIC));
        label.setForeground(theme.getMutedForeground());
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        addMessage(label);
    }

    public void startAssistant(String header) {
        assertEventDispatchThread();
        if (activeAssistantView != null) {
            finishAssistant();
        }
        activeAssistantView = new MarkdownMessageView(theme, DesktopLinkOpener.systemDefault());
        activeAssistantView.startStreaming();
        addMessage(createAssistantMessage(header, activeAssistantView));
    }

    public void appendAssistantDelta(String delta) {
        assertEventDispatchThread();
        ensureAssistantMessage();
        activeAssistantView.appendMarkdownDelta(delta);
        scrollToBottom();
    }

    public void finishAssistant() {
        assertEventDispatchThread();
        if (activeAssistantView == null) {
            return;
        }
        activeAssistantView.finishStreaming();
        activeAssistantView = null;
        scrollToBottom();
    }

    private JPanel createMessagesPanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return panel;
    }

    private JComponent createUserMessage(String text) {
        WrappingTextPane body = new WrappingTextPane();
        body.setText(text == null ? "" : text);
        body.setFont(theme.getBodyFont());
        body.setForeground(ThemeColor.of("bubble.userForeground"));
        body.setBackground(ThemeColor.of("markdown.userMessageBackground"));
        body.setOpaque(true);
        body.setBorder(BorderFactory.createEmptyBorder(MESSAGE_PADDING, MESSAGE_PADDING,
                MESSAGE_PADDING, MESSAGE_PADDING));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(0, 80, 0, 0));
        wrapper.add(body, BorderLayout.CENTER);
        return wrapper;
    }

    private JComponent createAssistantMessage(String header, MarkdownMessageView body) {
        JLabel title = new JLabel(header == null ? "Assistant" : header);
        title.setFont(theme.getBodyFont().deriveFont(Font.BOLD));
        title.setForeground(theme.getMutedForeground());

        JPanel content = new JPanel(new BorderLayout(0, 5));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 80));
        content.add(title, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        return content;
    }

    private void ensureAssistantMessage() {
        if (activeAssistantView == null) {
            startAssistant("Assistant");
        }
    }

    private void addMessage(JComponent component) {
        MessageSlot slot = new MessageSlot(component);
        slot.setAlignmentX(Component.LEFT_ALIGNMENT);
        messagesPanel.add(slot);
        messagesPanel.add(Box.createVerticalStrut(MESSAGE_GAP));
        refreshTranscript();
        scrollToBottom();
    }

    private void refreshTranscript() {
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                scrollPane.getVerticalScrollBar().setValue(
                        scrollPane.getVerticalScrollBar().getMaximum());
            }
        });
    }

    private String toHtml(String text) {
        String escaped = text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html>" + escaped + "</html>";
    }

    private static final class MessageSlot extends JPanel {

        private MessageSlot(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.CENTER);
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, preferred.height);
        }
    }

    private void assertEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Update MarkdownChatTranscript on the Swing EDT.");
        }
    }
}
