package com.aresstack.askai.research.lead;

import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.LeadDecision;
import com.aresstack.askai.research.memory.QuestionEvidence;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.SearchRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the research lead looks at before a round, read from the memory (component 7): the open questions with their
 * counts, searches and strategies, the topics inside the fence, the topics the author still decides, the last searches
 * and the yield of the last rounds. It holds counts and single quotes, never the stock. Immutable once built.
 */
final class LeadSituation {

    /** An open question with what the memory holds for it. */
    static final class QuestionView {
        final ResearchQuestion question;
        final QuestionEvidence evidence;
        final ResearchTopic topic;
        final int searches;
        final SearchStrategy current;
        final int fruitlessWithCurrent;
        final Set<SearchStrategy> tried;
        final String directQuote;
        final String leadQuote;

        QuestionView(ResearchQuestion question, QuestionEvidence evidence, ResearchTopic topic, int searches,
                     SearchStrategy current, int fruitlessWithCurrent, Set<SearchStrategy> tried, String directQuote,
                     String leadQuote) {
            this.question = question;
            this.evidence = evidence;
            this.topic = topic;
            this.searches = searches;
            this.current = current;
            this.fruitlessWithCurrent = fruitlessWithCurrent;
            this.tried = Collections.unmodifiableSet(tried);
            this.directQuote = directQuote;
            this.leadQuote = leadQuote;
        }

        String id() {
            return question.getId();
        }

        /** The counts a decision about this question rests on. */
        Map<String, Integer> counts() {
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>(evidence.counts());
            counts.put("suchen", searches);
            return counts;
        }
    }

    /** A topic inside the fence (a card of the concept or a topic found with the fence verdict INNEN). */
    static final class TopicView {
        final ResearchTopic topic;
        final List<String> openQuestionIds;
        final int questions;
        final int findings;
        final boolean fresh;
        final String quote;

        TopicView(ResearchTopic topic, List<String> openQuestionIds, int questions, int findings, boolean fresh,
                  String quote) {
            this.topic = topic;
            this.openQuestionIds = Collections.unmodifiableList(openQuestionIds);
            this.questions = questions;
            this.findings = findings;
            this.fresh = fresh;
            this.quote = quote;
        }

        String id() {
            return topic.getId();
        }

        boolean isCard() {
            return topic.getOrigin() == ResearchTopic.Origin.KONZEPT;
        }

        /** The counts a decision about this topic rests on. */
        Map<String, Integer> counts() {
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
            counts.put("offeneFragen", openQuestionIds.size());
            counts.put("fragen", questions);
            counts.put("fundstellen", findings);
            return counts;
        }
    }

    final int round;
    final int searchesDone;
    final List<QuestionView> questions;
    final List<TopicView> cards;
    final List<TopicView> found;
    final List<ResearchTopic> unclear;
    final List<SearchRecord> recent;
    final int lastRound;
    final int lastNewEvidence;
    final int lastNewTopics;
    final int quietRounds;
    final int neverSearched;

    private LeadSituation(int round, int searchesDone, List<QuestionView> questions, List<TopicView> cards,
                          List<TopicView> found, List<ResearchTopic> unclear, List<SearchRecord> recent, int lastRound,
                          int lastNewEvidence, int lastNewTopics, int quietRounds) {
        this.round = round;
        this.searchesDone = searchesDone;
        this.questions = Collections.unmodifiableList(questions);
        this.cards = Collections.unmodifiableList(cards);
        this.found = Collections.unmodifiableList(found);
        this.unclear = Collections.unmodifiableList(unclear);
        this.recent = Collections.unmodifiableList(recent);
        this.lastRound = lastRound;
        this.lastNewEvidence = lastNewEvidence;
        this.lastNewTopics = lastNewTopics;
        this.quietRounds = quietRounds;
        int never = 0;
        for (QuestionView view : questions) {
            if (view.searches == 0) {
                never++;
            }
        }
        this.neverSearched = never;
    }

