package com.aresstack.askai.research.document;

import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The Document tab's section editor (plan PR 26): pick a section of the approved outline, edit its text, Save as a
 * section revision, Skip/Unskip, and view or restore older revisions while the phase is open. The markers line
 * names the source of each local marker. On a save conflict the local text stays and the saved text shows below.
 */
final class SectionEditPanel extends JPanel {

    private final SectionEditor editor;
    private final ResearchAgentSession session;
    private final JLabel status;
    private final JComboBox<SectionEditor.Item> sections = ComicControls.comboBox();
    private final JLabel sectionState = new JLabel(" ");
    private final JTextArea text = ComicControls.textArea();
    private final JLabel markers = new JLabel(" ");
    private final JTextArea stored = ComicControls.textArea();
    private final JScrollPane storedScroll = new JScrollPane(stored);
    private final JComboBox<String> revisions = ComicControls.comboBox();
    private final JButton save = new ComicButton("Save");
    private final JButton skip = new ComicButton("Skip section");
    private final JButton view = new ComicButton("View");
    private final JButton restore = new ComicButton("Restore");
    private boolean loading;

    SectionEditPanel(SectionEditor editor, ResearchAgentSession session, JLabel status) {
        super(new BorderLayout(4, 4));
        this.editor = editor;
        this.session = session;
        this.status = status;
        setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        stored.setLineWrap(true);
        stored.setWrapStyleWord(true);
        stored.setEditable(false);
        storedScroll.setBorder(BorderFactory.createTitledBorder("Saved text"));
        storedScroll.setVisible(false);

        JPanel top = new JPanel(new GridLayout(0, 1, 2, 2));
        top.setOpaque(false);
        JPanel pick = row();
        pick.add(new JLabel("Section"));
        pick.add(sections);
        pick.add(save);
        pick.add(skip);
        pick.add(sectionState);
        top.add(pick);
        JPanel history = row();
        history.add(new JLabel("Revisions"));
        history.add(revisions);
        history.add(view);
        history.add(restore);
        top.add(history);
        add(top, BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(text), storedScroll);
        split.setResizeWeight(0.7);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        add(markers, BorderLayout.SOUTH);

        sections.addActionListener(e -> {
            if (!loading) {
                openSelected();
            }
        });
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
        save.addActionListener(e -> {
            editor.edit(text.getText());
            SectionEditor.SaveResult result = editor.save(System.currentTimeMillis());
            status.setText(result.message);
            storedScroll.setVisible(!result.saved && !result.storedText.isEmpty());
            stored.setText(result.storedText);
            revalidate();
            refreshState();
        });
        skip.addActionListener(e -> toggleSkip());
        view.addActionListener(e -> {
            SectionRevision revision = selectedRevision();
            if (revision != null) {
                stored.setText(revision.getText());
                storedScroll.setBorder(BorderFactory.createTitledBorder("Revision " + revision.getNumber()));
                storedScroll.setVisible(true);
                revalidate();
            }
        });
        restore.addActionListener(e -> restoreSelected());
    }

    private static JPanel row() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        return row;
    }

    /** Re-read the sections of the approved outline; the open section and its local text stay. */
    void refreshSections() {
        List<SectionEditor.Item> items = SectionEditor.items(session.approvedOutlineOrNull());
        String open = editor.item() == null ? null : editor.item().sectionId;
        loading = true;
        try {
            DefaultComboBoxModel<SectionEditor.Item> model = new DefaultComboBoxModel<SectionEditor.Item>();
            SectionEditor.Item selected = null;
            for (SectionEditor.Item item : items) {
                model.addElement(item);
                if (item.sectionId.equals(open)) {
                    selected = item;
                }
            }
            sections.setModel(model);
            if (selected != null) {
                sections.setSelectedItem(selected);
                editor.rebind(selected); // new chapter id and fingerprints, same local text
            }
        } finally {
            loading = false;
        }
        if (open == null || sections.getSelectedItem() == null
                || !((SectionEditor.Item) sections.getSelectedItem()).sectionId.equals(open)) {
            openSelected();
        } else {
            refreshState();
        }
    }

    /** Re-read the status, revisions and guard of the open section without touching its local text. */
    void refreshState() {
        SectionEditor.Item item = editor.item();
        String refusal = session.manuscriptWriteRefusal();
        boolean writable = refusal == null && item != null;
        ManuscriptSection state = item == null ? null : editor.manuscriptSection();
        sectionState.setText(state == null ? (item == null ? "No approved outline yet." : "Pending")
                : state.getStatus().label() + (state.getReason().isEmpty() ? "" : ": " + state.getReason()));
        boolean orphan = state != null && state.getStatus() == SectionStatus.ORPHAN;
        save.setEnabled(writable && !orphan);
        skip.setEnabled(writable && !orphan);
        skip.setText(state != null && state.getStatus() == SectionStatus.SKIPPED ? "Unskip" : "Skip section");
        text.setEditable(writable && !orphan);
        restore.setEnabled(writable && !orphan);
        if (refusal != null) {
            status.setText(refusal);
        }
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
        revisions.setModel(model);
        view.setEnabled(model.getSize() > 0);
        markers.setText(item == null ? " " : "Markers: " + (editor.markers().isEmpty() ? "none"
                : String.join("  ", editor.markers())));
    }

    private void openSelected() {
        SectionEditor.Item item = (SectionEditor.Item) sections.getSelectedItem();
        editor.open(item);
        loading = true;
        try {
            text.setText(editor.localText());
            text.setCaretPosition(0);
        } finally {
            loading = false;
        }
        storedScroll.setVisible(false);
        revalidate();
        refreshState();
        if (editor.isDirty()) {
            status.setText("Unsaved edits of this section were kept. Save them or reload.");
        }
    }

    private void changed() {
        if (!loading) {
            editor.edit(text.getText());
            markers.setText("Markers: " + (editor.markers().isEmpty() ? "none"
                    : String.join("  ", editor.markers())));
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
            status.setText("Not changed: " + refused.getMessage());
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
            status.setText("Save or discard your edits before you restore a revision.");
            return;
        }
        try {
            session.manuscript().restore(editor.item().sectionId, revision.getNumber(), editor.loadedRevision(),
                    System.currentTimeMillis());
            status.setText("Restored revision " + revision.getNumber() + ".");
            openSelected();
        } catch (RuntimeException failed) {
            status.setText("Not restored: " + failed.getMessage());
        }
    }
}
