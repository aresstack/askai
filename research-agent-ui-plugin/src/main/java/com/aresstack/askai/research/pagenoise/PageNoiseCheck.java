package com.aresstack.askai.research.pagenoise;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.memory.PassageText;
import com.aresstack.askai.research.memory.SourceDomain;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.writer.ProsePassageFilter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
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
import java.util.regex.Pattern;

/**
 * The page-noise check (Seitenrest-Prüfung) before the topic finder (13). The architect's measurement of 22.11.2026
 * proposed it: of 60 blind passages of the 17 Nov stock 11 were page leftovers, 20 % of the topic finder's proposals
 * rested on such passages, and the prose rules alone caught 3 of 22. Each passage text gets one mark, kept by text key
 * in {@code page-noise/marks.jsonl} ({@link NoiseMarks}); nothing is deleted. The topic finder (13), the evidence judge
 * (6), the sentence check (11) and the research memory (7) skip what is marked RAUSCHEN.
 *
 * <p>Stage 1, no model: the prose rules ({@link ProsePassageFilter}): too few words or finished sentences, a long run
 * without punctuation, site notices, promotion, capitals, links; on a page of almost no prose a lone short sentence is
 * a caption. A passage that breaks a rule is RAUSCHEN with the kind of the rule. A single site notice that is also a
 * word of prose on the matter (cookies, FAQ, privacy settings, ...) breaks no rule here: the model judges that passage
 * ({@link ProsePassageFilter#rule(String, boolean)}). A lead-in is an UEBERSCHRIFT by rule ({@link #leadIn}): a short
 * passage that ends in a colon and holds no sentence of its own, only the dots of abbreviations or numbers, or
 * questions.</p>
 *
 * <p>Stage 2, only on what stage 1 lets through: one call of a small model (slot {@code phase.research.noise},
 * thinking off) per text. The model says in a short sentence who speaks and to what end, then what the piece itself
 * says about the matter or {@code "keine"}, then names the kind of text by its biggest part: a statement on the matter
 * ({@code AUSSAGE}) or one of the kinds of leftover. The verdict follows from the kind in code: AUSSAGE is INHALT,
 * every kind of leftover is RAUSCHEN. The model gives no verdict of its own, because in version 1 it said INHALT for
 * passages its own reason called advertising or a table of contents. AUSSAGE is overruled in two plain cases: the
 * model says the piece states nothing ({@code "keine"}), or its first sentence calls the piece a leftover (a provider
 * recommends its own product, "Ein Inhaltsverzeichnis ...", {@link DescriptionCheck}). The model sees the passage and
 * the domain of its page, not the mission: whether a passage belongs in the book is the fence check's question (14). A
 * failed call is FEHLER and never RAUSCHEN; the text is checked again next time.</p>
 *
 * <p>A text with a mark that holds under this {@link #VERSION} is not checked again, except a stage-1 INHALT from a
 * run without a model, which stage 2 checks once a model is there. Every mark goes to the store the moment it is
 * there; when one cannot be written, no further text is asked and the run fails.</p>
 */
public final class PageNoiseCheck {

    /**
     * The rules' and prompts' version; a mark is reused only under the same one. 2: the model names the kind of text,
     * the verdict follows from it, there is no MISCH from the model any more. 3: wider definitions of the kinds
     * (partnerships, notes on the document itself, lists of standards, labels of a figure, teasers, key data of a
     * document) and the first sentence held against the kind. 4: the model also says what the piece itself states, or
     * "keine", and AUSSAGE without a statement is a leftover; a single site notice that is also a word of prose goes
     * to the model; the kinds name lines that only announce what follows, lists with an introductory sentence, key
     * data of a regulation and bare lists of names, and say where a provider's general explanation and a list that
     * states something stand. 5: a lead-in is a leftover by rule ({@link #leadIn}); advertising is narrower again: a
     * provider who says factually what a product or a service does or brings makes a statement, only offers, prices,
     * calls to buy and praise or recommendation of its own offer are advertising.
     */
    public static final int VERSION = 5;

    /** Prompt, passage and answer fit with room; thinking is off. */
    static final int CONTEXT_TOKENS = 8192;
    static final double TEMPERATURE = 0.0;
    /** Calls per text: the first and one repair attempt. */
    static final int ATTEMPTS = 2;
    private static final int REASON_CHARS = 300;
    private static final int MIN_REASON_CHARS = 5;

    static final String SYSTEM_DE = "Du sortierst Textstücke aus Webseiten und PDF-Dateien. Du entscheidest nur, ob "
            + "ein Stück Inhalt ist oder Seitenrest, nicht, ob es zu einem Thema passt. Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You sort pieces of text from web pages and PDF files. You only decide whether a "
            + "piece is content or a page leftover, not whether it fits a subject. Answer only with JSON.";

    /** The model's word for a statement on the matter: the one kind that is content. */
    static final String CONTENT_KIND = "AUSSAGE";

    /** What the reason of a mark says when the first sentence overruled AUSSAGE, before the kind it named. */
    static final String OVERRULED = ", die Beschreibung nennt ";

