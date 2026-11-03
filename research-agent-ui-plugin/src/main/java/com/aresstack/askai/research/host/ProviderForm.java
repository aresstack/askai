package com.aresstack.askai.research.host;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/** Small layout helpers shared by the compact provider cards (SerpApi, Google search browser). */
final class ProviderForm {

    /** The label column's width (see {@link #row}); hints start in the field column. */
    private static final int HINT_INDENT = 150;

    private ProviderForm() {
    }

    static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return p;
    }

    static JPanel row(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(150, 22));
        p.add(l, BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height + 6));
        return p;
    }

    /** A wrapping hint in the FIELD column (label column left empty), as wide as the field. */
    static JComponent hint(String text) {
        javax.swing.JTextArea area = com.aresstack.comiccontrols.control.ComicControls.textArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        area.setForeground(com.aresstack.comiccontrols.theme.ThemeColor.of("research.settingsHintText"));
        final javax.swing.JTextArea wrapped = area;
        // A wrapping text area knows its height only at a known width: measure at the width the column
        // gives this row, and re-layout the column once that height changes (otherwise lines are clipped).
        final JPanel p = new JPanel(new BorderLayout(8, 0)) {
            private int measuredHeight = -1;

            @Override
            public Dimension getPreferredSize() {
                int width = getWidth() - HINT_INDENT - 8;
                if (width > 0) {
                    wrapped.setSize(width, Short.MAX_VALUE);
                }
                Dimension pref = wrapped.getPreferredSize();
                java.awt.Insets in = getInsets();
                // Narrow preferred width: the hint never widens the page, it wraps instead.
                return new Dimension(HINT_INDENT + 8 + 120 + in.left + in.right, pref.height + in.top + in.bottom);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); // no BoxLayout stretch
            }

            @Override
            public void doLayout() {
                super.doLayout();
                int height = getPreferredSize().height;
                if (height != measuredHeight) {
                    measuredHeight = height;
                    invalidate();
                    if (getParent() != null) {
                        getParent().invalidate();
                    }
                    javax.swing.SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            revalidate();
                        }
                    });
                }
            }
        };
        p.setOpaque(false);
        JLabel spacer = new JLabel();
        spacer.setPreferredSize(new Dimension(HINT_INDENT, 1));
        p.add(spacer, BorderLayout.WEST);
        p.add(area, BorderLayout.CENTER);
        return p;
    }

    /** Typing a secret switches its provider on: a key without the tick would silently do nothing. */
    static void enableOnTyping(javax.swing.text.JTextComponent secret, final javax.swing.JCheckBox enabled) {
        secret.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                enabled.setSelected(true);
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
    }

    static String text(String value) {
        return value == null ? "" : value;
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
