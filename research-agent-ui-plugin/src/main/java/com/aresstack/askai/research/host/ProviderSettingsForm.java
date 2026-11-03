package com.aresstack.askai.research.host;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * THE frame every SERP provider's settings share (Brave, Bright Data, DataForSEO, SerpApi): the same
 * width, the same sections in the same order — <b>Credentials</b>, <b>Search options</b>,
 * <b>Advanced</b> — each a {@link ComicForm} group (label left, field right, status word beside the
 * secret), stacked top-down with aligned label columns; a section a provider has nothing for is left
 * out. A provider with a playground gets it as a second tab "Playground" beside "Settings"; without one
 * there are no tabs at all. Numbers are plain digits (no grouping), hints are short notes or tooltips.
 *
 * <p>Whether a provider is USED is decided by the provider dropdown only. There is no "use for the initial
 * search" switch any more: a provider is enabled exactly when a credential is stored.</p>
 */
final class ProviderSettingsForm {

    /** The width every provider dialog/card gets, so all providers look alike. */
    static final int DIALOG_WIDTH = 640;

    private final ComicForm credentials = ComicForm.titled("Credentials");
    private final ComicForm searchOptions = ComicForm.titled("Search options");
    private final ComicForm advanced = ComicForm.titled("Advanced");
    private JComponent playground;

    ComicForm credentials() {
        return credentials;
    }

    ComicForm searchOptions() {
        return searchOptions;
    }

    ComicForm advanced() {
        return advanced;
    }

    /** An optional playground (a try-it-out page); shown as its own tab only when set. */
    void playground(JComponent playground) {
        this.playground = playground;
    }

    /** The assembled settings component, the same shape for every provider. */
    JComponent build() {
        java.util.List<JComponent> sections = new java.util.ArrayList<JComponent>();
        java.util.List<ComicForm> forms = new java.util.ArrayList<ComicForm>();
        for (ComicForm form : new ComicForm[]{credentials, searchOptions, advanced}) {
            if (form.getComponentCount() > 0) {
                sections.add(form);
                forms.add(form);
            }
        }
        ComicForm.alignLabels(forms.toArray(new ComicForm[0]));
        JComponent settings = ResearchSettingsContribution.fitted(
                ComicForm.stack(sections.toArray(new JComponent[0])));
        if (playground == null) {
            return fixedWidth(settings);
        }
        JTabbedPane tabs = ComicControls.tabbedPane();
        tabs.addTab("Settings", settings);
        tabs.addTab("Playground", playground);
        fitToSelectedTab(tabs);
        return fixedWidth(tabs);
    }

    /** The dialog is as wide as {@link #DIALOG_WIDTH}; the content wraps inside it instead of widening it. */
    private static JComponent fixedWidth(final JComponent content) {
        JPanel p = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                return new Dimension(DIALOG_WIDTH, pref.height);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }
        };
        p.setOpaque(false);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    /**
     * A JTabbedPane is as tall as its tallest tab, which leaves empty space under a short tab. Only the
     * selected tab contributes its preferred size; the window re-fits on a tab switch.
     */
    static void fitToSelectedTab(final JTabbedPane tabs) {
        final Runnable fit = new Runnable() {
            public void run() {
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    java.awt.Component c = tabs.getComponentAt(i);
                    c.setPreferredSize(i == tabs.getSelectedIndex() ? null : new Dimension(0, 0));
                }
                tabs.revalidate();
                java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(tabs);
                if (w != null) {
                    w.pack();
                }
            }
        };
        tabs.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                fit.run();
            }
        });
        fit.run();
    }

    // ------------------------------------------------------------------ shared controls

    /** The secret field of a provider (the stored value is never shown). */
    static JPasswordField secretField() {
        return ComicControls.passwordField(28);
    }

    /** The muted status word shown right beside a field ("A key is stored."). */
    static JLabel status() {
        JLabel l = new JLabel(" ");
        l.setEnabled(false);
        return l;
    }

    /** A message line ("Saved.", "Not saved: …") that takes no room while it is empty. */
    static JLabel message() {
        JLabel l = new JLabel() {
            private static final long serialVersionUID = 1L;

            @Override
            public void setText(String text) {
                super.setText(text);
                setVisible(text != null && !text.trim().isEmpty());
            }
        };
        l.setVisible(false);
        return l;
    }

    /** A credential row: label, the secret field, the status word beside it. */
    static void secretRow(ComicForm form, String label, JPasswordField secret, JLabel status, String tooltip) {
        secret.setToolTipText(tooltip);
        form.row(label, secret, status);
    }

    /** A whole-number field without thousands grouping. */
    static JSpinner number(int value, int min, int max, int columns) {
        return ComicControls.integerSpinner(value, min, max, 1, columns);
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

    /** The provider is enabled exactly when a credential is stored or has just been typed. */
    static boolean enabled(boolean stored, char[] typed) {
        return stored || (typed != null && typed.length > 0);
    }

    private ProviderSettingsForm() {
    }

    static ProviderSettingsForm create() {
        return new ProviderSettingsForm();
    }
}
