package com.aresstack.askai.ui.markdown;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;

/** Run a small visual smoke test for Markdown and Mermaid rendering. */
public final class MarkdownMessageDemo {

    private MarkdownMessageDemo() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                installSystemLookAndFeel();
                showDemo();
            }
        });
    }

    private static void installSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep the default look and feel when the system look and feel is unavailable.
        }
    }

    private static void showDemo() {
        MarkdownMessageView messageView = new MarkdownMessageView();
        messageView.setMarkdown(sampleMarkdown());

        JScrollPane scrollPane = new JScrollPane(messageView);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);

        JFrame frame = new JFrame("AskAI Markdown Swing");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(920, 760));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static String sampleMarkdown() {
        return "# Native Markdown in Swing\n\n"
                + "This component renders **bold**, *italic*, ~~obsolete~~ text, `inline code`, "
                + "and [links](https://github.com/aresstack/mermaid-java).\n\n"
                + "> Flexmark parses the document. Swing renders the view. Mermaid stays native.\n\n"
                + "1. Parse Markdown into an AST\n"
                + "2. Map blocks to Swing components\n"
                + "3. Render Mermaid outside the EDT\n\n"
                + "```java\n"
                + "MarkdownMessageView view = new MarkdownMessageView();\n"
                + "view.setMarkdown(answer);\n"
                + "```\n\n"
                + "```mermaid\n"
                + "graph LR\n"
                + "  A[LLM stream] --> B[Flexmark AST]\n"
                + "  B --> C[Swing blocks]\n"
                + "  B --> D[Mermaid Java]\n"
                + "  D --> E[BufferedImage]\n"
                + "  E --> C\n"
                + "```\n";
    }
}
