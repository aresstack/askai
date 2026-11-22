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

/**
 * The page-noise check (Seitenrest-Prüfung) before the topic finder (13). The architect's measurement of 22.11.2026
 * proposed it: of 60 blind passages of the 17 Nov stock 11 were page leftovers, 20 % of the topic finder's proposals
 * rested on such passages, and the prose rules alone caught 3 of 22. Each passage text gets one mark, kept by text key
 * in {@code page-noise/marks.jsonl} ({@link NoiseMarks}); nothing is deleted. The topic finder (13), the evidence judge
 * (6), the sentence check (11) and the research memory (7) skip what is marked RAUSCHEN.
 *
 * <p>Stage 1, no model: the prose rules ({@link ProsePassageFilter}): too few words or finished sentences, a long run
 * without punctuation, site notices, promotion, capitals, links; on a page of almost no prose a lone short sentence is
 * a caption. A passage that breaks a rule is RAUSCHEN with the kind of the rule. Stage 2, only on what stage 1 lets
 * through: one call of a small model (slot {@code phase.research.noise}, thinking off) per text. The model first says
 * in a short sentence who speaks and to what end, then names the kind of text by its biggest part: a statement on the
 * matter ({@code AUSSAGE}) or one of the kinds of leftover. The verdict follows from the kind in code: AUSSAGE is
 * INHALT, every kind of leftover is RAUSCHEN. The model gives no verdict of its own, because in version 1 it said
 * INHALT for passages its own reason called advertising or a table of contents. The model sees the passage and the
 * domain of its page, not the mission: whether a passage belongs in the book is the fence check's question (14). A
 * failed call is FEHLER and never RAUSCHEN; the text is checked again next time.</p>
 *
 * <p>A text with a mark that holds under this {@link #VERSION} is not checked again, except a stage-1 INHALT from a
 * run without a model, which stage 2 checks once a model is there. Every mark goes to the store the moment it is
 * there; when one cannot be written, no further text is asked and the run fails.</p>
 */
public final class PageNoiseCheck {

    /**
     * The rules' and prompts' version; a mark is reused only under the same one. 2: the model names the kind of text,
     * the verdict follows from it, there is no MISCH from the model any more.
     */
    public static final int VERSION = 2;

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

    /**
     * The kinds of leftover the model may name, in the order of the prompt; {@link NoiseMark.Kind#STRUKTUR} belongs to
     * the prose rules, {@link NoiseMark.Kind#KEINE} is content.
     */
    static final List<NoiseMark.Kind> MODEL_KINDS = Collections.unmodifiableList(Arrays.asList(NoiseMark.Kind.WERBUNG,
            NoiseMark.Kind.SEITENBESCHREIBUNG, NoiseMark.Kind.VERZEICHNIS, NoiseMark.Kind.LITERATUR,
            NoiseMark.Kind.NAVIGATION, NoiseMark.Kind.BILDUNTERSCHRIFT, NoiseMark.Kind.UEBERSCHRIFT,
            NoiseMark.Kind.IMPRESSUM, NoiseMark.Kind.SEITENHINWEIS, NoiseMark.Kind.SONSTIGES));

    /** The answer: first who speaks and to what end, then the kind of text. */
    static final String SCHEMA = schema();

    public static final ResearchRole ROLE = ResearchRole.builder("page-noise-check",
                    ResearchModelSlots.PHASE_RESEARCH_NOISE)
            .purpose("page-noise check before the topic finder: names the kind of a passage the prose rules let "
                    + "through, a statement on the matter or a page leftover (advertising, site description, table "
                    + "of contents, literature list, navigation, caption, heading, imprint, site notice); the code "
                    + "derives content or leftover from the kind; never judges the subject")
            .promptFromCaller()
            .output(RoleOutput.schema(SCHEMA, "beschreibung, art"))
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