    /** What the reason of a mark says when the model named AUSSAGE but said the piece states nothing. */
    static final String NO_STATEMENT = ", aber keine Aussage zur Sache";

    /** The word the model gives as {@code aussage} when the piece says nothing about the matter. */
    static final String NO_STATEMENT_WORD = "keine";

    /** The most words a lead-in may have ({@link #leadIn}). */
    static final int LEAD_IN_MAX_WORDS = 25;

    /** How the reason of a lead-in's mark begins. */
    static final String LEAD_IN = "a lead-in: ";

    /**
     * The kinds of leftover the model may name, in the order of the prompt; {@link NoiseMark.Kind#STRUKTUR} belongs to
     * the prose rules, {@link NoiseMark.Kind#KEINE} is content.
     */
    static final List<NoiseMark.Kind> MODEL_KINDS = Collections.unmodifiableList(Arrays.asList(NoiseMark.Kind.WERBUNG,
            NoiseMark.Kind.SEITENBESCHREIBUNG, NoiseMark.Kind.VERZEICHNIS, NoiseMark.Kind.LITERATUR,
            NoiseMark.Kind.NAVIGATION, NoiseMark.Kind.BILDUNTERSCHRIFT, NoiseMark.Kind.UEBERSCHRIFT,
            NoiseMark.Kind.IMPRESSUM, NoiseMark.Kind.SEITENHINWEIS, NoiseMark.Kind.SONSTIGES));

    /** The answer: first who speaks and to what end, then what the piece states, then the kind of text. */
    static final String SCHEMA = schema();

    public static final ResearchRole ROLE = ResearchRole.builder("page-noise-check",
                    ResearchModelSlots.PHASE_RESEARCH_NOISE)
            .purpose("page-noise check before the topic finder: says what a passage the prose rules let through "
                    + "states and names its kind, a statement on the matter or a page leftover (advertising, site "
                    + "description, table of contents, literature list, navigation, caption, heading, imprint, site "
                    + "notice); the code derives content or leftover from the kind; never judges the subject")
            .promptFromCaller()
            .output(RoleOutput.schema(SCHEMA, "beschreibung, aussage, art"))
            .thinking(ThinkingMode.OFF)
            .contextTokens(CONTEXT_TOKENS)
            .build();

    /** One passage of a page as the check reads it. */
    public static final class Passage {
        private final String id;
        private final String sourceId;
        private final String domain;
        private final String text;

        /**
         * @param sourceId the page it stands on; the passages of one page, in page order, are the context of the
         *                 caption rule
         * @param domain   the domain of the page, as the model sees it ({@code ""} when there is none)
         */
        public Passage(String id, String sourceId, String domain, String text) {
            this.id = id == null ? "" : id.trim();
            this.sourceId = sourceId == null ? "" : sourceId.trim();
            this.domain = domain == null ? "" : domain.trim();
            this.text = text == null ? "" : text;
            if (this.id.isEmpty()) {
                throw new IllegalArgumentException("a passage needs an id");
            }
        }

        /** A passage with the domain taken from the address of its page. */
        public static Passage of(String id, String sourceId, String address, String text) {
            return new Passage(id, sourceId, SourceDomain.of(address), text);
        }

        public String getId() {
            return id;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getDomain() {
            return domain;
        }

        public String getText() {
            return text;
        }
    }

    /** Tuning; immutable. */
    public static final class Options {
        private final int parallel;
        private final int answerTokens;
        private final int passageChars;

        private Options(int parallel, int answerTokens, int passageChars) {
            if (parallel < 1 || parallel > 16 || answerTokens < 64 || answerTokens > 8192 || passageChars < 200
                    || passageChars > 20000) {
                throw new IllegalArgumentException("invalid options of the page-noise check");
            }
            this.parallel = parallel;
            this.answerTokens = answerTokens;
            this.passageChars = passageChars;
        }

        /** 4 calls at a time, 512 answer tokens, the first 2,000 characters of a passage. */
        public static Options defaults() {
            return new Options(4, 512, 2000);
        }

        public Options withParallel(int parallel) {
            return new Options(parallel, answerTokens, passageChars);
        }

        public Options withAnswerTokens(int answerTokens) {
            return new Options(parallel, answerTokens, passageChars);
        }

        public Options withPassageChars(int passageChars) {
            return new Options(parallel, answerTokens, passageChars);
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
    }

    /** A text through the model: its mark and the raw answers. */
    public static final class Judgement {
        private final Passage passage;
        private final NoiseMark mark;
        private final List<String> answers;
        private final boolean overruled;

        Judgement(Passage passage, NoiseMark mark, List<String> answers) {
            this(passage, mark, answers, false);
        }

        Judgement(Passage passage, NoiseMark mark, List<String> answers, boolean overruled) {
            this.passage = passage;
            this.mark = mark;
            this.answers = Collections.unmodifiableList(new ArrayList<String>(answers));
            this.overruled = overruled;
        }

        /** Whether the model named AUSSAGE and its own first sentence made the piece a leftover. */
        public boolean isOverruled() {
            return overruled;
        }

        public Passage getPassage() {
            return passage;
        }

