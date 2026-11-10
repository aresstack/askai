package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.border.ComicFieldBorder;
import com.aresstack.comiccontrols.theme.AskAiTheme;
import org.junit.Test;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Every factory returns the plain Swing type with the comic delegate — also after a tree-wide updateUI. */
public class ComicControlsTest {

    private final AskAiTheme theme = AskAiTheme.active();

    @Test
    public void checkBoxKeepsTheComicGlyphAcrossUpdateUi() {
        JCheckBox box = ComicControls.checkBox("x", true);
        assertTrue(box.isSelected());
        assertEquals("x", box.getText());
        assertTrue(box.getIcon() instanceof ComicFormControls.CheckIcon);
        refresh(box);
        assertTrue(box.getIcon() instanceof ComicFormControls.CheckIcon);
    }

    @Test
    public void comboBoxKeepsTheComicDelegateAndTheCallersRenderer() {
        JComboBox<String> combo = ComicControls.comboBox(new String[]{"a", "b"});
        assertEquals(2, combo.getItemCount());
        assertTrue(combo.getUI() instanceof ComicComboBoxUI);
        javax.swing.DefaultListCellRenderer custom = new javax.swing.DefaultListCellRenderer();
        combo.setRenderer(custom);
        refresh(combo);
        assertTrue(combo.getUI() instanceof ComicComboBoxUI);
        assertEquals(custom, combo.getRenderer());
        assertTrue(ComicControls.<String>comboBox().getUI() instanceof ComicComboBoxUI);
    }

    @Test
    public void spinnerKeepsTheComicDelegate() {
        JSpinner spinner = ComicControls.spinner(new SpinnerNumberModel(3, 0, 9, 1));
        assertEquals(3, spinner.getValue());
        assertTrue(spinner.getUI() instanceof ComicFormControls.SpinnerUI);
        refresh(spinner);
        assertTrue(spinner.getUI() instanceof ComicFormControls.SpinnerUI);
    }

    @Test
    public void textFieldsGetTheRoundedFieldLook() {
        JTextField field = ComicControls.textField("abc", 12);
        assertEquals("abc", field.getText());
        assertEquals(12, field.getColumns());
        assertTrue(field.getUI() instanceof ComicTextFieldUI);
        assertTrue(field.getBorder() instanceof ComicFieldBorder);
        assertFalse(field.isOpaque());
        assertEquals(theme.fieldBg(), field.getBackground());
        assertEquals(theme.comicPalette().getInk(), field.getForeground());
        refresh(field);
        assertTrue(field.getUI() instanceof ComicTextFieldUI);
        ComicFormControlsTest.paint(field);

        JPasswordField password = ComicControls.passwordField("pw", 8);
        assertTrue(password.getUI() instanceof ComicPasswordFieldUI);
        assertTrue(password.getBorder() instanceof ComicFieldBorder);
        refresh(password);
        assertTrue(password.getUI() instanceof ComicPasswordFieldUI);
        ComicFormControlsTest.paint(password);
    }

    @Test
    public void explicitFieldColorsWin() {
        JTextField field = ComicControls.textField(5);
        field.setBackground(java.awt.Color.MAGENTA);
        refresh(field);
        assertEquals(java.awt.Color.MAGENTA, field.getBackground());
        assertEquals(java.awt.Color.MAGENTA, ComicTextFieldUI.plateColor(field));
    }

    @Test
    public void readOnlyFieldRestsOnTheQuietPaper() {
        JTextField field = ComicControls.textField();
        field.setEditable(false);
        assertEquals(theme.fieldBgQuiet(), ComicTextFieldUI.plateColor(field));
    }

    @Test
    public void textAreaIsAComicTextArea() {
        JTextArea area = ComicControls.textArea("t", 3, 10);
        assertTrue(area instanceof ComicTextArea);
        assertEquals(3, area.getRows());
        assertEquals(theme.fieldBg(), area.getBackground());
        assertEquals(theme.comicPalette().getInk(), area.getCaretColor());
        refresh(area);
        assertEquals(theme.fieldBg(), area.getBackground());
    }

    @Test
    public void sliderProgressAndTabsKeepTheirComicDelegates() {
        JSlider slider = ComicControls.slider(0, 10, 4);
        assertEquals(4, slider.getValue());
        assertTrue(slider.getUI() instanceof ComicFormControls.SliderUI);
        refresh(slider);
        assertTrue(slider.getUI() instanceof ComicFormControls.SliderUI);

        JProgressBar progress = ComicControls.progressBar(0, 10);
        assertEquals(10, progress.getMaximum());
        assertTrue(progress.getUI() instanceof ComicProgressBarUI);
        refresh(progress);
        assertTrue(progress.getUI() instanceof ComicProgressBarUI);

        JTabbedPane tabs = ComicControls.tabbedPane();
        assertTrue(tabs.getUI() instanceof ComicTabbedPaneUI);
        refresh(tabs);
        assertTrue(tabs.getUI() instanceof ComicTabbedPaneUI);
    }

    @Test
    public void listGetsTheComicColors() {
        JList<String> list = ComicControls.list(new String[]{"a"});
        assertEquals(theme.fieldBg(), list.getBackground());
        assertEquals(theme.comicPalette().getInk(), list.getSelectionForeground());
        assertTrue(list.getCellRenderer() instanceof ComicListSupport.PaddedRenderer);
    }

    @Test
    public void titledBorderIsTheComicGroupFrame() {
        TitledBorder border = ComicControls.titledBorder("Group");
        assertEquals("Group", border.getTitle());
        assertEquals(theme.comicPalette().getInk(), border.getTitleColor());
        JPanel panel = new JPanel();
        panel.setBorder(border);
        ComicFormControlsTest.paint(panel);
    }

    @Test
    public void iconButtonSaysWhatItDoesAndStaysReachableByKeyboard() {
        ComicButton button = ComicControls.iconButton(
                com.aresstack.comiccontrols.paint.ResearchIcons.copy(), "Copy the address");
        assertEquals("Copy the address", button.getToolTipText());
        assertEquals("Copy the address", button.getAccessibleContext().getAccessibleName());
        assertTrue("without a label the Tab key is the only way to it without a mouse", button.isFocusable());
        assertTrue(button.getText() == null || button.getText().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void iconButtonWithoutATooltipIsRefused() {
        ComicControls.iconButton(com.aresstack.comiccontrols.paint.ResearchIcons.copy(), " ");
    }

    private static void refresh(JComponent c) {
        JPanel host = new JPanel();
        host.add(c);
        SwingUtilities.updateComponentTreeUI(host);
    }
}
