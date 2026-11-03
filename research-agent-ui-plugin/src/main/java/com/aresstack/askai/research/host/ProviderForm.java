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

    static JComponent hint(String text) {
        JLabel l = new JLabel("<html><body style='width:420px'>" + text + "</body></html>");
        l.setEnabled(false);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
        return l;
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
