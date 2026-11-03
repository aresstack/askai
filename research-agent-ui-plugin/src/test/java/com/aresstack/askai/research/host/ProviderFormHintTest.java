package com.aresstack.askai.research.host;

import org.junit.Assume;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import static org.junit.Assert.assertTrue;

public class ProviderFormHintTest {

    @Test
    public void aWrappedHintGetsTheHeightOfAllItsLines() throws Exception {
        Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("word ");
        }
        final JComponent[] hint = new JComponent[1];
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                JPanel column = ProviderForm.column();
                hint[0] = ProviderForm.hint(text.toString());
                column.add(hint[0]);
                column.setSize(500, 400);
                for (int pass = 0; pass < 3; pass++) {
                    column.doLayout();
                    ((JPanel) hint[0]).doLayout();
                    column.validate();
                }
            }
        });
        JTextArea area = null;
        for (java.awt.Component child : ((JPanel) hint[0]).getComponents()) {
            if (child instanceof JTextArea) {
                area = (JTextArea) child;
            }
        }
        int line = area.getFontMetrics(area.getFont()).getHeight();
        assertTrue("hint must show several lines, got " + hint[0].getHeight(), hint[0].getHeight() >= 2 * line);
    }
}