        Judgement(Passage passage, NoiseMark mark, List<String> answers) {
            this.passage = passage;
            this.mark = mark;
            this.answers = Collections.unmodifiableList(new ArrayList<String>(answers));
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
        private final List<NoiseMark> marks;

        Result(int passages, int texts, int reused, int byRules, int asked, List<NoiseMark> marks) {
            this.passages = passages;
            this.texts = texts;
            this.reused = reused;
            this.byRules = byRules;
            this.asked = asked;
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
        for (Judgement judgement : judgeAll(todo, marks)) {
            made.add(judgement.getMark());
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
        return new Result(count, seen.size(), reused, byRules, todo.size(), ordered);
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

    /** Stage 1: the mark of the prose rules, or {@code null} when stage 2 must decide. */
    NoiseMark byRules(Passage passage, String key, boolean caption) {
        ProsePassageFilter.Rule rule = filter.rule(passage.getText());
        long now = clock.millis();
        if (rule != ProsePassageFilter.Rule.NONE) {
            return NoiseMark.of(key, passage.getId(), passage.getSourceId(), NoiseMark.Verdict.RAUSCHEN, kindOf(rule),
                    NoiseMark.RULES, filter.reason(passage.getText()), "", VERSION, now, 0);
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
                return new Judgement(passage, mark, answers);
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
        NoiseMark.Verdict verdict;
        NoiseMark.Kind kind = NoiseMark.Kind.SONSTIGES;
        String problem = "";
    }

    /**
     * Reads an answer leniently (case and blanks of the kind do not count) and derives the verdict from the kind:
     * {@link #CONTENT_KIND} is INHALT without a kind of leftover, every kind of {@link #MODEL_KINDS} is RAUSCHEN of
     * that kind. Any other word is no answer.
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
        }
        return answer;
    }

    // ------------------------------------------------------------------------------------------------- prompt

    /** What each kind of leftover is, as the German prompt says it. */
    private static final Map<NoiseMark.Kind, String> KINDS_DE = kinds(
            "will verkaufen oder Kunden gewinnen: Angebote, Produkte, Preise, Rabatte, Gutscheine, Aufrufe zum "
                    + "Kaufen, Bestellen, Buchen, Testen, Anmelden oder Anfragen; auch was ein Anbieter über sein "
                    + "eigenes Produkt oder seine eigene Leistung sagt (Funktionen, Vorteile, Lob), selbst wenn es "
                    + "sachlich klingt.",
            "stellt die Seite oder ihren Anbieter vor: wer er ist, was er anbietet, Ziele, Team, Kunden, Referenzen, "
                    + "Auszeichnungen.",
            "verweist auf andere Texte: Inhaltsverzeichnis, Register, Liste von Titeln, Kapiteln, Artikeln, "
                    + "Dokumenten, Downloads, Links, Produkten oder Terminen, deren Einträge selbst nichts aussagen.",
            "Literaturangaben, Quellenlisten und Fußnoten, die nur Fundstellen nennen.",
            "bedient die Seite: Menüs, Pfade, Weiter- und Zurück-Verweise, „Mehr lesen“, Schlagwörter, Kategorien, "
                    + "Seitenzahlen, Knöpfe zum Teilen.",
            "beschreibt ein Bild, eine Grafik oder eine Tabelle oder nennt ihren Urheber.",
            "nur eine Überschrift oder ein Titel, ohne Aussage.",
            "Impressum, Kontaktdaten, Anschrift, Telefon, Öffnungszeiten, Angaben zum Betreiber.",
            "Hinweise der Seite: Cookies, Datenschutzeinstellungen, Lizenzen, Newsletter, Anmeldung, Kommentare, "
                    + "Nutzungsbedingungen.",
            "ein anderer Teil der Seite, der nichts über die Sache aussagt.");

    /** What each kind of leftover is, as the English prompt says it. */
    private static final Map<NoiseMark.Kind, String> KINDS_EN = kinds(
            "wants to sell or to win customers: offers, products, prices, discounts, coupons, calls to buy, order, "
                    + "book, try, sign up or get in touch; also what a provider says about its own product or service "
                    + "(features, advantages, praise), even when it sounds factual.",
            "presents the site or its provider: who they are, what they offer, aims, team, customers, references, "
                    + "awards.",
            "points to other texts: table of contents, index, list of titles, chapters, articles, documents, "
                    + "downloads, links, products or dates whose entries say nothing themselves.",
            "literature references, lists of sources and footnotes that only name where something is found.",
            "operates the page: menus, breadcrumbs, next and back links, \"read more\", tags, categories, page "
                    + "numbers, share buttons.",
            "describes a picture, a chart or a table or names its author.",
            "only a heading or a title, without a statement.",
            "imprint, contact data, address, phone, opening hours, details of the operator.",
            "notices of the site: cookies, data protection settings, licences, newsletters, login, comments, terms of "
                    + "use.",
            "another part of the page that says nothing about the matter.");

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
                            + "Einträge etwas aussagen; auch was ein Anbieter allgemein über die Sache erklärt. "
                            + "Prüfstein: Ein Sachbuch könnte das Stück als Aussage zur Sache zitieren.\n\n")
                    .append("Ob die Sache zu einem Buch passt, spielt keine Rolle.\n\n")
                    .append("Antworte nur mit JSON: {\"beschreibung\": ein kurzer Satz, wer hier spricht und wozu, "
                            + "\"art\": eine der Arten oben}");
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
                            + "explains about the matter in general. Test: a non-fiction book could quote the piece as "
                            + "a statement on the matter.\n\n")
                    .append("Whether the matter fits a book does not count.\n\n")
                    .append("Answer only with JSON: {\"beschreibung\": one short sentence, who speaks here and to what "
                            + "end, \"art\": one of the kinds above}");
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
