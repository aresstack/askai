package com.aresstack.askai.research.sentencecheck;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Attribution;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Jurisdiction;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Kind;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Verdict;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Component 11 of the build plan of 21.11.2026, the sentence check (Satzprüfer): whether the passage a sentence of
 * the book cites covers it. One call per sentence and ONE passage it cites ({@code gemma4:e2b} on the slot
 * {@code phase.draft.check}, thinking off, temperature 0), the pairs in parallel, one repair. The model sees the
 * sentence without its citation markers and the passage, nothing of the book, the mission or the other sources.
 *
 * <p>The answer, in this order: the kind of the passage ({@link Kind}: only TEXT carries statements; a link list, a
 * table of contents, a literature list or a model clause does not), the quote of the passage that carries the
 * sentence, one sentence of reason, then GEDECKT, TEILWEISE or NICHT_GEDECKT. Where the source has an interest of its
 * own (component 16: manufacturer or shop, association), it also says whether the sentence attributes the statement
 * ("nach Angaben des Herstellers") or states it as a fact; where the source belongs to another legal space than the
 * book, whether the sentence says where a rule it names holds. The quote check of {@link SentenceVerdict} keeps a
 * GEDECKT only when the quote stands in the passage and holds the numbers of the sentence.</p>
 *
 * <p>A sentence that is not covered, states such a statement as a fact or a rule of another legal space without
 * saying so ({@link SentenceVerdict#needsRewrite()}) goes back to the writer once (component 10); if it stays so, it is
 * marked, never deleted. A sentence that cites several passages is checked against each alone
 * ({@link SentenceVerdict#ofSentence}). A failed call is FEHLER, never NICHT_GEDECKT; the pair is checked again next
 * time. A pair with a verdict that holds under this {@link #VERSION} is not checked again unless now a question is to
 * be asked that was not asked then. Every verdict goes to the store the moment it is there; when one cannot be
 * written, no further pair is asked and the run fails.</p>
 */
public final class SentenceCheck {

    /** The prompt's and rules' version; a verdict is reused only under the same one. */
    public static final int VERSION = 1;

    /** Prompt, sentence, passage and answer fit with room; thinking is off. */
    static final int CONTEXT_TOKENS = 8192;
    static final double TEMPERATURE = 0.0;
    /** Calls per pair: the first and one repair attempt. */
    static final int ATTEMPTS = 2;
    private static final int REASON_CHARS = 300;
    private static final int MIN_REASON_CHARS = 5;
    private static final int QUOTE_CHARS = 1000;

    /** The kinds of source (component 16) whose statements the book attributes instead of stating them as facts. */
    static final Set<String> OWN_INTEREST = new HashSet<String>(Arrays.asList("HERSTELLER_HANDEL", "VERBAND"));
    /** The legal spaces whose rules the book marks when they are not its own; EU law and texts without one are not. */
    static final List<String> LEGAL_SPACES = Arrays.asList("DE", "AT", "CH", "ANDERE");

    static final String SYSTEM_DE = "Du prüfst Sätze eines Sachbuchs an der Passage, die der Satz als Beleg zitiert. "
            + "Du urteilst nur nach dem, was in der Passage steht. Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You check sentences of a non-fiction book against the passage the sentence cites "
            + "as evidence. You judge only by what the passage says. Answer only with JSON.";

    public static final ResearchRole ROLE = ResearchRole.builder("sentence-check",
                    ResearchModelSlots.PHASE_DRAFT_CHECK)
            .purpose("component 11: says whether the passage a sentence of the book cites covers it (GEDECKT, "
                    + "TEILWEISE, NICHT_GEDECKT) with the quote that carries it and the kind of the passage; for a "
                    + "source with an interest of its own or of another legal space also whether the sentence "
                    + "attributes the statement or marks the rule")
            .promptFromCaller()
            .output(RoleOutput.schemaPerRequest("belegart, zitat, grund, urteil (zuschreibung, rechtsraum)"))
            .thinking(ThinkingMode.OFF)
            .contextTokens(CONTEXT_TOKENS)
            .build();

    /** One sentence of the book and one passage it cites. */
    public static final class Item {
        private final String sentenceId;
        private final String sentence;
        private final String passageId;
        private final String sourceId;
        private final String passageText;
        private final String sourceKind;
        private final String legalSpace;

        /** A pair without what is known of the source: only the coverage is checked. */
        public Item(String sentenceId, String sentence, String passageId, String sourceId, String passageText) {
            this(sentenceId, sentence, passageId, sourceId, passageText, "", "");
        }

        /**
         * @param sourceKind the kind of the source (component 16, e.g. {@code HERSTELLER_HANDEL}); {@code ""} when
         *                   unknown
         * @param legalSpace the legal space of the source (component 16: DE, AT, CH, EU, ANDERE, OHNE); {@code ""}
         *                   when unknown
         */
        public Item(String sentenceId, String sentence, String passageId, String sourceId, String passageText,
                    String sourceKind, String legalSpace) {
            this.sentenceId = clean(sentenceId);
            this.sentence = sentence == null ? "" : sentence;
            this.passageId = clean(passageId);
            this.sourceId = clean(sourceId);
            this.passageText = passageText == null ? "" : passageText;
            this.sourceKind = clean(sourceKind).toUpperCase(Locale.ROOT);
            this.legalSpace = clean(legalSpace).toUpperCase(Locale.ROOT);
            if (this.sentenceId.isEmpty() || this.passageId.isEmpty()) {
                throw new IllegalArgumentException("a pair needs a sentence id and a passage id");
            }
        }

        public String getSentenceId() {
            return sentenceId;
        }

        public String getSentence() {
            return sentence;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getPassageText() {
            return passageText;
        }

        public String getSourceKind() {
            return sourceKind;
        }

        public String getLegalSpace() {
            return legalSpace;
        }

        String sentenceKey() {
            return SentenceVerdict.sentenceKey(sentenceId, sentence);
        }

        String textKey() {
            return SentenceVerdict.textKey(passageId, passageText);
        }
    }

    /** Tuning; immutable. */
    public static final class Options {
        private final int parallel;
        private final int answerTokens;
        private final int passageChars;
        private final String legalSpace;

        private Options(int parallel, int answerTokens, int passageChars, String legalSpace) {
            String space = legalSpace == null ? "" : legalSpace.trim().toUpperCase(Locale.ROOT);
            if (parallel < 1 || parallel > 16 || answerTokens < 128 || answerTokens > 8192 || passageChars < 200
                    || passageChars > 20000 || !Arrays.asList("DE", "AT", "CH").contains(space)) {
                throw new IllegalArgumentException("invalid options of the sentence check");
            }
            this.parallel = parallel;
            this.answerTokens = answerTokens;
            this.passageChars = passageChars;
            this.legalSpace = space;
        }

        /** 4 calls at a time, 768 answer tokens, the first 4,000 characters of a passage, a book for Germany. */
        public static Options defaults() {
            return new Options(4, 768, 4000, "DE");
        }

        public Options withParallel(int parallel) {
            return new Options(parallel, answerTokens, passageChars, legalSpace);
        }

        public Options withAnswerTokens(int answerTokens) {
            return new Options(parallel, answerTokens, passageChars, legalSpace);
        }

        public Options withPassageChars(int passageChars) {
            return new Options(parallel, answerTokens, passageChars, legalSpace);
        }

        /** The legal space the book is written for: DE, AT or CH. */
        public Options withLegalSpace(String legalSpace) {
            return new Options(parallel, answerTokens, passageChars, legalSpace);
        }

        public int getParallel() {
            return parallel;
        }

        public int getAnswerTokens() {
            return answerTokens;
        }

        public int getPassageChars() {
            return passageChars;
        }

        public String getLegalSpace() {
            return legalSpace;
        }
    }

    /** A pair through the model: its verdict and the raw answers. */
    public static final class Judgement {
        private final Item item;
        private final SentenceVerdict verdict;
        private final List<String> answers;

        Judgement(Item item, SentenceVerdict verdict, List<String> answers) {
            this.item = item;
            this.verdict = verdict;
            this.answers = Collections.unmodifiableList(new ArrayList<String>(answers));
        }

        public Item getItem() {
            return item;
        }

        public SentenceVerdict getVerdict() {
            return verdict;
        }

        public List<String> getAnswers() {
            return answers;
        }
    }

    /** What one run did; counts by pair of sentence and passage text. */
    public static final class Result {
        private final int items;
        private final int pairs;
        private final int reused;
        private final int asked;
        private final List<SentenceVerdict> verdicts;

        Result(int items, int pairs, int reused, int asked, List<SentenceVerdict> verdicts) {
            this.items = items;
            this.pairs = pairs;
            this.reused = reused;
            this.asked = asked;
            this.verdicts = Collections.unmodifiableList(new ArrayList<SentenceVerdict>(verdicts));
        }

        /** The pairs given. */
        public int getItems() {
            return items;
        }

        /** Their distinct sentence and passage texts. */
        public int getPairs() {
            return pairs;
        }

        /** Pairs with a verdict that holds from before. */
        public int getReused() {
            return reused;
        }

        /** Pairs through the model. */
        public int getAsked() {
            return asked;
        }

        /** The new verdicts, in the order of the pairs. */
        public List<SentenceVerdict> getVerdicts() {
            return verdicts;
        }

        /** New verdicts that hold this verdict after the quote check ({@code null}: FEHLER). */
        public int count(Verdict verdict) {
            int n = 0;
            for (SentenceVerdict made : verdicts) {
                n += made.getVerdict() == verdict ? 1 : 0;
            }
            return n;
        }

        /** New verdicts the quote check took down from GEDECKT to TEILWEISE. */
        public int downgraded() {
            int n = 0;
            for (SentenceVerdict made : verdicts) {
                n += made.isDowngraded() ? 1 : 0;
            }
            return n;
        }

        /** New verdicts that send the sentence back to the writer. */
        public int toRewrite() {
            int n = 0;
            for (SentenceVerdict made : verdicts) {
                n += made.needsRewrite() ? 1 : 0;
            }
            return n;
        }
    }

    private final MainModelChat chat;
    private final boolean german;
    private final Options options;
    private final Clock clock;

    /**
     * @param chat     the model on {@link #ROLE}'s slot; {@code null} makes every pair FEHLER ("no model")
     * @param language the book's language: {@code de} gives German prompts, anything else English
     */
    public SentenceCheck(MainModelChat chat, String language, Options options, Clock clock) {
        this.chat = chat;
        this.german = language == null || language.trim().isEmpty()
                || language.trim().toLowerCase(Locale.ROOT).startsWith("de");
        this.options = options == null ? Options.defaults() : options;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Checks the pairs that have no verdict that holds under this version, each sentence and passage text once, and
     * writes every verdict to {@code store} the moment it is there.
     *
     * @throws IOException when a verdict cannot be written; no further pair is asked then
     */
    public Result check(List<Item> items, SentenceVerdicts store) throws IOException {
        Set<String> seen = new HashSet<String>();
        List<Item> todo = new ArrayList<Item>();
        int reused = 0;
        for (Item item : items) {
            if (!seen.add(SentenceVerdicts.key(item.sentenceKey(), item.textKey()))) {
                continue;
            }
            if (holds(store.verdict(item.sentenceKey(), item.textKey()), item)) {
                reused++;
            } else {
                todo.add(item);
            }
        }
        Map<String, SentenceVerdict> made = new LinkedHashMap<String, SentenceVerdict>();
        for (Judgement judgement : judgeAll(todo, store)) {
            SentenceVerdict verdict = judgement.getVerdict();
            made.put(SentenceVerdicts.key(verdict.getSentenceKey(), verdict.getTextKey()), verdict);
        }
        List<SentenceVerdict> ordered = new ArrayList<SentenceVerdict>();
        for (Item item : items) {
            SentenceVerdict verdict = made.remove(SentenceVerdicts.key(item.sentenceKey(), item.textKey()));
            if (verdict != null) {
                ordered.add(verdict);
            }
        }
        return new Result(items.size(), seen.size(), reused, todo.size(), ordered);
    }

    /** Whether a verdict from before holds for the pair and need not be given again. */
    boolean holds(SentenceVerdict verdict, Item item) {
        return verdict != null && verdict.getStatus() == SentenceVerdict.Status.OK && verdict.getVersion() == VERSION
                && (!asksAttribution(item) || verdict.getAttribution() != Attribution.NICHT_GEFRAGT)
                && (!asksJurisdiction(item) || verdict.getJurisdiction() != Jurisdiction.NICHT_GEFRAGT);
    }

    /** Whether the source has an interest of its own, so the sentence must attribute its statement. */
    boolean asksAttribution(Item item) {
        return OWN_INTEREST.contains(item.getSourceKind());
    }

    /** Whether the source belongs to another legal space than the book, so a rule it names must be marked. */
    boolean asksJurisdiction(Item item) {
        return LEGAL_SPACES.contains(item.getLegalSpace()) && !item.getLegalSpace().equals(options.legalSpace);
    }

    /** The pairs in parallel, in their order; each verdict is written the moment it is there. */
    List<Judgement> judgeAll(List<Item> todo, SentenceVerdicts store) throws IOException {
        List<Judgement> judgements = new ArrayList<Judgement>();
        if (todo.isEmpty()) {
            return judgements;
        }
        AtomicReference<IOException> unwritten = new AtomicReference<IOException>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.parallel, todo.size()), runnable -> {
            Thread thread = new Thread(runnable, "sentence-check");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Judgement>> futures = new ArrayList<Future<Judgement>>();
            for (Item item : todo) {
                futures.add(pool.submit(() -> {
                    if (unwritten.get() != null) {
                        return failed(item, "not asked: an earlier verdict could not be written", 0,
                                Collections.<String>emptyList());
                    }
                    Judgement judgement = judge(item);
                    try {
                        store.record(judgement.getVerdict(), judgement.getAnswers());
                    } catch (IOException failed) {
                        unwritten.compareAndSet(null, failed);
                    }
                    return judgement;
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    judgements.add(futures.get(i).get());
                } catch (Exception failed) {
                    judgements.add(failed(todo.get(i), "checking failed: " + failed, 0,
                            Collections.<String>emptyList()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (unwritten.get() != null) {
            throw new IOException("a verdict of the sentence check could not be written: "
                    + unwritten.get().getMessage(), unwritten.get());
        }
        return judgements;
    }

    /** One pair, with one repair; a pair without a verdict is FEHLER. */
    public Judgement judge(Item item) {
        long began = clock.millis();
        boolean attribution = asksAttribution(item);
        boolean jurisdiction = asksJurisdiction(item);
        String schema = schema(attribution, jurisdiction);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(item)));
        List<String> answers = new ArrayList<String>();
        String problem = "no model";
        for (int attempt = 1; chat != null && attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, options.answerTokens,
                        schema);
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
            Answer answer = parse(text, attribution, jurisdiction);
            if (answer.problem.isEmpty()) {
                SentenceVerdict verdict = SentenceVerdict.checked(item.getSentenceId(), item.getSentence(),
                        item.getPassageId(), item.getSourceId(), item.getPassageText(), answer.verdict, answer.kind,
                        answer.quote, answer.reason, answer.attribution, answer.jurisdiction, chat.modelName(),
                        VERSION, clock.millis(), clock.millis() - began);
                return new Judgement(item, verdict, answers);
            }
            problem = "invalid answer: " + answer.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + answer.problem + (german ? "\nAntworte nur mit dem JSON-Objekt." : "\nAnswer with the JSON "
                    + "object only.")));
        }
        return failed(item, problem, clock.millis() - began, answers);
    }

    private Judgement failed(Item item, String problem, long millis, List<String> answers) {
        return new Judgement(item, SentenceVerdict.failed(item.getSentenceId(), item.getSentence(),
                item.getPassageId(), item.getSourceId(), item.getPassageText(), problem,
                chat == null ? "" : chat.modelName(), VERSION, clock.millis(), millis), answers);
    }

    // ------------------------------------------------------------------------------------------------- prompt

    /** The question for one pair: the sentence without markers, the passage (cut), the rules, the answer's form. */
    String prompt(Item item) {
        String passage = item.getPassageText().trim();
        if (passage.length() > options.passageChars) {
            passage = passage.substring(0, options.passageChars) + " …";
        }
        String sentence = SentenceVerdict.plain(item.getSentence());
        StringBuilder text = new StringBuilder();
        List<String> fields = new ArrayList<String>(Arrays.asList("belegart", "zitat", "grund", "urteil"));
        if (german) {
            text.append("Satz aus dem Buch:\n„").append(sentence).append("“\n\nZitierte Passage:\n„").append(passage)
                    .append("“\n\nPrüfe, ob die Passage den Satz deckt. Es zählt nur, was in der Passage steht; "
                            + "Allgemeinwissen und andere Quellen zählen nicht.\n")
                    .append("- belegart: Was ist die Passage? TEXT (Text mit Aussagen), MUSTERKLAUSEL (Vorlage oder "
                            + "Mustertext zum Übernehmen), LITERATURLISTE, LINKLISTE, INHALTSVERZEICHNIS oder "
                            + "SONSTIGES (anderes ohne Aussage, etwa Menü, Werbung oder Bildunterschrift).\n")
                    .append("- zitat: die Stelle der Passage, die die Aussage des Satzes trägt, wörtlich abgeschrieben "
                            + "und mit den Zahlen des Satzes; leer, wenn keine Stelle sie trägt.\n")
                    .append("- grund: ein Satz: was der Satz behauptet und ob die Passage das ganz, zum Teil oder "
                            + "nicht sagt.\n")
                    .append("- urteil: GEDECKT, wenn die Passage alles sagt, was der Satz behauptet, mit seinen Zahlen "
                            + "und Bedingungen; TEILWEISE, wenn sie nur einen Teil davon sagt oder der Satz mehr, "
                            + "Allgemeineres oder Stärkeres behauptet; NICHT_GEDECKT, wenn sie die Aussage nicht "
                            + "trägt, etwas anderes sagt oder nur das Thema mit dem Satz teilt.\n");
        } else {
            text.append("Sentence of the book:\n\"").append(sentence).append("\"\n\nCited passage:\n\"")
                    .append(passage).append("\"\n\nCheck whether the passage covers the sentence. Only what the "
                            + "passage says counts; general knowledge and other sources do not.\n")
                    .append("- belegart: What is the passage? TEXT (text with statements), MUSTERKLAUSEL (a template "
                            + "or model text to copy), LITERATURLISTE (literature list), LINKLISTE (link list), "
                            + "INHALTSVERZEICHNIS (table of contents) or SONSTIGES (anything else without a "
                            + "statement, such as a menu, advertising or a caption).\n")
                    .append("- zitat: the part of the passage that carries the statement of the sentence, copied "
                            + "verbatim and with the numbers of the sentence; empty when no part carries it.\n")
                    .append("- grund: one sentence: what the sentence claims and whether the passage says it fully, "
                            + "in part or not.\n")
                    .append("- urteil: GEDECKT when the passage says everything the sentence claims, with its numbers "
                            + "and conditions; TEILWEISE when it says only a part of it or the sentence claims more, "
                            + "something more general or something stronger; NICHT_GEDECKT when it does not carry the "
                            + "statement, says something else or only shares the subject with the sentence.\n");
        }
        if (asksAttribution(item)) {
            fields.add("zuschreibung");
            boolean shop = "HERSTELLER_HANDEL".equals(item.getSourceKind());
            text.append('\n').append(german ? (shop ? "Die Quelle der Passage ist ein Hersteller oder Händler.\n"
                    : "Die Quelle der Passage ist ein Verband.\n")
                    : (shop ? "The source of the passage is a manufacturer or shop.\n"
                    : "The source of the passage is an association.\n"))
                    .append(german ? "- zuschreibung: ZUGESCHRIEBEN, wenn der Satz die Aussage dieser Quelle "
                            + "zuschreibt (etwa „nach Angaben des Herstellers“ oder „laut dem Verband“); "
                            + "ALS_TATSACHE, wenn er sie als Tatsache hinstellt.\n"
                            : "- zuschreibung: ZUGESCHRIEBEN when the sentence attributes the statement to this "
                            + "source (such as \"according to the manufacturer\" or \"the association says\"); "
                            + "ALS_TATSACHE when it states it as a fact.\n");
        }
        if (asksJurisdiction(item)) {
            fields.add("rechtsraum");
            text.append('\n').append(german ? "Die Quelle der Passage gilt für " + space(item.getLegalSpace(), true)
                    + ", das Buch für " + space(options.legalSpace, true) + ".\n"
                    : "The source of the passage holds for " + space(item.getLegalSpace(), false)
                    + ", the book for " + space(options.legalSpace, false) + ".\n")
                    .append(german ? "- rechtsraum: KEINE_RECHTSAUSSAGE, wenn der Satz keine Rechtsregel nennt; "
                            + "GEKENNZEICHNET, wenn er eine Regel nennt und sagt, wo sie gilt; NICHT_GEKENNZEICHNET, "
                            + "wenn er eine Regel nennt, ohne zu sagen, wo sie gilt.\n"
                            : "- rechtsraum: KEINE_RECHTSAUSSAGE when the sentence names no legal rule; GEKENNZEICHNET "
                            + "when it names a rule and says where it holds; NICHT_GEKENNZEICHNET when it names a "
                            + "rule without saying where it holds.\n");
        }
        StringBuilder form = new StringBuilder("{");
        for (int i = 0; i < fields.size(); i++) {
            form.append(i == 0 ? "" : ", ").append('"').append(fields.get(i)).append("\": ...");
        }
        text.append('\n').append(german ? "Antworte nur mit JSON: " : "Answer only with JSON: ").append(form)
                .append('}');
        return text.toString();
    }

    private static String space(String code, boolean german) {
        switch (code) {
            case "DE":
                return german ? "Deutschland" : "Germany";
            case "AT":
                return german ? "Österreich" : "Austria";
            case "CH":
                return german ? "die Schweiz" : "Switzerland";
            default:
                return german ? "einen anderen Rechtsraum" : "another legal space";
        }
    }

    /** The answer schema: kind, quote, reason, verdict, and the questions asked for this source. */
    static String schema(boolean attribution, boolean jurisdiction) {
        JsonObject properties = new JsonObject();
        properties.add("belegart", enumOf(Kind.values()));
        properties.add("zitat", type("string"));
        JsonObject reason = type("string");
        reason.addProperty("minLength", MIN_REASON_CHARS);
        properties.add("grund", reason);
        properties.add("urteil", enumOf(Verdict.values()));
        JsonArray required = new JsonArray();
        required.add("belegart");
        required.add("zitat");
        required.add("grund");
        required.add("urteil");
        if (attribution) {
            properties.add("zuschreibung", enumOf(new Enum<?>[]{Attribution.ZUGESCHRIEBEN, Attribution.ALS_TATSACHE}));
            required.add("zuschreibung");
        }
        if (jurisdiction) {
            properties.add("rechtsraum", enumOf(new Enum<?>[]{Jurisdiction.GEKENNZEICHNET,
                    Jurisdiction.NICHT_GEKENNZEICHNET, Jurisdiction.KEINE_RECHTSAUSSAGE}));
            required.add("rechtsraum");
        }
        JsonObject root = type("object");
        root.add("properties", properties);
        root.add("required", required);
        return root.toString();
    }

    private static JsonObject enumOf(Enum<?>[] values) {
        JsonObject json = type("string");
        JsonArray names = new JsonArray();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        json.add("enum", names);
        return json;
    }

    private static JsonObject type(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return json;
    }

    // ------------------------------------------------------------------------------------------------- answer

    /** The model's answer as read, or why it holds none. */
    static final class Answer {
        Verdict verdict;
        Kind kind = Kind.SONSTIGES;
        String quote = "";
        String reason = "";
        Attribution attribution = Attribution.NICHT_GEFRAGT;
        Jurisdiction jurisdiction = Jurisdiction.NICHT_GEFRAGT;
        String problem = "";
    }

    /**
     * Reads an answer leniently: case, blanks and hyphens of the words do not count, a code fence around the object
     * neither; a kind of passage the model may not name is SONSTIGES. The verdict, the reason, the kind and every
     * question asked must be there.
     */
    static Answer parse(String text, boolean attribution, boolean jurisdiction) {
        Answer answer = new Answer();
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(unfenced(text));
            if (!element.isJsonObject()) {
                answer.problem = "the answer is no JSON object";
                return answer;
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            answer.problem = "the answer is no JSON";
            return answer;
        }
        answer.verdict = match(Verdict.values(), word(string(json, "urteil")));
        if (answer.verdict == null) {
            answer.problem = "\"urteil\" must be GEDECKT, TEILWEISE or NICHT_GEDECKT";
            return answer;
        }
        answer.reason = cut(string(json, "grund"), REASON_CHARS);
        if (answer.reason.length() < MIN_REASON_CHARS) {
            answer.problem = "\"grund\" must give the reason in one sentence";
            return answer;
        }
        String kind = word(string(json, "belegart"));
        if (kind.isEmpty()) {
            answer.problem = "\"belegart\" must name the kind of the passage";
            return answer;
        }
        Kind named = match(Kind.values(), kind);
        answer.kind = named == null ? Kind.SONSTIGES : named;
        answer.quote = cut(string(json, "zitat"), QUOTE_CHARS);
        if (attribution) {
            answer.attribution = match(new Attribution[]{Attribution.ZUGESCHRIEBEN, Attribution.ALS_TATSACHE},
                    word(string(json, "zuschreibung")));
            if (answer.attribution == null) {
                answer.problem = "\"zuschreibung\" must be ZUGESCHRIEBEN or ALS_TATSACHE";
                return answer;
            }
        }
        if (jurisdiction) {
            answer.jurisdiction = match(new Jurisdiction[]{Jurisdiction.GEKENNZEICHNET,
                    Jurisdiction.NICHT_GEKENNZEICHNET, Jurisdiction.KEINE_RECHTSAUSSAGE},
                    word(string(json, "rechtsraum")));
            if (answer.jurisdiction == null) {
                answer.problem = "\"rechtsraum\" must be GEKENNZEICHNET, NICHT_GEKENNZEICHNET or KEINE_RECHTSAUSSAGE";
                return answer;
            }
        }
        return answer;
    }

    private static <E extends Enum<E>> E match(E[] values, String word) {
        for (E value : values) {
            if (value.name().equals(word)) {
                return value;
            }
        }
        return null;
    }

    private static String unfenced(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            int start = value.indexOf('\n');
            int end = value.lastIndexOf("```");
            if (start > 0 && end > start) {
                value = value.substring(start + 1, end).trim();
            }
        }
        return value;
    }

    /** "nicht gedeckt", "Nicht-Gedeckt." and "NICHT_GEDECKT" read the same. */
    static String word(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_")
                .replaceAll("[^A-ZÄÖÜ_]", "");
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static String cut(String value, int chars) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= chars ? text : text.substring(0, chars);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
