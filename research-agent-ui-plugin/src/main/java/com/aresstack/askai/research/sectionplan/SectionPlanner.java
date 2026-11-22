package com.aresstack.askai.research.sectionplan;

import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.QuestionEvidence;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.TopicEvidence;
import com.aresstack.askai.research.missionreport.MissionCheck;
import com.aresstack.askai.research.missionreport.Placement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Component 9: the section plan, built from the research memory without reading a passage.
 * <p>
 * <b>Fate of every topic.</b> A topic outside the Weidezaun, unclear, not checked yet, without a question or without
 * direct evidence is left out with that reason. Every other topic goes into the book: with a section of its own or
 * into the section of another topic. Topics merged away (component 15) follow the topic they went into.
 * <p>
 * <b>Weight.</b> Direct evidence (distinct texts) plus independent sources, as the build plan says; the number of
 * passages is no weight. The mission's share for a card ({@link ResearchTopic#getWeight()}) fixes that card's share of
 * the words.
 * <p>
 * <b>Chapters.</b> A card of the concept with material is a chapter, with the found topics under it (the parent
 * lookup of component 15, else the card the topic names by its anchor id). The concept gives the direction, not the
 * chapters: a found topic with its subtopics gets a chapter of its own when its weight earns at least
 * {@link #FOUND_CHAPTER_WORDS} words, as long as the chapter count stays within the budget (words / 900, never below
 * the cards with material). A smaller found topic joins the chapter of the topic it shares evidence with, else of the
 * one the model names, else of the one it shares words with, and competes there by weight for a section of its own;
 * those that fit nowhere form the chapter "Weitere Themen aus der Recherche".
 * <p>
 * <b>Words and sections.</b> The rules of step 5a: a chapter keeps at most words / 450 sections, no section falls
 * below 350 words while the budget allows it. Within a chapter the card keeps its own section, the heaviest topics
 * get the others, the lighter ones go into the section they share evidence with (else the model's choice, shared
 * words, the card's section, the heaviest). Without a length the plan assumes 450 words per topic with direct
 * evidence and says so.
 * <p>
 * Each section lists the direct evidence of its topics' questions (one entry per text, with the questions it answers),
 * the leads apart, every question with "Belegt", "Weiterführender Hinweis" or "Kein Beleg gefunden", and the open
 * contradictions (component 17) of its questions and topics.
 */
public final class SectionPlanner {

    public static final int VERSION = 1;
    /** Words per section the budget aims at (step 5a). */
    public static final int SECTION_WORDS = 450;
    /** No section below this while the budget allows it (step 5a). */
    public static final int SECTION_FLOOR = 350;
    /** Words per chapter the chapter count follows (step 5a). */
    public static final int CHAPTER_WORDS = 900;
    /** The words a found topic's weight must earn for a chapter of its own. */
    public static final int FOUND_CHAPTER_WORDS = 2 * SECTION_FLOOR;
    public static final String COLLECTION_ID = "kapitel-weitere-themen";
    public static final String COLLECTION_TITLE = "Weitere Themen aus der Recherche";

    /** Words that say nothing about a topic's matter when names are compared. */
    private static final Set<String> STOP_WORDS = new HashSet<String>(Arrays.asList("oder", "sowie", "durch",
            "über", "unter", "nach", "eine", "einer", "eines", "einem", "einen", "werden", "wird", "sind", "beim",
            "zwischen", "allgemein", "thema", "themen", "frage", "fragen", "with", "from", "into", "about", "their",
            "this", "that", "and", "the", "for", "aspekte", "grundlagen", "überblick"));

    private final ResearchMemory memory;
    private int targetWords;
    private Function<String, String> parentOf = id -> "";
    private HostChooser chooser;
    private final List<MissionCheck.ContradictionRecord> contradictions =
            new ArrayList<MissionCheck.ContradictionRecord>();

    public SectionPlanner(ResearchMemory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("the section plan needs the research memory");
        }
        this.memory = memory;
    }

    /** The length of the book from component 1; 0 or less when the mission names none. */
    public SectionPlanner targetWords(int words) {
        this.targetWords = words;
        return this;
    }

    /** The parent of a topic (component 15, {@code unterthemaVon}); {@code ""} when it has none. */
    public SectionPlanner parents(Function<String, String> lookup) {
        this.parentOf = lookup == null ? id -> "" : lookup;
        return this;
    }

    /** The model that names a section for a topic without shared evidence; {@code null} for none. */
    public SectionPlanner hostChooser(HostChooser value) {
        this.chooser = value;
        return this;
    }

    /** The contradictions of component 17; only the open ones go into the plan. */
    public SectionPlanner contradictions(List<MissionCheck.ContradictionRecord> records) {
        if (records != null) {
            contradictions.addAll(records);
        }
        return this;
    }

    // ------------------------------------------------------------------------------------------------- model

    /** A topic that stands on its own, as the plan weighs it. */
    private static final class Node {
        final ResearchTopic topic;
        final int order;
        final int direct;
        final int sources;
        final List<String> questionIds;
        final Set<String> keys = new LinkedHashSet<String>();
        boolean candidate;
        long weight;
        String reasonOut = "";
        String root = "";
        Node host;
        String hostReason = "";
        Family family;
        /** A found topic without a chapter of its own that joined the chapter of the topic it fits. */
        boolean joined;

        Node(ResearchTopic topic, int order, TopicEvidence evidence) {
            this.topic = topic;
            this.order = order;
            this.direct = evidence == null ? 0 : evidence.getDirectCount();
            this.sources = evidence == null ? 0 : evidence.getIndependentSources();
            this.questionIds = evidence == null ? Collections.<String>emptyList() : evidence.getQuestionIds();
        }

        String id() {
            return topic.getId();
        }

        String name() {
            return topic.getName();
        }
    }

    /** A chapter in the making. */
    private static final class Family {
        final String id;
        final String title;
        final SectionPlan.ChapterKind kind;
        final Node root;
        final List<Node> members = new ArrayList<Node>();
        double share = Double.NaN;
        long weight;
        int words;
        final List<Node> own = new ArrayList<Node>();

        Family(String id, String title, SectionPlan.ChapterKind kind, Node root) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.root = root;
        }

        void add(Node node) {
            members.add(node);
            weight += node.weight;
            node.family = this;
        }
    }

    private static final Comparator<Node> HEAVIEST_FIRST = (a, b) -> a.weight != b.weight
            ? Long.compare(b.weight, a.weight) : Integer.compare(a.order, b.order);

    // ------------------------------------------------------------------------------------------------- plan

    public SectionPlan plan(long timeMillis) {
        List<String> notes = new ArrayList<String>();
        Map<String, Node> nodes = new LinkedHashMap<String, Node>();
        int order = 0;
        for (ResearchTopic topic : memory.topics()) {
            Node node = new Node(topic, order++, memory.evidenceOfTopic(topic.getId()));
            decideFate(node);
            nodes.put(topic.getId(), node);
        }
        List<Node> candidates = new ArrayList<Node>();
        for (Node node : nodes.values()) {
            if (node.candidate) {
                candidates.add(node);
            }
        }

        boolean given = targetWords > 0;
        int total = given ? targetWords : SECTION_WORDS * candidates.size();
        if (!given && !candidates.isEmpty()) {
            notes.add("Kein Umfang vorgegeben: angenommen werden " + SECTION_WORDS + " Wörter je Thema mit direkten "
                    + "Belegen (" + candidates.size() + " Themen, " + total + " Wörter).");
        }
        if (candidates.isEmpty()) {
            notes.add("Kein Thema hat direkte Belege; die Gliederung ist leer.");
        }

        // Families: a card with the found topics under it, or a found topic with its subtopics.
        Map<String, String> cardByAnchor = new HashMap<String, String>();
        for (Node node : nodes.values()) {
            if (node.topic.getOrigin() == ResearchTopic.Origin.KONZEPT && !node.topic.getCardAnchorId().isEmpty()) {
                cardByAnchor.put(node.topic.getCardAnchorId(), node.id());
            }
        }
        Map<String, Family> families = new LinkedHashMap<String, Family>();
        for (Node node : candidates) {
            node.root = rootOf(node, nodes, cardByAnchor);
        }
        for (Node node : nodes.values()) {
            if (node.topic.getOrigin() == ResearchTopic.Origin.KONZEPT && hasMembers(node.id(), candidates)) {
                Family family = new Family("kapitel-" + node.id(), node.name(), SectionPlan.ChapterKind.KARTE, node);
                if (node.topic.getWeight() > 0) {
                    family.share = node.topic.getWeight();
                }
                families.put(node.id(), family);
            }
        }
        for (Node node : candidates) {
            Family family = families.get(node.root);
            if (family == null) {
                Node root = nodes.get(node.root);
                family = new Family("kapitel-" + root.id(), root.name(), SectionPlan.ChapterKind.GEFUNDEN, root);
                families.put(node.root, family);
            }
            family.add(node);
        }
        for (Node node : nodes.values()) {
            if (node.topic.getOrigin() == ResearchTopic.Origin.KONZEPT && node.topic.getWeight() > 0
                    && !families.containsKey(node.id())) {
                notes.add("Die Karte „" + node.name() + "“ hat einen Anteil von " + percent(node.topic.getWeight())
                        + ", aber keine direkten Belege; ihr Anteil geht an die übrigen Kapitel.");
            }
        }

        // Chapters: every card with material; found topics as their weight earns it, within the chapter count.
        List<Family> chapters = new ArrayList<Family>();
        List<Family> found = new ArrayList<Family>();
        for (Family family : families.values()) {
            if (family.kind == SectionPlan.ChapterKind.KARTE) {
                chapters.add(family);
            } else {
                found.add(family);
            }
        }
        Collections.sort(found, (a, b) -> a.weight != b.weight ? Long.compare(b.weight, a.weight)
                : Integer.compare(a.root.order, b.root.order));
        int cap = Math.max(chapters.size(), Math.max(1, total / CHAPTER_WORDS));
        double explicit = 0;
        long openWeight = 0;
        for (Family family : families.values()) {
            if (Double.isNaN(family.share)) {
                openWeight += family.weight;
            } else {
                explicit += family.share;
            }
        }
        double openWords = total * Math.max(0.0, 1.0 - Math.min(1.0, explicit));
        List<Node> pool = new ArrayList<Node>();
        for (Family family : found) {
            double earned = openWeight == 0 ? 0 : openWords * family.weight / openWeight;
            if (chapters.size() < cap && earned >= FOUND_CHAPTER_WORDS) {
                chapters.add(family);
            } else {
                pool.addAll(family.members);
            }
        }

        // Found topics without a chapter: into the section of a topic of the chapters, or into the collection.
        List<Node> inChapters = new ArrayList<Node>();
        for (Family family : chapters) {
            inChapters.addAll(family.members);
        }
        Collections.sort(pool, HEAVIEST_FIRST);
        List<Node> leftover = new ArrayList<Node>();
        for (Node node : pool) {
            if (placeWithHost(node, inChapters, false, notes)) {
                // It joins the host's chapter and competes there by weight for a section of its own.
                node.joined = true;
                node.host.family.add(node);
            } else {
                leftover.add(node);
            }
        }
        if (!leftover.isEmpty()) {
            Family collection = new Family(COLLECTION_ID, COLLECTION_TITLE, SectionPlan.ChapterKind.SAMMEL, null);
            for (Node node : leftover) {
                collection.add(node);
            }
            chapters.add(collection);
        }

        // Words per chapter, then sections per chapter.
        long[] weights = new long[chapters.size()];
        double[] shares = new double[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            weights[i] = chapters.get(i).weight;
            shares[i] = chapters.get(i).share;
        }
        int[] chapterWords = WordBudget.spread(total, weights, shares, SECTION_FLOOR);
        for (int i = 0; i < chapters.size(); i++) {
            Family family = chapters.get(i);
            family.words = chapterWords[i];
            List<Node> ranked = new ArrayList<Node>(family.members);
            Collections.sort(ranked, HEAVIEST_FIRST);
            if (family.root != null && family.root.candidate && ranked.remove(family.root)) {
                ranked.add(0, family.root);
            }
            int n = Math.max(1, Math.min(ranked.size(), family.words / SECTION_WORDS));
            family.own.addAll(ranked.subList(0, n));
            for (Node own : family.own) {
                own.host = null;
                own.hostReason = "";
            }
            for (Node node : ranked.subList(n, ranked.size())) {
                if (!node.joined) {
                    placeWithHost(node, family.own, true, notes);
                }
                // A joined topic keeps the topic it fits; when that one has no section either, it follows it.
                node.hostReason = "kein eigener Abschnitt: das Kapitel hat Wörter für " + n
                        + (n == 1 ? " Abschnitt" : " Abschnitte") + ", Gewicht " + node.weight + "; "
                        + node.hostReason;
            }
        }

        return build(timeMillis, total, given, chapters, nodes, notes);
    }

    // ------------------------------------------------------------------------------------------------- fate

    private void decideFate(Node node) {
        ResearchTopic topic = node.topic;
        String reason = topic.getFenceReason();
        switch (topic.getFence()) {
            case AUSSEN:
                node.reasonOut = "außerhalb des Weidezauns" + (reason.isEmpty() ? "" : ": " + reason);
                return;
            case UNKLAR:
                node.reasonOut = "Weidezaun unklar" + (reason.isEmpty() ? "" : ": " + reason);
                return;
            case UNGEPRUEFT:
                node.reasonOut = "Weidezaun noch nicht geprüft";
                return;
            default:
                break;
        }
        if (node.questionIds.isEmpty()) {
            node.reasonOut = "noch keine Frage zu diesem Thema gestellt";
            return;
        }
        int leads = 0;
        for (String questionId : node.questionIds) {
            QuestionEvidence evidence = memory.evidenceOf(questionId);
            if (evidence == null) {
                continue;
            }
            leads += evidence.getLeadCount();
            for (EvidenceVerdict verdict : evidence.getDirect()) {
                node.keys.add(verdict.getTextKey());
            }
        }
        if (node.direct == 0) {
            node.reasonOut = leads > 0 ? "keine direkten Belege, nur weiterführende Hinweise"
                    : QuestionEvidence.LABEL_NONE;
            return;
        }
        node.candidate = true;
        node.weight = (long) node.direct + node.sources;
    }

    // ------------------------------------------------------------------------------------------------- families

    private String rootOf(Node node, Map<String, Node> nodes, Map<String, String> cardByAnchor) {
        if (node.topic.getOrigin() == ResearchTopic.Origin.KONZEPT) {
            return node.id();
        }
        String best = node.id();
        Set<String> seen = new HashSet<String>();
        seen.add(node.id());
        String current = node.id();
        while (true) {
            String parent = parentId(nodes.get(current), cardByAnchor);
            if (parent.isEmpty()) {
                return best;
            }
            ResearchTopic standing = memory.currentTopic(parent);
            Node next = standing == null ? null : nodes.get(standing.getId());
            if (next == null || !seen.add(next.id())) {
                return best;
            }
            if (next.topic.getOrigin() == ResearchTopic.Origin.KONZEPT) {
                return next.id();
            }
            if (next.candidate) {
                best = next.id();
            }
            current = next.id();
        }
    }

    private String parentId(Node node, Map<String, String> cardByAnchor) {
        String parent;
        try {
            parent = SectionPlan.clean(parentOf.apply(node.id()));
        } catch (RuntimeException failed) {
            parent = "";
        }
        if (parent.isEmpty() && node.topic.getOrigin() == ResearchTopic.Origin.GEFUNDEN
                && !node.topic.getCardAnchorId().isEmpty()) {
            String card = cardByAnchor.get(node.topic.getCardAnchorId());
            parent = card == null ? "" : card;
        }
        return parent;
    }

    private static boolean hasMembers(String rootId, List<Node> candidates) {
        for (Node node : candidates) {
            if (node.root.equals(rootId)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------- hosts

    /**
     * Finds the topic in whose section {@code node} goes: shared evidence, then the model, then shared words. In a
     * chapter ({@code fallback}) the card's section or the heaviest catches what finds nothing; outside one a topic
     * without a host stays unplaced (it goes into the collection).
     */
    private boolean placeWithHost(Node node, List<Node> hosts, boolean fallback, List<String> notes) {
        List<Node> options = new ArrayList<Node>(hosts);
        options.remove(node);
        if (options.isEmpty()) {
            return false;
        }
        Node host = byEvidence(node, options);
        if (host != null) {
            return hostWith(node, host, "gemeinsame Belege mit „" + host.name() + "“");
        }
        boolean askWords = true;
        if (chooser != null && (!fallback || options.size() >= 2)) {
            HostChooser.Choice choice = chooser.choose(option(node), optionsOf(options));
            if (choice.isFailed()) {
                notes.add("Die Wahl des Abschnitts für „" + node.name() + "“ ist fehlgeschlagen (" + choice.getReason()
                        + "); gemeinsame Wörter entschieden.");
            } else if (!choice.getTopicId().isEmpty()) {
                for (Node option : options) {
                    if (option.id().equals(choice.getTopicId())) {
                        return hostWith(node, option, "Wahl des Modells: " + choice.getReason());
                    }
                }
            } else {
                askWords = false;
            }
        }
        if (askWords) {
            host = byWords(node, options);
            if (host != null) {
                return hostWith(node, host, "gemeinsame Wörter mit „" + host.name() + "“");
            }
        }
        if (!fallback) {
            return false;
        }
        Node root = node.family == null ? null : node.family.root;
        if (root != null && options.contains(root)) {
            return hostWith(node, root, "beim Abschnitt der Karte „" + root.name() + "“");
        }
        List<Node> ranked = new ArrayList<Node>(options);
        Collections.sort(ranked, HEAVIEST_FIRST);
        return hostWith(node, ranked.get(0), "kein passender Abschnitt; beim schwersten Abschnitt „"
                + ranked.get(0).name() + "“");
    }

    private static boolean hostWith(Node node, Node host, String reason) {
        node.host = host;
        node.hostReason = reason;
        return true;
    }

    private static Node byEvidence(Node node, List<Node> options) {
        Node best = null;
        int bestShared = 0;
        for (Node option : options) {
            int shared = 0;
            for (String key : node.keys) {
                if (option.keys.contains(key)) {
                    shared++;
                }
            }
            if (shared > bestShared
                    || (shared == bestShared && shared > 0 && HEAVIEST_FIRST.compare(option, best) < 0)) {
                best = option;
                bestShared = shared;
            }
        }
        return best;
    }

    private static Node byWords(Node node, List<Node> options) {
        Set<String> words = words(node.topic.getName() + " " + node.topic.getDescription());
        Node best = null;
        int bestShared = 0;
        for (Node option : options) {
            Set<String> other = words(option.topic.getName() + " " + option.topic.getDescription());
            int shared = 0;
            for (String word : words) {
                for (String candidate : other) {
                    if (sameStem(word, candidate)) {
                        shared++;
                        break;
                    }
                }
            }
            if (shared > bestShared
                    || (shared == bestShared && shared > 0 && HEAVIEST_FIRST.compare(option, best) < 0)) {
                best = option;
                bestShared = shared;
            }
        }
        return best;
    }

    /** Lower-case words of at least four letters, without words that say nothing about the matter. */
    static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<String>();
        for (String word : (text == null ? "" : text).toLowerCase(Locale.GERMAN).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 4 && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /** The same word, or one begins with the other and the shorter has at least five letters. */
    static boolean sameStem(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        return shorter.length() >= 5 && longer.startsWith(shorter);
    }

    private HostChooser.Option option(Node node) {
        return new HostChooser.Option(node.id(), node.name(), node.topic.getDescription(), "");
    }

    private List<HostChooser.Option> optionsOf(List<Node> options) {
        List<HostChooser.Option> list = new ArrayList<HostChooser.Option>();
        for (Node option : options) {
            list.add(new HostChooser.Option(option.id(), option.name(), option.topic.getDescription(),
                    option.family == null ? "" : option.family.title));
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------------- output

    private SectionPlan build(long timeMillis, int total, boolean given, List<Family> chapters,
                              Map<String, Node> nodes, List<String> notes) {
        // Where every placed topic ends up: its own section, or its host's (a host may itself have gone into one).
        Map<Node, Node> sectionOwner = new HashMap<Node, Node>();
        for (Family family : chapters) {
            for (Node own : family.own) {
                sectionOwner.put(own, own);
            }
        }
        for (Node node : nodes.values()) {
            Node owner = node;
            for (int depth = 0; owner != null && !sectionOwner.containsKey(owner) && depth < 8; depth++) {
                owner = owner.host;
            }
            if (owner != null && sectionOwner.containsKey(owner)) {
                sectionOwner.put(node, sectionOwner.get(owner));
            }
        }
        Map<String, String> sectionOfTopic = new HashMap<String, String>();
        for (Map.Entry<Node, Node> entry : sectionOwner.entrySet()) {
            sectionOfTopic.put(entry.getKey().id(), sectionId(entry.getValue()));
        }
        Map<String, List<SectionPlan.Contradiction>> open = openContradictions(sectionOfTopic, notes);

        Map<String, List<String>> passagesOfTopic = new HashMap<String, List<String>>();
        List<SectionPlan.Chapter> built = new ArrayList<SectionPlan.Chapter>();
        for (Family family : chapters) {
            List<List<Node>> members = new ArrayList<List<Node>>();
            long[] weights = new long[family.own.size()];
            for (int i = 0; i < family.own.size(); i++) {
                Node own = family.own.get(i);
                List<Node> inSection = new ArrayList<Node>();
                for (Node node : nodes.values()) {
                    if (node != own && sectionOwner.get(node) == own) {
                        inSection.add(node);
                    }
                }
                Collections.sort(inSection, HEAVIEST_FIRST);
                inSection.add(0, own);
                members.add(inSection);
                for (Node node : inSection) {
                    weights[i] += node.weight;
                }
            }
            int[] words = WordBudget.withFloor(family.words, weights, SECTION_FLOOR);
            List<SectionPlan.Section> sections = new ArrayList<SectionPlan.Section>();
            for (int i = 0; i < family.own.size(); i++) {
                Node own = family.own.get(i);
                String id = sectionId(own);
                sections.add(section(id, own, members.get(i), weights[i], words[i], open.get(id), passagesOfTopic));
            }
            built.add(new SectionPlan.Chapter(family.id, family.title, family.kind,
                    family.root == null ? "" : family.root.id(), family.share, family.words, sections));
        }

        List<SectionPlan.TopicLine> lines = new ArrayList<SectionPlan.TopicLine>();
        Set<String> listed = new HashSet<String>();
        for (Node node : nodes.values()) {
            lines.add(line(node, placement(node, sectionOwner, passagesOfTopic)));
            listed.add(node.id());
        }
        for (Node node : nodes.values()) {
            for (String mergedId : node.topic.getMergedFrom()) {
                ResearchTopic merged = memory.topic(mergedId);
                if (merged == null || !listed.add(mergedId)) {
                    continue;
                }
                Placement current = placement(node, sectionOwner, passagesOfTopic);
                String reason = "zusammengeführt mit „" + node.name() + "“";
                Placement placement = current.getSectionId().isEmpty()
                        ? Placement.notTaken(mergedId, reason + ": " + current.getReason())
                        : Placement.mergedInto(mergedId, current.getSectionId(), reason,
                        Collections.<String>emptyList());
                lines.add(new SectionPlan.TopicLine(mergedId, merged.getName(), merged.getOrigin().name(),
                        merged.getFence().name(), 0, 0, 0, placement));
            }
        }
        return new SectionPlan(VERSION, timeMillis, total, given, built, lines, notes,
                chooser == null ? "" : chooser.modelName());
    }

    private SectionPlan.Section section(String id, Node own, List<Node> topics, long weight, int words,
                                        List<SectionPlan.Contradiction> open,
                                        Map<String, List<String>> passagesOfTopic) {
        Map<String, Builder> direct = new LinkedHashMap<String, Builder>();
        Map<String, Builder> further = new LinkedHashMap<String, Builder>();
        List<SectionPlan.Question> questions = new ArrayList<SectionPlan.Question>();
        List<String> topicIds = new ArrayList<String>();
        for (Node topic : topics) {
            topicIds.add(topic.id());
            for (String questionId : topic.questionIds) {
                QuestionEvidence evidence = memory.evidenceOf(questionId);
                if (evidence == null) {
                    continue;
                }
                ResearchQuestion question = evidence.getQuestion();
                questions.add(new SectionPlan.Question(question.getId(), question.getText(), topic.id(),
                        evidence.getDirectCount() > 0 ? QuestionEvidence.LABEL_DIRECT : evidence.getLeadCount() > 0
                                ? QuestionEvidence.LABEL_LEAD : QuestionEvidence.LABEL_NONE));
                for (EvidenceVerdict verdict : evidence.getDirect()) {
                    take(direct, verdict, questionId, topic.id());
                }
                for (EvidenceVerdict verdict : evidence.getLeads()) {
                    take(further, verdict, questionId, topic.id());
                }
            }
        }
        further.keySet().removeAll(direct.keySet());
        List<SectionPlan.Evidence> evidence = new ArrayList<SectionPlan.Evidence>();
        for (Builder builder : direct.values()) {
            SectionPlan.Evidence entry = builder.build();
            evidence.add(entry);
            for (String topicId : entry.getTopicIds()) {
                List<String> ids = passagesOfTopic.get(topicId);
                if (ids == null) {
                    ids = new ArrayList<String>();
                    passagesOfTopic.put(topicId, ids);
                }
                ids.add(entry.getPassageId());
            }
        }
        List<SectionPlan.Evidence> leads = new ArrayList<SectionPlan.Evidence>();
        for (Builder builder : further.values()) {
            leads.add(builder.build());
        }
        return new SectionPlan.Section(id, own.name(), own.id(), topicIds, weight, words, evidence, leads, questions,
                open);
    }

    /** One text of evidence with every question and topic it serves in the section. */
    private static final class Builder {
        final EvidenceVerdict first;
        final List<String> questionIds = new ArrayList<String>();
        final List<String> topicIds = new ArrayList<String>();

        Builder(EvidenceVerdict first) {
            this.first = first;
        }

        SectionPlan.Evidence build() {
            return new SectionPlan.Evidence(first.getPassageId(), first.getSourceId(), first.getSourceUrl(),
                    first.getTextKey(), questionIds, topicIds);
        }
    }

    private static void take(Map<String, Builder> into, EvidenceVerdict verdict, String questionId, String topicId) {
        Builder builder = into.get(verdict.getTextKey());
        if (builder == null) {
            builder = new Builder(verdict);
            into.put(verdict.getTextKey(), builder);
        }
        if (!builder.questionIds.contains(questionId)) {
            builder.questionIds.add(questionId);
        }
        if (!builder.topicIds.contains(topicId)) {
            builder.topicIds.add(topicId);
        }
    }

    private Map<String, List<SectionPlan.Contradiction>> openContradictions(Map<String, String> sectionOfTopic,
                                                                           List<String> notes) {
        Map<String, List<SectionPlan.Contradiction>> bySection = new HashMap<String, List<SectionPlan.Contradiction>>();
        for (MissionCheck.ContradictionRecord record : contradictions) {
            if (!record.isOpen()) {
                continue;
            }
            String topicId = "";
            if ("FRAGE".equals(record.getScope())) {
                ResearchQuestion question = memory.currentQuestion(record.getScopeId());
                ResearchTopic topic = question == null ? null : memory.currentTopic(question.getTopicId());
                topicId = topic == null ? "" : topic.getId();
            } else if ("THEMA".equals(record.getScope())) {
                ResearchTopic topic = memory.currentTopic(record.getScopeId());
                topicId = topic == null ? "" : topic.getId();
            }
            String sectionId = sectionOfTopic.get(topicId);
            if (sectionId == null) {
                notes.add("Der offene Widerspruch " + record.getId() + " gehört zu keinem Abschnitt der Gliederung.");
                continue;
            }
            List<SectionPlan.Contradiction> list = bySection.get(sectionId);
            if (list == null) {
                list = new ArrayList<SectionPlan.Contradiction>();
                bySection.put(sectionId, list);
            }
            list.add(new SectionPlan.Contradiction(record.getId(), record.getScope(), record.getScopeId(),
                    record.getSourceA(), record.getSourceB(), record.getVerdict()));
        }
        return bySection;
    }

    private static Placement placement(Node node, Map<Node, Node> sectionOwner,
                                       Map<String, List<String>> passagesOfTopic) {
        Node owner = sectionOwner.get(node);
        if (owner == null) {
            return Placement.notTaken(node.id(), node.reasonOut.isEmpty() ? "ohne Abschnitt geblieben"
                    : node.reasonOut);
        }
        List<String> passages = passagesOfTopic.getOrDefault(node.id(), Collections.<String>emptyList());
        if (owner == node) {
            return Placement.ownSection(node.id(), sectionId(node), passages);
        }
        return Placement.mergedInto(node.id(), sectionId(owner), node.hostReason, passages);
    }

    private static SectionPlan.TopicLine line(Node node, Placement placement) {
        return new SectionPlan.TopicLine(node.id(), node.name(), node.topic.getOrigin().name(),
                node.topic.getFence().name(), node.weight, node.direct, node.sources, placement);
    }

    static String sectionId(Node node) {
        return "abschnitt-" + node.id();
    }

    private static String percent(double share) {
        return Math.round(share * 100) + " %";
    }
}
