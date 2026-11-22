package com.aresstack.askai.research.contradictions;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.memory.Contradiction;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.SourceDomain;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Component 17 of the build plan of 21.11.2026, the contradiction check (Widerspruchsanalyse): compares direct
 * evidence on the same question, or on the same topic, from different source groups and says whether two statements
 * contradict each other. It resolves nothing and never says which side is right: an open contradiction goes to the
 * outline (9) and the writer (10) with both sources, the research lead (8) may search to clear it up, and the user may
 * decide it.
 *
 * <p>Candidates, never across the whole stock: per question the pairs of its direct evidence (BELEGT, one per text)
 * from different source groups ({@link ResearchMemory#sourceGroups}: sources that carry the same text are one group);
 * per topic the pairs of direct evidence of two different questions of the topic, again from different groups. bge-m3
 * sorts the pairs of each question or topic by the similarity of their quotes, the most similar first, and the first
 * {@link Options#getPairsPerScope} are the candidates of that question or topic. No pair counts twice: its id comes
 * from the two texts, whichever question they meet under. A candidate judged under the same prompt version is not
 * judged again, one the text shows or the user decided never again; one that failed is.</p>
 *
 * <p>One call of gemma4:12b per pair on the slot {@code phase.research.contradictions} (thinking on), pairs in
 * parallel, one repair. The model sees the mission, the question (or the topic and both questions), both quotes with
 * the address of their source and, when a passage reader is given, the passage around each quote. It answers
 * WIDERSPRUCH, VEREINBAR with what separates the two statements (another jurisdiction, time, group, context or thing,
 * or nothing when they agree) or UNKLAR, the reason first. A failed call is FEHLER and the pair comes again; it is
 * never VEREINBAR.</p>
 *
 * <p>Every verdict goes to the memory as a {@link Contradiction} in the state OFFEN, in the order of the candidates;
 * {@code contradictions/log.jsonl} keeps each call with its raw answers.</p>
 */
public final class ContradictionCheck {

    /** The prompts' version; a verdict is reused only under the same one. */
    public static final int PROMPT_VERSION = 1;

    /** Prompt (mission, two quotes, two passages) and answer with thinking fit; the platform's minimum. */
    static final int CONTEXT_TOKENS = 16384;
    static final double TEMPERATURE = 0.0;
    /** Calls per pair: the first and one repair attempt. */
    static final int ATTEMPTS = 2;
    private static final int MISSION_CHARS = 800;
    private static final int TEXT_CHARS = 300;
    private static final int REASON_CHARS = 600;
    private static final int MIN_REASON_CHARS = 10;
    private static final int EMBED_BATCH = 32;

    static final String SYSTEM_DE = "Du prüfst für ein Sachbuch, ob zwei belegte Aussagen einander widersprechen. Du "
            + "löst nichts auf und entscheidest nicht, welche Aussage stimmt. Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You check for a non-fiction book whether two statements with evidence contradict "
            + "each other. You resolve nothing and do not decide which statement is right. Answer only with JSON.";

    /** The answer: the reason first, then the verdict and what separates two statements that both hold. */
    static final String SCHEMA = schema();

    public static final ResearchRole ROLE = ResearchRole.builder("contradiction-check",
                    ResearchModelSlots.PHASE_RESEARCH_CONTRADICTIONS)
            .purpose("component 17: says whether two pieces of direct evidence on one question or topic from "
                    + "different source groups contradict each other; resolves nothing")
            .promptFromCaller()
            .output(RoleOutput.schema(SCHEMA, "grund, urteil, unterschied"))
            .thinking(ThinkingMode.ON)
            .contextTokens(CONTEXT_TOKENS)
            .build();

    /** Reads a passage by its id, for the text around a quote; {@code null} or {@code ""} when it is unknown. */
    public interface Passages {
        String text(String passageId);
    }

    /** Tuning; immutable. */
    public static final class Options {
        private final int pairsPerScope;
        private final double minSimilarity;
        private final int parallel;
        private final int answerTokens;
        private final int quoteChars;
        private final int contextChars;

        private Options(int pairsPerScope, double minSimilarity, int parallel, int answerTokens, int quoteChars,
                        int contextChars) {
            if (pairsPerScope < 1 || minSimilarity < -1 || minSimilarity > 1 || parallel < 1 || answerTokens < 256
                    || quoteChars < 40 || contextChars < 0) {
                throw new IllegalArgumentException("invalid options of the contradiction check");
            }
            this.pairsPerScope = pairsPerScope;
            this.minSimilarity = minSimilarity;
            this.parallel = parallel;
            this.answerTokens = answerTokens;
            this.quoteChars = quoteChars;
            this.contextChars = contextChars;
        }

        /**
         * 6 pairs per question or topic, no lower bound on the similarity (it only sorts), 2 calls at a time, 6,144
         * answer tokens (the thinking counts in), quotes up to 600 characters, 1,200 characters of passage around each.
         */
        public static Options defaults() {
            return new Options(6, -1, 2, 6144, 600, 1200);
        }

        public Options withPairsPerScope(int pairs) {
            return new Options(pairs, minSimilarity, parallel, answerTokens, quoteChars, contextChars);
        }

        /** Pairs less similar than this are no candidates; -1 (the default) leaves every pair in. */
        public Options withMinSimilarity(double similarity) {
            return new Options(pairsPerScope, similarity, parallel, answerTokens, quoteChars, contextChars);
        }

        public Options withParallel(int calls) {
            return new Options(pairsPerScope, minSimilarity, calls, answerTokens, quoteChars, contextChars);
        }

        public Options withAnswerTokens(int tokens) {
            return new Options(pairsPerScope, minSimilarity, parallel, tokens, quoteChars, contextChars);
        }

        public Options withContextChars(int chars) {
            return new Options(pairsPerScope, minSimilarity, parallel, answerTokens, quoteChars, chars);
        }

        public int getPairsPerScope() {
            return pairsPerScope;
        }

        public double getMinSimilarity() {
            return minSimilarity;
        }

        public int getParallel() {
            return parallel;
        }

        public int getAnswerTokens() {
            return answerTokens;
        }

        public int getQuoteChars() {
            return quoteChars;
        }

        public int getContextChars() {
            return contextChars;
        }
    }

    /** One pair to judge: where it meets, its two sides with their quotes and questions, the similarity. */
    public static final class Pair {
        private final Contradiction.Scope scope;
        private final String scopeId;
        private final String scopeName;
        private final Contradiction.Side a;
        private final Contradiction.Side b;
        private final String quoteA;
        private final String quoteB;
        private final String questionA;
        private final String questionB;
        private final double similarity;

        /**
         * @param scopeName the question's text (scope FRAGE) or the topic's name (scope THEMA)
         * @param questionA the text of the question side A answers
         */
        public Pair(Contradiction.Scope scope, String scopeId, String scopeName, Contradiction.Side a,
                    Contradiction.Side b, String quoteA, String quoteB, String questionA, String questionB,
                    double similarity) {
            this.scope = scope;
            this.scopeId = scopeId;
            this.scopeName = scopeName == null ? "" : scopeName;
            this.a = a;
            this.b = b;
            this.quoteA = quoteA == null ? "" : quoteA;
            this.quoteB = quoteB == null ? "" : quoteB;
            this.questionA = questionA == null ? "" : questionA;
            this.questionB = questionB == null ? "" : questionB;
            this.similarity = similarity;
        }

        public String id() {
            return Contradiction.idOf(a, b);
        }

        public Contradiction.Scope getScope() {
            return scope;
        }

        public String getScopeId() {
            return scopeId;
        }

        public String getScopeName() {
            return scopeName;
        }

        public Contradiction.Side getA() {
            return a;
        }

        public Contradiction.Side getB() {
            return b;
        }

        public String getQuoteA() {
            return quoteA;
        }

        public String getQuoteB() {
            return quoteB;
        }

        public String getQuestionA() {
            return questionA;
        }

        public String getQuestionB() {
            return questionB;
        }

        public double getSimilarity() {
            return similarity;
        }
    }

    /** One pair through the model: the record for the memory and the raw answers. */
    public static final class Judgement {
        private final Pair pair;
        private final Contradiction contradiction;
        private final List<String> answers;
        private final long millis;

        Judgement(Pair pair, Contradiction contradiction, List<String> answers, long millis) {
            this.pair = pair;
            this.contradiction = contradiction;
            this.answers = Collections.unmodifiableList(new ArrayList<String>(answers));
            this.millis = millis;
        }

        public Pair getPair() {
            return pair;
        }

        public Contradiction getContradiction() {
            return contradiction;
        }

        public List<String> getAnswers() {
            return answers;
        }

        public long getMillis() {
            return millis;
        }
    }

    /** The candidates of the memory as it stands, with what was left out and why. */
    public static final class Candidates {
        private final List<Pair> toJudge;
        private final int pairs;
        private final int sameGroup;
        private final int belowSimilarity;
        private final int overCap;
        private final int judgedBefore;
        private final int twice;
        private final String problem;

        Candidates(List<Pair> toJudge, int pairs, int sameGroup, int belowSimilarity, int overCap, int judgedBefore,
                   int twice, String problem) {
            this.toJudge = Collections.unmodifiableList(new ArrayList<Pair>(toJudge));
            this.pairs = pairs;
            this.sameGroup = sameGroup;
            this.belowSimilarity = belowSimilarity;
            this.overCap = overCap;
            this.judgedBefore = judgedBefore;
            this.twice = twice;
            this.problem = problem == null ? "" : problem;
        }

        /** The pairs that go to the model, questions first, each question or topic most similar first. */
        public List<Pair> getToJudge() {
            return toJudge;
        }

        /** Every pair of two texts within a question or a topic, before anything was left out. */
        public int getPairs() {
            return pairs;
        }

        public int getSameGroup() {
            return sameGroup;
        }

        public int getBelowSimilarity() {
            return belowSimilarity;
        }

        public int getOverCap() {
            return overCap;
        }

        public int getJudgedBefore() {
            return judgedBefore;
        }

        /** Pairs that met again under another question or topic. */
        public int getTwice() {
            return twice;
        }

        /** Why no pair could be put forward (the embedding failed), {@code ""} when they could. */
        public String getProblem() {
            return problem;
        }
    }

    /** What a run did. */
    public static final class Result {
        private final Candidates candidates;
        private final List<Judgement> judgements;
        private final long millis;

        Result(Candidates candidates, List<Judgement> judgements, long millis) {
            this.candidates = candidates;
            this.judgements = Collections.unmodifiableList(new ArrayList<Judgement>(judgements));
            this.millis = millis;
        }

        public Candidates getCandidates() {
            return candidates;
        }

        public List<Judgement> getJudgements() {
            return judgements;
        }

        /** How many judgements gave this verdict ({@code null} counts the failed ones). */
        public int count(Contradiction.Verdict verdict) {
            int n = 0;
            for (Judgement judgement : judgements) {
                Contradiction.Verdict given = judgement.getContradiction().getVerdict();
                n += verdict == null ? (given == null ? 1 : 0) : (verdict == given ? 1 : 0);
            }
            return n;
        }

        public long getMillis() {
            return millis;
        }
    }

    private final MainModelChat chat;
    private final EmbeddingPort embeddings;
    private final boolean german;
    private final Options options;
    private final Passages passages;
    private final ContradictionLog log;
    private final Consumer<String> progress;
    private final Clock clock;

    /**
     * @param embeddings the embedding model that sorts the pairs (bge-m3); {@code null} leaves them unsorted
     * @param passages   reads a passage for the text around a quote; {@code null} shows the quotes only
     * @param log        {@code contradictions/log.jsonl}, opened; {@code null} writes none
     */
    public ContradictionCheck(MainModelChat chat, EmbeddingPort embeddings, String language, Options options,
                              Passages passages, ContradictionLog log, Consumer<String> progress) {
        this(chat, embeddings, language, options, passages, log, progress, Clock.systemUTC());
    }

    ContradictionCheck(MainModelChat chat, EmbeddingPort embeddings, String language, Options options,
                       Passages passages, ContradictionLog log, Consumer<String> progress, Clock clock) {
        if (chat == null) {
            throw new IllegalArgumentException("the contradiction check needs a chat model");
        }
        this.chat = chat;
        this.embeddings = embeddings;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.passages = passages;
        this.log = log;
        this.progress = progress;
        this.clock = clock;
    }

    /**
     * Judges the candidates of the memory as it stands and writes every verdict to the memory and the log, in the
     * order of the candidates. A failed embedding judges nothing (the problem is in the candidates).
     */
    public Result check(ResearchMemory memory, String mission) throws IOException {
        long start = clock.millis();
        Candidates candidates = candidates(memory);
        List<Judgement> judgements = judgeAll(candidates.getToJudge(), mission);
        for (Judgement judgement : judgements) {
            memory.record(judgement.getContradiction());
            if (log != null) {
                log.append(judgement, clock.millis());
            }
        }
        say("contradiction check: " + candidates.getPairs() + " pairs, " + judgements.size() + " judged"
                + (candidates.getProblem().isEmpty() ? "" : ", " + candidates.getProblem()));
        return new Result(candidates, judgements, clock.millis() - start);
    }

    // ------------------------------------------------------------------------------------------------- candidates

    /** A piece of direct evidence on its way to a pair. */
    private static final class Item {
        final EvidenceVerdict verdict;
        final String questionText;

        Item(EvidenceVerdict verdict, String questionText) {
            this.verdict = verdict;
            this.questionText = questionText;
        }
    }

    /** The pairs of the memory as it stands, what goes to the model and what was left out. */
    public Candidates candidates(ResearchMemory memory) {
        Map<String, List<Item>> byQuestion = new LinkedHashMap<String, List<Item>>();
        Map<String, List<String>> questionsOfTopic = new LinkedHashMap<String, List<String>>();
        Map<String, String> topicNames = new HashMap<String, String>();
        Map<String, String> questionTexts = new HashMap<String, String>();
        for (ResearchQuestion question : memory.questions()) {
            List<Item> items = new ArrayList<Item>();
            for (EvidenceVerdict verdict : memory.evidenceOf(question.getId()).getDirect()) {
                items.add(new Item(verdict, question.getText()));
            }
            byQuestion.put(question.getId(), items);
            questionTexts.put(question.getId(), question.getText());
            ResearchTopic topic = question.getTopicId().isEmpty() ? null : memory.currentTopic(question.getTopicId());
            if (topic != null) {
                questionsOfTopic.computeIfAbsent(topic.getId(), id -> new ArrayList<String>()).add(question.getId());
                topicNames.put(topic.getId(), topic.getName());
            }
        }
        Map<String, float[]> vectors;
        try {
            vectors = vectors(byQuestion);
        } catch (RuntimeException failed) {
            String problem = "embedding failed: " + (failed.getMessage() == null ? failed.getClass().getSimpleName()
                    : failed.getMessage());
            return new Candidates(Collections.<Pair>emptyList(), 0, 0, 0, 0, 0, 0, problem);
        }
        int[] counts = new int[6];
        Set<String> seen = new HashSet<String>();
        List<Pair> toJudge = new ArrayList<Pair>();
        for (Map.Entry<String, List<Item>> entry : byQuestion.entrySet()) {
            List<Item> items = entry.getValue();
            Map<String, String> groups = memory.sourceGroups(Collections.singletonList(entry.getKey()));
            List<Pair> pairs = new ArrayList<Pair>();
            for (int i = 0; i < items.size(); i++) {
                for (int j = i + 1; j < items.size(); j++) {
                    Pair pair = pair(Contradiction.Scope.FRAGE, entry.getKey(), questionTexts.get(entry.getKey()),
                            items.get(i), items.get(j), groups, vectors, counts);
                    if (pair != null) {
                        pairs.add(pair);
                    }
                }
            }
            take(pairs, memory, seen, toJudge, counts);
        }
        for (Map.Entry<String, List<String>> entry : questionsOfTopic.entrySet()) {
            List<String> ids = entry.getValue();
            if (ids.size() < 2) {
                continue;
            }
            Map<String, String> groups = memory.sourceGroups(ids);
            List<Pair> pairs = new ArrayList<Pair>();
            for (int q = 0; q < ids.size(); q++) {
                for (int r = q + 1; r < ids.size(); r++) {
                    for (Item first : byQuestion.get(ids.get(q))) {
                        for (Item second : byQuestion.get(ids.get(r))) {
                            if (first.verdict.getTextKey().equals(second.verdict.getTextKey())) {
                                continue;
                            }
                            Pair pair = pair(Contradiction.Scope.THEMA, entry.getKey(), topicNames.get(entry.getKey()),
                                    first, second, groups, vectors, counts);
                            if (pair != null) {
                                pairs.add(pair);
                            }
                        }
                    }
                }
            }
            take(pairs, memory, seen, toJudge, counts);
        }
        return new Candidates(toJudge, counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], "");
    }

    /** A pair of two texts, or {@code null} when both come from one source group (counted). */
    private Pair pair(Contradiction.Scope scope, String scopeId, String scopeName, Item first, Item second,
                      Map<String, String> groups, Map<String, float[]> vectors, int[] counts) {
        counts[0]++;
        String groupA = groups.get(first.verdict.getSourceId());
        String groupB = groups.get(second.verdict.getSourceId());
        if (groupA != null && groupA.equals(groupB)) {
            counts[1]++;
            return null;
        }
        double similarity = cosine(vectors.get(first.verdict.getTextKey()), vectors.get(second.verdict.getTextKey()));
        return new Pair(scope, scopeId, scopeName, Contradiction.Side.of(first.verdict),
                Contradiction.Side.of(second.verdict), first.verdict.getQuote(), second.verdict.getQuote(),
                first.questionText, second.questionText, similarity);
    }

    /**
     * The most similar pairs of one question or topic up to the cap. A pair judged under this version stays out, and
     * so does one the text shows or the user decided, under any version: a new verdict would make it open again.
     */
    private void take(List<Pair> pairs, ResearchMemory memory, Set<String> seen, List<Pair> toJudge, int[] counts) {
        List<Pair> sorted = new ArrayList<Pair>(pairs);
        Collections.sort(sorted, (x, y) -> Double.compare(y.getSimilarity(), x.getSimilarity()));
        int taken = 0;
        for (Pair pair : sorted) {
            if (pair.getSimilarity() < options.minSimilarity) {
                counts[2]++;
                continue;
            }
            if (taken >= options.pairsPerScope) {
                counts[3]++;
                continue;
            }
            taken++;
            if (!seen.add(pair.id())) {
                counts[5]++;
                continue;
            }
            Contradiction known = memory.contradiction(pair.id());
            if (known != null && (known.getState() != Contradiction.State.OFFEN
                    || known.getStatus() == Contradiction.Status.OK && known.getVersion() == PROMPT_VERSION)) {
                counts[4]++;
                continue;
            }
            toJudge.add(pair);
        }
    }

    /** One vector per text key of the direct evidence, from its quote; empty without an embedding model. */
    private Map<String, float[]> vectors(Map<String, List<Item>> byQuestion) {
        Map<String, float[]> vectors = new HashMap<String, float[]>();
        if (embeddings == null) {
            return vectors;
        }
        Map<String, String> quotes = new LinkedHashMap<String, String>();
        for (List<Item> items : byQuestion.values()) {
            for (Item item : items) {
                quotes.putIfAbsent(item.verdict.getTextKey(), cut(item.verdict.getQuote(), options.quoteChars));
            }
        }
        List<String> keys = new ArrayList<String>(quotes.keySet());
        for (int start = 0; start < keys.size(); start += EMBED_BATCH) {
            List<String> batch = keys.subList(start, Math.min(keys.size(), start + EMBED_BATCH));
            List<String> texts = new ArrayList<String>();
            for (String key : batch) {
                texts.add(quotes.get(key));
            }
            List<EmbeddingPort.EmbeddingVector> result = embeddings.embed(EmbeddingPort.Role.DOCUMENT, texts);
            if (result == null || result.size() != batch.size()) {
                throw new IllegalStateException("the embedding model returned " + (result == null ? "nothing"
                        : result.size() + " vectors for " + batch.size() + " texts"));
            }
            for (int i = 0; i < batch.size(); i++) {
                vectors.put(batch.get(i), result.get(i).getValues());
            }
        }
        return vectors;
    }

    static double cosine(float[] x, float[] y) {
        if (x == null || y == null || x.length != y.length || x.length == 0) {
            return 0;
        }
        double dot = 0;
        double nx = 0;
        double ny = 0;
        for (int i = 0; i < x.length; i++) {
            dot += x[i] * (double) y[i];
            nx += x[i] * (double) x[i];
            ny += y[i] * (double) y[i];
        }
        return nx == 0 || ny == 0 ? 0 : dot / Math.sqrt(nx * ny);
    }

    // ------------------------------------------------------------------------------------------------- calls

    /** The pairs through the model, {@link Options#getParallel} at a time, in their order. */
    List<Judgement> judgeAll(List<Pair> pairs, String mission) {
        List<Judgement> judgements = new ArrayList<Judgement>();
        if (pairs.isEmpty()) {
            return judgements;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.parallel, pairs.size()), runnable -> {
            Thread thread = new Thread(runnable, "contradiction-check");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Judgement>> futures = new ArrayList<Future<Judgement>>();
            for (Pair pair : pairs) {
                futures.add(pool.submit(() -> judge(pair, mission)));
            }
            for (int i = 0; i < futures.size(); i++) {
                Pair pair = pairs.get(i);
                try {
                    judgements.add(futures.get(i).get());
                } catch (Exception failed) {
                    judgements.add(new Judgement(pair, Contradiction.failed(pair.getScope(), pair.getScopeId(),
                            pair.getA(), pair.getB(), pair.getSimilarity(), "judging failed: " + failed, "",
                            PROMPT_VERSION, clock.millis()), Collections.<String>emptyList(), 0));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return judgements;
    }

    /** One pair through the model, with one repair; a pair without a verdict is FEHLER. */
    public Judgement judge(Pair pair, String mission) {
        long began = clock.millis();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(pair, mission)));
        List<String> answers = new ArrayList<String>();
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, options.answerTokens,
                        SCHEMA);
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (result == null || !result.isOk()) {
                answers.add("");
                problem = result == null ? "model call returned nothing"
                        : "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue;
            }
            String text = result.getText() == null ? "" : result.getText();
            answers.add(text);
            Answer answer = parse(text);
            if (answer.problem.isEmpty()) {
                Contradiction judged = Contradiction.judged(pair.getScope(), pair.getScopeId(), pair.getA(),
                        pair.getB(), pair.getSimilarity(), answer.verdict, answer.difference, answer.reason,
                        chat.modelName(), PROMPT_VERSION, clock.millis());
                return new Judgement(pair, judged, answers, clock.millis() - began);
            }
            problem = "invalid answer: " + answer.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + answer.problem + (german ? "\nAntworte nur mit dem JSON-Objekt." : "\nAnswer with the JSON "
                    + "object only.")));
        }
        Contradiction failed = Contradiction.failed(pair.getScope(), pair.getScopeId(), pair.getA(), pair.getB(),
                pair.getSimilarity(), problem, chat.modelName(), PROMPT_VERSION, clock.millis());
        return new Judgement(pair, failed, answers, clock.millis() - began);
    }

    /** The model's answer as read, or why it holds none. */
    static final class Answer {
        String reason = "";
        Contradiction.Verdict verdict;
        Contradiction.Difference difference = Contradiction.Difference.KEINER;
        String problem = "";
    }

    /** Reads an answer leniently: case and blanks of the words do not count, a missing difference is KEINER. */
    static Answer parse(String text) {
        Answer answer = new Answer();
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text == null ? "" : text.trim());
            if (!element.isJsonObject()) {
                answer.problem = "the answer is no JSON object";
                return answer;
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            answer.problem = "the answer is no JSON";
            return answer;
        }
        String verdict = word(string(json, "urteil"));
        for (Contradiction.Verdict value : Contradiction.Verdict.values()) {
            if (value.name().equals(verdict)) {
                answer.verdict = value;
            }
        }
        if (answer.verdict == null) {
            answer.problem = "\"urteil\" must be WIDERSPRUCH, VEREINBAR or UNKLAR";
            return answer;
        }
        answer.reason = cut(string(json, "grund"), REASON_CHARS);
        if (answer.reason.length() < MIN_REASON_CHARS) {
            answer.problem = "\"grund\" must give the reason in a sentence";
            return answer;
        }
        String difference = word(string(json, "unterschied"));
        for (Contradiction.Difference value : Contradiction.Difference.values()) {
            if (value.name().equals(difference)) {
                answer.difference = value;
            }
        }
        return answer;
    }

    // ------------------------------------------------------------------------------------------------- prompt

    String prompt(Pair pair, String mission) {
        StringBuilder text = new StringBuilder();
        String m = clean(mission);
        text.append(german ? "Buchauftrag:\n" : "Book mission:\n")
                .append(m.isEmpty() ? (german ? "(keiner angegeben)" : "(none given)") : cut(m, MISSION_CHARS))
                .append("\n\n");
        if (pair.getScope() == Contradiction.Scope.FRAGE) {
            text.append(german ? "Frage: " : "Question: ").append(cut(pair.getScopeName(), TEXT_CHARS))
                    .append("\n\n");
        } else {
            text.append(german ? "Thema: " : "Topic: ").append(cut(pair.getScopeName(), TEXT_CHARS)).append('\n')
                    .append(german ? "Aussage A antwortet auf die Frage: " : "Statement A answers the question: ")
                    .append(cut(pair.getQuestionA(), TEXT_CHARS)).append('\n')
                    .append(german ? "Aussage B antwortet auf die Frage: " : "Statement B answers the question: ")
                    .append(cut(pair.getQuestionB(), TEXT_CHARS)).append("\n\n");
        }
        side(text, "A", pair.getA(), pair.getQuoteA());
        side(text, "B", pair.getB(), pair.getQuoteB());
        if (german) {
            text.append("Urteile:\n")
                    .append("- \"WIDERSPRUCH\": Beide können nicht zugleich wahr sein, für denselben Fall, dasselbe "
                            + "Recht, dieselbe Zeit und dieselbe Gruppe.\n")
                    .append("- \"VEREINBAR\": Beide können zugleich wahr sein. \"unterschied\" nennt, was sie trennt: "
                            + "\"RECHTSRAUM\" (anderes Land, anderes Recht oder andere Behörde), \"JAHR\" (andere "
                            + "Zeit oder anderer Stand der Regeln), \"GRUPPE\" (andere Personen, Geräte, Betriebe oder "
                            + "Größen), \"ZUSAMMENHANG\" (andere Lage oder anderer Zweck), \"SACHE\" (sie sprechen von "
                            + "verschiedenen Dingen) oder \"KEINER\" (sie sagen dasselbe oder ergänzen sich).\n")
                    .append("- \"UNKLAR\": Die beiden Texte sagen nicht genug, um das zu entscheiden.\n")
                    .append("Bei WIDERSPRUCH und UNKLAR ist \"unterschied\" \"KEINER\". Verschiedene Zahlen "
                            + "widersprechen sich nur, wenn sie dasselbe messen. Entscheide nicht, welche Aussage "
                            + "stimmt.\n\n")
                    .append("Antworte nur mit JSON: {\"grund\": ein oder zwei Sätze, \"urteil\": ..., "
                            + "\"unterschied\": ...}");
        } else {
            text.append("Verdicts:\n")
                    .append("- \"WIDERSPRUCH\": Both cannot be true at once, for the same case, the same law, the "
                            + "same time and the same group.\n")
                    .append("- \"VEREINBAR\": Both can be true at once. \"unterschied\" names what separates them: "
                            + "\"RECHTSRAUM\" (another country, law or authority), \"JAHR\" (another time or state of "
                            + "the rules), \"GRUPPE\" (other people, devices, firms or sizes), \"ZUSAMMENHANG\" "
                            + "(another situation or purpose), \"SACHE\" (they speak of different things) or "
                            + "\"KEINER\" (they say the same or add to each other).\n")
                    .append("- \"UNKLAR\": The two texts do not say enough to decide.\n")
                    .append("With WIDERSPRUCH and UNKLAR, \"unterschied\" is \"KEINER\". Different numbers contradict "
                            + "each other only when they measure the same thing. Do not decide which statement is "
                            + "right.\n\n")
                    .append("Answer only with JSON: {\"grund\": one or two sentences, \"urteil\": ..., "
                            + "\"unterschied\": ...}");
        }
        return text.toString();
    }

    private void side(StringBuilder text, String name, Contradiction.Side side, String quote) {
        String domain = SourceDomain.of(side.getSourceUrl());
        text.append(german ? "Aussage " : "Statement ").append(name).append(german ? ", aus " : ", from ")
                .append(domain.isEmpty() ? (german ? "einer Quelle ohne Adresse" : "a source without address")
                        : domain).append(":\n")
                .append(german ? "„" : "\"").append(cut(quote, options.quoteChars)).append(german ? "“\n" : "\"\n");
        String around = around(side.getPassageId(), quote);
        if (!around.isEmpty() && !around.equalsIgnoreCase(clean(quote))) {
            text.append(german ? "Umfeld: „" : "Context: \"").append(around).append(german ? "“\n" : "\"\n");
        }
        text.append('\n');
    }

    /** Up to {@link Options#getContextChars} of the passage around the quote; {@code ""} without a passage. */
    String around(String passageId, String quote) {
        if (passages == null || options.contextChars == 0) {
            return "";
        }
        String passage;
        try {
            passage = clean(passages.text(passageId));
        } catch (RuntimeException unreadable) {
            return "";
        }
        if (passage.isEmpty()) {
            return "";
        }
        if (passage.length() <= options.contextChars) {
            return passage;
        }
        String head = clean(quote);
        head = head.substring(0, Math.min(30, head.length())).toLowerCase(Locale.ROOT);
        int at = head.isEmpty() ? -1 : passage.toLowerCase(Locale.ROOT).indexOf(head);
        int from = at < 0 ? 0 : Math.max(0, at - options.contextChars / 3);
        int to = Math.min(passage.length(), from + options.contextChars);
        from = Math.max(0, to - options.contextChars);
        return (from > 0 ? "… " : "") + passage.substring(from, to).trim() + (to < passage.length() ? " …" : "");
    }

    private static String schema() {
        JsonObject properties = new JsonObject();
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        reason.addProperty("minLength", MIN_REASON_CHARS);
        properties.add("grund", reason);
        properties.add("urteil", choice(Contradiction.Verdict.values()));
        properties.add("unterschied", choice(Contradiction.Difference.values()));
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add("urteil");
        required.add("unterschied");
        root.add("required", required);
        return root.toString();
    }

    private static JsonObject choice(Enum<?>[] values) {
        JsonObject choice = new JsonObject();
        choice.addProperty("type", "string");
        JsonArray names = new JsonArray();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        choice.add("enum", names);
        return choice;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static String word(String value) {
        return clean(value).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    static String cut(String text, int chars) {
        String flat = clean(text);
        if (flat.length() <= chars) {
            return flat;
        }
        String head = flat.substring(0, chars);
        int blank = head.lastIndexOf(' ');
        return (blank >= chars * 3 / 5 ? head.substring(0, blank) : head).trim() + " …";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private void say(String message) {
        if (progress != null) {
            progress.accept(message);
        }
    }
}