    /**
     * The situation before the next round: the round is one after the latest round of a search or a decision.
     *
     * @param recentCount how many of the last searches to keep
     * @param quoteChars  the longest quote kept per question or topic
     */
    static LeadSituation of(ResearchMemory memory, int recentCount, int quoteChars) {
        List<SearchRecord> searches = memory.searches();
        int round = 1;
        for (SearchRecord search : searches) {
            round = Math.max(round, search.getRound() + 1);
        }
        Set<String> decidedTopics = new HashSet<String>();
        for (LeadDecision decision : memory.decisions()) {
            round = Math.max(round, decision.getRound() + 1);
            if (decision.getTarget() == LeadDecision.Target.THEMA) {
                decidedTopics.add(decision.getTargetId());
            }
        }

        List<QuestionView> questions = new ArrayList<QuestionView>();
        Map<String, List<String>> openByTopic = new HashMap<String, List<String>>();
        Map<String, Integer> allByTopic = new HashMap<String, Integer>();
        for (ResearchQuestion question : memory.questions()) {
            ResearchTopic topic = question.getTopicId().isEmpty() ? null : memory.currentTopic(question.getTopicId());
            if (topic != null) {
                Integer all = allByTopic.get(topic.getId());
                allByTopic.put(topic.getId(), all == null ? 1 : all + 1);
            }
            if (question.getState() != ResearchQuestion.State.OFFEN) {
                continue;
            }
            if (topic != null) {
                List<String> open = openByTopic.get(topic.getId());
                if (open == null) {
                    open = new ArrayList<String>();
                    openByTopic.put(topic.getId(), open);
                }
                open.add(question.getId());
            }
            questions.add(view(memory, question, topic, quoteChars));
        }
        List<QuestionView> ordered = new ArrayList<QuestionView>(questions);
        Collections.sort(ordered, ORDER);

        List<TopicView> cards = new ArrayList<TopicView>();
        List<TopicView> found = new ArrayList<TopicView>();
        List<ResearchTopic> unclear = new ArrayList<ResearchTopic>();
        for (ResearchTopic topic : memory.topics()) {
            List<String> open = openByTopic.containsKey(topic.getId()) ? openByTopic.get(topic.getId())
                    : Collections.<String>emptyList();
            Integer all = allByTopic.get(topic.getId());
            int questionCount = all == null ? 0 : all;
            if (topic.getOrigin() == ResearchTopic.Origin.KONZEPT) {
                cards.add(new TopicView(topic, new ArrayList<String>(open), questionCount,
                        topic.getFindings().size(), false, ""));
            } else if (topic.getFence() == ResearchTopic.Fence.INNEN) {
                String quote = topic.getFindings().isEmpty() ? "" : cut(topic.getFindings().get(0).getQuote(),
                        quoteChars);
                found.add(new TopicView(topic, new ArrayList<String>(open), questionCount,
                        topic.getFindings().size(), questionCount == 0 && !decidedTopics.contains(topic.getId()),
                        quote));
            } else if (topic.getFence() == ResearchTopic.Fence.UNKLAR) {
                unclear.add(topic);
            }
        }
        Collections.sort(found, (a, b) -> a.fresh != b.fresh ? (a.fresh ? -1 : 1)
                : Integer.compare(b.findings, a.findings));

        List<SearchRecord> recent = new ArrayList<SearchRecord>(
                searches.subList(Math.max(0, searches.size() - Math.max(0, recentCount)), searches.size()));
        TreeMap<Integer, int[]> yieldByRound = new TreeMap<Integer, int[]>();
        for (SearchRecord search : searches) {
            SearchRecord.Yield yield = search.getYield();
            if (yield == null) {
                continue;
            }
            int[] sum = yieldByRound.get(search.getRound());
            if (sum == null) {
                sum = new int[2];
                yieldByRound.put(search.getRound(), sum);
            }
            sum[0] += yield.getNewEvidence();
            sum[1] += yield.getNewTopics();
        }
        int lastRound = yieldByRound.isEmpty() ? 0 : yieldByRound.lastKey();
        int[] last = yieldByRound.isEmpty() ? new int[2] : yieldByRound.lastEntry().getValue();
        int quiet = 0;
        for (int[] sum : yieldByRound.descendingMap().values()) {
            if (sum[0] + sum[1] > 0) {
                break;
            }
            quiet++;
        }
        return new LeadSituation(round, searches.size(), ordered, cards, found, unclear, recent, lastRound, last[0],
                last[1], quiet);
    }

    /** Questions without direct evidence first (never searched, then fewer searches first), then the rest. */
    private static final Comparator<QuestionView> ORDER = (a, b) -> {
        int rank = Integer.compare(rank(a), rank(b));
        if (rank != 0) {
            return rank;
        }
        if (rank(a) == 1) {
            return Integer.compare(a.searches, b.searches);
        }
        if (rank(a) == 2) {
            return Integer.compare(a.evidence.getDirectCount(), b.evidence.getDirectCount());
        }
        return 0;
    };

    private static int rank(QuestionView view) {
        if (view.evidence.getDirectCount() > 0) {
            return 2;
        }
        return view.searches == 0 ? 0 : 1;
    }

    private static QuestionView view(ResearchMemory memory, ResearchQuestion question, ResearchTopic topic,
                                     int quoteChars) {
        QuestionEvidence evidence = memory.evidenceOf(question.getId());
        List<SearchRecord> searches = memory.searchesFor(question.getId());
        SearchStrategy current = searches.isEmpty() ? SearchStrategy.STANDARD
                : strategyOf(searches.get(searches.size() - 1));
        int fruitless = 0;
        boolean streak = true;
        Set<SearchStrategy> tried = new LinkedHashSet<SearchStrategy>();
        for (int i = searches.size() - 1; i >= 0; i--) {
            SearchRecord search = searches.get(i);
            SearchRecord.Yield yield = search.getYield();
            if (yield == null) {
                continue;
            }
            if (yield.getNewEvidence() > 0) {
                break;
            }
            SearchStrategy strategy = strategyOf(search);
            tried.add(strategy);
            if (streak && strategy == current) {
                fruitless++;
            } else {
                streak = false;
            }
        }
        return new QuestionView(question, evidence, topic, searches.size(), current, fruitless, tried,
                quote(evidence.getDirect(), quoteChars), quote(evidence.getLeads(), quoteChars));
    }

    /** The strategy a search record names; one this build does not know counts as the standard search. */
    static SearchStrategy strategyOf(SearchRecord search) {
        SearchStrategy strategy = SearchStrategy.of(search.getStrategy());
        return strategy == null ? SearchStrategy.STANDARD : strategy;
    }

    private static String quote(List<EvidenceVerdict> verdicts, int chars) {
        for (EvidenceVerdict verdict : verdicts) {
            if (!verdict.getQuote().isEmpty()) {
                return cut(verdict.getQuote(), chars);
            }
        }
        return "";
    }

    /** At most {@code chars} characters on one line, cut at a blank when it is not too far back, then " …". */
    static String cut(String text, int chars) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= chars) {
            return flat;
        }
        String head = flat.substring(0, chars);
        int blank = head.lastIndexOf(' ');
        return (blank >= chars * 3 / 5 ? head.substring(0, blank) : head).trim() + " …";
    }
}