        public NoiseMark getMark() {
            return mark;
        }

        public List<String> getAnswers() {
            return answers;
        }
    }

    /** What one run did; counts by text. */
    public static final class Result {
        private final int passages;
        private final int texts;
        private final int reused;
        private final int byRules;
        private final int asked;
        private final int overruled;
        private final List<NoiseMark> marks;

        Result(int passages, int texts, int reused, int byRules, int asked, int overruled, List<NoiseMark> marks) {
            this.passages = passages;
            this.texts = texts;
            this.reused = reused;
            this.byRules = byRules;
            this.asked = asked;
            this.overruled = overruled;
            this.marks = Collections.unmodifiableList(new ArrayList<NoiseMark>(marks));
        }

        /** The passages to check. */
        public int getPassages() {
            return passages;
        }

        /** Their distinct texts. */
        public int getTexts() {
            return texts;
        }

        /** Texts with a mark that holds from before. */
        public int getReused() {
            return reused;
        }

        /** Texts the prose rules marked RAUSCHEN. */
        public int getByRules() {
            return byRules;
        }

        /** Texts through the model. */
        public int getAsked() {
            return asked;
        }

        /** Texts the model called AUSSAGE that its own first sentence made a leftover. */
        public int getOverruled() {
            return overruled;
        }

        /** The new marks, in the order of the passages. */
        public List<NoiseMark> getMarks() {
            return marks;
        }

        /** New marks with this verdict ({@code null}: FEHLER). */
        public int count(NoiseMark.Verdict verdict) {
            int n = 0;
            for (NoiseMark mark : marks) {
                n += mark.getVerdict() == verdict ? 1 : 0;
            }
            return n;
        }

        /** New RAUSCHEN marks per kind. */
        public Map<NoiseMark.Kind, Integer> noiseByKind() {
            Map<NoiseMark.Kind, Integer> kinds = new EnumMap<NoiseMark.Kind, Integer>(NoiseMark.Kind.class);
            for (NoiseMark mark : marks) {
                if (mark.isNoise()) {
                    kinds.merge(mark.getKind(), 1, Integer::sum);
                }
            }
            return kinds;
        }
    }

    private final MainModelChat chat;
    private final boolean german;
    private final Options options;
    private final ProsePassageFilter filter;
    private final Clock clock;

