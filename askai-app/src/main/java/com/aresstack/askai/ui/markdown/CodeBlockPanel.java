package com.aresstack.askai.ui.markdown;

import com.aresstack.comiccontrols.control.ComicControls;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/** Display a fenced code block with a language label and copy action. */
final class CodeBlockPanel extends JPanel {

    CodeBlockPanel(String language, final String code, MarkdownTheme theme) {
        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(theme.getCodeBackground());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getSeparatorColor()),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel languageLabel = new JLabel(language == null || language.trim().isEmpty() ? "code" : language.trim());
        languageLabel.setForeground(theme.getMutedForeground());
        languageLabel.setHorizontalAlignment(SwingConstants.LEFT);
        MarkdownActionButton copyButton = new MarkdownActionButton(
                new MarkdownActionButton.CopyIcon(), "Copy code", theme.getMutedForeground(),
                new Runnable() {
                    public void run() {
                        copy(code);
                    }
                });
        header.add(languageLabel, BorderLayout.WEST);
        header.add(copyButton, BorderLayout.EAST);

        JTextArea textArea = ComicControls.textArea(code == null ? "" : code);
        textArea.setEditable(false);
        textArea.setFont(theme.getCodeFont());
        textArea.setForeground(theme.getForeground());
        textArea.setBackground(theme.getCodeBackground());
        textArea.setBorder(BorderFactory.createEmptyBorder(4, 2, 2, 2));
        textArea.setLineWrap(false);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        int lineCount = Math.max(1, textArea.getLineCount());
        int height = Math.min(320, lineCount * textArea.getFontMetrics(textArea.getFont()).getHeight() + 14);
        scrollPane.setPreferredSize(new Dimension(200, height));

        add(header, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(text == null ? "" : text), null);
    }
}
