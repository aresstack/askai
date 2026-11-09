package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineEdits;
import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineJson;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.WrapLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The Outline tab (plan PR 23): a tree editor (rename, add, delete, move, indent, outdent) and a raw JSON editor
 * with Save and Discard, after the {@link ConceptPaperView} pattern: unsaved text survives every refresh, the
 * parser is strict and a save names the revision it was based on. Above the editor: the mode message of the D4
 * table and the latest change request; below: the working revisions with view and restore. Every write goes
 * through {@link Actions}; the view holds no outline logic beyond the pure {@link OutlineEdits} gestures. EDT only.
 */
public final class OutlineEditorView extends JPanel {

    /** The writes of the tab; each answers {@code null} when applied, otherwise the reason it was refused. */
    public interface Actions {
        String save(Outline draft, long expectedRevision);

        String restore(long revision, long expectedRevision);

        /** The working revision's JSON, or {@code null}. */
        String history(long revision);

        void generate();
    }

    /** One consistent read of the session for a repaint. */
    public static final class Snapshot {
        final Outline outline;
        final long revision;
        final String refusal;
        final String changeRequest;

        public Snapshot(Outline outline, long revision, String refusal, String changeRequest) {
            this.outline = outline == null ? Outline.EMPTY : outline;
            this.revision = revision;
            this.refusal = refusal;
            this.changeRequest = changeRequest == null ? "" : changeRequest.trim();
        }
    }

    private final JLabel modeLabel = new JLabel();
    private final JTextArea changeRequest = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JButton generateButton = new ComicButton("Generate outline");
    private final JButton modeToggle = new ComicButton("JSON");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Outline"));
    private final JTree tree = new JTree(treeModel);
    private final JButton renameButton = new ComicButton("Rename");
    private final JButton addChapterButton = new ComicButton("Add chapter");
    private final JButton addSectionButton = new ComicButton("Add section");
    private final JButton deleteButton = new ComicButton("Delete");
    private final JButton upButton = new ComicButton("Up");
    private final JButton downButton = new ComicButton("Down");
    private final JButton indentButton = new ComicButton("Indent");
    private final JButton outdentButton = new ComicButton("Outdent");
    private final JTextArea jsonArea = new JTextArea();
    private final JButton saveButton = new ComicButton("Save");
    private final JButton discardButton = new ComicButton("Discard");
    private final DefaultListModel<Long> revisionModel = new DefaultListModel<Long>();
    private final JList<Long> revisions = new JList<Long>(revisionModel);
    private final JButton viewButton = new ComicButton("View");
    private final JButton restoreButton = new ComicButton("Restore");
    private final JPanel center = new JPanel(new CardLayout());

    private Actions actions;
    private Snapshot snapshot = new Snapshot(Outline.EMPTY, 0, "Not started yet.", "");
    private boolean treeMode = true;
    private boolean dirty;
    private long browsingRevision = -1;
    private boolean settingText;

    public OutlineEditorView() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel north = new JPanel(new BorderLayout(4, 2));
        JPanel head = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        head.add(generateButton);
        head.add(modeToggle);
        head.add(modeLabel);
        north.add(head, BorderLayout.NORTH);
        changeRequest.setEditable(false);
        changeRequest.setLineWrap(true);
        changeRequest.setWrapStyleWord(true);
        changeRequest.setOpaque(false);
        changeRequest.setBorder(BorderFactory.createTitledBorder("Change request"));
        north.add(changeRequest, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> updateButtons());
        JPanel treeCard = new JPanel(new BorderLayout(4, 4));
        treeCard.add(new JScrollPane(tree), BorderLayout.CENTER);
        JPanel treeButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        for (JButton button : new JButton[]{renameButton, addChapterButton, addSectionButton, deleteButton,
                upButton, downButton, indentButton, outdentButton}) {
            treeButtons.add(button);
        }
        treeCard.add(treeButtons, BorderLayout.SOUTH);

        jsonArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, jsonArea.getFont().getSize()));
        jsonArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        });
        JPanel jsonCard = new JPanel(new BorderLayout(4, 4));
        jsonCard.add(new JScrollPane(jsonArea), BorderLayout.CENTER);
        JPanel jsonButtons = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        jsonButtons.add(saveButton);
        jsonButtons.add(discardButton);
        jsonCard.add(jsonButtons, BorderLayout.SOUTH);
        center.add(treeCard, "tree");
        center.add(jsonCard, "json");
        add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 2));
        revisions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        revisions.setVisibleRowCount(3);
        revisions.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        revisions.addListSelectionListener(e -> updateButtons());
        JPanel revisionRow = new JPanel(new BorderLayout(6, 0));
        revisionRow.add(new JLabel("Revisions"), BorderLayout.WEST);
        revisionRow.add(new JScrollPane(revisions), BorderLayout.CENTER);
        JPanel revisionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        revisionButtons.add(viewButton);
        revisionButtons.add(restoreButton);
        revisionRow.add(revisionButtons, BorderLayout.EAST);
        south.add(revisionRow, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        wire();
        render(snapshot);
    }

    public void setActions(Actions actions) {
        this.actions = actions;
        updateButtons();
    }

    /** Repaint from the session. Unsaved JSON text and a viewed revision are never replaced. */
    public void render(Snapshot next) {
        String selected = selectedId();
        snapshot = next;
        boolean editable = editable();
        modeLabel.setText(editable ? "Editable" : next.refusal);
        changeRequest.setText(next.changeRequest);
        changeRequest.setVisible(!next.changeRequest.isEmpty());
        rebuildTree(next.outline, selected);
        Long chosen = revisions.getSelectedValue();
        revisionModel.clear();
        for (long r = next.revision; r >= 1; r--) {
            revisionModel.addElement(r);
        }
        if (chosen != null && chosen <= next.revision) {
            revisions.setSelectedValue(chosen, false);
        }
        if (!dirty && browsingRevision < 0) {
            setJson(OutlineJson.write(next.outline));
        }
        if (dirty && !editable) {
            status.setText("Unsaved text kept. " + next.refusal);
        }
        updateButtons();
    }

    // ------------------------------------------------------------------ for tests

    String jsonText() {
        return jsonArea.getText();
    }

    void typeJson(String text) {
        if (treeMode) {
            toggleMode();
        }
        jsonArea.setText(text);
    }

    boolean isDirty() {
        return dirty;
    }

    boolean isEditable() {
        return editable() && tree.isEnabled();
    }

    String statusText() {
        return status.getText();
    }

    void select(String id) {
        DefaultMutableTreeNode node = find((DefaultMutableTreeNode) treeModel.getRoot(), id);
        if (node != null) {
            tree.setSelectionPath(new TreePath(node.getPath()));
        }
    }

    JButton button(String text) {
        for (JButton b : new JButton[]{renameButton, addChapterButton, addSectionButton, deleteButton, upButton,
                downButton, indentButton, outdentButton, saveButton, discardButton, viewButton, restoreButton,
                generateButton, modeToggle}) {
            if (b.getText().equals(text)) {
                return b;
            }
        }
        return null;
    }

    void selectRevision(long revision) {
        revisions.setSelectedValue(revision, false);
    }

    /** The gesture without the dialog (tests). */
    String applyGesture(Outline draft) {
        return save(draft);
    }

    // ------------------------------------------------------------------ internals

    private boolean editable() {
        return snapshot.refusal == null;
    }

    private void wire() {
        modeToggle.addActionListener(e -> toggleMode());
        generateButton.addActionListener(e -> {
            if (actions != null) {
                actions.generate();
            }
        });
        renameButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry == null) {
                return;
            }
            String title = (String) JOptionPane.showInputDialog(this, "New title", "Rename",
                    JOptionPane.PLAIN_MESSAGE, null, null, entry.getTitle());
            if (title != null) {
                gesture(() -> OutlineEdits.rename(snapshot.outline, entry.getId(), title));
            }
        });
        addChapterButton.addActionListener(e -> {
            String title = JOptionPane.showInputDialog(this, "Chapter title", "Add chapter",
                    JOptionPane.PLAIN_MESSAGE);
            if (title != null) {
                OutlineEntry entry = selectedEntry();
                String after = entry == null ? null
                        : entry instanceof OutlineChapter ? entry.getId() : entry.getParentId();
                gesture(() -> OutlineEdits.addChapter(snapshot.outline, after, title));
            }
        });
        addSectionButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry == null) {
                return;
            }
            String title = JOptionPane.showInputDialog(this, "Section title", "Add section",
                    JOptionPane.PLAIN_MESSAGE);
            if (title != null) {
                String chapter = entry instanceof OutlineChapter ? entry.getId() : entry.getParentId();
                gesture(() -> OutlineEdits.addSection(snapshot.outline, chapter, title));
            }
        });
        deleteButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry == null) {
                return;
            }
            String what = entry instanceof OutlineChapter ? "the chapter \"" + entry.getTitle()
                    + "\" with its sections" : "the section \"" + entry.getTitle() + "\"";
            if (JOptionPane.showConfirmDialog(this, "Delete " + what + "?", "Delete",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                gesture(() -> OutlineEdits.delete(snapshot.outline, entry.getId()));
            }
        });
        upButton.addActionListener(e -> moveSelected(-1));
        downButton.addActionListener(e -> moveSelected(1));
        indentButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry != null) {
                gesture(() -> OutlineEdits.indent(snapshot.outline, entry.getId()));
            }
        });
        outdentButton.addActionListener(e -> {
            OutlineEntry entry = selectedEntry();
            if (entry != null) {
                gesture(() -> OutlineEdits.outdent(snapshot.outline, entry.getId()));
            }
        });
        saveButton.addActionListener(e -> saveJson());
        discardButton.addActionListener(e -> discard());
        viewButton.addActionListener(e -> viewRevision());
        restoreButton.addActionListener(e -> restoreRevision());
    }

    private interface Gesture {
        Outline draft();
    }

    private void gesture(Gesture gesture) {
        Outline draft;
        try {
            draft = gesture.draft();
        } catch (IllegalArgumentException refused) {
            status.setText(refused.getMessage());
            return;
        }
        String id = selectedId();
        String refusal = save(draft);
        if (refusal == null && id != null) {
            select(id);
        }
    }

    private void moveSelected(int delta) {
        OutlineEntry entry = selectedEntry();
        if (entry != null) {
            gesture(() -> OutlineEdits.move(snapshot.outline, entry.getId(), delta));
        }
    }

    private String save(Outline draft) {
        if (actions == null) {
            return "This session has no outline store.";
        }
        String refusal = actions.save(draft, snapshot.revision);
        status.setText(refusal == null ? "Saved." : refusal);
        return refusal;
    }

    private void saveJson() {
        if (browsingRevision >= 0 && !dirty) {
            restoreRevision();
            return;
        }
        Outline draft;
        try {
            draft = OutlineJson.read(jsonArea.getText());
            OutlineEdits.validate(draft);
        } catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
            return;
        }
        String refusal = save(draft);
        if (refusal == null) {
            dirty = false;
            browsingRevision = -1;
            setJson(OutlineJson.write(snapshot.outline));
            updateButtons();
        }
    }

    private void discard() {
        dirty = false;
        browsingRevision = -1;
        setJson(OutlineJson.write(snapshot.outline));
        status.setText("Discarded.");
        updateButtons();
    }

    private void viewRevision() {
        Long revision = revisions.getSelectedValue();
        if (revision == null || actions == null || dirty) {
            return;
        }
        String json = actions.history(revision);
        if (json == null) {
            status.setText("Revision " + revision + " is not readable.");
            return;
        }
        browsingRevision = revision;
        if (treeMode) {
            toggleMode();
        }
        setJson(json);
        status.setText("Viewing revision " + revision + ". Restore makes it current, Discard returns.");
        updateButtons();
    }

    private void restoreRevision() {
        Long revision = browsingRevision >= 0 ? Long.valueOf(browsingRevision) : revisions.getSelectedValue();
        if (revision == null || actions == null || dirty) {
            return;
        }
        String refusal = actions.restore(revision, snapshot.revision);
        status.setText(refusal == null ? "Restored revision " + revision + "." : refusal);
        if (refusal == null) {
            browsingRevision = -1;
            setJson(OutlineJson.write(snapshot.outline));
            updateButtons();
        }
    }

    private void toggleMode() {
        treeMode = !treeMode;
        ((CardLayout) center.getLayout()).show(center, treeMode ? "tree" : "json");
        modeToggle.setText(treeMode ? "JSON" : "Tree");
        updateButtons();
    }

    private void edited() {
        if (!settingText && !dirty) {
            dirty = true;
            updateButtons();
        }
    }

    private void setJson(String text) {
        settingText = true;
        try {
            if (!text.equals(jsonArea.getText())) {
                jsonArea.setText(text);
                jsonArea.setCaretPosition(0);
            }
        } finally {
            settingText = false;
        }
    }

    private void updateButtons() {
        boolean editable = editable() && actions != null;
        OutlineEntry entry = selectedEntry();
        boolean chapter = entry instanceof OutlineChapter;
        boolean clean = !dirty && browsingRevision < 0;
        generateButton.setEnabled(editable && actions != null);
        tree.setEnabled(true);
        renameButton.setEnabled(editable && clean && entry != null);
        addChapterButton.setEnabled(editable && clean);
        addSectionButton.setEnabled(editable && clean && entry != null);
        deleteButton.setEnabled(editable && clean && entry != null);
        upButton.setEnabled(editable && clean && entry != null);
        downButton.setEnabled(editable && clean && entry != null);
        indentButton.setEnabled(editable && clean && chapter
                && snapshot.outline.getChapters().indexOf(entry) > 0);
        outdentButton.setEnabled(editable && clean && entry instanceof OutlineSection);
        jsonArea.setEditable(editable && browsingRevision < 0);
        saveButton.setEnabled(editable && (dirty || browsingRevision >= 0));
        discardButton.setEnabled(dirty || browsingRevision >= 0);
        viewButton.setEnabled(actions != null && !dirty && revisions.getSelectedValue() != null);
        restoreButton.setEnabled(editable && !dirty && (browsingRevision >= 0
                || (revisions.getSelectedValue() != null && revisions.getSelectedValue() != snapshot.revision)));
    }

    private void rebuildTree(Outline outline, String selected) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Outline");
        int c = 0;
        for (OutlineChapter chapter : outline.getChapters()) {
            c++;
            DefaultMutableTreeNode chapterNode = new DefaultMutableTreeNode(new Row(chapter, c + ""));
            int s = 0;
            for (OutlineSection section : chapter.getSections()) {
                s++;
                chapterNode.add(new DefaultMutableTreeNode(new Row(section, c + "." + s)));
            }
            root.add(chapterNode);
        }
        treeModel.setRoot(root);
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
        if (selected != null) {
            select(selected);
        }
    }

    private DefaultMutableTreeNode find(DefaultMutableTreeNode node, String id) {
        Object value = node.getUserObject();
        if (value instanceof Row && ((Row) value).entry.getId().equals(id)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode hit = find((DefaultMutableTreeNode) node.getChildAt(i), id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private OutlineEntry selectedEntry() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return value instanceof Row ? ((Row) value).entry : null;
    }

    private String selectedId() {
        OutlineEntry entry = selectedEntry();
        return entry == null ? null : entry.getId();
    }

    /** A tree row: number, title and the marks of the entry. */
    private static final class Row {
        final OutlineEntry entry;
        final String number;

        Row(OutlineEntry entry, String number) {
            this.entry = entry;
            this.number = number;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder(number).append("  ").append(entry.getTitle());
            if (entry.isUserEdited()) {
                text.append("  (edited)");
            }
            if (entry.isStale()) {
                text.append("  (stale: ").append(entry.getStaleReason()).append(')');
            }
            return text.toString();
        }
    }
}
