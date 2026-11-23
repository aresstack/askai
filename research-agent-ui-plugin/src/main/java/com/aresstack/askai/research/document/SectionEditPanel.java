package com.aresstack.askai.research.document;

import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.comiccontrols.border.ComicBorder;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.paint.ComposerIcons;
import com.aresstack.comiccontrols.paint.ResearchIcons;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * The editor of one section of the book (plan PR 26), shown in the main area when a section is selected in
 * the Document tab: the text at full height, and below it everything that acts on this section as icon
 * buttons: Save, Skip/Unskip, view or restore a revision, rewrite the section or its chapter with a comment,
 * research the comment as an open point. The markers line names the source of each local marker. On a save
 * conflict the local text stays and the saved text shows below it.
 */
final class SectionEditPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final float TEXT_FONT_SIZE = 13.5f;
    private static final float SMALL_FONT_SIZE = 11.5f;
    private static final int TEXT_PADDING_V = 8;
    private static final int TEXT_PADDING_H = 10;
    private static final int GAP = 6;
    private static final int BUTTON_GAP = 4;
    /** The share of the height the editor keeps when a saved text or a revision shows below it. */
    private static final double EDITOR_SHARE = 0.7;
    /** How far the muted texts step back from the ink (0 = ink, 1 = background). */
    private static final float MUTED_MIX = 0.35f;
    private static final int DIVIDER_SIZE = 6;
    private static final String NO_REVISION = "No revision yet";

    private final SectionEditor editor;
    private final DocumentSession session;
    private final Consumer<String> feedback;
    private final JTextArea text = ComicControls.textArea();
    private final JLabel markers = new JLabel(" ");
    private final JTextArea stored = ComicControls.textArea();
    private final JScrollPane storedScroll = new ComicScrollPane(stored,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final JComboBox<String> revisions = ComicControls.comboBox();
    private final ComicButton save = ComicControls.iconButton(ComposerIcons.floppyDisk(), "Save");
    private final ComicButton skip = ComicControls.iconButton(ResearchIcons.skip(), "Skip this section");
    private final ComicButton view = ComicControls.iconButton(ResearchIcons.eye(), "View the selected revision");
    private final ComicButton restore =
            ComicControls.iconButton(ResearchIcons.undo(), "Restore the selected revision");
    private final JTextField comment = ComicControls.textField();
    private final ComicButton rewriteSection =
            ComicControls.iconButton(ComposerIcons.refresh(), "Rewrite this section (uses the comment)");
    private final ComicButton rewriteChapter =
            ComicControls.iconButton(ComposerIcons.openBook(), "Rewrite the whole chapter (uses the comment)");
    private final ComicButton researchGap = ComicControls.iconButton(ComposerIcons.magnifier(),
            "Research the comment as an open point of this section");
    private boolean loading;

    SectionEditPanel(SectionEditor editor, DocumentSession session, Consumer<String> feedback) {
        super(new BorderLayout(0, GAP));
        this.editor = editor;
        this.session = session;
        this.feedback = feedback;
        setOpaque(false);
        for (JTextArea area : new JTextArea[] {text, stored}) {
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setFont(ResearchUiTypography.regular(TEXT_FONT_SIZE));
            area.setBorder(BorderFactory.createEmptyBorder(TEXT_PADDING_V, TEXT_PADDING_H, TEXT_PADDING_V,
                    TEXT_PADDING_H));
        }
        stored.setEditable(false);
        storedScroll.setBorder(ComicBorder.titled("Saved text"));
        storedScroll.setVisible(false);

        split.setTopComponent(new ComicScrollPane(text, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));
        split.setBottomComponent(storedScroll);
        split.setResizeWeight(EDITOR_SHARE);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        split.setDividerSize(0); // no divider while nothing shows below the editor
        add(split, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);
        wire();
        refreshState();
    }

    /** Below the text: the revisions with Skip and Save, the comment with the three things it can start. */
    private JPanel buildControls() {
        ComicPalette palette = ComicPalette.defaultPalette();
        java.awt.Color muted = ThemeColor.mix(palette.getInk(), ThemeColor.of("comic.fieldBg"), MUTED_MIX);
        markers.setFont(ResearchUiTypography.regular(SMALL_FONT_SIZE));
        markers.setForeground(muted);

        JPanel revisionActions = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_GAP, 0));
        revisionActions.setOpaque(false);
        revisionActions.add(revisions);
        revisionActions.add(view);
        revisionActions.add(restore);
        JPanel history = new JPanel(new BorderLayout(GAP - BUTTON_GAP, 0));
        history.setOpaque(false);
        history.add(caption("Revisions", muted), BorderLayout.WEST);
        history.add(revisionActions, BorderLayout.CENTER);
        JPanel sectionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, BUTTON_GAP, 0));
        sectionActions.setOpaque(false);
        sectionActions.add(skip);
        sectionActions.add(save);
        JPanel first = new JPanel(new BorderLayout(GAP, 0));
        first.setOpaque(false);
        first.add(history, BorderLayout.CENTER);
        first.add(sectionActions, BorderLayout.EAST);

        comment.setToolTipText("What a rewrite should change, or an open point to research for this section");
        JPanel commentActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, BUTTON_GAP, 0));
        commentActions.setOpaque(false);
        commentActions.add(rewriteSection);
        commentActions.add(rewriteChapter);
        commentActions.add(researchGap);
        JPanel second = new JPanel(new BorderLayout(GAP, 0));
        second.setOpaque(false);
        JLabel commentCaption = caption("Comment", muted);
        commentCaption.setPreferredSize(new java.awt.Dimension(
                Math.max(commentCaption.getPreferredSize().width, history.getComponent(0).getPreferredSize().width),
                commentCaption.getPreferredSize().height));
        history.getComponent(0).setPreferredSize(commentCaption.getPreferredSize());
        second.add(commentCaption, BorderLayout.WEST);
        second.add(comment, BorderLayout.CENTER);
        second.add(commentActions, BorderLayout.EAST);

        JPanel rows = new JPanel(new BorderLayout(0, GAP));
        rows.setOpaque(false);
        rows.add(first, BorderLayout.NORTH);
        rows.add(second, BorderLayout.CENTER);
        rows.add(markers, BorderLayout.SOUTH);
        return rows;
    }

    private static JLabel caption(String text, java.awt.Color colour) {
        JLabel caption = new JLabel(text);
        caption.setFont(ResearchUiTypography.regular(SMALL_FONT_SIZE));
        caption.setForeground(colour);
        return caption;
    }

    private void wire() {
        text.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
        comment.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                updateGapButton();
            }

            public void removeUpdate(DocumentEvent e) {
                updateGapButton();
            }

            public void changedUpdate(DocumentEvent e) {
                updateGapButton();
            }
        });
        save.addActionListener(e -> {
            editor.edit(text.getText());
            SectionEditor.SaveResult result = editor.save(System.currentTimeMillis());
            feedback.accept(result.message);
            showBelow("Saved text", result.saved ? "" : result.storedText);
            refreshState();
        });
        skip.addActionListener(e -> toggleSkip());
        view.addActionListener(e -> {
            SectionRevision revision = selectedRevision();
            if (revision != null) {
                showBelow("Revision " + revision.getNumber(), revision.getText());
            }
        });
        restore.addActionListener(e -> restoreSelected());
        rewriteSection.addActionListener(e -> {
            SectionEditor.Item item = editor.item();
            if (item != null) {
                report(session.rewriteSection(item.sectionId, comment.getText()), "Rewriting the section...");
            }
        });
        rewriteChapter.addActionListener(e -> {
            SectionEditor.Item item = editor.item();
            if (item != null) {
                report(session.rewriteChapter(item.chapterId, comment.getText()), "Rewriting the chapter...");
            }
        });
        researchGap.addActionListener(e -> {
            SectionEditor.Item item = editor.item();
            if (item != null) {
                String result = session.researchGap(item.sectionId, item.section.getTitle(), comment.getText());
                feedback.accept(result != null && result.startsWith("gap-")
                        ? "Gap search added to the search plan and started." : result);
            }
        });
    }

    /** Every button of the panel (tests: icon-only, each with a tooltip). */
    JButton[] allButtons() {
        return new JButton[] {view, restore, skip, save, rewriteSection, rewriteChapter, researchGap};
    }

    /** Show a saved text or a revision below the editor; an empty text hides the lower half. */
    private void showBelow(String title, String content) {
        boolean show = content != null && !content.isEmpty();
        stored.setText(show ? content : "");
        stored.setCaretPosition(0);
        storedScroll.setBorder(ComicBorder.titled(title));
        storedScroll.setVisible(show);
        split.setDividerSize(show ? DIVIDER_SIZE : 0);
        revalidate();
        if (show) {
            split.setDividerLocation(EDITOR_SHARE);
        }
    }

    /** Open a section (the selection of the Document tab); the unsaved text of the one left is kept. */
    void open(SectionEditor.Item item) {
        editor.open(item);
        loading = true;
        try {
            text.setText(editor.localText());
            text.setCaretPosition(0);
        } finally {
            loading = false;
        }
        showBelow("Saved text", "");
        refreshState();
        if (editor.isDirty()) {
            feedback.accept("Unsaved edits of this section were kept. Save them or reload.");
        }
    }

    /** Re-read the approved outline; the open section keeps its local text and takes the entry's new data. */
    void refreshSections() {
        SectionEditor.Item open = editor.item();
        if (open != null) {
            for (SectionEditor.Item item : SectionEditor.items(session.approvedOutline())) {
                if (item.sectionId.equals(open.sectionId)) {
                    editor.rebind(item); // new chapter id and fingerprints, same local text
                }
            }
        }
        refreshState();
    }

    /** The section that is open, or {@code null}. */
    SectionEditor.Item item() {
        return editor.item();
    }

    /** "Written · revision 3", "Failed: no evidence", "Pending"; "" while no section is open. */
    String stateText() {
        SectionEditor.Item item = editor.item();
        if (item == null) {
            return "";
        }
        ManuscriptSection state = editor.manuscriptSection();
        if (state == null) {
            return SectionStatus.PENDING.label();
        }
        return state.getStatus().label() + (state.getReason().isEmpty() ? "" : ": " + state.getReason())
                + (state.getRevision() > 0 ? " · revision " + state.getRevision() : "");
    }

    /** Re-read the status, revisions and guard of the open section without touching its local text. */
    void refreshState() {
        SectionEditor.Item item = editor.item();
        if (item != null && !editor.isDirty()) {
            ManuscriptSection latest = editor.manuscriptSection();
            if (latest != null && latest.getRevision() != editor.loadedRevision()) {
                // The writer (or a restore) moved the section on and nothing is unsaved here: show the new text.
                open(item);
                return;
            }
        }
        String refusal = session.writeRefusal();
        boolean writable = refusal == null && item != null;
        ManuscriptSection state = item == null ? null : editor.manuscriptSection();
        boolean orphan = state != null && state.getStatus() == SectionStatus.ORPHAN;
        boolean skipped = state != null && state.getStatus() == SectionStatus.SKIPPED;
        boolean running = session.writingRunning();
        save.setEnabled(writable && !orphan);
        skip.setEnabled(writable && !orphan);
        skip.setIcon(skipped ? ComposerIcons.play() : ResearchIcons.skip());
        skip.setToolTipText(skipped ? "Write this section after all (unskip)" : "Skip this section");
        text.setEditable(writable && !orphan);
        rewriteSection.setEnabled(writable && !orphan && !running);
        rewriteChapter.setEnabled(writable && !running);
        comment.setEnabled(item != null);
        updateGapButton();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        if (item != null) {
            List<SectionRevision> list = editor.revisions();
            for (int i = list.size() - 1; i >= 0; i--) {
                SectionRevision r = list.get(i);
                model.addElement(r.getNumber() + " · " + r.getAuthor()
                        + (r.getModelLabel().isEmpty() ? "" : " (" + r.getModelLabel() + ")")
                        + " · " + format.format(new Date(r.getCreatedAt())));
            }
        }
        boolean hasRevisions = model.getSize() > 0;
        if (!hasRevisions) {
            model.addElement(NO_REVISION);
        }
        int chosen = revisions.getSelectedIndex();
        revisions.setModel(model);
        if (hasRevisions && chosen > 0 && chosen < model.getSize()) {
            revisions.setSelectedIndex(chosen);
        }
        revisions.setEnabled(hasRevisions);
        view.setEnabled(hasRevisions);
        restore.setEnabled(writable && !orphan && hasRevisions);
        showMarkers();
    }

    /** An open point needs words: the button wakes up with the comment. */
    private void updateGapButton() {
        researchGap.setEnabled(editor.item() != null && !comment.getText().trim().isEmpty());
    }

    private void showMarkers() {
        markers.setText(editor.item() == null ? " " : "Markers: " + (editor.markers().isEmpty() ? "none"
                : String.join("  ", editor.markers())));
    }

    private void changed() {
        if (!loading) {
            editor.edit(text.getText());
            showMarkers();
        }
    }

    private void toggleSkip() {
        SectionEditor.Item item = editor.item();
        if (item == null) {
            return;
        }
        try {
            ManuscriptSection state = editor.manuscriptSection();
            if (state != null && state.getStatus() == SectionStatus.SKIPPED) {
                session.manuscript().unskip(item.sectionId, System.currentTimeMillis());
            } else {
                session.manuscript().skip(item.sectionId, item.chapterId, System.currentTimeMillis());
            }
        } catch (RuntimeException refused) {
            feedback.accept("Not changed: " + refused.getMessage());
        }
        refreshState();
    }

    private SectionRevision selectedRevision() {
        int index = revisions.getSelectedIndex();
        List<SectionRevision> list = editor.revisions();
        return index < 0 || index >= list.size() ? null : list.get(list.size() - 1 - index);
    }

    private void restoreSelected() {
        SectionRevision revision = selectedRevision();
        if (revision == null || editor.item() == null) {
            return;
        }
        if (editor.isDirty()) {
            feedback.accept("Save or discard your edits before you restore a revision.");
            return;
        }
        try {
            session.manuscript().restore(editor.item().sectionId, revision.getNumber(), editor.loadedRevision(),
                    System.currentTimeMillis());
            SectionEditor.Item item = editor.item();
            open(item);
            feedback.accept("Restored revision " + revision.getNumber() + ".");
        } catch (RuntimeException failed) {
            feedback.accept("Not restored: " + failed.getMessage());
        }
    }

    /** Show why a writer action did not start, or that it started. */
    private void report(String refusal, String started) {
        feedback.accept(refusal == null ? started : refusal);
    }

    // ------------------------------------------------------------------ for tests

    String shownText() {
        return text.getText();
    }

    void type(String content) {
        text.setText(content);
    }

    void typeComment(String content) {
        comment.setText(content);
    }

    String belowText() {
        return storedScroll.isVisible() ? stored.getText() : "";
    }

    JButton button(String name) {
        for (JButton button : allButtons()) {
            if (name.equals(button.getAccessibleContext().getAccessibleName())) {
                return button;
            }
        }
        return null;
    }
}
