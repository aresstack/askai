package com.aresstack.askai.research.lead;

import com.aresstack.askai.research.memory.QuestionEvidence;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the research leaves open when it ends (component 8): every open question with what was found for it, the
 * questions set aside, and the topics only the author can place. It goes to the author, who releases the sources. It
 * never says a question is answered: "Belegt" only says that evidence was found. Immutable.
 */
public final class OpenList {

    /** A question with its topic and what the memory holds for it. */
    public static final class Entry {
        private final String questionId;
        private final String text;
        private final String topicName;
        private final String label;
        private final Map<String, Integer> counts;
        private final int searches;

        Entry(String questionId, String text, String topicName, String label, Map<String, Integer> counts,
              int searches) {
            this.questionId = questionId;
            this.text = text;
            this.topicName = topicName == null ? "" : topicName;
            this.label = label;
            this.counts = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(counts));
            this.searches = searches;
        }

        public String getQuestionId() {
            return questionId;
        }

        public String getText() {
            return text;
        }

        public String getTopicName() {
            return topicName;
        }

        /** "Belegt: 3 (2 Quellen) · Weiterführender Hinweis: 1" or "Kein Beleg gefunden". */
        public String getLabel() {
            return label;
        }

        public Map<String, Integer> getCounts() {
            return counts;
        }

        public int getSearches() {
            return searches;
        }
    }

    /** A topic whose place in the book the author decides (fence verdict UNKLAR). */
    public static final class AuthorTopic {
        private final String topicId;
        private final String name;
        private final String reason;

        AuthorTopic(String topicId, String name, String reason) {
            this.topicId = topicId;
            this.name = name;
            this.reason = reason == null ? "" : reason;
        }

        public String getTopicId() {
            return topicId;
        }

        public String getName() {
            return name;
        }

        /** The fence check's reason. */
        public String getReason() {
            return reason;
        }
    }

    private final int round;
    private final String reason;
    private final List<Entry> open;
    private final List<Entry> setAside;
    private final List<AuthorTopic> forTheAuthor;

    private OpenList(int round, String reason, List<Entry> open, List<Entry> setAside,
                     List<AuthorTopic> forTheAuthor) {
        this.round = round;
        this.reason = reason == null ? "" : reason.trim();
        this.open = Collections.unmodifiableList(open);
        this.setAside = Collections.unmodifiableList(setAside);
        this.forTheAuthor = Collections.unmodifiableList(forTheAuthor);
    }

    /** The open list as the memory stands, after {@code round}, with the reason the research ended. */
    public static OpenList of(ResearchMemory memory, int round, String reason) {
        List<Entry> open = new ArrayList<Entry>();
        List<Entry> setAside = new ArrayList<Entry>();
        for (ResearchQuestion question : memory.questions()) {
            QuestionEvidence evidence = memory.evidenceOf(question.getId());
            ResearchTopic topic = question.getTopicId().isEmpty() ? null : memory.currentTopic(question.getTopicId());
            Entry entry = new Entry(question.getId(), question.getText(), topic == null ? "" : topic.getName(),
                    evidence.summary(), evidence.counts(), memory.searchesFor(question.getId()).size());
            (question.getState() == ResearchQuestion.State.OFFEN ? open : setAside).add(entry);
        }
        List<AuthorTopic> forTheAuthor = new ArrayList<AuthorTopic>();
        for (ResearchTopic topic : memory.topics()) {
            if (topic.getOrigin() == ResearchTopic.Origin.GEFUNDEN && topic.getFence() == ResearchTopic.Fence.UNKLAR) {
                forTheAuthor.add(new AuthorTopic(topic.getId(), topic.getName(), topic.getFenceReason()));
            }
        }
        return new OpenList(round, reason, open, setAside, forTheAuthor);
    }

    /** The round after which the research ended. */
    public int getRound() {
        return round;
    }

    /** Why the research ended (budget, standstill, the lead's judgement). */
    public String getReason() {
        return reason;
    }

    public List<Entry> getOpen() {
        return open;
    }

    public List<Entry> getSetAside() {
        return setAside;
    }

    public List<AuthorTopic> getForTheAuthor() {
        return forTheAuthor;
    }

    /** The list as plain text for the author. */
    public String text(boolean german) {
        StringBuilder out = new StringBuilder();
        out.append(german ? "Offene Liste nach Runde " : "Open list after round ").append(round)
                .append(german ? ". Die Recherche ist nicht vollständig; der Autor gibt die Quellen frei.\n"
                        : ". The research is not complete; the author releases the sources.\n");
        if (!reason.isEmpty()) {
            out.append(german ? "Ende: " : "End: ").append(reason).append('\n');
        }
        entries(out, german ? "Offene Fragen" : "Open questions", open, german);
        entries(out, german ? "Zurückgestellte Fragen" : "Questions set aside", setAside, german);
        if (!forTheAuthor.isEmpty()) {
            out.append('\n').append(german ? "Themen, über die der Autor entscheidet:" : "Topics the author decides:")
                    .append('\n');
            for (AuthorTopic topic : forTheAuthor) {
                out.append("- ").append(topic.name);
                if (!topic.reason.isEmpty()) {
                    out.append(" (").append(topic.reason).append(')');
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void entries(StringBuilder out, String title, List<Entry> entries, boolean german) {
        if (entries.isEmpty()) {
            return;
        }
        out.append('\n').append(title).append(":\n");
        for (Entry entry : entries) {
            out.append("- ").append(entry.questionId).append(' ').append(entry.text);
            if (!entry.topicName.isEmpty()) {
                out.append(" [").append(entry.topicName).append(']');
            }
            out.append("\n  ").append(entry.label).append(german ? "; Suchen: " : "; searches: ")
                    .append(entry.searches).append(german ? ", Passagen geprüft: " : ", passages judged: ")
                    .append(count(entry, "geprueft")).append('\n');
        }
    }

    private static int count(Entry entry, String name) {
        Integer value = entry.counts.get(name);
        return value == null ? 0 : value;
    }
}
