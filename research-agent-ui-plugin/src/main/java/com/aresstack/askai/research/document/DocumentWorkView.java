package com.aresstack.askai.research.document;

import com.aresstack.comiccontrols.control.ComicSegmentedToggle;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.function.IntConsumer;

/**
 * The main area of the Document tab: the book on the right of its table of contents. A title line says what
 * is shown (the book, or the section that is open, with its state on the right), a centred switch changes
 * between the two faces:
 *
 * <ul>
 *   <li><b>Book</b> — the assembled, read-only document as pages;</li>
 *   <li><b>Section</b> — the editor of the section selected in the sidebar ({@link SectionEditPanel}).</li>
 * </ul>
 *
 * Switching never drops unsaved text. EDT only.
 */
final class DocumentWorkView extends JPanel {

    private static final long serialVersionUID = 1L;

    static final int CARD_BOOK = 0;
    static final int CARD_SECTION = 1;
    private static final String[] CARD_NAMES = {"book", "section"};

    private static final String BOOK_TITLE = "Book";
    private static final String NO_SECTION = "Select a section in the list on the left.";
    private static final float TITLE_FONT_SIZE = 16f;
    private static final float SMALL_FONT_SIZE = 11.5f;
    /** How far the muted texts step back from the ink (0 = ink, 1 = background). */
    private static final float MUTED_MIX = 0.35f;
    private static final int PADDING_V = 12;
    private static final int PADDING_H = 16;
    private static final int GAP = 8;

    private final JLabel title = new JLabel(BOOK_TITLE);
    private final JLabel state = new JLabel(" ");
    private final ComicSegmentedToggle cardSwitch = new ComicSegmentedToggle("Book", "Section");
    private final JPanel cards = new JPanel(new CardLayout());
    private final SectionEditPanel editor;
    private IntConsumer cardListener;
    private int card = CARD_BOOK;
    private String bookState = "";
    private String feedback = "";

    /**
     * @param page   the book's pages (in their scroll pane)
     * @param editor the section editor, or {@code null} when the session has no manuscript (the book only)
     */
    DocumentWorkView(JComponent page, SectionEditPanel editor) {
        super(new BorderLayout(0, GAP));
        this.editor = editor;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(PADDING_V, PADDING_H, PADDING_V, PADDING_H));

        ComicPalette palette = ComicPalette.defaultPalette();
        title.setFont(ResearchUiTypography.semiBold(TITLE_FONT_SIZE));
        state.setFont(ResearchUiTypography.regular(SMALL_FONT_SIZE));
        state.setForeground(ThemeColor.mix(palette.getInk(), ThemeColor.of("comic.fieldBg"), MUTED_MIX));
        JPanel titleRow = new JPanel(new BorderLayout(GAP, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(state, BorderLayout.EAST);
        JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        switchRow.setOpaque(false);
        switchRow.add(cardSwitch);
        switchRow.setVisible(editor != null);
        JPanel head = new JPanel(new BorderLayout(0, GAP));
        head.setOpaque(false);
        head.add(titleRow, BorderLayout.NORTH);
        head.add(switchRow, BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);

        cards.setOpaque(false);
        cards.add(page, CARD_NAMES[CARD_BOOK]);
        if (editor != null) {
            cards.add(editor, CARD_NAMES[CARD_SECTION]);
        }
        add(cards, BorderLayout.CENTER);
        cardSwitch.setSelectionListener(new IntConsumer() {
            public void accept(int index) {
                showCard(index);
            }
        });
        updateHead();
    }

    /** Told which card shows after every switch (the book reloads, the editor re-reads its section). */
    void setCardListener(IntConsumer listener) {
        this.cardListener = listener;
    }

    /** Open a section in the editor; {@code null} (a chapter heading, nothing selected) changes nothing. */
    void openSection(SectionEditor.Item item) {
        if (editor == null || item == null) {
            return;
        }
        feedback = "";
        editor.open(item);
        showCard(CARD_SECTION);
    }

    void showCard(int index) {
        if (index == CARD_SECTION && editor == null) {
            return;
        }
        card = index;
        ((CardLayout) cards.getLayout()).show(cards, CARD_NAMES[index]);
        if (cardSwitch.getSelectedIndex() != index) {
            cardSwitch.setSelectedIndex(index);
        }
        updateHead();
        if (cardListener != null) {
            cardListener.accept(index);
        }
    }

    int shownCard() {
        return card;
    }

    /** The book's state for the title line ("incomplete draft"). */
    void setBookState(String text) {
        bookState = text == null ? "" : text.trim();
        updateHead();
    }

    /** The last outcome of the section editor ("Saved (revision 3).", a refusal); shown on the title line. */
    void say(String line) {
        feedback = line == null ? "" : line.trim();
        updateHead();
    }

    /** The title line: what is shown and where it stands. */
    void updateHead() {
        if (card == CARD_BOOK || editor == null) {
            title.setText(BOOK_TITLE);
            state.setText(bookState.isEmpty() ? " " : bookState);
            return;
        }
        SectionEditor.Item item = editor.item();
        title.setText(item == null ? "Section" : item.label);
        String text = item == null ? NO_SECTION : editor.stateText();
        state.setText(feedback.isEmpty() ? text : feedback);
    }

    // ------------------------------------------------------------------ for tests

    String titleText() {
        return title.getText();
    }

    String stateText() {
        return state.getText();
    }
}
