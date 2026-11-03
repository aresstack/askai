package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.border.ComicBorder;

import javax.swing.BoundedRangeModel;
import javax.swing.ComboBoxModel;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListModel;
import javax.swing.SpinnerModel;
import javax.swing.border.TitledBorder;
import javax.swing.text.Document;
import java.util.Vector;

/**
 * THE factory facade of the comic controls: {@code ComicControls.checkBox("x")} instead of
 * {@code new JCheckBox("x")}. Every factory returns the plain Swing type, so field and parameter types
 * of the callers stay unchanged, and mirrors the Swing constructor it replaces (same arguments).
 *
 * <p>The returned instances re-apply their comic delegate in {@code updateUI()}, so a runtime theme
 * switch ({@code SwingUtilities.updateComponentTreeUI}) never falls back to the look-and-feel's
 * delegate.</p>
 */
public final class ComicControls {

    private ComicControls() {
    }

    // ------------------------------------------------------------------ check box

    public static JCheckBox checkBox() {
        return checkBox(null, null, false);
    }

    public static JCheckBox checkBox(String text) {
        return checkBox(text, null, false);
    }

    public static JCheckBox checkBox(String text, boolean selected) {
        return checkBox(text, null, selected);
    }

    public static JCheckBox checkBox(Icon icon) {
        return checkBox(null, icon, false);
    }

    public static JCheckBox checkBox(String text, Icon icon) {
        return checkBox(text, icon, false);
    }

