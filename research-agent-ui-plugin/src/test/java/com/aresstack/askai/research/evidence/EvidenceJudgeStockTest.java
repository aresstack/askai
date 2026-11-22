package com.aresstack.askai.research.evidence;

import com.aresstack.askai.research.evidence.EvidenceJudge.Options;
import com.aresstack.askai.research.evidence.EvidenceJudge.Outcome;
import com.aresstack.askai.research.evidence.EvidenceJudge.Pair;
import com.aresstack.askai.research.evidence.EvidenceJudge.Passage;
import com.aresstack.askai.research.evidence.EvidenceJudge.Question;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.EvidenceVerdict.Kind;
import com.aresstack.askai.research.memory.PassageText;
import com.aresstack.askai.research.memory.QuestionEvidence;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Component 6 on the stock of 17 Nov: the evidence judge against the architect's blind verdicts on 969 pairs of
 * question and passage ({@code belegpaare-1711.jsonl} on the owner's machine: BELEGT 135, HINWEIS 241, KEIN 593; the
 * file holds passage text and never enters the repository). Each pair is judged as in the product: one call with the
 * passage text of the file and the mission of the stock, then the check after the answer; a second passage with the
 * same text is not asked again for the same question.
 *
 * <p>Criteria after the memory probe of 21.11. (build plan: "Schwelle wie in der Gedächtnisprobe"), each a property:
 * K1 of the final BELEGT at least {@code .minPraezision} (0.85) are BELEGT in the reference; K2 at least
 * {@code .minBreite} (all) of the answerable questions of {@code fragen-1711.json} get a final BELEGT the reference
 * also calls BELEGT; K3 at most {@code .maxOhneUrteil} (0.01) of the pairs end without a verdict. Only when set: K4
 * {@code .minGefunden} (share of the reference BELEGT judged BELEGT) and K5 {@code .maxKeinAlsBelegt} (share of the
 * reference KEIN judged BELEGT). BELEGT and HINWEIS are counted apart; everything else is report, among it the status
 * per question the research memory derives from the verdicts (3 texts from 2 source groups, as in the file).</p>
 *
 * <p>Several models run one after the other ({@code .chatModel=gemma4:12b,gemma4:e2b}); the first decides, every
 * later one is compared with it: "nicht schlechter" when no criterion value is worse by more than
 * {@code .toleranz} (0.02).</p>
 *
 * <p>{@link #sampleStock()} runs the whole path on self-written pairs with scripted models in every build;
 * {@link #storedStock()} needs Ollama and is skipped without {@code .chatUrl}:</p>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*EvidenceJudgeStockTest*'
 *   -Daskai.stock.evidence.chatUrl=http://127.0.0.1:11434 -Daskai.stock.evidence.chatModel=gemma4:12b,gemma4:e2b
 *   -Daskai.stock.evidence.pairs=belegpaare-1711.jsonl
 *   -Daskai.stock.evidence.corpus=replay-corpus.json (only "mission" and "language" are read)
 *   -Daskai.stock.evidence.out=build/component-stock/evidence-judge
 *   optional: .missionFile (UTF-8 text instead of .corpus), .questions (another file in the form of fragen-1711.json),
 *             .minPraezision (0.85), .minBreite (all answerable), .maxOhneUrteil (0.01), .minGefunden,
 *             .maxKeinAlsBelegt, .toleranz (0.02), .parallel (4), .passageChars (4000), .limit (0 = all pairs),
 *             .nurFragen (F01,F02), .numCtx (only when set), .think=omit (else think:false is sent),
 *             .timeoutSeconds (120)
 * </pre>
 * {@code result.json} holds ids, verdicts and counts, no passage text and no quote. Per model,
 * {@code <out>/<model>/urteile.jsonl} holds every verdict with the model's quote and sentence; it carries words of
 * the sources and stays on the machine.
 */
public class EvidenceJudgeStockTest {

    private static final String P = "askai.stock.evidence.";
    static final String QUESTIONS = "/evidence-judge-stock/fragen-1711.json";
    /** UTF-8 read as Latin-1 ("Ã¤", "â€", "Â§"): a damaged copy of the corpus looks like this. */
    static final Pattern MOJIBAKE = Pattern.compile("\\u00C3[\\u0080-\\u00BF]|\\u00E2\\u20AC|\\u00C2[\\u00A0-\\u00BF]");
    private static final List<Kind> REFERENCE_KINDS = Arrays.asList(Kind.BELEGT, Kind.HINWEIS, Kind.KEIN);

    @Rule
    public TemporaryFolder scratch = new TemporaryFolder();

    /** One judged pair of the file. */
    static final class StockPair {
        final String question;
        final String passage;
        final String source;
        final String domain;
        final String text;
        final Kind reference;
        final String origin;

        StockPair(String question, String passage, String source, String domain, String text, Kind reference,
                  String origin) {
            this.question = question;
            this.passage = passage;
            this.source = source;
            this.domain = domain;
            this.text = text;
            this.reference = reference;
            this.origin = origin;
        }
    }

    /** The questions of the file: text, topic, whether the stock answers them. */
    static final class Questions {
        String stock = "";
        final Map<String, String> topicNames = new LinkedHashMap<String, String>();
        final Map<String, String> texts = new LinkedHashMap<String, String>();
        final Map<String, String> topicOf = new LinkedHashMap<String, String>();
        final Set<String> answerable = new LinkedHashSet<String>();
    }

    /** Criteria and how the run works. */
    static final class Settings {
        List<String> models = Collections.singletonList("gemma4:12b");
        int parallel = Options.DEFAULT_PARALLEL;
        int passageChars = Options.DEFAULT_PASSAGE_CHARS;
        int limit;
        final Set<String> onlyQuestions = new LinkedHashSet<String>();
        double minPrecision = 0.85;
        /** {@code null}: every answerable question that is tested. */
        Integer minBreadth;
        double maxUnjudged = 0.01;
        Double minFound;
        Double maxNoneAsDirect;
        double tolerance = 0.02;

        void check() {
            if (models.isEmpty()) {
                throw new IllegalArgumentException("at least one model");
            }
            new Options(parallel, passageChars);
        }
    }

    /** Counts and times the calls of a model. */
    static final class TimedChat implements MainModelChat {
        private final MainModelChat inner;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong nanos = new AtomicLong();

        TimedChat(MainModelChat inner) {
            this.inner = inner;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            long start = System.nanoTime();
            try {
                return inner.completeJson(messages, temperature, maxOutputTokens, schemaJson);
            } finally {
                nanos.addAndGet(System.nanoTime() - start);
            }
        }

        @Override
        public String modelName() {
            return inner.modelName();
        }
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    public void theRepoCopyOfTheQuestionsIsComplete() throws IOException {
        Questions questions = readQuestions(resource(QUESTIONS));
        assertEquals(20, questions.texts.size());
        assertEquals(4, questions.topicNames.size());
        assertEquals(16, questions.answerable.size());
        assertFalse(questions.answerable.contains("F07"));
        assertFalse(questions.answerable.contains("F16"));
        assertTrue(questions.stock.contains("18fbde60"));
        for (int i = 1; i <= 20; i++) {
            String id = String.format("F%02d", i);
            assertFalse(id, questions.texts.get(id).isEmpty());
            assertTrue(id, questions.topicNames.containsKey(questions.topicOf.get(id)));
        }
    }

    @Test
    public void sampleStock() throws IOException {
        Questions questions = readQuestions(SAMPLE_QUESTIONS);
        List<StockPair> pairs = readPairs(new BufferedReader(new StringReader(samplePairs())));
        assertEquals(11, pairs.size());
        File out = scratch.newFolder("out");
        Settings settings = new Settings();
        settings.models = Arrays.asList("richtig", "fehlerhaft");
        settings.parallel = 2;
        Map<String, MainModelChat> chats = new HashMap<String, MainModelChat>();
        chats.put("richtig", new Scripted(pairs, false));
        chats.put("fehlerhaft", new Scripted(pairs, true));

        JsonObject result = run(pairs, questions, SAMPLE_MISSION, "de", chats::get, settings, out, sources());

        JsonObject right = result.getAsJsonArray("modelle").get(0).getAsJsonObject();
        assertEquals("richtig", right.get("modell").getAsString());
        assertEquals(11, right.get("paare").getAsInt());
        assertEquals(10, right.get("gefragt").getAsInt());
        assertEquals(1, right.get("wiederverwendet").getAsInt());
        assertEquals(10, right.get("aufrufe").getAsInt());
        assertEquals(1.0, right.getAsJsonObject("belegt").get("praezision").getAsDouble(), 1e-9);
        assertEquals(1.0, right.getAsJsonObject("belegt").get("gefunden").getAsDouble(), 1e-9);
        assertEquals(1.0, right.getAsJsonObject("hinweis").get("praezision").getAsDouble(), 1e-9);
        assertEquals("2 von 2", right.getAsJsonObject("breite").get("text").getAsString());
        assertEquals(3, right.getAsJsonObject("fragenStatus").get("uebereinstimmungMitDatei").getAsInt());
        assertTrue(right.get("bestanden").getAsBoolean());

        JsonObject flawed = result.getAsJsonArray("modelle").get(1).getAsJsonObject();
        JsonObject said = flawed.getAsJsonObject("matrixModell");
        assertEquals(1, said.getAsJsonObject("KEIN").get("BELEGT").getAsInt());
        JsonObject held = flawed.getAsJsonObject("matrixNachPruefung");
        assertEquals(0, held.getAsJsonObject("KEIN").get("BELEGT").getAsInt());
        assertEquals(1, held.getAsJsonObject("KEIN").get("HINWEIS").getAsInt());
        assertEquals(1, held.getAsJsonObject("BELEGT").get("FEHLER").getAsInt());
        JsonObject lowered = flawed.getAsJsonObject("herabgestuft");
        assertEquals(1, lowered.get("zitat").getAsInt());
        assertEquals(2, lowered.get("zahl").getAsInt());
        assertEquals(2, lowered.get("davonReferenzBelegt").getAsInt());
        assertEquals(0.8, flawed.getAsJsonObject("belegt").get("praezision").getAsDouble(), 1e-9);
        assertEquals(1, flawed.get("ohneUrteil").getAsInt());
        assertFalse(flawed.get("bestanden").getAsBoolean());
        assertTrue(flawed.get("befund").getAsString().contains("K1"));
        assertTrue(flawed.get("befund").getAsString().contains("K3"));

        JsonObject comparison = result.getAsJsonArray("vergleich").get(0).getAsJsonObject();
        assertEquals("fehlerhaft", comparison.get("modell").getAsString());
        assertFalse(comparison.get("nichtSchlechter").getAsBoolean());
        JsonObject verdict = result.getAsJsonObject("ergebnis");
        assertEquals("richtig", verdict.get("entscheidet").getAsString());
        assertTrue(verdict.get("bestanden").getAsBoolean());

        String written = new String(Files.readAllBytes(new File(out, "result.json").toPath()), StandardCharsets.UTF_8);
        for (StockPair pair : pairs) {
            assertFalse("no passage text in result.json", written.contains(pair.text.substring(0, 30)));
        }
        List<String> lines = Files.readAllLines(new File(out, "fehlerhaft/urteile.jsonl").toPath(),
                StandardCharsets.UTF_8);
        assertEquals(11, lines.size());
        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("V1", first.get("frage").getAsString());
        assertEquals("HINWEIS", first.get("urteil").getAsString());
        assertEquals("BELEGT", first.get("modell").getAsString());
        assertEquals("zahl", first.get("herabgestuft").getAsString());
    }

    @Test
    public void theMissionComesFromTheCorpusWithoutReadingItsPassages() throws IOException {
        String corpus = "{\"title\":\"T\",\"passages\":[{\"id\":\"p1\",\"text\":\"lang\"}],\"dim\":3,"
                + "\"frameVector\":[0.1,0.2],\"mission\":\"Ein Buch über Kreuzungen.\",\"language\":\"de\"}";
        String[] mission = missionOf(new StringReader(corpus));
        assertEquals("Ein Buch über Kreuzungen.", mission[0]);
        assertEquals("de", mission[1]);
        try {
            missionOf(new StringReader("{\"passages\":[]}"));
            throw new AssertionError("a corpus without mission must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("mission"));
        }
        try {
            readPairs(new BufferedReader(new StringReader(samplePairs() + samplePairs().split("\n")[0] + "\n")));
            throw new AssertionError("a pair twice must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("twice"));
        }
    }

    @Test
    public void storedStock() throws Exception {
        String chatUrl = System.getProperty(P + "chatUrl", "");
        Assume.assumeTrue("evidence judge stock test skipped (no -D" + P + "chatUrl)", !chatUrl.isEmpty());
        String pairsPath = required("pairs");
        String corpusPath = System.getProperty(P + "corpus", "");
        String missionPath = System.getProperty(P + "missionFile", "");
        if (corpusPath.isEmpty() == missionPath.isEmpty()) {
            throw new IllegalArgumentException("the evidence judge stock test needs either -D" + P + "corpus or -D"
                    + P + "missionFile");
        }
        String questionsPath = System.getProperty(P + "questions", "");
        File out = new File(System.getProperty(P + "out", "build/component-stock/evidence-judge"));
        Files.createDirectories(out.toPath());
        Settings settings = new Settings();
        settings.models = list(System.getProperty(P + "chatModel", "gemma4:12b"));
        settings.parallel = Integer.getInteger(P + "parallel", settings.parallel);
        settings.passageChars = Integer.getInteger(P + "passageChars", settings.passageChars);
        settings.limit = Integer.getInteger(P + "limit", 0);
        settings.onlyQuestions.addAll(list(System.getProperty(P + "nurFragen", "")));
        settings.minPrecision = number("minPraezision", settings.minPrecision);
        String breadth = System.getProperty(P + "minBreite", "");
        settings.minBreadth = breadth.isEmpty() ? null : Integer.valueOf(breadth);
        settings.maxUnjudged = number("maxOhneUrteil", settings.maxUnjudged);
        String found = System.getProperty(P + "minGefunden", "");
        settings.minFound = found.isEmpty() ? null : Double.valueOf(found);
        String noneAsDirect = System.getProperty(P + "maxKeinAlsBelegt", "");
        settings.maxNoneAsDirect = noneAsDirect.isEmpty() ? null : Double.valueOf(noneAsDirect);
        settings.tolerance = number("toleranz", settings.tolerance);
        settings.check();
        String numCtx = System.getProperty(P + "numCtx", "");
        boolean think = !"omit".equalsIgnoreCase(System.getProperty(P + "think", "false"));
        int timeout = 1000 * Integer.getInteger(P + "timeoutSeconds", 120);

        List<StockPair> pairs;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(pairsPath),
                StandardCharsets.UTF_8))) {
            pairs = readPairs(in);
        }
        String[] mission;
        if (corpusPath.isEmpty()) {
            mission = new String[]{read(missionPath).trim(), "de"};
        } else {
            try (Reader in = new InputStreamReader(new FileInputStream(corpusPath), StandardCharsets.UTF_8)) {
                mission = missionOf(in);
            }
        }
        Questions questions = readQuestions(questionsPath.isEmpty() ? resource(QUESTIONS) : read(questionsPath));
        JsonObject sources = new JsonObject();
        sources.addProperty("paare", pairsPath);
        sources.addProperty("auftrag", corpusPath.isEmpty() ? missionPath : corpusPath);
        sources.addProperty("fragen", questionsPath.isEmpty() ? "Repo-Kopie fragen-1711.json" : questionsPath);
        sources.addProperty("chatUrl", chatUrl);
        sources.addProperty("aufrufOptionen", (numCtx.isEmpty() ? "" : "num_ctx " + numCtx + ", ")
                + (think ? "think:false" : "think weggelassen") + ", Zeitlimit " + timeout / 1000 + " s");
        System.out.println("[evidence-judge] " + pairs.size() + " pairs, " + questions.texts.size() + " questions, "
                + "models " + settings.models + " at " + chatUrl + ", " + settings.parallel + " at a time");

        Function<String, MainModelChat> chats = model -> new OllamaStockChat(chatUrl, model,
                numCtx.isEmpty() ? null : Integer.valueOf(numCtx), think, timeout);
        JsonObject result = run(pairs, questions, mission[0], mission[1], chats, settings, out, sources);
        System.out.println("[evidence-judge] result " + new File(out, "result.json").getAbsolutePath());
        JsonObject verdict = result.getAsJsonObject("ergebnis");
        assertTrue(verdict.get("befund").getAsString(), verdict.get("bestanden").getAsBoolean());
    }

    // ------------------------------------------------------------------------------------------------- run

    /** Judges the pairs with each model, writes {@code result.json} and the verdict logs, returns the result. */
    static JsonObject run(List<StockPair> all, Questions questions, String mission, String language,
                          Function<String, MainModelChat> chats, Settings settings, File out, JsonObject sources)
            throws IOException {
        settings.check();
        List<StockPair> pairs = new ArrayList<StockPair>();
        for (StockPair pair : all) {
            if (!questions.texts.containsKey(pair.question)) {
                throw new IllegalArgumentException("pair " + pair.passage + " names an unknown question "
                        + pair.question);
            }
            if (settings.onlyQuestions.isEmpty() || settings.onlyQuestions.contains(pair.question)) {
                pairs.add(pair);
            }
        }
        if (settings.limit > 0 && pairs.size() > settings.limit) {
            pairs = new ArrayList<StockPair>(pairs.subList(0, settings.limit));
        }
        List<Pair> asked = new ArrayList<Pair>();
        for (StockPair pair : pairs) {
            asked.add(new Pair(new Question(pair.question, questions.texts.get(pair.question)),
                    new Passage(pair.passage, pair.source, pair.domain.isEmpty() ? "" : "https://" + pair.domain + "/",
                            pair.domain, pair.text)));
        }

        JsonObject result = new JsonObject();
        result.addProperty("komponente", "6 Belegprüfer");
        result.addProperty("erstellt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
                .format(new Date()));
        result.add("quellen", sources == null ? new JsonObject() : sources);
        result.addProperty("bestand", questions.stock);
        JsonObject task = new JsonObject();
        task.addProperty("zeichen", mission.length());
        task.addProperty("sprache", language);
        JsonArray warnings = new JsonArray();
        if (mission.trim().isEmpty()) {
            warnings.add("Der Auftrag ist leer.");
        }
        if (MOJIBAKE.matcher(mission).find()) {
            warnings.add("Der Auftrag sieht beschädigt aus (UTF-8 als Latin-1 gelesen).");
        }
        result.add("auftrag", task);
        result.add("einstellungen", settingsJson(settings, pairs.size(), all.size()));
        result.add("referenz", referenceJson(pairs, questions));

        JsonArray models = new JsonArray();
        for (String model : settings.models) {
            TimedChat chat = new TimedChat(chats.apply(model));
            EvidenceJudge judge = new EvidenceJudge(chat, mission, language,
                    new Options(settings.parallel, settings.passageChars),
                    line -> System.out.println("[evidence-judge] " + model + ": " + line));
            long start = System.nanoTime();
            List<Outcome> outcomes = judge.judgeAll(asked, Collections.<EvidenceVerdict>emptyList(), 1);
            long wall = System.nanoTime() - start;
            File dir = new File(out, model.replaceAll("[^A-Za-z0-9._-]", "_"));
            Files.createDirectories(dir.toPath());
            writeVerdicts(new File(dir, "urteile.jsonl"), pairs, outcomes, settings.passageChars);
            JsonObject measured = measure(pairs, outcomes, questions, settings);
            JsonObject block = new JsonObject();
            block.addProperty("modell", model);
            for (Map.Entry<String, JsonElement> entry : measured.entrySet()) {
                block.add(entry.getKey(), entry.getValue());
            }
            block.addProperty("wanduhrSekunden", Math.round(wall / 1e8) / 10.0);
            block.addProperty("modellzeitSekunden", Math.round(chat.nanos.get() / 1e8) / 10.0);
            block.addProperty("urteile", new File(dir, "urteile.jsonl").getPath());
            models.add(block);
            System.out.println("[evidence-judge] " + model + ": " + block.get("befund").getAsString());
        }
        result.add("modelle", models);

        JsonArray comparisons = new JsonArray();
        JsonObject first = models.get(0).getAsJsonObject();
        for (int i = 1; i < models.size(); i++) {
            comparisons.add(compare(first, models.get(i).getAsJsonObject(), settings.tolerance));
        }
        result.add("vergleich", comparisons);
        result.add("warnungen", warnings);
        JsonObject verdict = new JsonObject();
        verdict.addProperty("entscheidet", first.get("modell").getAsString());
        verdict.addProperty("bestanden", first.get("bestanden").getAsBoolean());
        verdict.addProperty("befund", first.get("modell").getAsString() + ": " + first.get("befund").getAsString());
        result.add("ergebnis", verdict);
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(result).getBytes(StandardCharsets.UTF_8));
        return result;
    }

    /** The measures of one model; ids and counts only. */
    static JsonObject measure(List<StockPair> pairs, List<Outcome> outcomes, Questions questions,
                              Settings settings) throws IOException {
        Map<Kind, Map<Kind, Integer>> said = matrix();
        Map<Kind, Map<Kind, Integer>> held = matrix();
        Map<String, int[]> perQuestion = new TreeMap<String, int[]>();
        Map<String, int[]> perOrigin = new TreeMap<String, int[]>();
        Map<String, Integer> failures = new TreeMap<String, Integer>();
        Set<String> breadth = new TreeSet<String>();
        List<EvidenceVerdict> verdicts = new ArrayList<EvidenceVerdict>();
        JsonArray single = new JsonArray();
        int asked = 0;
        int reused = 0;
        int calls = 0;
        int byQuote = 0;
        int byNumber = 0;
        int loweredDirect = 0;
        for (int i = 0; i < pairs.size(); i++) {
            StockPair pair = pairs.get(i);
            Outcome outcome = outcomes.get(i);
            EvidenceVerdict verdict = outcome.getVerdict();
            Kind finalKind = verdict.getKind();
            said.get(pair.reference).merge(verdict.getJudgedKind(), 1, Integer::sum);
            held.get(pair.reference).merge(finalKind, 1, Integer::sum);
            if (outcome.isReused()) {
                reused++;
            } else {
                asked++;
                calls += outcome.getAnswers().size();
            }
            String cause = loweredBy(verdict, pair.text, settings.passageChars);
            if ("zitat".equals(cause)) {
                byQuote++;
            } else if ("zahl".equals(cause)) {
                byNumber++;
            }
            if (!cause.isEmpty() && pair.reference == Kind.BELEGT) {
                loweredDirect++;
            }
            if (finalKind == Kind.FEHLER) {
                String reason = verdict.getReason();
                failures.merge(reason.length() > 120 ? reason.substring(0, 120) + " …" : reason, 1, Integer::sum);
            }
            boolean hit = finalKind == Kind.BELEGT && pair.reference == Kind.BELEGT;
            if (hit && questions.answerable.contains(pair.question)) {
                breadth.add(pair.question);
            }
            count(perQuestion, pair.question, pair.reference, finalKind);
            count(perOrigin, pair.origin.isEmpty() ? "ohne Angabe" : pair.origin, pair.reference, finalKind);
            if (finalKind != Kind.FEHLER) {
                verdicts.add(verdict);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("frage", pair.question);
            entry.addProperty("passage", pair.passage);
            entry.addProperty("referenz", pair.reference.name());
            entry.addProperty("modell", verdict.getJudgedKind().name());
            entry.addProperty("urteil", finalKind.name());
            if (!cause.isEmpty()) {
                entry.addProperty("herabgestuft", cause);
            }
            if (outcome.isReused()) {
                entry.addProperty("wiederverwendetVon", verdict.getPassageId());
            }
            single.add(entry);
        }

        int n = pairs.size();
        int direct = column(held, Kind.BELEGT);
        int directRight = held.get(Kind.BELEGT).get(Kind.BELEGT);
        int directMild = directRight + held.get(Kind.HINWEIS).get(Kind.BELEGT);
        int referenceDirect = row(held, Kind.BELEGT);
        int referenceDirectKept = directRight + held.get(Kind.BELEGT).get(Kind.HINWEIS);
        int lead = column(held, Kind.HINWEIS);
        int leadRight = held.get(Kind.HINWEIS).get(Kind.HINWEIS);
        int referenceLead = row(held, Kind.HINWEIS);
        int referenceNone = row(held, Kind.KEIN);
        int noneAsDirect = held.get(Kind.KEIN).get(Kind.BELEGT);
        int unjudged = column(held, Kind.FEHLER);

        JsonObject out = new JsonObject();
        out.addProperty("paare", n);
        out.addProperty("gefragt", asked);
        out.addProperty("wiederverwendet", reused);
        out.addProperty("aufrufe", calls);
        out.add("matrixModell", matrixJson(said));
        out.add("matrixNachPruefung", matrixJson(held));
        JsonObject directJson = new JsonObject();
        directJson.addProperty("urteile", direct);
        directJson.addProperty("davonReferenzBelegt", directRight);
        directJson.addProperty("praezision", share(directRight, direct));
        directJson.addProperty("praezisionMild", share(directMild, direct));
        directJson.addProperty("referenz", referenceDirect);
        directJson.addProperty("gefunden", share(directRight, referenceDirect));
        directJson.addProperty("nichtVerloren", share(referenceDirectKept, referenceDirect));
        directJson.addProperty("praezisionText", directRight + " von " + direct);
        out.add("belegt", directJson);
        JsonObject leadJson = new JsonObject();
        leadJson.addProperty("urteile", lead);
        leadJson.addProperty("davonReferenzHinweis", leadRight);
        leadJson.addProperty("davonReferenzBelegt", held.get(Kind.BELEGT).get(Kind.HINWEIS));
        leadJson.addProperty("praezision", share(leadRight, lead));
        leadJson.addProperty("referenz", referenceLead);
        leadJson.addProperty("gefunden", share(leadRight, referenceLead));
        out.add("hinweis", leadJson);
        out.addProperty("keinAlsBelegt", noneAsDirect);
        out.addProperty("keinAlsBelegtAnteil", share(noneAsDirect, referenceNone));
        out.addProperty("ohneUrteil", unjudged);
        out.addProperty("ohneUrteilAnteil", share(unjudged, n));
        JsonObject failureJson = new JsonObject();
        for (Map.Entry<String, Integer> failure : failures.entrySet()) {
            failureJson.addProperty(failure.getKey(), failure.getValue());
        }
        out.add("fehlerGruende", failureJson);
        JsonObject lowered = new JsonObject();
        lowered.addProperty("zitat", byQuote);
        lowered.addProperty("zahl", byNumber);
        lowered.addProperty("davonReferenzBelegt", loweredDirect);
        out.add("herabgestuft", lowered);

        Set<String> tested = new TreeSet<String>();
        for (StockPair pair : pairs) {
            tested.add(pair.question);
        }
        Set<String> answerableTested = new TreeSet<String>(tested);
        answerableTested.retainAll(questions.answerable);
        JsonObject breadthJson = new JsonObject();
        breadthJson.addProperty("fragen", breadth.size());
        breadthJson.addProperty("von", answerableTested.size());
        breadthJson.addProperty("text", breadth.size() + " von " + answerableTested.size());
        JsonArray missing = new JsonArray();
        for (String id : answerableTested) {
            if (!breadth.contains(id)) {
                missing.add(id);
            }
        }
        breadthJson.add("ohneTreffer", missing);
        out.add("breite", breadthJson);
        out.add("fragenStatus", statusJson(questions, tested, verdicts));
        out.add("proFrage", countsJson(perQuestion));
        out.add("proHerkunft", countsJson(perOrigin));

        JsonObject criteria = new JsonObject();
        List<String> failed = new ArrayList<String>();
        criterion(criteria, failed, "K1 Präzision BELEGT", share(directRight, direct), settings.minPrecision, true,
                directRight + " von " + direct);
        int needed = settings.minBreadth == null ? answerableTested.size() : settings.minBreadth;
        criterion(criteria, failed, "K2 Breite", breadth.size(), needed, true, breadth.size() + " von "
                + answerableTested.size() + " beantwortbaren Fragen");
        criterion(criteria, failed, "K3 ohne Urteil", share(unjudged, n), settings.maxUnjudged, false,
                unjudged + " von " + n);
        if (settings.minFound != null) {
            criterion(criteria, failed, "K4 gefunden", share(directRight, referenceDirect), settings.minFound, true,
                    directRight + " von " + referenceDirect);
        }
        if (settings.maxNoneAsDirect != null) {
            criterion(criteria, failed, "K5 KEIN als BELEGT", share(noneAsDirect, referenceNone),
                    settings.maxNoneAsDirect, false, noneAsDirect + " von " + referenceNone);
        }
        out.add("kriterien", criteria);
        out.addProperty("bestanden", failed.isEmpty());
        out.addProperty("befund", (failed.isEmpty() ? "bestanden" : "nicht bestanden: " + String.join(", ", failed))
                + "; BELEGT " + directRight + " von " + direct + " richtig, " + directRight + " von " + referenceDirect
                + " gefunden, Breite " + breadth.size() + " von " + answerableTested.size() + ", ohne Urteil "
                + unjudged);
        out.add("paareEinzeln", single);
        return out;
    }

    /** "zitat" when the check lowered a BELEGT for its quote, "zahl" for a number, "" when it lowered nothing. */
    static String loweredBy(EvidenceVerdict verdict, String text, int passageChars) {
        if (!verdict.isDowngraded()) {
            return "";
        }
        return PassageText.holdsQuote(EvidenceJudge.read(text, passageChars), verdict.getQuote()) ? "zahl" : "zitat";
    }

    /** The status the research memory derives: 3 texts from 2 source groups (the rule of the file). */
    static JsonObject statusJson(Questions questions, Set<String> tested, List<EvidenceVerdict> verdicts)
            throws IOException {
        Map<String, int[]> counts = memoryCounts(questions, verdicts);
        JsonObject out = new JsonObject();
        JsonObject each = new JsonObject();
        int agree = 0;
        for (String id : tested) {
            int[] c = counts.get(id);
            boolean answered = c[0] >= 3 && c[1] >= 2;
            boolean file = questions.answerable.contains(id);
            agree += answered == file ? 1 : 0;
            JsonObject entry = new JsonObject();
            entry.addProperty("belegeTexte", c[0]);
            entry.addProperty("quellengruppen", c[1]);
            entry.addProperty("beantwortbar", answered);
            entry.addProperty("datei", file);
            each.add(id, entry);
        }
        out.addProperty("uebereinstimmungMitDatei", agree);
        out.addProperty("von", tested.size());
        out.add("fragen", each);
        return out;
    }

    static Map<String, int[]> memoryCounts(Questions questions, List<EvidenceVerdict> verdicts) throws IOException {
        ResearchMemory memory = new ResearchMemory();
        int index = 0;
        for (Map.Entry<String, String> topic : questions.topicNames.entrySet()) {
            memory.addTopic(ResearchTopic.ofCard("T" + topic.getKey(), topic.getValue(), "anchor-" + topic.getKey(),
                    index++, 1L));
        }
        for (Map.Entry<String, String> question : questions.texts.entrySet()) {
            memory.addQuestion(ResearchQuestion.open(question.getKey(), question.getValue(),
                    "T" + questions.topicOf.get(question.getKey()), ResearchQuestion.Origin.KONZEPT, 1L));
        }
        for (EvidenceVerdict verdict : verdicts) {
            memory.record(verdict);
        }
        Map<String, int[]> counts = new HashMap<String, int[]>();
        for (String id : questions.texts.keySet()) {
            QuestionEvidence evidence = memory.evidenceOf(id);
            counts.put(id, new int[]{evidence.getDirectCount(), evidence.getIndependentSources()});
        }
        return counts;
    }

    /** The reference in numbers, and the status the memory derives from it (does it reproduce the file?). */
    static JsonObject referenceJson(List<StockPair> pairs, Questions questions) throws IOException {
        Map<Kind, Integer> kinds = new EnumMap<Kind, Integer>(Kind.class);
        List<EvidenceVerdict> verdicts = new ArrayList<EvidenceVerdict>();
        Set<String> tested = new TreeSet<String>();
        int lowered = 0;
        for (StockPair pair : pairs) {
            kinds.merge(pair.reference, 1, Integer::sum);
            tested.add(pair.question);
            // the reference needs no quote check: its quote is the longest part of the text between ellipses
            EvidenceVerdict verdict = EvidenceVerdict.judged(pair.question, pair.passage, pair.source,
                    pair.domain.isEmpty() ? "" : "https://" + pair.domain + "/", pair.text, pair.reference,
                    referenceQuote(pair.text), "", "Referenz", 1, 0L);
            lowered += verdict.isDowngraded() ? 1 : 0;
            verdicts.add(verdict);
        }
        JsonObject out = new JsonObject();
        for (Kind kind : REFERENCE_KINDS) {
            out.addProperty(kind.name(), kinds.getOrDefault(kind, 0));
        }
        out.addProperty("belegtOhneHaltbaresZitat", lowered);
        out.add("fragenStatus", statusJson(questions, tested, verdicts));
        return out;
    }

    /** The longest part of a text between ellipses (the whole text when it has none): a quote that holds. */
    static String referenceQuote(String text) {
        String best = "";
        for (String part : text.split("\\.\\.\\.|\u2026")) {
            if (part.trim().length() > best.length()) {
                best = part.trim();
            }
        }
        return best;
    }

    /** The criteria of a later model against the first: worse by more than the tolerance is worse. */
    static JsonObject compare(JsonObject first, JsonObject other, double tolerance) {
        JsonObject out = new JsonObject();
        out.addProperty("modell", other.get("modell").getAsString());
        out.addProperty("gegen", first.get("modell").getAsString());
        out.addProperty("toleranz", tolerance);
        JsonObject values = new JsonObject();
        boolean notWorse = true;
        JsonObject mine = first.getAsJsonObject("kriterien");
        JsonObject theirs = other.getAsJsonObject("kriterien");
        for (String name : mine.keySet()) {
            JsonObject a = mine.getAsJsonObject(name);
            JsonObject b = theirs.getAsJsonObject(name);
            double reference = a.get("wert").getAsDouble();
            double value = b.get("wert").getAsDouble();
            boolean higherIsBetter = a.get("mindestens").getAsBoolean();
            double slack = name.startsWith("K2") ? 0 : tolerance;
            boolean ok = higherIsBetter ? value >= reference - slack - 1e-9 : value <= reference + slack + 1e-9;
            JsonObject entry = new JsonObject();
            entry.addProperty(first.get("modell").getAsString(), reference);
            entry.addProperty(other.get("modell").getAsString(), value);
            entry.addProperty("nichtSchlechter", ok);
            values.add(name, entry);
            notWorse &= ok;
        }
        out.add("kriterien", values);
        out.addProperty("nichtSchlechter", notWorse);
        return out;
    }

    // ------------------------------------------------------------------------------------------------- output

    private static void writeVerdicts(File file, List<StockPair> pairs, List<Outcome> outcomes, int passageChars)
            throws IOException {
        try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < pairs.size(); i++) {
                StockPair pair = pairs.get(i);
                Outcome outcome = outcomes.get(i);
                EvidenceVerdict verdict = outcome.getVerdict();
                JsonObject line = new JsonObject();
                line.addProperty("frage", pair.question);
                line.addProperty("passage", pair.passage);
                line.addProperty("quelle", pair.source);
                line.addProperty("domain", pair.domain);
                line.addProperty("herkunft", pair.origin);
                line.addProperty("referenz", pair.reference.name());
                line.addProperty("modell", verdict.getJudgedKind().name());
                line.addProperty("urteil", verdict.getKind().name());
                String cause = loweredBy(verdict, pair.text, passageChars);
                if (!cause.isEmpty()) {
                    line.addProperty("herabgestuft", cause);
                }
                line.addProperty("zitat", verdict.getQuote());
                line.addProperty("aussage", verdict.getReason());
                if (outcome.isReused()) {
                    line.addProperty("wiederverwendetVon", verdict.getPassageId());
                }
                line.addProperty("ms", outcome.getMillis());
                JsonArray answers = new JsonArray();
                for (String answer : outcome.getAnswers()) {
                    answers.add(answer);
                }
                line.add("antworten", answers);
                out.write(line.toString());
                out.write('\n');
            }
        }
    }

    private static JsonObject settingsJson(Settings settings, int tested, int inFile) {
        JsonObject out = new JsonObject();
        JsonArray models = new JsonArray();
        for (String model : settings.models) {
            models.add(model);
        }
        out.add("modelle", models);
        out.addProperty("gleichzeitig", settings.parallel);
        out.addProperty("passagenZeichen", settings.passageChars);
        out.addProperty("paareGeprueft", tested);
        out.addProperty("paareInDatei", inFile);
        out.addProperty("limit", settings.limit);
        JsonArray only = new JsonArray();
        for (String id : settings.onlyQuestions) {
            only.add(id);
        }
        out.add("nurFragen", only);
        JsonObject thresholds = new JsonObject();
        thresholds.addProperty("minPraezision", settings.minPrecision);
        thresholds.addProperty("minBreite", settings.minBreadth == null ? "alle beantwortbaren"
                : String.valueOf(settings.minBreadth));
        thresholds.addProperty("maxOhneUrteil", settings.maxUnjudged);
        thresholds.addProperty("minGefunden", settings.minFound == null ? "nicht gesetzt"
                : String.valueOf(settings.minFound));
        thresholds.addProperty("maxKeinAlsBelegt", settings.maxNoneAsDirect == null ? "nicht gesetzt"
                : String.valueOf(settings.maxNoneAsDirect));
        thresholds.addProperty("toleranz", settings.tolerance);
        out.add("schwellen", thresholds);
        return out;
    }

    private static void criterion(JsonObject criteria, List<String> failed, String name, double value,
                                  double threshold, boolean atLeast, String text) {
        boolean ok = atLeast ? value >= threshold - 1e-9 : value <= threshold + 1e-9;
        JsonObject entry = new JsonObject();
        entry.addProperty("wert", value);
        entry.addProperty("schwelle", threshold);
        entry.addProperty("mindestens", atLeast);
        entry.addProperty("text", text);
        entry.addProperty("erfuellt", ok);
        criteria.add(name, entry);
        if (!ok) {
            failed.add(name + " (" + text + ")");
        }
    }

    private static Map<Kind, Map<Kind, Integer>> matrix() {
        Map<Kind, Map<Kind, Integer>> matrix = new EnumMap<Kind, Map<Kind, Integer>>(Kind.class);
        for (Kind reference : REFERENCE_KINDS) {
            Map<Kind, Integer> row = new EnumMap<Kind, Integer>(Kind.class);
            for (Kind kind : Kind.values()) {
                row.put(kind, 0);
            }
            matrix.put(reference, row);
        }
        return matrix;
    }

    private static JsonObject matrixJson(Map<Kind, Map<Kind, Integer>> matrix) {
        JsonObject out = new JsonObject();
        for (Map.Entry<Kind, Map<Kind, Integer>> row : matrix.entrySet()) {
            JsonObject cells = new JsonObject();
            for (Map.Entry<Kind, Integer> cell : row.getValue().entrySet()) {
                cells.addProperty(cell.getKey().name(), cell.getValue());
            }
            out.add(row.getKey().name(), cells);
        }
        return out;
    }

    private static int row(Map<Kind, Map<Kind, Integer>> matrix, Kind reference) {
        int sum = 0;
        for (int n : matrix.get(reference).values()) {
            sum += n;
        }
        return sum;
    }

    private static int column(Map<Kind, Map<Kind, Integer>> matrix, Kind judged) {
        int sum = 0;
        for (Map<Kind, Integer> row : matrix.values()) {
            sum += row.get(judged);
        }
        return sum;
    }

    /** Per key: pairs, reference BELEGT, final BELEGT, both, reference KEIN judged BELEGT. */
    private static void count(Map<String, int[]> counts, String key, Kind reference, Kind judged) {
        int[] c = counts.computeIfAbsent(key, k -> new int[5]);
        c[0]++;
        c[1] += reference == Kind.BELEGT ? 1 : 0;
        c[2] += judged == Kind.BELEGT ? 1 : 0;
        c[3] += reference == Kind.BELEGT && judged == Kind.BELEGT ? 1 : 0;
        c[4] += reference == Kind.KEIN && judged == Kind.BELEGT ? 1 : 0;
    }

    private static JsonObject countsJson(Map<String, int[]> counts) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            int[] c = entry.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("paare", c[0]);
            json.addProperty("referenzBelegt", c[1]);
            json.addProperty("urteilBelegt", c[2]);
            json.addProperty("treffer", c[3]);
            json.addProperty("keinAlsBelegt", c[4]);
            out.add(entry.getKey(), json);
        }
        return out;
    }

    private static double share(int part, int whole) {
        return whole == 0 ? 0.0 : Math.round(10000.0 * part / whole) / 10000.0;
    }

    // ------------------------------------------------------------------------------------------------- input

    /** The judged pairs, one JSON object per line; a pair of question and passage may appear once. */
    static List<StockPair> readPairs(BufferedReader in) throws IOException {
        List<StockPair> pairs = new ArrayList<StockPair>();
        Set<String> seen = new HashSet<String>();
        String line;
        int number = 0;
        while ((line = in.readLine()) != null) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String reference = text(o, "urteil");
            Kind kind;
            try {
                kind = Kind.valueOf(reference);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("line " + number + ": unknown verdict " + reference);
            }
            if (kind == Kind.FEHLER) {
                throw new IllegalArgumentException("line " + number + ": the reference holds no FEHLER");
            }
            StockPair pair = new StockPair(text(o, "frage"), text(o, "passage"), text(o, "quelle"), text(o, "domain"),
                    o.has("text") && o.get("text").isJsonPrimitive() ? o.get("text").getAsString() : "", kind,
                    text(o, "herkunft"));
            if (pair.question.isEmpty() || pair.passage.isEmpty()) {
                throw new IllegalArgumentException("line " + number + ": a pair needs \"frage\" and \"passage\"");
            }
            if (!seen.add(pair.question + "\n" + pair.passage)) {
                throw new IllegalArgumentException("line " + number + ": pair " + pair.question + " / "
                        + pair.passage + " twice");
            }
            pairs.add(pair);
        }
        return pairs;
    }

    static Questions readQuestions(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Questions questions = new Questions();
        questions.stock = text(root, "bestand");
        JsonObject topics = root.getAsJsonObject("themen");
        if (topics != null) {
            for (Map.Entry<String, JsonElement> topic : topics.entrySet()) {
                questions.topicNames.put(topic.getKey(), topic.getValue().getAsString());
            }
        }
        for (JsonElement element : root.getAsJsonArray("fragen")) {
            JsonObject q = element.getAsJsonObject();
            String id = text(q, "id");
            String topic = q.has("thema") ? q.get("thema").getAsString() : "";
            if (!questions.topicNames.containsKey(topic)) {
                questions.topicNames.put(topic, "Thema " + topic);
            }
            questions.texts.put(id, text(q, "text"));
            questions.topicOf.put(id, topic);
            if (q.has("beantwortbar") && q.get("beantwortbar").getAsBoolean()) {
                questions.answerable.add(id);
            }
        }
        return questions;
    }

    /** "mission" and "language" of a corpus in the format of the outline replay; the passages are skipped. */
    static String[] missionOf(Reader in) throws IOException {
        JsonReader reader = new JsonReader(in);
        String mission = null;
        String language = "de";
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("mission".equals(name) && reader.peek() == JsonToken.STRING) {
                mission = reader.nextString();
            } else if ("language".equals(name) && reader.peek() == JsonToken.STRING) {
                language = reader.nextString();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        if (mission == null) {
            throw new IllegalArgumentException("the corpus holds no \"mission\"");
        }
        return new String[]{mission, language};
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    private static String required(String name) {
        String value = System.getProperty(P + name, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("the evidence judge stock test needs -D" + P + name);
        }
        return value;
    }

    private static double number(String name, double fallback) {
        String value = System.getProperty(P + name, "");
        return value.isEmpty() ? fallback : Double.parseDouble(value);
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<String>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = EvidenceJudgeStockTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------------------------------------- sample

    static final String SAMPLE_MISSION = "Ein Sachbuch für Stadtplaner über Verkehrssicherheit in Innenstädten.";

    static final String SAMPLE_QUESTIONS = "{\"bestand\":\"Beispiel\",\"themen\":{\"1\":\"Wirkung\",\"2\":\"Betrieb\"},"
            + "\"fragen\":[{\"id\":\"V1\",\"thema\":1,\"text\":\"Wie wirkt Tempo 30 auf schwere Unfälle?\","
            + "\"beantwortbar\":true},{\"id\":\"V2\",\"thema\":1,\"text\":\"Was kosten neue Verkehrsschilder?\","
            + "\"beantwortbar\":true},{\"id\":\"V3\",\"thema\":2,"
            + "\"text\":\"Wie reagieren Lieferdienste auf Tempo 30?\","
            + "\"beantwortbar\":false}]}";

    private static final String T1 = "Nach Einführung von Tempo 30 sank die Zahl der schweren Unfälle im ersten Jahr "
            + "um 30 Prozent.";
    private static final String T2 = "Auf den umgestellten Straßen ereigneten sich deutlich weniger Unfälle mit "
            + "Schwerverletzten als auf den Vergleichsstraßen.";
    private static final String T3 = "Die Polizei meldete nach der Umstellung weniger schwere Zusammenstöße an "
            + "Kreuzungen der Innenstadt.";
    private static final String T4 = "Die Parkgebühren in der Innenstadt steigen im kommenden Jahr auf zwei Euro je "
            + "Stunde.";
    private static final String T5 = "Ein neues Verkehrsschild kostet die Stadt einschließlich Montage etwa 250 Euro.";
    private static final String T6 = "Der Austausch der Schilder wurde aus dem allgemeinen Haushalt bezahlt.";
    private static final String T7 = "Für die Umstellung von 400 Straßen veranschlagte das Tiefbauamt Kosten von "
            + "100.000 Euro.";
    private static final String T8 = "Die Beschilderung einer Tempo-30-Zone kostet nach Angaben des Amtes rund 2.000 "
            + "Euro.";

    private static String samplePairs() {
        String[][] rows = {
                {"V1", "a1", "s1", "stadt-a.example", T1, "BELEGT"},
                {"V1", "a2", "s2", "stadt-b.example", T2, "BELEGT"},
                {"V1", "a3", "s3", "polizei.example", T3, "BELEGT"},
                {"V1", "a4", "s4", "parken.example", T4, "KEIN"},
                {"V1", "a5", "s9", "kopie.example", T1, "BELEGT"},
                {"V2", "b1", "s5", "amt.example", T5, "BELEGT"},
                {"V2", "b2", "s6", "haushalt.example", T6, "HINWEIS"},
                {"V2", "b3", "s7", "tiefbau.example", T7, "BELEGT"},
                {"V2", "b4", "s8", "zone.example", T8, "BELEGT"},
                {"V3", "c1", "s4", "parken.example", T4, "KEIN"},
                {"V3", "c2", "s6", "haushalt.example", T6, "HINWEIS"},
        };
        StringBuilder out = new StringBuilder();
        for (String[] row : rows) {
            JsonObject line = new JsonObject();
            line.addProperty("frage", row[0]);
            line.addProperty("passage", row[1]);
            line.addProperty("quelle", row[2]);
            line.addProperty("domain", row[3]);
            line.addProperty("text", row[4]);
            line.addProperty("urteil", row[5]);
            line.addProperty("herkunft", row[0].equals("V3") ? "ks" : "ks40");
            line.addProperty("gleicherTextIn", 1);
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static JsonObject sources() {
        JsonObject sources = new JsonObject();
        sources.addProperty("paare", "Beispiel");
        return sources;
    }

    /**
     * A model from the reference: the right one answers each pair with the reference (quote: the first 40 characters);
     * the flawed one invents a quote for a4, calls b2 BELEGT, names a number its quote lacks for a1 and fails on b1.
     */
    static final class Scripted implements MainModelChat {
        private final Map<String, StockPair> byPrompt = new HashMap<String, StockPair>();
        private final boolean flawed;

        Scripted(List<StockPair> pairs, boolean flawed) {
            this.flawed = flawed;
            Questions questions = readQuestions(SAMPLE_QUESTIONS);
            for (StockPair pair : pairs) {
                byPrompt.putIfAbsent(questions.texts.get(pair.question) + "\n" + pair.text, pair);
            }
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            String prompt = messages.get(1).getContent();
            String question = between(prompt, "Frage, die das Buch beantworten muss:\n", "\n\n");
            String text = between(prompt, "\"\"\"\n", "\n\"\"\"");
            StockPair pair = byPrompt.get(question + "\n" + text);
            if (pair == null) {
                throw new IllegalStateException("unknown pair in the prompt");
            }
            String quote = pair.text.substring(0, 40);
            if (flawed) {
                switch (pair.passage) {
                    case "a1":
                        return answer(quote, "Schwere Unfälle sanken um 40 Prozent.", "BELEGT");
                    case "a4":
                        return answer("Tempo 30 halbiert die schweren Unfälle", "Es belegt die Wirkung.", "BELEGT");
                    case "b2":
                        return answer(quote, "Die Schilder wurden bezahlt.", "BELEGT");
                    case "b1":
                        return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "Read timed out");
                    default:
                        break;
                }
            }
            return pair.reference == Kind.BELEGT ? answer(quote, "Die Passage belegt es.", "BELEGT")
                    : answer("", "nichts", pair.reference.name());
        }

        @Override
        public String modelName() {
            return flawed ? "fehlerhaft" : "richtig";
        }

        private static String between(String text, String start, String end) {
            int from = text.indexOf(start);
            if (from < 0) {
                return "";
            }
            from += start.length();
            int to = text.indexOf(end, from);
            return to < 0 ? text.substring(from) : text.substring(from, to);
        }

        private static MainModelChatResult answer(String quote, String statement, String urteil) {
            JsonObject json = new JsonObject();
            json.addProperty("zitat", quote);
            json.addProperty("aussage", statement);
            json.addProperty("urteil", urteil);
            return MainModelChatResult.ok(json.toString());
        }
    }
}