    /**
     * @param chat     the model of stage 2 on {@link #ROLE}'s slot; {@code null} runs the prose rules only, and what
     *                 they let through is INHALT of stage 1 until a model checks it
     * @param language the book's language: {@code de} gives German prompts, anything else English
     * @param filter   the prose rules; {@code null} takes their defaults
     */
    public PageNoiseCheck(MainModelChat chat, String language, Options options, ProsePassageFilter filter,
                          Clock clock) {
        this.chat = chat;
        this.german = language == null || language.trim().isEmpty()
                || language.trim().toLowerCase(Locale.ROOT).startsWith("de");
        this.options = options == null ? Options.defaults() : options;
        this.filter = filter == null ? new ProsePassageFilter(ProsePassageFilter.Criteria.defaults()) : filter;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** The key a passage's mark is kept by: {@link PassageText#key} of its text, {@code passage:<id>} when empty. */
    public static String textKey(Passage passage) {
        String key = PassageText.key(passage.getText());
        return key.isEmpty() ? "passage:" + passage.getId() : key;
    }

    /** Marks every passage given (see {@link #check(List, Collection, NoiseMarks)}). */
    public Result check(List<Passage> passages, NoiseMarks marks) throws IOException {
        return check(passages, null, marks);
    }

    /**
     * Marks the texts of the passages that have no mark that holds under this version, each once, and writes every
     * mark to {@code marks} the moment it is there.
     *
     * @param passages the passages of whole pages, each page in page order: the context of the caption rule
     * @param wanted   the ids of the passages to mark; {@code null} for all
     * @throws IOException when a mark cannot be written; no further text is asked then
     */
    public Result check(List<Passage> passages, Collection<String> wanted, NoiseMarks marks) throws IOException {
        Set<String> captions = captions(passages);
        Set<String> seen = new HashSet<String>();
        List<NoiseMark> made = new ArrayList<NoiseMark>();
        List<Passage> todo = new ArrayList<Passage>();
        int count = 0;
        int reused = 0;
        int byRules = 0;
        for (Passage passage : passages) {
            if (wanted != null && !wanted.contains(passage.getId())) {
                continue;
            }
            count++;
            String key = textKey(passage);
            if (!seen.add(key)) {
                continue;
            }
            if (holds(marks.mark(key))) {
                reused++;
                continue;
            }
            NoiseMark mark = byRules(passage, key, captions.contains(passage.getId()));
            if (mark != null) {
                marks.record(mark);
                made.add(mark);
                byRules += mark.isNoise() ? 1 : 0;
            } else {
                todo.add(passage);
            }
        }
        int overruled = 0;
        for (Judgement judgement : judgeAll(todo, marks)) {
            made.add(judgement.getMark());
            overruled += judgement.isOverruled() ? 1 : 0;
        }
        List<NoiseMark> ordered = new ArrayList<NoiseMark>();
        Map<String, NoiseMark> byKey = new LinkedHashMap<String, NoiseMark>();
        for (NoiseMark mark : made) {
            byKey.put(mark.getTextKey(), mark);
        }
        for (Passage passage : passages) {
            NoiseMark mark = byKey.remove(textKey(passage));
            if (mark != null) {
                ordered.add(mark);
            }
        }
        return new Result(count, seen.size(), reused, byRules, todo.size(), overruled, ordered);
    }

    /** Whether the model named AUSSAGE for the mark's text and its own first sentence made it a leftover. */
    public static boolean isOverruled(NoiseMark mark) {
        return mark != null && mark.isNoise() && mark.getStage() == NoiseMark.MODEL
                && mark.getReason().contains("(Art " + CONTENT_KIND + OVERRULED);
    }

    /** Whether the model named AUSSAGE for the mark's text but said the piece states nothing about the matter. */
    public static boolean isWithoutStatement(NoiseMark mark) {
        return mark != null && mark.isNoise() && mark.getStage() == NoiseMark.MODEL
                && mark.getReason().contains("(Art " + CONTENT_KIND + NO_STATEMENT + ")");
    }

    /** Whether a mark from before holds and need not be given again. */
    private boolean holds(NoiseMark mark) {
        if (mark == null || mark.getStatus() != NoiseMark.Status.OK || mark.getVersion() != VERSION) {
            return false;
        }
        return chat == null || mark.getStage() == NoiseMark.MODEL || mark.isNoise();
    }

    /** The ids of the passages the caption rule names, page by page (passages of one source in their order). */
    Set<String> captions(List<Passage> passages) {
        Map<String, List<Passage>> pages = new LinkedHashMap<String, List<Passage>>();
        for (Passage passage : passages) {
            String page = passage.getSourceId().isEmpty() ? "passage:" + passage.getId() : passage.getSourceId();
            pages.computeIfAbsent(page, ignored -> new ArrayList<Passage>()).add(passage);
        }
        Set<String> ids = new HashSet<String>();
        for (List<Passage> page : pages.values()) {
            List<String> texts = new ArrayList<String>();
            for (Passage passage : page) {
                texts.add(passage.getText());
            }
            for (int index : filter.captions(texts)) {
                ids.add(page.get(index).getId());
            }
        }
        return ids;
    }

    /**
     * Stage 1: the mark of the prose rules, or {@code null} when stage 2 must decide. A single site notice that is also
     * a word of prose is left to the model.
     */
    NoiseMark byRules(Passage passage, String key, boolean caption) {
        ProsePassageFilter.Rule rule = filter.rule(passage.getText(), true);
        long now = clock.millis();
        if (rule != ProsePassageFilter.Rule.NONE) {
            return NoiseMark.of(key, passage.getId(), passage.getSourceId(), NoiseMark.Verdict.RAUSCHEN, kindOf(rule),
                    NoiseMark.RULES, filter.reason(passage.getText(), true), "", VERSION, now, 0);
        }
        String leadIn = leadIn(passage.getText());
        if (!leadIn.isEmpty()) {
            return NoiseMark.of(key, passage.getId(), passage.getSourceId(), NoiseMark.Verdict.RAUSCHEN,
                    NoiseMark.Kind.UEBERSCHRIFT, NoiseMark.RULES, leadIn, "", VERSION, now, 0);
        }
        if (caption) {
            return NoiseMark.of(key, passage.getId(), passage.getSourceId(), NoiseMark.Verdict.RAUSCHEN,
                    NoiseMark.Kind.BILDUNTERSCHRIFT, NoiseMark.RULES, "a lone short sentence on a page of almost no "
                            + "prose", "", VERSION, now, 0);
        }
        if (chat == null) {
            return NoiseMark.of(key, passage.getId(), passage.getSourceId(), NoiseMark.Verdict.INHALT,
                    NoiseMark.Kind.KEINE, NoiseMark.RULES, "passes the prose rules; no model", "", VERSION, now, 0);
        }
        return null;
    }

    /** Abbreviations whose dot ends no sentence (lower case, without the dot). */
    private static final Set<String> ABBREVIATIONS = new HashSet<String>(Arrays.asList("abb", "abs", "allg", "anm",
            "art", "aufl", "bd", "betr", "bspw", "bzgl", "bzw", "ca", "dr", "ebd", "einschl", "etc", "evtl", "ff",
            "gem", "ggf", "hrsg", "inkl", "insb", "insbes", "jh", "kap", "lfd", "lit", "lt", "max", "mind", "min",
            "mio", "mrd", "nr", "prof", "rd", "sog", "st", "std", "str", "tab", "tel", "tsd", "usw", "vgl", "vs",
            "ziff", "zzgl", "zt", "approx", "cf", "eg", "ie", "no", "vol", "fig", "pp", "jr", "sr", "mr", "mrs", "ms"));
    private static final Pattern OPENING = Pattern.compile("^[\"„“«‚‘(\\[]+");
    private static final Pattern CLOSING = Pattern.compile("[\"“”»’')\\]]+$");
    private static final Pattern WORDLIKE = Pattern.compile("[\\p{L}\\p{N}]");

    /**
     * Why the passage is a lead-in, a line that only announces what follows; {@code ""} when it is none. A lead-in has
     * at most {@link #LEAD_IN_MAX_WORDS} words, ends in a colon and holds no sentence of its own: no full stop or
     * exclamation mark after a word that is no abbreviation, initial or number of one or two digits, before a capital
     * or a digit. Questions count as no sentence of its own ("Was gilt für Lastenräder? Die Regeln im Überblick:").
     * The prose rules already drop such a line when nothing in it looks like the end of a sentence; this rule also
     * takes the line whose only dots are those of "z. B.", "Abs." or "1. Januar".
     */
    static String leadIn(String text) {
        String t = text == null ? "" : text.replace("\u00AD", "").trim();
        if (!t.endsWith(":")) {
            return "";
        }
        String[] tokens = t.split("\\s+");
        int words = 0;
        for (String token : tokens) {
            words += WORDLIKE.matcher(token).find() ? 1 : 0;
        }
        if (words > LEAD_IN_MAX_WORDS) {
            return "";
        }
        for (int i = 0; i + 1 < tokens.length; i++) {
            if (endsSentence(tokens[i], tokens[i + 1])) {
                return "";
            }
        }
        return LEAD_IN + words + " words that end in a colon, without a sentence of their own";
    }

    private static boolean endsSentence(String token, String next) {
        String bare = CLOSING.matcher(token).replaceAll("");
        if (!bare.endsWith(".") && !bare.endsWith("!")) {
            return false;
        }
        String following = OPENING.matcher(next).replaceAll("");
        if (following.isEmpty() || !(Character.isUpperCase(following.codePointAt(0))
                || Character.isDigit(following.codePointAt(0)))) {
            return false;
        }
        String word = OPENING.matcher(bare.replaceAll("[.!]+$", "")).replaceAll("");
        if (word.contains(".") || word.matches("\\d{1,2}|[IVXLC]+")) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < word.length(); i++) {
            letters += Character.isLetter(word.charAt(i)) ? 1 : 0;
        }
        if (letters <= 1 && !word.matches("\\d{3,}")) {
            // An initial or a single letter ("z. B."); a year ("2024.") ends a sentence.
            return false;
        }
        return !ABBREVIATIONS.contains(word.toLowerCase(Locale.ROOT));
    }

