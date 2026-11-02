package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletion;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult;
import com.aresstack.askai.plugin.host.ActiveAgentCommandRegistry;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * The composer's slash-command completion popup. It only ever appears when the shared composer is routing to
 * an active agent whose {@link ActiveAgentCommandRegistry} reports a command line — in Yapping the registry is
 * empty, so {@code /} stays ordinary text. The popup handles Up/Down/Tab/Escape and mouse selection; Enter is
 * deliberately NOT consumed here — it falls through to the composer's send action, which executes the command
 * line. Tab accepts the highlighted suggestion. All work is local and synchronous on the EDT.
 */
final class SlashCommandPopup {

    private final JTextArea editor;
    private final DefaultListModel<CommandCompletion> model = new DefaultListModel<CommandCompletion>();
    private final JList<CommandCompletion> list = ComicControls.<CommandCompletion>list(model);
    private final JScrollPane scroll;

    private ActiveAgentCommandRegistry registry;
    private Popup popup;
    private boolean shown;

    SlashCommandPopup(JTextArea editor) {
        this.editor = editor;
        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.setCellRenderer(new Renderer());
        this.scroll = new JScrollPane(list);
        this.scroll.setBorder(javax.swing.BorderFactory.createLineBorder(
                com.aresstack.comiccontrols.theme.ThemeColor.of("chat.slashPopupBorder")));
        attach();
    }

    void setRegistry(ActiveAgentCommandRegistry registry) {
        this.registry = registry;
    }

    /** @return whether the popup is currently visible (so callers can branch key handling). */
    boolean isShowing() {
        return shown;
    }

    /** Coalesces refreshes scheduled from document callbacks (see attach()). */
    private boolean refreshPending;

    private void attach() {
        // NEVER refresh synchronously from a DocumentListener: during the callback the caret has not
        // been updated yet, so typing a lone "/" computed completions for caret position 0 and the
        // popup only appeared one keystroke late. Deferring via invokeLater (coalesced) runs after
        // the caret moved — "/" alone immediately lists all commands.
        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                scheduleRefresh();
            }

            public void removeUpdate(DocumentEvent e) {
                scheduleRefresh();
            }

            public void changedUpdate(DocumentEvent e) {
                scheduleRefresh();
            }
        });
        // Key handling runs before the editor's InputMap bindings, so consuming here suppresses send/discard.
        editor.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (!shown) {
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN:
                        move(1);
                        e.consume();
                        break;
                    case KeyEvent.VK_UP:
                        move(-1);
                        e.consume();
                        break;
                    case KeyEvent.VK_TAB:
                        acceptSelected();
                        e.consume();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        hide();
                        e.consume();
                        break;
                    default:
                        break;
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) {
                    acceptSelected();
                    editor.requestFocusInWindow();
                }
            }
        });
    }

    private void scheduleRefresh() {
        if (refreshPending) {
            return;
        }
        refreshPending = true;
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                refreshPending = false;
                refresh();
            }
        });
    }

    /** Recompute completions for the current editor text; show/hide the popup accordingly. Call on the EDT. */
    void refresh() {
        if (registry == null) {
            hide();
            return;
        }
        String text = editor.getText();
        if (!registry.isCommandLine(text)) {
            hide();
            return;
        }
        CommandCompletionResult result = registry.complete(text, editor.getCaretPosition());
        List<CommandCompletion> completions = result.getCompletions();
        if (completions.isEmpty()) {
            hide();
            return;
        }
        model.clear();
        for (CommandCompletion completion : completions) {
            model.addElement(completion);
        }
        list.setSelectedIndex(0);
        show();
    }

    private void move(int delta) {
        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int index = (list.getSelectedIndex() + delta + size) % size;
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    private void acceptSelected() {
        CommandCompletion selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        editor.setText(selected.getInsertionText());
        editor.setCaretPosition(editor.getText().length());
        refresh(); // may offer the next argument's suggestions
    }

    private void show() {
        int rows = Math.min(model.getSize(), 8);
        scroll.setPreferredSize(new Dimension(Math.max(260, editor.getWidth()), rows * 22 + 6));
        if (!editor.isShowing()) {
            return;
        }
        Point origin = editor.getLocationOnScreen();
        int x = origin.x;
        // The composer sits at the BOTTOM of the window: prefer showing the popup ABOVE the editor;
        // only fall back below when there is genuinely not enough screen space above.
        int popupHeight = scroll.getPreferredSize().height;
        int y = origin.y - popupHeight;
        if (y < 0) {
            y = origin.y + editor.getHeight();
        }
        if (shown && popup != null) {
            popup.hide();
        }
        popup = PopupFactory.getSharedInstance().getPopup(editor, scroll, x, y);
        popup.show();
        shown = true;
    }

    /** Hide the popup if visible. Safe to call repeatedly; used on agent switch / disable / send. */
    void hide() {
        if (popup != null) {
            popup.hide();
            popup = null;
        }
        shown = false;
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof CommandCompletion) {
                CommandCompletion completion = (CommandCompletion) value;
                String description = completion.getDescription();
                setText(description == null || description.isEmpty()
                        ? completion.getDisplayText()
                        : "<html><b>" + escape(completion.getDisplayText()) + "</b>&nbsp;&nbsp;<font color='gray'>"
                                + escape(description) + "</font></html>");
            }
            return this;
        }

        private static String escape(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
