package com.aresstack.askai.research.concept.questions;

/**
 * Repair plan step 2a, part A: one research question of the book mission. A question names what the research must
 * find out inside the subject space; it hangs on a first-level card of the concept but is no card itself, so it
 * moves neither the search plan nor the Weidezaun. Immutable.
 */
public final class MissionQuestion {

    /** Who wrote the question. In 2a only the app (draft) and the user write questions. */
    public enum Origin { APP, USER }

    private final String id;
    private final String card;
    private final String text;
    private final Origin origin;

    public MissionQuestion(String id, String card, String text, Origin origin) {
        this.id = id == null ? "" : id.trim();
        this.card = card == null ? "" : card.trim();
        this.text = text == null ? "" : text.trim();
        this.origin = origin == null ? Origin.USER : origin;
    }

    /** {@code F01}, {@code F02}, ... in list order. */
    public String getId() {
        return id;
    }

    /** The name of the first-level card the question belongs to. */
    public String getCard() {
        return card;
    }

    public String getText() {
        return text;
    }

    public Origin getOrigin() {
        return origin;
    }

    @Override
    public String toString() {
        return id + " [" + card + "] " + text;
    }
}