    /** Whether stage 1 marked the text as a lead-in ({@link #leadIn}). */
    public static boolean isLeadIn(NoiseMark mark) {
        return mark != null && mark.isNoise() && mark.getStage() == NoiseMark.RULES
                && mark.getKind() == NoiseMark.Kind.UEBERSCHRIFT && mark.getReason().startsWith(LEAD_IN);
    }

    static NoiseMark.Kind kindOf(ProsePassageFilter.Rule rule) {
        switch (rule) {
            case SITE_NOTICES:
                return NoiseMark.Kind.SEITENHINWEIS;
            case PROMOTION:
            case CAPITALS:
                return NoiseMark.Kind.WERBUNG;
            case LINKS:
                return NoiseMark.Kind.NAVIGATION;
            case NONE:
                return NoiseMark.Kind.KEINE;
            default:
                return NoiseMark.Kind.STRUKTUR;
        }
    }

    /** Stage 2 on the texts in parallel, in their order; each mark is written the moment it is there. */
    List<Judgement> judgeAll(List<Passage> todo, NoiseMarks marks) throws IOException {
        List<Judgement> judgements = new ArrayList<Judgement>();
        if (todo.isEmpty()) {
            return judgements;
        }
        AtomicReference<IOException> unwritten = new AtomicReference<IOException>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.parallel, todo.size()), runnable -> {
            Thread thread = new Thread(runnable, "page-noise-check");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Judgement>> futures = new ArrayList<Future<Judgement>>();
            for (Passage passage : todo) {
                futures.add(pool.submit(() -> {
                    if (unwritten.get() != null) {
                        return failed(passage, "not asked: an earlier mark could not be written", 0,
                                Collections.<String>emptyList());
                    }
                    Judgement judgement = judge(passage);
                    try {
                        marks.record(judgement.getMark(), judgement.getAnswers());
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
            throw new IOException("a mark of the page-noise check could not be written: "
                    + unwritten.get().getMessage(), unwritten.get());
        }
        return judgements;
    }

    /** Stage 2 on one text, with one repair; a text without a verdict is FEHLER. */
    public Judgement judge(Passage passage) {
        long began = clock.millis();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(passage)));
        List<String> answers = new ArrayList<String>();
        String problem = "no model";
        for (int attempt = 1; chat != null && attempt <= ATTEMPTS; attempt++) {
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
                NoiseMark mark = NoiseMark.of(textKey(passage), passage.getId(), passage.getSourceId(),
                        answer.verdict, answer.kind, NoiseMark.MODEL, answer.reason, chat.modelName(), VERSION,
                        clock.millis(), clock.millis() - began);
                return new Judgement(passage, mark, answers, answer.overruled);
            }
            problem = "invalid answer: " + answer.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + answer.problem + (german ? "\nAntworte nur mit dem JSON-Objekt." : "\nAnswer with the JSON "
                    + "object only.")));
        }
        return failed(passage, problem, clock.millis() - began, answers);
    }

