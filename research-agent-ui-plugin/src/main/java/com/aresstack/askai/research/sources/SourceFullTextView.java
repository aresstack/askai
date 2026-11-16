package com.aresstack.askai.research.sources;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.control.ComicSegmentedToggle;
import com.aresstack.comiccontrols.paint.ComposerIcons;
import com.aresstack.comiccontrols.paint.ResearchIcons;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The reader of one source, shown in the workspace's main area (where the chat is) while the Sources pane is
 * active. Everything about the selected source lives HERE, so the sidebar only lists:
 *
 * <ul>
 *   <li>title line: the title, the author at the right;</li>
 *   <li>address line: the address as a link (click opens it, the copy button copies it), then the actions
 *       (relevant, exclude, save) and the toggle of the details;</li>
 *   <li>details (closed by default): author, linked sections, status and ratings;</li>
 *   <li>a centred switch between full text, search excerpt, passages and the user's comment.</li>
 * </ul>
 *
 * Saves go through {@link ResearchSourceRepository} with the revision the reader loaded (optimistic locking): a
 * conflict reloads the current record and says so. A refresh of the same revision leaves the reader alone, so
 * the reading position and unsaved edits survive a running search.
 */
final class SourceFullTextView extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The passages the knowledge pipeline derived from a source (read-only). */
    interface PassagesProvider {
        List<com.aresstack.askai.research.domain.Passage> passagesForSource(String sourceId);
    }

    static final int MODE_FULL_TEXT = 0;
    static final int MODE_EXCERPT = 1;
    static final int MODE_PASSAGES = 2;
    static final int MODE_COMMENT = 3;

    private static final String CARD_READ = "read";
    private static final String CARD_COMMENT = "comment";
    /** Rows of the sections editor: one linked section per line. */
    private static final int SECTION_ROWS = 4;
    /** Space between the two columns of the details. */
    private static final int DETAIL_COLUMN_GAP = 24;
    /** How far the muted texts (author, labels, feedback) step back from the ink (0 = ink, 1 = background). */
    private static final float MUTED_MIX = 0.35f;
    private static final String ORPHAN_MARK = " (orphan)";

    private final ComicPalette palette = ComicPalette.defaultPalette();
    private final ResearchSourceRepository repository;
    private final Set<String> knownSectionIds;

    private final JLabel title = new JLabel();
    private final JLabel author = new JLabel();
    private final JLabel address = new JLabel();
    private final ComicButton copyButton =
            ComicControls.iconButton(ResearchIcons.copy(), "Copy the address");
    private final ComicButton relevantButton =
            ComicControls.iconButton(ResearchIcons.star(), "Mark as relevant");
    private final ComicButton excludeButton =
            ComicControls.iconButton(ResearchIcons.ban(), "Exclude this source (reversible)");
    private final ComicButton saveButton =
            ComicControls.iconButton(ComposerIcons.floppyDisk(), "Save the details and the comment");
    private final ComicButton detailsButton =
            ComicControls.iconButton(ComposerIcons.chevronDown(), "Show the details");
    private final JLabel feedback = new JLabel(" ");

    private final JPanel details = new JPanel(new GridBagLayout());
    private final JTextField authorField = ComicControls.textField();
    private final JTextArea sectionsArea = ComicControls.textArea(SECTION_ROWS, 20);
    private final JComboBox<SourceStatus> statusCombo = ComicControls.<SourceStatus>comboBox(SourceStatus.values());
    private final JComboBox<SourceRelevance> relevanceCombo =
            ComicControls.<SourceRelevance>comboBox(SourceRelevance.values());
    private final JComboBox<SourceReliability> reliabilityCombo =
            ComicControls.<SourceReliability>comboBox(SourceReliability.values());
    private final JLabel scoreLabel = new JLabel(" ");

    private final ComicSegmentedToggle modeSwitch =
            new ComicSegmentedToggle("Full text", "Search excerpt", "Passages", "Comment");
    private final JTextArea text = ComicControls.textArea();
    private final JTextArea commentArea = ComicControls.textArea();
    private final JPanel body = new JPanel(new CardLayout());

    private PassagesProvider passagesProvider;
    private Runnable changeListener;
    private ResearchSourceRecord shown;
    private boolean relevant;
    private int mode = MODE_FULL_TEXT;
    private boolean loading;
    private boolean dirty;

    /** A read-only reader (no repository): the actions that would save are off. */
    SourceFullTextView() {
        this(null, null);
    }

    SourceFullTextView(ResearchSourceRepository repository, Set<String> knownSectionIds) {
        super(new BorderLayout(0, 8));
        this.repository = repository;
        this.knownSectionIds = knownSectionIds;
        setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        add(buildHead(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        wire();
        showDetails(false);
        updateControls();
    }

    // ------------------------------------------------------------------ layout

    private JPanel buildHead() {
        title.setFont(ResearchUiTypography.semiBold(16f));
        author.setFont(ResearchUiTypography.regular(11.5f));
        author.setForeground(mutedInk());
        JPanel titleRow = new JPanel(new BorderLayout(12, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(author, BorderLayout.EAST);

        address.setFont(ResearchUiTypography.regular(11.5f));
        address.setForeground(palette.getNavigationBlue());
        JPanel link = new JPanel(new BorderLayout(4, 0));
        link.setOpaque(false);
        link.add(address, BorderLayout.CENTER);
        link.add(copyButton, BorderLayout.EAST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        feedback.setFont(ResearchUiTypography.regular(11.5f));
        actions.add(feedback);
        actions.add(relevantButton);
        actions.add(excludeButton);
        actions.add(saveButton);
        actions.add(detailsButton);
        JPanel addressRow = new JPanel(new BorderLayout(12, 0));
        addressRow.setOpaque(false);
        addressRow.add(link, BorderLayout.WEST);
        addressRow.add(actions, BorderLayout.EAST);

        buildDetails();

        JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        switchRow.setOpaque(false);
        switchRow.add(modeSwitch);

        JPanel lines = new JPanel(new BorderLayout(0, 4));
        lines.setOpaque(false);
        lines.add(titleRow, BorderLayout.NORTH);
        lines.add(addressRow, BorderLayout.CENTER);
        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.setOpaque(false);
        head.add(lines, BorderLayout.NORTH);
        head.add(details, BorderLayout.CENTER);
        head.add(switchRow, BorderLayout.SOUTH);
        return head;
    }

    /** Two columns: what the source is (author, sections) left, how it is rated right. */
    private void buildDetails() {
        details.setOpaque(false);
        sectionsArea.setToolTipText("The outline sections this source is linked to, one per line;"
                + ORPHAN_MARK + " = the section no longer exists");
        statusCombo.setRenderer(labelRenderer());
        relevanceCombo.setRenderer(labelRenderer());
        reliabilityCombo.setRenderer(labelRenderer());
        scoreLabel.setForeground(mutedInk());
        JPanel identity = new JPanel(new GridBagLayout());
        identity.setOpaque(false);
        addDetailRow(identity, 0, "Author", authorField, true);
        addDetailRow(identity, 1, "Sections", new ComicScrollPane(sectionsArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), true);
        JPanel rating = new JPanel(new GridBagLayout());
        rating.setOpaque(false);
        addDetailRow(rating, 0, "Status", statusCombo, true);
        addDetailRow(rating, 1, "Relevance", relevanceCombo, true);
        addDetailRow(rating, 2, "Reliability", reliabilityCombo, true);
        addDetailRow(rating, 3, "Score", scoreLabel, false);
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.weightx = 1.0;
        left.fill = GridBagConstraints.HORIZONTAL;
        left.anchor = GridBagConstraints.NORTHWEST;
        details.add(identity, left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.anchor = GridBagConstraints.NORTHWEST;
        right.insets = new Insets(0, DETAIL_COLUMN_GAP, 0, 0);
        details.add(rating, right);
    }

    /** Label left, field right; a stretching field takes the width of its column. */
    private void addDetailRow(JPanel form, int row, String label, java.awt.Component field, boolean stretch) {
        JLabel caption = new JLabel(label);
        caption.setFont(ResearchUiTypography.regular(11.5f));
        caption.setForeground(mutedInk());
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.NORTHEAST;
        left.insets = new Insets(4, 0, 2, 8);
        form.add(caption, left);
        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1.0;
        right.anchor = GridBagConstraints.WEST;
        right.fill = stretch ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        right.insets = new Insets(2, 0, 2, 0);
        form.add(field, right);
    }

    private JPanel buildBody() {
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(ResearchUiTypography.regular(13.5f));
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        commentArea.setLineWrap(true);
        commentArea.setWrapStyleWord(true);
        commentArea.setFont(ResearchUiTypography.regular(13.5f));
        commentArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        body.setOpaque(false);
        body.add(new ComicScrollPane(text, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), CARD_READ);
        body.add(new ComicScrollPane(commentArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), CARD_COMMENT);
        return body;
    }

    private void wire() {
        address.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                if (isOpenable(address.getText())) {
                    address.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    underline(address, true);
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                address.setCursor(Cursor.getDefaultCursor());
                underline(address, false);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                openAddress();
            }
        });
        copyButton.addActionListener(e -> copyAddress());
        detailsButton.addActionListener(e -> showDetails(!details.isVisible()));
        relevantButton.addActionListener(e -> {
            relevant = !relevant;
            save();
        });
        excludeButton.addActionListener(e -> {
            statusCombo.setSelectedItem(isExcluded() ? statusWithoutExclusion() : SourceStatus.EXCLUDED);
            save();
        });
        saveButton.addActionListener(e -> save());
        modeSwitch.setSelectionListener(new IntConsumer() {
            public void accept(int index) {
                showMode(index);
            }
        });
        javax.swing.event.DocumentListener edited = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                edited();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                edited();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                edited();
            }
        };
        commentArea.getDocument().addDocumentListener(edited);
        authorField.getDocument().addDocumentListener(edited);
        sectionsArea.getDocument().addDocumentListener(edited);
        java.awt.event.ActionListener chosen = e -> edited();
        statusCombo.addActionListener(chosen);
        relevanceCombo.addActionListener(chosen);
        reliabilityCombo.addActionListener(chosen);
    }

    // ------------------------------------------------------------------ wiring from the contribution

    void setPassagesProvider(PassagesProvider provider) {
        this.passagesProvider = provider;
    }

    /** Runs after every successful save, so the sidebar table shows the new values. */
    void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    // ------------------------------------------------------------------ showing

    /**
     * Show this source. The same source at the same revision is left as it is: the reading position and the
     * user's unsaved edits stay.
     */
    void show(ResearchSourceRecord record) {
        if (record == null) {
            return;
        }
        boolean same = shown != null && shown.getSourceId().equals(record.getSourceId())
                && shown.getRevision() == record.getRevision()
                && shown.getFullText().equals(record.getFullText());
        if (same) {
            return;
        }
        load(record);
        showQuiet(" ");
    }

    private void load(ResearchSourceRecord record) {
        loading = true;
        try {
            shown = record;
            relevant = record.isUserRelevant();
            title.setText(record.getTitle() == null || record.getTitle().trim().isEmpty()
                    ? record.getSourceId() : record.getTitle());
            title.setToolTipText(title.getText());
            author.setText(record.getAuthor());
            String url = SourceTableRow.addressOf(record);
            address.setText(url);
            address.setToolTipText(isOpenable(url) ? "Open " + url : url);
            underline(address, false);
            authorField.setText(record.getAuthor());
            sectionsArea.setText(joinSections(record.getLinkedSectionIds()));
            statusCombo.setSelectedItem(record.getStatus());
            relevanceCombo.setSelectedItem(record.getRelevance());
            reliabilityCombo.setSelectedItem(record.getReliability());
            scoreLabel.setText(record.hasRerankScore()
                    ? String.format(java.util.Locale.ROOT, "%.4f", record.getRerankScore()) : "—");
            commentArea.setText(record.getComment());
            commentArea.setCaretPosition(0);
            dirty = false;
        } finally {
            loading = false;
        }
        showMode(mode);
        updateControls();
    }

    private void showMode(int index) {
        mode = index;
        if (modeSwitch.getSelectedIndex() != index) {
            modeSwitch.setSelectedIndex(index);
        }
        ((CardLayout) body.getLayout()).show(body, index == MODE_COMMENT ? CARD_COMMENT : CARD_READ);
        if (shown == null || index == MODE_COMMENT) {
            return;
        }
        text.setText(index == MODE_EXCERPT ? excerptOf(shown)
                : index == MODE_PASSAGES ? passagesOf(shown.getSourceId()) : fullTextOf(shown));
        text.setCaretPosition(0);
    }

    private static String fullTextOf(ResearchSourceRecord record) {
        return record.isParked() ? "(the page has not been read yet)" : record.getFullText();
    }

    private static String excerptOf(ResearchSourceRecord record) {
        return record.getExcerpt().trim().isEmpty() ? "(the search returned no excerpt)" : record.getExcerpt();
    }

    private String passagesOf(String sourceId) {
        if (passagesProvider == null) {
            return "(no knowledge capability in this session)";
        }
        List<com.aresstack.askai.research.domain.Passage> passages = passagesProvider.passagesForSource(sourceId);
        if (passages == null || passages.isEmpty()) {
            return "(no passages yet — the source has not been processed, or processing is still running)";
        }
        StringBuilder sb = new StringBuilder();
        int ordinal = 1;
        for (com.aresstack.askai.research.domain.Passage passage : passages) {
            sb.append('#').append(ordinal++);
            String heading = passage.getHeadingPath();
            if (heading != null && !heading.trim().isEmpty()) {
                sb.append("  [").append(heading.trim()).append(']');
            }
            sb.append('\n').append(passage.getText().trim()).append("\n\n");
        }
        return sb.append(passages.size()).append(" passage(s)").toString();
    }

    private void showDetails(boolean open) {
        details.setVisible(open);
        detailsButton.setIcon(open ? ComposerIcons.chevronUp() : ComposerIcons.chevronDown());
        detailsButton.setToolTipText(open ? "Hide the details" : "Show the details");
        revalidate();
        repaint();
    }

    private void edited() {
        if (!loading) {
            dirty = true;
            updateControls();
        }
    }

    private void updateControls() {
        boolean editable = repository != null && shown != null;
        relevantButton.setEnabled(editable);
        excludeButton.setEnabled(editable);
        saveButton.setEnabled(editable && dirty);
        copyButton.setEnabled(shown != null && !address.getText().isEmpty());
        authorField.setEditable(editable);
        sectionsArea.setEditable(editable);
        commentArea.setEditable(editable);
        statusCombo.setEnabled(editable);
        relevanceCombo.setEnabled(editable);
        reliabilityCombo.setEnabled(editable);
        relevantButton.setIcon(relevant ? ResearchIcons.starFilled() : ResearchIcons.star());
        relevantButton.setToolTipText(relevant ? "Relevant — click to unmark" : "Mark as relevant");
        boolean excluded = isExcluded();
        excludeButton.setIcon(excluded ? ResearchIcons.undo() : ResearchIcons.ban());
        excludeButton.setToolTipText(excluded ? "Take this source back into the corpus"
                : "Exclude this source (reversible)");
    }

    private boolean isExcluded() {
        return shown != null && shown.getStatus() == SourceStatus.EXCLUDED;
    }

    /** The status an excluded source returns to: read sources are new again, unread ones wait to be read. */
    private SourceStatus statusWithoutExclusion() {
        return shown != null && shown.getFullText().trim().isEmpty() ? SourceStatus.PARKED : SourceStatus.NEW;
    }

    // ------------------------------------------------------------------ actions

    private void save() {
        if (repository == null || shown == null) {
            return;
        }
        String id = shown.getSourceId();
        ResearchSourceRecord current = repository.get(id);
        if (current == null) {
            showProblem("The source no longer exists.");
            notifyChanged();
            return;
        }
        SourceUpdate update = SourceUpdate.from(current)
                .author(authorField.getText())
                .comment(commentArea.getText())
                .linkedSectionIds(parseSections(sectionsArea.getText()))
                .status((SourceStatus) statusCombo.getSelectedItem())
                .relevance((SourceRelevance) relevanceCombo.getSelectedItem())
                .reliability((SourceReliability) reliabilityCombo.getSelectedItem())
                .userRelevant(relevant)
                .build();
        SourceUpdateResult result = repository.update(id, shown.getRevision(), update);
        switch (result.getStatus()) {
            case UPDATED:
                load(result.getRecord());
                showQuiet("Saved.");
                break;
            case CONFLICT:
                load(result.getRecord());
                showProblem("Not saved: the source changed meanwhile. Reloaded.");
                break;
            default:
                relevant = shown.isUserRelevant();
                updateControls();
                showProblem("Not saved: " + result.getReason());
                break;
        }
        notifyChanged();
    }

    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private void openAddress() {
        String url = address.getText().trim();
        if (!isOpenable(url)) {
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception cannotOpen) {
            showProblem("Could not open the address: " + cannotOpen.getMessage());
        }
    }

    private void copyAddress() {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(address.getText()), null);
            showQuiet("Address copied.");
        } catch (Exception noClipboard) {
            showProblem("Could not copy the address: " + noClipboard.getMessage());
        }
    }

    static boolean isOpenable(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    // ------------------------------------------------------------------ sections

    private String joinSections(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(id);
            if (knownSectionIds != null && !knownSectionIds.contains(id)) {
                sb.append(ORPHAN_MARK);
            }
        }
        return sb.toString();
    }

    static List<String> parseSections(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) {
            return out;
        }
        for (String part : text.split("[,\\n]")) {
            String id = part.replace(ORPHAN_MARK.trim(), "").trim();
            if (!id.isEmpty() && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ dressing

    private Color mutedInk() {
        return ThemeColor.mix(palette.getInk(), ThemeColor.of("comic.fieldBg"), MUTED_MIX);
    }

    private void showQuiet(String message) {
        feedback.setForeground(mutedInk());
        feedback.setText(message);
    }

    private void showProblem(String message) {
        feedback.setForeground(palette.getAccentRed());
        feedback.setText(message);
    }

    private static void underline(JLabel label, boolean on) {
        Map<TextAttribute, Object> attributes = new HashMap<TextAttribute, Object>();
        attributes.put(TextAttribute.UNDERLINE, on ? TextAttribute.UNDERLINE_ON : Integer.valueOf(-1));
        Font font = label.getFont();
        label.setFont(font.deriveFont(attributes));
    }

    private static javax.swing.ListCellRenderer<Object> labelRenderer() {
        return new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(SourceLabels.of(value));
                return this;
            }
        };
    }

    // ------------------------------------------------------------------ tests

    /** The text the reading area shows. */
    String shownText() {
        return text.getText();
    }

    String shownAuthor() {
        return author.getText();
    }

    String shownAddress() {
        return address.getText();
    }

    String feedbackText() {
        return feedback.getText();
    }

    boolean detailsOpen() {
        return details.isVisible();
    }

    void selectMode(int index) {
        showMode(index);
    }

    void typeComment(String comment) {
        commentArea.setText(comment);
    }

    void typeSections(String sections) {
        sectionsArea.setText(sections);
    }

    ComicButton button(String accessibleName) {
        for (ComicButton button : new ComicButton[] {copyButton, relevantButton, excludeButton, saveButton,
                detailsButton}) {
            if (accessibleName.equals(button.getAccessibleContext().getAccessibleName())) {
                return button;
            }
        }
        return null;
    }
}
