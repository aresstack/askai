package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Repair plan step 2a, part A: the question list of the book in Phase 1. The app drafts it on request ("Draft"), the
 * user edits it as text (one {@code ## card} heading per card, one question per line) and saves it; the list the
 * user saved is approved with the concept. Outside Concept the dialog shows the approved list read-only.
 */
final class MissionQuestionsDialog {

    /** What the dialog does with the list; the session implements it. */
    interface Handler {
        /** The list as text. */
        String text();

        /** {@code null} when saved, otherwise the reason. */
        String save(String text);

        /** Drafts the list in the background; {@code done} gets {@code null} or the reason, on the UI thread. */
        void draft(java.util.function.Consumer<String> done);

        /** Whether the list can be changed now (Concept phase). */
        boolean editable();
    }

    static final String HINT = "One \"## card\" line per first-level card, one question per line below it. The "
            + "questions say what the research must find out; they change neither the search plan nor the "
            + "Weidezaun. The saved list is approved with the concept.";

    private MissionQuestionsDialog() {
    }

    static void show(Component owner, final Handler handler) {
        final JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), "Research questions",
                java.awt.Dialog.ModalityType.MODELESS);
        final JTextArea area = ComicControls.textArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setText(handler.text());
        final boolean editable = handler.editable();
        area.setEditable(editable);
        final JLabel status = new JLabel(editable ? HINT : "Approved with the concept (read-only).");
        final ComicButton draft = new ComicButton("Draft");
        final ComicButton save = new ComicButton("Save");
        final ComicButton close = new ComicButton("Close");
        draft.setToolTipText("Let the model draft four to five questions per first-level card (replaces the list)");
        draft.setEnabled(editable);
        save.setEnabled(editable);
        draft.addActionListener(e -> {
            draft.setEnabled(false);
            save.setEnabled(false);
            status.setText("Drafting questions… (about a minute with model loading)");
            handler.draft(problem -> {
                draft.setEnabled(true);
                save.setEnabled(true);
                if (problem == null) {
                    area.setText(handler.text());
                    status.setText("Drafted. Edit, delete or add questions, then save.");
                } else {
                    status.setText(problem);
                }
            });
        });
        save.addActionListener(e -> {
            String problem = handler.save(area.getText());
            if (problem == null) {
                area.setText(handler.text());
                status.setText("Saved.");
            } else {
                status.setText(problem);
            }
        });
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(draft);
        buttons.add(save);
        buttons.add(close);
        JPanel south = new JPanel(new BorderLayout(6, 4));
        south.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        south.add(status, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(720, 520));
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(scroll, BorderLayout.CENTER);
        dialog.getContentPane().add(south, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