    private Judgement failed(Passage passage, String problem, long millis, List<String> answers) {
        return new Judgement(passage, NoiseMark.failed(textKey(passage), passage.getId(), passage.getSourceId(),
                problem, chat == null ? "" : chat.modelName(), VERSION, clock.millis(), millis), answers);
    }

    /** The model's answer as read, or why it holds none. */
    static final class Answer {
        String reason = "";
        /** What the piece states, as the model says it; {@link #NO_STATEMENT_WORD} when it states nothing. */
        String statement = "";
        NoiseMark.Verdict verdict;
        NoiseMark.Kind kind = NoiseMark.Kind.SONSTIGES;
        String problem = "";
        /** The model named AUSSAGE, and its first sentence named a kind of leftover or it said "keine". */
        boolean overruled;
    }

    /**
     * Reads an answer leniently (case and blanks of the kind do not count) and derives the verdict from the kind:
     * {@link #CONTENT_KIND} is INHALT without a kind of leftover, every kind of {@link #MODEL_KINDS} is RAUSCHEN of
     * that kind. Any other word is no answer. AUSSAGE with a first sentence that plainly names a kind of leftover
     * ({@link DescriptionCheck}) is RAUSCHEN of that kind; AUSSAGE whose statement is {@code "keine"}
     * ({@link #saysNothing}) is RAUSCHEN of kind SONSTIGES; the reason says which. The statement must be there for
     * AUSSAGE; for a kind of leftover a missing one does not count.
     */
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
        String kind = word(string(json, "art"));
        if (CONTENT_KIND.equals(kind)) {
            answer.verdict = NoiseMark.Verdict.INHALT;
            answer.kind = NoiseMark.Kind.KEINE;
        }
        for (NoiseMark.Kind value : MODEL_KINDS) {
            if (value.name().equals(kind)) {
                answer.verdict = NoiseMark.Verdict.RAUSCHEN;
                answer.kind = value;
            }
        }
        if (answer.verdict == null) {
            answer.problem = "\"art\" must be one of " + kindNames(", ");
            return answer;
        }
        answer.reason = cut(string(json, "beschreibung"), REASON_CHARS);
        if (answer.reason.length() < MIN_REASON_CHARS) {
            answer.problem = "\"beschreibung\" must say in a short sentence who speaks and to what end";
            return answer;
        }
        answer.statement = cut(string(json, "aussage"), REASON_CHARS);
        if (answer.verdict != NoiseMark.Verdict.INHALT) {
            return answer;
        }
        if (answer.statement.isEmpty()) {
            answer.problem = "\"aussage\" must say in a short sentence what the piece states, or \""
                    + NO_STATEMENT_WORD + "\"";
            return answer;
        }
        NoiseMark.Kind named = DescriptionCheck.kindNamed(answer.reason);
        if (named != null) {
            answer.verdict = NoiseMark.Verdict.RAUSCHEN;
            answer.kind = named;
            answer.overruled = true;
            answer.reason = answer.reason + " (Art " + CONTENT_KIND + OVERRULED + named.name() + ")";
        } else if (saysNothing(answer.statement)) {
            answer.verdict = NoiseMark.Verdict.RAUSCHEN;
            answer.kind = NoiseMark.Kind.SONSTIGES;
            answer.overruled = true;
            answer.reason = answer.reason + " (Art " + CONTENT_KIND + NO_STATEMENT + ")";
        }
        return answer;
    }

    /**
     * "keine" (or "none", "nichts"), alone or followed by "Aussage ..." or by a comma, colon, dash or bracket with an
     * explanation, or a sentence that begins with "(das Stück) sagt nichts". A statement that merely begins with the
     * word ("Keine Person darf ...") says something.
     */
    private static final Pattern NOTHING = Pattern.compile("^(?:keine|keiner|keines|nichts|none|nothing)"
            + "(?:\\s+(?:eigene\\s+)?aussage\\b.*|\\s*[,;:(\u2013\u2014-].*)?$"
            + "|^(?:(?:das\\s+(?:text)?stück|der\\s+text|es|it|the\\s+piece)\\s+)?(?:sagt|says)\\s+"
            + "(?:selbst\\s+|itself\\s+)?(?:nichts|nothing)\\b.*|^no\\s+statement\\b.*",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.DOTALL);

    /** Quotes and blanks around the statement, and a closing full stop or exclamation mark. */
    private static final Pattern AROUND = Pattern.compile("^[\\s\"'„“”«»‚‘’]+|[\\s\"'„“”«»‚‘’.!]+$");

    /** Whether the model's statement says that the piece states nothing about the matter. */
    static boolean saysNothing(String statement) {
        String text = AROUND.matcher(clean(statement).toLowerCase(Locale.ROOT)).replaceAll("");
        return NOTHING.matcher(text).matches();
    }