    public static JCheckBox checkBox(String text, Icon icon, boolean selected) {
        return new JCheckBox(text, icon, selected) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                super.updateUI();
                ComicFormControls.install(this);
            }
        };
    }

    // ------------------------------------------------------------------ combo box

    public static <E> JComboBox<E> comboBox() {
        return install(new ThemedComboBox<E>());
    }

    public static <E> JComboBox<E> comboBox(E[] items) {
        return install(new ThemedComboBox<E>(items));
    }

    public static <E> JComboBox<E> comboBox(Vector<E> items) {
        return install(new ThemedComboBox<E>(items));
    }

    public static <E> JComboBox<E> comboBox(ComboBoxModel<E> model) {
        return install(new ThemedComboBox<E>(model));
    }

    private static <E> JComboBox<E> install(JComboBox<E> combo) {
        return ComicComboBoxUI.install(combo);
    }

    /** A combo box whose delegate stays the comic one across {@code updateUI()} (renderer untouched). */
    static final class ThemedComboBox<E> extends JComboBox<E> {
        private static final long serialVersionUID = 1L;

        ThemedComboBox() {
            super();
        }

        ThemedComboBox(E[] items) {
            super(items);
        }

        ThemedComboBox(Vector<E> items) {
            super(items);
        }

        ThemedComboBox(ComboBoxModel<E> model) {
            super(model);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (getRenderer() != null) {
                setUI(new ComicComboBoxUI());
            }
        }
    }

    // ------------------------------------------------------------------ spinner

    public static JSpinner spinner() {
        return themedSpinner(null);
    }

    public static JSpinner spinner(SpinnerModel model) {
        return themedSpinner(model);
    }

    private static JSpinner themedSpinner(SpinnerModel model) {
        JSpinner spinner = model == null ? new ThemedSpinner() : new ThemedSpinner(model);
        ComicFormControls.install(spinner);
        return spinner;
    }

    /** A whole-number spinner for settings: {@code value} in {@code min..max}, an editor of {@code columns} digits. */
    public static JSpinner integerSpinner(int value, int min, int max, int step, int columns) {
        JSpinner spinner = themedSpinner(new javax.swing.SpinnerNumberModel(
                Math.max(min, Math.min(max, value)), min, max, step));
        fitColumns(spinner, columns);
        return spinner;
    }

    /** Sizes a spinner's editor to {@code columns} digits (plain digits, no thousands grouping). */
    public static void fitColumns(JSpinner spinner, int columns) {
        javax.swing.JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            javax.swing.JFormattedTextField text = ((JSpinner.DefaultEditor) editor).getTextField();
            if (editor instanceof JSpinner.NumberEditor) {
                ((JSpinner.NumberEditor) editor).getFormat().setGroupingUsed(false);
            }
            text.setColumns(columns);
        }
    }

    static final class ThemedSpinner extends JSpinner {
        private static final long serialVersionUID = 1L;

        ThemedSpinner() {
            super();
        }

        ThemedSpinner(SpinnerModel model) {
            super(model);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (getEditor() != null) {
                ComicFormControls.install(this);
            }
        }
    }

    // ------------------------------------------------------------------ text field / password field

    public static JTextField textField() {
        return textField(null, null, 0);
    }

    public static JTextField textField(int columns) {
        return textField(null, null, columns);
    }

    public static JTextField textField(String text) {
        return textField(null, text, 0);
    }

    public static JTextField textField(String text, int columns) {
        return textField(null, text, columns);
    }

    public static JTextField textField(Document document, String text, int columns) {
        return new ComicTextField(document, text, columns);
    }

    public static JPasswordField passwordField() {
        return passwordField(null, null, 0);
    }

    public static JPasswordField passwordField(int columns) {
        return passwordField(null, null, columns);
    }

    public static JPasswordField passwordField(String text) {
        return passwordField(null, text, 0);
    }

    public static JPasswordField passwordField(String text, int columns) {
        return passwordField(null, text, columns);
    }

    public static JPasswordField passwordField(Document document, String text, int columns) {
        return new ComicPasswordField(document, text, columns);
    }

    // ------------------------------------------------------------------ text area

    public static JTextArea textArea() {
        return new ComicTextArea();
    }

    public static JTextArea textArea(String text) {
        return new ComicTextArea(text);
    }

    public static JTextArea textArea(int rows, int columns) {
        return new ComicTextArea(rows, columns);
    }

    public static JTextArea textArea(String text, int rows, int columns) {
        return new ComicTextArea(text, rows, columns);
    }

    public static JTextArea textArea(Document document) {
        return new ComicTextArea(document);
    }

    public static JTextArea textArea(Document document, String text, int rows, int columns) {
        return new ComicTextArea(document, text, rows, columns);
    }

    // ------------------------------------------------------------------ slider

    public static JSlider slider() {
        return slider(JSlider.HORIZONTAL, 0, 100, 50);
    }

    public static JSlider slider(int orientation) {
        return slider(orientation, 0, 100, 50);
    }

    public static JSlider slider(int min, int max) {
        return slider(JSlider.HORIZONTAL, min, max, (min + max) / 2);
    }

    public static JSlider slider(int min, int max, int value) {
        return slider(JSlider.HORIZONTAL, min, max, value);
    }

    public static JSlider slider(int orientation, int min, int max, int value) {
        return new JSlider(orientation, min, max, value) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                ComicFormControls.install(this);
            }
        };
    }

    public static JSlider slider(BoundedRangeModel model) {
        return new JSlider(model) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                ComicFormControls.install(this);
            }
        };
    }

    // ------------------------------------------------------------------ progress bar

    public static JProgressBar progressBar() {
        return progressBar(JProgressBar.HORIZONTAL, 0, 100);
    }

    public static JProgressBar progressBar(int orientation) {
        return progressBar(orientation, 0, 100);
    }

    public static JProgressBar progressBar(int min, int max) {
        return progressBar(JProgressBar.HORIZONTAL, min, max);
    }

    public static JProgressBar progressBar(int orientation, int min, int max) {
        return new JProgressBar(orientation, min, max) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                setUI(new ComicProgressBarUI());
            }
        };
    }

    public static JProgressBar progressBar(BoundedRangeModel model) {
        return new JProgressBar(model) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                setUI(new ComicProgressBarUI());
            }
        };
    }

    // ------------------------------------------------------------------ tabbed pane

    public static JTabbedPane tabbedPane() {
        return tabbedPane(JTabbedPane.TOP, JTabbedPane.WRAP_TAB_LAYOUT);
    }

    public static JTabbedPane tabbedPane(int tabPlacement) {
        return tabbedPane(tabPlacement, JTabbedPane.WRAP_TAB_LAYOUT);
    }

    public static JTabbedPane tabbedPane(int tabPlacement, int tabLayoutPolicy) {
        return new JTabbedPane(tabPlacement, tabLayoutPolicy) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                setUI(new ComicTabbedPaneUI());
            }
        };
    }

    /**
     * Comic tabs whose LAST tab is painted at the right edge of a single tab run (e.g. a
     * "Technical details" tab set apart from the content tabs). With wrapped runs the tabs stay
     * in their normal positions.
     */
    public static JTabbedPane tabbedPaneWithTrailingTab() {
        return new JTabbedPane(JTabbedPane.TOP, JTabbedPane.WRAP_TAB_LAYOUT) {
            private static final long serialVersionUID = 1L;

            @Override
            public void updateUI() {
                setUI(new TrailingTabUI());
            }
        };
    }

    private static final class TrailingTabUI extends ComicTabbedPaneUI {
        @Override
        protected java.awt.LayoutManager createLayoutManager() {
            return new TabbedPaneLayout() {
                @Override
                protected void calculateTabRects(int tabPlacement, int tabCount) {
                    super.calculateTabRects(tabPlacement, tabCount);
                    if (tabCount < 2 || runCount != 1) {
                        return;
                    }
                    java.awt.Insets insets = tabPane.getInsets();
                    java.awt.Insets area = getTabAreaInsets(tabPlacement);
                    java.awt.Rectangle last = rects[tabCount - 1];
                    java.awt.Rectangle previous = rects[tabCount - 2];
                    int x = tabPane.getWidth() - insets.right - area.right - last.width;
                    last.x = Math.max(previous.x + previous.width, x);
                }
            };
        }
    }

    // ------------------------------------------------------------------ list

    public static <E> JList<E> list() {
        return ComicListSupport.style(new JList<E>());
    }

    public static <E> JList<E> list(E[] items) {
        return ComicListSupport.style(new JList<E>(items));
    }

    public static <E> JList<E> list(Vector<? extends E> items) {
        return ComicListSupport.style(new JList<E>(items));
    }

    public static <E> JList<E> list(ListModel<E> model) {
        return ComicListSupport.style(new JList<E>(model));
    }

    // ------------------------------------------------------------------ titled border

    /** The comic group frame instead of {@code BorderFactory.createTitledBorder(title)}. */
    public static TitledBorder titledBorder(String title) {
        return ComicBorder.titled(title);
    }
}