    // ------------------------------------------------------------------------------------------------- prompt

    /** What each kind of leftover is, as the German prompt says it. */
    private static final Map<NoiseMark.Kind, String> KINDS_DE = kinds(
            "will verkaufen oder Kunden gewinnen: Angebote, Preise, Rabatte, Gutscheine, Aufrufe zum Kaufen, "
                    + "Bestellen, Buchen, Testen, Anmelden oder Anfragen, Lob oder Empfehlung des eigenen Angebots. "
                    + "Sagt ein Anbieter sachlich, was ein Produkt oder eine Leistung kann oder bringt, ohne zum Kauf "
                    + "aufzurufen und ohne es zu loben, ist das keine Werbung.",
            "stellt die Seite oder ihren Anbieter vor (wer er ist, was er anbietet, Ziele, Team, Partner, Kunden, "
                    + "Referenzen, Auszeichnungen) oder spricht über den Beitrag oder das Dokument selbst: was er "
                    + "behandelt, wie er aufgebaut ist, für wen er gedacht ist, was der Leser darin erfährt.",
            "verweist auf andere Texte: Inhaltsverzeichnis, Register, Liste von Titeln, Kapiteln, Artikeln, "
                    + "Dokumenten, Downloads, Links, Produkten oder Terminen, deren Einträge selbst nichts aussagen. "
                    + "Eine Liste, deren Einträge etwas aussagen, ist kein Verzeichnis.",
            "Literaturangaben, Quellenlisten, Listen von Normen oder Vorschriften und Fußnoten, die nur Fundstellen "
                    + "nennen, auch wenn ein einleitender Satz davorsteht.",
            "bedient die Seite: Menüs, Pfade, Weiter- und Zurück-Verweise, „Mehr lesen“, Schlagwörter, Kategorien, "
                    + "Seitenzahlen, Knöpfe zum Teilen.",
            "beschreibt ein Bild, eine Grafik oder eine Tabelle, nennt ihren Urheber oder gibt nur ihre "
                    + "Beschriftungen oder Begriffe wieder.",
            "nur eine Überschrift, ein Titel oder ein Anriss: eine Frage, ein Satzanfang oder eine Zeile, die nur "
                    + "ankündigt, was folgt (etwa eine Liste), ohne selbst etwas auszusagen.",
            "Impressum, Kontaktdaten, Anschrift, Telefon, Öffnungszeiten, Angaben zum Betreiber.",
            "Hinweise der Seite: Cookies, Datenschutzeinstellungen, Lizenzen, Newsletter, Anmeldung, Kommentare, "
                    + "Nutzungsbedingungen.",
            "ein anderer Teil der Seite, der nichts über die Sache aussagt, etwa Eckdaten eines Dokuments oder einer "
                    + "Vorschrift (Titel, Datum, Stand, Änderungen, Fundstelle, Aktenzeichen, Herausgeber), bloße "
                    + "Namenslisten (Autoren, Mitwirkende, Mitglieder) oder Formularfelder.");

    /** What each kind of leftover is, as the English prompt says it. */
    private static final Map<NoiseMark.Kind, String> KINDS_EN = kinds(
            "wants to sell or to win customers: offers, prices, discounts, coupons, calls to buy, order, book, try, "
                    + "sign up or get in touch, praise or recommendation of its own offer. When a provider says "
                    + "factually what a product or a service does or brings, without a call to buy and without praise, "
                    + "it is no advertising.",
            "presents the site or its provider (who they are, what they offer, aims, team, partners, customers, "
                    + "references, awards) or speaks about the article or document itself: what it covers, how it is "
                    + "built, whom it is meant for, what the reader learns in it.",
            "points to other texts: table of contents, index, list of titles, chapters, articles, documents, "
                    + "downloads, links, products or dates whose entries say nothing themselves. A list whose entries "
                    + "say something is no index.",
            "literature references, lists of sources, lists of standards or regulations and footnotes that only name "
                    + "where something is found, also when an introductory sentence stands before them.",
            "operates the page: menus, breadcrumbs, next and back links, \"read more\", tags, categories, page "
                    + "numbers, share buttons.",
            "describes a picture, a chart or a table, names its author or only gives its labels or terms.",
            "only a heading, a title or a teaser: a question, the start of a sentence or a line that only announces "
                    + "what follows (such as a list) without saying anything itself.",
            "imprint, contact data, address, phone, opening hours, details of the operator.",
            "notices of the site: cookies, data protection settings, licences, newsletters, login, comments, terms of "
                    + "use.",
            "another part of the page that says nothing about the matter, such as the key data of a document or a "
                    + "regulation (title, date, version, amendments, reference, file number, publisher), bare lists of "
                    + "names (authors, contributors, members) or form fields.");

    String prompt(Passage passage) {
        StringBuilder text = new StringBuilder();
        String domain = passage.getDomain();
        if (german) {
            text.append("Textstück")
                    .append(domain.isEmpty() ? " aus einer Quelle ohne Adresse" : " von " + domain).append(":\n„")
                    .append(cut(passage.getText(), options.passageChars)).append("“\n\n")
                    .append("Bestimme, was für ein Text das Stück ist. Entscheide nach seinem größten Teil.\n\n")
                    .append("Seitenrest gehört zur Seite, nicht zur Sache:\n");
            appendKinds(text, KINDS_DE);
            text.append("\nKein Seitenrest:\n")
                    .append("- \"").append(CONTENT_KIND).append("\": informiert über die Sache: ein Sachverhalt, "
                            + "eine Regel, eine Pflicht, eine Zahl, ein Ergebnis, eine Erklärung, eine Anleitung, "
                            + "eine Empfehlung, ein Beispiel oder eine begründete Meinung, auch als Liste, wenn ihre "
                            + "Einträge etwas aussagen; auch was ein Anbieter sachlich über die Sache erklärt oder "
                            + "darüber, was ein Produkt oder eine Leistung kann oder bringt. "
                            + "Prüfstein: Ein Sachbuch könnte das Stück als Aussage zur Sache zitieren.\n\n")
                    .append("Ob die Sache zu einem Buch passt, spielt keine Rolle.\n\n")
                    .append("Antworte nur mit JSON: {\"beschreibung\": ein kurzer Satz, wer hier spricht und wozu, "
                            + "\"aussage\": was das Stück selbst über die Sache aussagt, in einem kurzen Satz, oder \""
                            + NO_STATEMENT_WORD + "\", wenn es nichts darüber aussagt (es kündigt nur an, verweist, "
                            + "wirbt, stellt vor oder nennt nur Namen, Titel, Daten oder Fundstellen), \"art\": eine "
                            + "der Arten oben}");
        } else {
            text.append("Piece of text")
                    .append(domain.isEmpty() ? " from a source without address" : " from " + domain).append(":\n\"")
                    .append(cut(passage.getText(), options.passageChars)).append("\"\n\n")
                    .append("Say what kind of text the piece is. Decide by its biggest part.\n\n")
                    .append("A page leftover belongs to the page, not to the matter:\n");
            appendKinds(text, KINDS_EN);
            text.append("\nNo page leftover:\n")
                    .append("- \"").append(CONTENT_KIND).append("\": informs about the matter: a fact, a rule, a "
                            + "duty, a number, a result, an explanation, an instruction, an advice, an example or an "
                            + "opinion with reasons, also as a list whose entries say something; also what a provider "
                            + "explains factually about the matter or about what a product or a service does or "
                            + "brings. Test: a non-fiction book could quote the piece as a statement on the "
                            + "matter.\n\n")
                    .append("Whether the matter fits a book does not count.\n\n")
                    .append("Answer only with JSON: {\"beschreibung\": one short sentence, who speaks here and to what "
                            + "end, \"aussage\": what the piece itself says about the matter, in one short sentence, "
                            + "or \"" + NO_STATEMENT_WORD + "\" if it says nothing about it (it only announces, points "
                            + "elsewhere, advertises, presents someone or only names names, titles, dates or "
                            + "references), \"art\": one of the kinds above}");
        }
        return text.toString();
    }

    private static void appendKinds(StringBuilder text, Map<NoiseMark.Kind, String> kinds) {
        for (Map.Entry<NoiseMark.Kind, String> kind : kinds.entrySet()) {
            text.append("- \"").append(kind.getKey().name()).append("\": ").append(kind.getValue()).append('\n');
        }
    }

    /** The descriptions in the order of {@link #MODEL_KINDS}. */
    private static Map<NoiseMark.Kind, String> kinds(String... descriptions) {
        if (descriptions.length != MODEL_KINDS.size()) {
            throw new IllegalStateException("one description per kind of leftover");
        }
        Map<NoiseMark.Kind, String> kinds = new LinkedHashMap<NoiseMark.Kind, String>();
        for (int i = 0; i < descriptions.length; i++) {
            kinds.put(MODEL_KINDS.get(i), descriptions[i]);
        }
        return Collections.unmodifiableMap(kinds);
    }

    /** The words the model may give as {@code art}: the kinds of leftover, then {@link #CONTENT_KIND}. */
    static List<String> kindWords() {
        List<String> words = new ArrayList<String>();
        for (NoiseMark.Kind kind : MODEL_KINDS) {
            words.add(kind.name());
        }
        words.add(CONTENT_KIND);
        return words;
    }

    private static String kindNames(String separator) {
        return String.join(separator, kindWords());
    }

    private static String schema() {
        JsonObject properties = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty("type", "string");
        description.addProperty("minLength", MIN_REASON_CHARS);
        properties.add("beschreibung", description);
        JsonObject statement = new JsonObject();
        statement.addProperty("type", "string");
        statement.addProperty("minLength", 1);
        properties.add("aussage", statement);
        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray kinds = new JsonArray();
        for (String word : kindWords()) {
            kinds.add(word);
        }
        kind.add("enum", kinds);
        properties.add("art", kind);
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("beschreibung");
        required.add("aussage");
        required.add("art");
        root.add("required", required);
        return root.toString();
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

    static String clean(String text) {
        return text == null ? "" : text.replace("­", "").replaceAll("\\s+", " ").trim();
    }
}
