package com.aresstack.askai.research.repeat;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.writer.BookRepeatCheck;
import com.aresstack.askai.research.runtime.writer.RepeatCheck;
import com.aresstack.askai.research.runtime.writer.SameStatementJudge;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Component 12 with the model stage on judged sentence pairs (decision of 21.11.2026, 22:35: the similarity only picks
 * candidates, a small model judges each candidate pair). The architect fixes the targets before each run and passes
 * them as {@code .minFound}, {@code .maxFalse} and {@code .maxUnjudged}. First run on the 60 pairs of the book of
 * 17 Nov ({@code alt\c12b-prereg.json}: at least 20 of 24 GLEICH reported, at most 2 of 32 VERSCHIEDEN, at most 1
 * without a verdict): failed at the boundary GLEICH/TEILWEISE. Second run on 60 fresh pairs
 * ({@code alt\c12c-prereg.json}): TEILWEISE unreliable with both models, GLEICH held with gemma4:12b. Since then only
 * GLEICH reports to the writer and TEILWEISE is a hint; gemma4:12b is the default judge. Every run is counted in two
 * readings, with the targets of c12c as defaults: "nurGleich" (the product): of GLEICH at least {@code .gleichMinFound}
 * (7) reported as GLEICH, of VERSCHIEDEN at most {@code .gleichMaxFalse} (2) as GLEICH; "beide" (a TEILWEISE hint
 * counts as found): of GLEICH and TEILWEISE pairs at least {@code .minFound} (16) found, of VERSCHIEDEN at most
 * {@code .maxFalse} (3), of GLEICH at most {@code .maxSameAsDifferent} (1) with the verdict VERSCHIEDEN. Both allow at
 * most {@code .maxUnjudged} (1) without a verdict; {@code .lesart} (nurGleich) names the reading that decides. Fresh
 * pairs need {@code .url} for their sentence vectors.
 * {@link #samplePairs()} runs the whole path on six self-written pairs with a fake sentence model and a scripted judge
 * in every build; {@link #storedPairs()} needs Ollama and is skipped without {@code .chatUrl}:
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*BookRepeatJudgeStockTest*'
 *   -Daskai.stock.repeat.chatUrl=http://127.0.0.1:11434 -Daskai.stock.repeat.chatModel=gemma4:12b
 *   -Daskai.stock.repeat.vectors=sentence-vectors.json (stored vectors of an earlier run; or .url with .embedModel)
 *   -Daskai.stock.repeat.parallel=4 -Daskai.stock.repeat.runs=2 (two runs one after the other: agreement at 0)
 *   -Daskai.stock.repeat.out=build/component-stock/repeat-judge
 *   optional: .pairs (another file of judged pairs in the same form), .candidateSimilarity (0.70), .maxJudged (3),
 *             .minFound (16), .maxFalse (3), .maxSameAsDifferent (1), .gleichMinFound (7), .gleichMaxFalse (2),
 *             .maxUnjudged (1), .lesart (beide|nurGleich),
 *             .numCtx (only when set), .think=omit (else think:false is sent), .timeoutSeconds (120)
 * </pre>
 * {@code result.json} holds per pair the measures, whether it was a candidate, and per run the model's verdict, its
 * reason, every raw answer and the time; the counts per run for all, odd and even sheets; the agreement of the runs.
 */
public class BookRepeatJudgeStockTest {

    private static final String P = "askai.stock.repeat.";
    /** The reading that counts a TEILWEISE hint as found too. */
    static final String BOTH = "beide";
    /** The reading of the product: only GLEICH reports a repeat to the writer. */
    static final String ONLY_SAME = "nurGleich";

    @Rule
    public TemporaryFolder scratch = new TemporaryFolder();

    /** A judge from a script: the first marker found in the later sentence picks the verdict. */
    static final class Scripted implements MainModelChat {
        final Map<String, String> byMarker = new LinkedHashMap<String, String>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean down;

        Scripted answer(String marker, String word) {
            byMarker.put(marker, word);
            return this;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            if (down) {
                return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "model down");
            }
            String prompt = messages.get(1).getContent();
            int from = prompt.indexOf("Späterer Satz:");
            String later = prompt.substring(from, prompt.indexOf("\n\n", from)); // the sentence, not the rules
            for (Map.Entry<String, String> entry : byMarker.entrySet()) {
                if (later.contains(entry.getKey())) {
                    return MainModelChatResult.ok("{\"grund\":\"Grund " + entry.getKey() + ".\",\"urteil\":\""
                            + entry.getValue() + "\"}");
                }
            }
            return MainModelChatResult.ok("{\"grund\":\"Anderes.\",\"urteil\":\"VERSCHIEDEN\"}");
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }

    /** The settings of one stock run. */
    static final class Settings {
        double candidateSimilarity = BookRepeatCheck.Options.DEFAULT_CANDIDATE_SIMILARITY;
        int maxJudged = BookRepeatCheck.Options.DEFAULT_MAX_JUDGED_PER_SENTENCE;
        int parallel = 4;
        int runs = 1;
        int minFound = 16;
        int maxFalse = 3;
        int maxSameAsDifferent = 1;
        int onlySameMinFound = 7;
        int onlySameMaxFalse = 2;
        int maxUnjudged = 1;
        String reading = ONLY_SAME;
        RepeatCheck.Criteria base = RepeatCheck.Criteria.defaults();
    }

    @Test
    public void samplePairs() throws Exception {
        List<RepeatStock.Case> cases = RepeatStock.read(BookRepeatStockTest.resource("sample-pairs.json"));
        RepeatStock.CachingVectors vectors = new RepeatStock.CachingVectors(BookRepeatStockTest.fake(cases));
        Scripted judge = new Scripted().answer("Datenbrille", "GLEICH").answer("Helm umgehend", "GLEICH")
                .answer("widersprechen", "VERSCHIEDEN").answer("freiwillig", "GLEICH").answer("stoppen", "TEILWEISE");
        Settings settings = new Settings();
        settings.runs = 2;
        settings.minFound = 3;
        settings.maxFalse = 1;
        settings.onlySameMinFound = 2;
        settings.onlySameMaxFalse = 1;
        File out = scratch.newFolder("out");

        JsonObject result = run(cases, vectors, judge, "fake", settings, "sample-pairs.json", out, "test", "");

        assertEquals("S5 states a new number and is no candidate: five calls per run", 10, judge.calls.get());
        JsonObject run1 = result.getAsJsonArray("laeufe").get(0).getAsJsonObject();
        JsonObject all = run1.getAsJsonObject("alle");
        assertEquals(2, all.get("gleichGemeldet").getAsInt());
        assertEquals(1, all.get("verschiedenGemeldet").getAsInt());
        assertEquals("[\"S4\"]", all.get("verschiedenGemeldetBlaetter").toString());
        assertEquals("S6: the TEILWEISE hint counts in the reading beide", 1, all.get("teilweiseGemeldet").getAsInt());
        assertEquals(1, all.get("teilweiseGemeldetAlsTeilweise").getAsInt());
        assertEquals(0, all.get("gleichGemeldetAlsTeilweise").getAsInt());
        assertEquals(0, all.get("verschiedenGemeldetAlsTeilweise").getAsInt());
        assertEquals(5, all.get("vorausgewaehlt").getAsInt());
        assertEquals(0, all.get("fehlerOderNichtBeurteilt").getAsInt());
        assertEquals("S1, S2 and S6", 3, all.get("gleichOderTeilweiseGemeldet").getAsInt());
        assertEquals(0, all.get("gleichAlsVerschieden").getAsInt());
        assertEquals(2, all.get("gleichAlsGleichGemeldet").getAsInt());
        assertEquals(1, all.get("verschiedenAlsGleichGemeldet").getAsInt());
        assertTrue(run1.get("bestanden").getAsBoolean());
        assertTrue(run1.getAsJsonObject("lesarten").getAsJsonObject(BOTH).get("bestanden").getAsBoolean());
        assertTrue(run1.getAsJsonObject("lesarten").getAsJsonObject(ONLY_SAME).get("bestanden").getAsBoolean());
        JsonObject odd = run1.getAsJsonObject("ungerade");
        assertEquals("S1, S3, S5", 1, odd.get("gleich").getAsInt());
        assertEquals(2, odd.get("verschieden").getAsInt());
        assertEquals(0, odd.get("verschiedenGemeldet").getAsInt());
        JsonObject even = run1.getAsJsonObject("gerade");
        assertEquals(1, even.get("verschiedenGemeldet").getAsInt());
        assertEquals(1, even.get("teilweise").getAsInt());

        JsonObject agreement = result.getAsJsonObject("uebereinstimmung");
        assertEquals(5, agreement.get("vorausgewaehlt").getAsInt());
        assertEquals(5, agreement.get("gleichesUrteil").getAsInt());
        assertTrue(result.getAsJsonObject("ergebnis").get("bestanden").getAsBoolean());

        JsonObject s4 = pair(result, "S4");
        assertTrue(s4.get("vorausgewaehlt").getAsBoolean());
        JsonObject s4run = s4.getAsJsonArray("laeufe").get(1).getAsJsonObject();
        assertEquals("GLEICH", s4run.get("modellUrteil").getAsString());
        assertEquals("Grund freiwillig.", s4run.get("grund").getAsString());
        assertEquals("{\"grund\":\"Grund freiwillig.\",\"urteil\":\"GLEICH\"}",
                s4run.getAsJsonArray("rohantworten").get(0).getAsString());
        assertTrue(s4run.get("gemeldet").getAsBoolean());
        assertEquals("streichen", s4run.get("meldung").getAsString());
        JsonObject s6run = pair(result, "S6").getAsJsonArray("laeufe").get(0).getAsJsonObject();
        assertEquals("Hinweis TEILWEISE", s6run.get("meldung").getAsString());
        assertFalse("a hint is no order to the writer", s6run.get("gemeldet").getAsBoolean());
        JsonObject s5 = pair(result, "S5");
        assertFalse(s5.get("vorausgewaehlt").getAsBoolean());
        assertEquals("Zahlenregel", s5.get("nichtVorausgewaehltWeil").getAsString());
        assertEquals("", s5.getAsJsonArray("laeufe").get(0).getAsJsonObject().get("modellUrteil").getAsString());
        assertTrue(new File(out, "result.json").isFile());

        Scripted down = new Scripted();
        down.down = true;
        JsonObject failed = run(cases, vectors, down, "fake", new Settings(), "sample-pairs.json",
                scratch.newFolder("down"), "test", "");
        JsonObject failedRun = failed.getAsJsonArray("laeufe").get(0).getAsJsonObject();
        assertEquals(5, failedRun.getAsJsonObject("alle").get("fehlerOderNichtBeurteilt").getAsInt());
        assertEquals("[\"S1\",\"S2\",\"S3\",\"S4\",\"S6\"]",
                failedRun.getAsJsonObject("alle").get("fehlerBlaetter").toString());
        assertFalse(failed.getAsJsonObject("ergebnis").get("bestanden").getAsBoolean());
        assertEquals("FEHLER", pair(failed, "S1").getAsJsonArray("laeufe").get(0).getAsJsonObject()
                .get("modellUrteil").getAsString());
    }

    @Test
    public void storedPairs() throws Exception {
        String chatUrl = System.getProperty(P + "chatUrl", "");
        Assume.assumeTrue("repeat judge stock test skipped (no -D" + P + "chatUrl)", !chatUrl.isEmpty());
        String url = System.getProperty(P + "url", "");
        String vectorsPath = System.getProperty(P + "vectors", "");
        Assume.assumeTrue("repeat judge stock test needs -D" + P + "vectors or -D" + P + "url",
                !url.isEmpty() || !vectorsPath.isEmpty());
        String pairsPath = System.getProperty(P + "pairs", "");
        List<RepeatStock.Case> cases = RepeatStock.read(pairsPath.isEmpty()
                ? BookRepeatStockTest.resource("wiederholung-1711.json") : BookRepeatStockTest.read(pairsPath));
        File out = new File(System.getProperty(P + "out", "build/component-stock/repeat-judge"));
        Files.createDirectories(out.toPath());
        String embedModel = System.getProperty(P + "embedModel", "bge-m3:latest");
        int dimension = Integer.getInteger(P + "dimension", 1024);
        RepeatStock.CachingVectors vectors = vectorsPath.isEmpty()
                ? new RepeatStock.CachingVectors(BookRepeatStockTest.ollama(url, embedModel, dimension))
                : RepeatStock.CachingVectors.fromJson(BookRepeatStockTest.read(vectorsPath));
        String chatModel = System.getProperty(P + "chatModel", "gemma4:12b");
        String numCtx = System.getProperty(P + "numCtx", "");
        boolean think = !"omit".equalsIgnoreCase(System.getProperty(P + "think", "false"));
        OllamaJudgeChat chat = new OllamaJudgeChat(chatUrl, chatModel, numCtx.isEmpty() ? null
                : Integer.valueOf(numCtx), think, 1000 * Integer.getInteger(P + "timeoutSeconds", 120));
        Settings settings = new Settings();
        settings.candidateSimilarity = Double.parseDouble(System.getProperty(P + "candidateSimilarity",
                String.valueOf(settings.candidateSimilarity)));
        settings.maxJudged = Integer.getInteger(P + "maxJudged", settings.maxJudged);
        settings.parallel = Integer.getInteger(P + "parallel", settings.parallel);
        settings.runs = Integer.getInteger(P + "runs", settings.runs);
        settings.minFound = Integer.getInteger(P + "minFound", settings.minFound);
        settings.maxFalse = Integer.getInteger(P + "maxFalse", settings.maxFalse);
        settings.maxSameAsDifferent = Integer.getInteger(P + "maxSameAsDifferent", settings.maxSameAsDifferent);
        settings.onlySameMinFound = Integer.getInteger(P + "gleichMinFound", settings.onlySameMinFound);
        settings.onlySameMaxFalse = Integer.getInteger(P + "gleichMaxFalse", settings.onlySameMaxFalse);
        settings.maxUnjudged = Integer.getInteger(P + "maxUnjudged", settings.maxUnjudged);
        settings.reading = System.getProperty(P + "lesart", settings.reading);
        if (!BOTH.equals(settings.reading) && !ONLY_SAME.equals(settings.reading)) {
            throw new IllegalArgumentException("-D" + P + "lesart must be " + BOTH + " or " + ONLY_SAME);
        }
        System.out.println("[repeat-judge] " + cases.size() + " pairs " + RepeatStock.counts(cases) + ", judge "
                + chatModel + " at " + chatUrl + ", " + settings.parallel + " at a time, " + settings.runs
                + " run(s)");

        JsonObject result = run(cases, vectors, chat, embedModel + (vectorsPath.isEmpty() ? " at " + url
                        : " (gespeichert: " + vectorsPath + ")"), settings,
                pairsPath.isEmpty() ? "Repo-Kopie wiederholung-1711.json" : pairsPath, out, chatUrl,
                (numCtx.isEmpty() ? "" : "num_ctx " + numCtx + ", ") + (think ? "think:false" : "think weggelassen"));
        if (vectorsPath.isEmpty()) {
            Files.write(new File(out, "sentence-vectors.json").toPath(),
                    vectors.toJson(embedModel, dimension).getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("[repeat-judge] result " + new File(out, "result.json").getAbsolutePath());
        JsonObject verdict = result.getAsJsonObject("ergebnis");
        assertTrue(verdict.get("befund").getAsString(), verdict.get("bestanden").getAsBoolean());
    }

    /** Runs the pairs {@code settings.runs} times, writes {@code result.json} and returns it. */
    static JsonObject run(List<RepeatStock.Case> cases, RepeatStock.CachingVectors vectors, MainModelChat chat,
                          String sentenceModel, Settings settings, String source, File out, String chatUrl,
                          String chatOptions) throws Exception {
        int embedCalls = vectors.calls;
        vectors.warm(RepeatStock.texts(cases), BookRepeatCheck.Options.DEFAULT_BATCH_SIZE);
        embedCalls = vectors.calls - embedCalls;
        final SameStatementJudge judge = new SameStatementJudge(chat, "de");
        final BookRepeatCheck check = new BookRepeatCheck(settings.base, null, vectors, judge,
                BookRepeatCheck.Options.defaults().withJudging(settings.candidateSimilarity, settings.maxJudged, 1),
                null);

        JsonObject result = new JsonObject();
        result.addProperty("stand", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()));
        result.addProperty("datei", source);
        result.addProperty("paare", cases.size());
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> e : RepeatStock.counts(cases).entrySet()) {
            counts.addProperty(e.getKey(), e.getValue());
        }
        result.add("zaehlung", counts);
        JsonObject sentence = new JsonObject();
        sentence.addProperty("modell", sentenceModel);
        sentence.addProperty("aufrufe", embedCalls);
        result.add("satzmodell", sentence);
        JsonObject judgeInfo = new JsonObject();
        judgeInfo.addProperty("modell", chat.modelName());
        judgeInfo.addProperty("url", chatUrl);
        judgeInfo.addProperty("optionen", chatOptions);
        judgeInfo.addProperty("gleichzeitig", settings.parallel);
        judgeInfo.addProperty("temperatur", 0);
        judgeInfo.addProperty("nachdenken", "aus");
        judgeInfo.addProperty("laeufe", settings.runs);
        result.add("richter", judgeInfo);
        JsonObject rules = new JsonObject();
        rules.addProperty("vorauswahlAehnlichkeit", settings.candidateSimilarity);
        rules.addProperty("vorauswahlWortanteil", settings.base.getMinShare());
        rules.addProperty("mindestInhaltswoerter", settings.base.getMinWords());
        rules.addProperty("hoechstensJeSatz", settings.maxJudged);
        rules.addProperty("zahlenregel", "vor dem Modell, ohne Ausnahme");
        rules.addProperty("gemeldet", "GLEICH (streichen); TEILWEISE nur als Hinweis; gleicher Text ohne Modell");
        rules.addProperty("normnummern", "Paragraphen- und Artikelnummern zaehlen nicht fuer die Zahlenregel");
        result.add("regeln", rules);
        JsonObject targets = new JsonObject();
        targets.addProperty("massgeblicheLesart", settings.reading);
        JsonObject both = new JsonObject();
        both.addProperty("gleichOderTeilweiseGemeldetMindestens", settings.minFound);
        both.addProperty("verschiedenGemeldetHoechstens", settings.maxFalse);
        both.addProperty("gleichAlsVerschiedenHoechstens", settings.maxSameAsDifferent);
        both.addProperty("ohneUrteilHoechstens", settings.maxUnjudged);
        targets.add(BOTH, both);
        JsonObject onlySame = new JsonObject();
        onlySame.addProperty("gleichAlsGleichGemeldetMindestens", settings.onlySameMinFound);
        onlySame.addProperty("verschiedenAlsGleichGemeldetHoechstens", settings.onlySameMaxFalse);
        onlySame.addProperty("ohneUrteilHoechstens", settings.maxUnjudged);
        targets.add(ONLY_SAME, onlySame);
        result.add("vorgaben", targets);

        List<List<BookRepeatCheck.Result>> runs = new ArrayList<List<BookRepeatCheck.Result>>();
        List<long[]> millis = new ArrayList<long[]>();
        JsonArray runReports = new JsonArray();
        boolean allPassed = true;
        for (int r = 1; r <= settings.runs; r++) {
            int callsBefore = calls(chat);
            long start = System.nanoTime();
            final long[] times = new long[cases.size()];
            List<BookRepeatCheck.Result> results = judgeAll(cases, check, settings.parallel, times);
            double seconds = (System.nanoTime() - start) / 1e9;
            runs.add(results);
            millis.add(times);
            JsonObject report = new JsonObject();
            report.addProperty("lauf", r);
            report.addProperty("dauerSekunden", RepeatStock.round(seconds));
            int callCount = calls(chat) - callsBefore;
            if (callCount >= 0) {
                report.addProperty("aufrufe", callCount);
                report.addProperty("aufrufeJeMinute", RepeatStock.round(seconds == 0 ? 0 : 60 * callCount / seconds));
            }
            JsonObject all = counts(cases, results, 0);
            report.add("alle", all);
            report.add("ungerade", counts(cases, results, 1));
            report.add("gerade", counts(cases, results, 2));
            int unjudged = all.get("fehlerOderNichtBeurteilt").getAsInt();
            boolean bothPassed = all.get("gleichOderTeilweiseGemeldet").getAsInt() >= settings.minFound
                    && all.get("verschiedenGemeldet").getAsInt() <= settings.maxFalse
                    && all.get("gleichAlsVerschieden").getAsInt() <= settings.maxSameAsDifferent
                    && unjudged <= settings.maxUnjudged;
            boolean onlySamePassed = all.get("gleichAlsGleichGemeldet").getAsInt() >= settings.onlySameMinFound
                    && all.get("verschiedenAlsGleichGemeldet").getAsInt() <= settings.onlySameMaxFalse
                    && unjudged <= settings.maxUnjudged;
            JsonObject readings = new JsonObject();
            readings.add(BOTH, reading(bothPassed, String.format(Locale.ROOT, "GLEICH oder TEILWEISE %d von %d "
                            + "gemeldet (mindestens %d), VERSCHIEDEN %d von %d gemeldet (hoechstens %d), GLEICH als "
                            + "VERSCHIEDEN %d (hoechstens %d), ohne Urteil %d (hoechstens %d)",
                    all.get("gleichOderTeilweiseGemeldet").getAsInt(), all.get("gleichOderTeilweise").getAsInt(),
                    settings.minFound, all.get("verschiedenGemeldet").getAsInt(), all.get("verschieden").getAsInt(),
                    settings.maxFalse, all.get("gleichAlsVerschieden").getAsInt(), settings.maxSameAsDifferent,
                    unjudged, settings.maxUnjudged)));
            readings.add(ONLY_SAME, reading(onlySamePassed, String.format(Locale.ROOT, "GLEICH %d von %d als GLEICH "
                            + "gemeldet (mindestens %d), VERSCHIEDEN %d von %d als GLEICH (hoechstens %d), ohne "
                            + "Urteil %d (hoechstens %d)", all.get("gleichAlsGleichGemeldet").getAsInt(),
                    all.get("gleich").getAsInt(), settings.onlySameMinFound,
                    all.get("verschiedenAlsGleichGemeldet").getAsInt(), all.get("verschieden").getAsInt(),
                    settings.onlySameMaxFalse, unjudged, settings.maxUnjudged)));
            report.add("lesarten", readings);
            boolean passed = BOTH.equals(settings.reading) ? bothPassed : onlySamePassed;
            report.addProperty("bestanden", passed);
            report.addProperty("befund", String.format(Locale.ROOT, "Lauf %d: GLEICH %d von %d gemeldet (mindestens "
                            + "%d; davon %d als TEILWEISE), VERSCHIEDEN %d von %d (hoechstens %d; davon %d als "
                            + "TEILWEISE), TEILWEISE %d von %d (nur Bericht), ohne Urteil %d (hoechstens %d)", r,
                    all.get("gleichGemeldet").getAsInt(), all.get("gleich").getAsInt(), settings.minFound,
                    all.get("gleichGemeldetAlsTeilweise").getAsInt(), all.get("verschiedenGemeldet").getAsInt(),
                    all.get("verschieden").getAsInt(), settings.maxFalse,
                    all.get("verschiedenGemeldetAlsTeilweise").getAsInt(), all.get("teilweiseGemeldet").getAsInt(),
                    all.get("teilweise").getAsInt(), all.get("fehlerOderNichtBeurteilt").getAsInt(),
                    settings.maxUnjudged));
            System.out.println("[repeat-judge] " + report.get("befund").getAsString());
            System.out.println("[repeat-judge] Lesart " + BOTH + ": " + readings.getAsJsonObject(BOTH).get("befund")
                    .getAsString() + " -> " + (bothPassed ? "bestanden" : "nicht bestanden"));
            System.out.println("[repeat-judge] Lesart " + ONLY_SAME + ": " + readings.getAsJsonObject(ONLY_SAME)
                    .get("befund").getAsString() + " -> " + (onlySamePassed ? "bestanden" : "nicht bestanden"));
            allPassed &= passed;
            runReports.add(report);
        }
        result.add("laeufe", runReports);
        if (runs.size() > 1) {
            result.add("uebereinstimmung", agreement(cases, runs));
        }
        result.add("paarwerte", pairValues(cases, runs, millis));
        JsonObject verdict = new JsonObject();
        verdict.addProperty("bestanden", allPassed);
        StringBuilder text = new StringBuilder(allPassed ? "bestanden" : "nicht bestanden");
        text.append(" in der Lesart ").append(settings.reading);
        text.append(runs.size() > 1 ? " (jeder Lauf muss die Vorgaben erfuellen)" : "");
        for (JsonElement report : runReports) {
            JsonObject r = report.getAsJsonObject();
            text.append("; Lauf ").append(r.get("lauf").getAsInt()).append(": ").append(r.getAsJsonObject("lesarten")
                    .getAsJsonObject(settings.reading).get("befund").getAsString());
        }
        verdict.addProperty("befund", text.toString());
        result.add("ergebnis", verdict);
        Files.write(new File(out, "result.json").toPath(), RepeatStock.pretty(result).getBytes(StandardCharsets.UTF_8));
        return result;
    }

    private static JsonObject reading(boolean passed, String finding) {
        JsonObject o = new JsonObject();
        o.addProperty("bestanden", passed);
        o.addProperty("befund", finding);
        return o;
    }

    private static int calls(MainModelChat chat) {
        if (chat instanceof OllamaJudgeChat) {
            return ((OllamaJudgeChat) chat).calls.get();
        }
        if (chat instanceof Scripted) {
            return ((Scripted) chat).calls.get();
        }
        return -1;
    }

    /** Every pair as a book of its own, {@code parallel} at a time; the times per pair in ms. */
    private static List<BookRepeatCheck.Result> judgeAll(final List<RepeatStock.Case> cases,
                                                         final BookRepeatCheck check, int parallel,
                                                         final long[] times) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(parallel, cases.size())));
        try {
            List<Future<BookRepeatCheck.Result>> futures = new ArrayList<Future<BookRepeatCheck.Result>>();
            for (int i = 0; i < cases.size(); i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    long start = System.nanoTime();
                    BookRepeatCheck.Result result = check.check(cases.get(index).book());
                    times[index] = (System.nanoTime() - start) / 1000000;
                    return result;
                }));
            }
            List<BookRepeatCheck.Result> results = new ArrayList<BookRepeatCheck.Result>();
            for (Future<BookRepeatCheck.Result> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The sheet number's parity: 1 odd, 2 even, 0 when the sheet has no number. */
    static int parity(String sheet) {
        String digits = sheet.replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits) % 2 == 1 ? 1 : 2;
    }

    /** The counts of one run over all pairs ({@code parity} 0), the odd (1) or the even sheets (2). */
    private static JsonObject counts(List<RepeatStock.Case> cases, List<BookRepeatCheck.Result> results, int parity) {
        int same = 0;
        int different = 0;
        int part = 0;
        int candidates = 0;
        List<String> sameReported = new ArrayList<String>();
        List<String> sameMissed = new ArrayList<String>();
        List<String> differentReported = new ArrayList<String>();
        List<String> partReported = new ArrayList<String>();
        int samePartly = 0;
        int differentPartly = 0;
        int partPartly = 0;
        List<String> sameAsDifferent = new ArrayList<String>();
        List<String> unjudged = new ArrayList<String>();
        for (int i = 0; i < cases.size(); i++) {
            RepeatStock.Case c = cases.get(i);
            if (parity != 0 && parity(c.sheet) != parity) {
                continue;
            }
            BookRepeatCheck.Result result = results.get(i);
            BookRepeatCheck.Reason found = foundReason(result);
            boolean reported = found != null;
            boolean partly = found == BookRepeatCheck.Reason.PARTLY_SAME;
            if (!result.getJudged().isEmpty() || reported) {
                candidates++;
            }
            for (BookRepeatCheck.Judged judged : result.getJudged()) {
                if (!judged.getJudgement().isJudged()) {
                    unjudged.add(c.sheet);
                    break;
                }
            }
            if (RepeatStock.SAME.equals(c.verdict)) {
                same++;
                (reported ? sameReported : sameMissed).add(c.sheet);
                samePartly += partly ? 1 : 0;
                if (SameStatementJudge.Verdict.DIFFERENT.wireName().equals(verdictOf(result))) {
                    sameAsDifferent.add(c.sheet);
                }
            } else if (RepeatStock.DIFFERENT.equals(c.verdict)) {
                different++;
                if (reported) {
                    differentReported.add(c.sheet);
                }
                differentPartly += partly ? 1 : 0;
            } else {
                part++;
                if (reported) {
                    partReported.add(c.sheet);
                }
                partPartly += partly ? 1 : 0;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("gleich", same);
        o.addProperty("gleichOderTeilweise", same + part);
        o.addProperty("gleichOderTeilweiseGemeldet", sameReported.size() + partReported.size());
        o.addProperty("gleichGemeldet", sameReported.size());
        o.addProperty("gleichGemeldetAlsTeilweise", samePartly);
        o.addProperty("gleichAlsGleichGemeldet", sameReported.size() - samePartly);
        o.add("gleichVerfehlt", RepeatStock.strings(sameMissed));
        o.addProperty("gleichAlsVerschieden", sameAsDifferent.size());
        o.add("gleichAlsVerschiedenBlaetter", RepeatStock.strings(sameAsDifferent));
        o.addProperty("verschieden", different);
        o.addProperty("verschiedenGemeldet", differentReported.size());
        o.addProperty("verschiedenGemeldetAlsTeilweise", differentPartly);
        o.addProperty("verschiedenAlsGleichGemeldet", differentReported.size() - differentPartly);
        o.add("verschiedenGemeldetBlaetter", RepeatStock.strings(differentReported));
        o.addProperty("teilweise", part);
        o.addProperty("teilweiseGemeldet", partReported.size());
        o.addProperty("teilweiseGemeldetAlsTeilweise", partPartly);
        o.add("teilweiseGemeldetBlaetter", RepeatStock.strings(partReported));
        o.addProperty("vorausgewaehlt", candidates);
        o.addProperty("fehlerOderNichtBeurteilt", unjudged.size());
        o.add("fehlerBlaetter", RepeatStock.strings(unjudged));
        return o;
    }

    /** How often the runs gave every candidate pair the same verdict. */
    private static JsonObject agreement(List<RepeatStock.Case> cases, List<List<BookRepeatCheck.Result>> runs) {
        int candidates = 0;
        int same = 0;
        List<String> differing = new ArrayList<String>();
        for (int i = 0; i < cases.size(); i++) {
            String first = verdictOf(runs.get(0).get(i));
            if (first.isEmpty()) {
                continue;
            }
            candidates++;
            boolean agree = true;
            for (int r = 1; r < runs.size(); r++) {
                agree &= first.equals(verdictOf(runs.get(r).get(i)));
            }
            if (agree) {
                same++;
            } else {
                differing.add(cases.get(i).sheet);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("vorausgewaehlt", candidates);
        o.addProperty("gleichesUrteil", same);
        o.add("abweichend", RepeatStock.strings(differing));
        return o;
    }

    /** A repeat reported to the writer, else a TEILWEISE hint, else {@code null}. */
    private static BookRepeatCheck.Reason foundReason(BookRepeatCheck.Result result) {
        if (!result.getPairs().isEmpty()) {
            return result.getPairs().get(0).getReason();
        }
        return result.getHints().isEmpty() ? null : BookRepeatCheck.Reason.PARTLY_SAME;
    }

    /** The model's verdict on the pair ({@code GLEICHER_TEXT} without a call), empty when it was no candidate. */
    private static String verdictOf(BookRepeatCheck.Result result) {
        if (!result.getJudged().isEmpty()) {
            return result.getJudged().get(0).getVerdict().wireName();
        }
        if (!result.getPairs().isEmpty() && result.getPairs().get(0).getReason() == BookRepeatCheck.Reason.SAME_TEXT) {
            return "GLEICHER_TEXT";
        }
        return "";
    }

    private static JsonArray pairValues(List<RepeatStock.Case> cases, List<List<BookRepeatCheck.Result>> runs,
                                        List<long[]> millis) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < cases.size(); i++) {
            RepeatStock.Case c = cases.get(i);
            BookRepeatCheck.Result first = runs.get(0).get(i);
            JsonObject o = new JsonObject();
            o.addProperty("blatt", c.sheet);
            o.addProperty("schicht", c.stratum);
            o.addProperty("urteil", c.verdict);
            o.addProperty("gleicherBeleg", c.sameEvidence);
            if (c.borderline != null) {
                o.add("grenzfall", c.borderline);
            }
            BookRepeatCheck.Pair nearest = first.getNearest().isEmpty() ? null : first.getNearest().get(0);
            if (nearest != null) {
                o.addProperty("kosinus", RepeatStock.round(nearest.getSimilarity()));
                o.addProperty("wortanteil", RepeatStock.round(nearest.getShare()));
                o.addProperty("zahlenGedeckt", nearest.isNumbersCovered());
            }
            boolean candidate = !verdictOf(first).isEmpty();
            o.addProperty("vorausgewaehlt", candidate);
            if (!candidate) {
                o.addProperty("nichtVorausgewaehltWeil", nearest == null ? "zu wenige Inhaltswoerter"
                        : !nearest.isNumbersCovered() ? "Zahlenregel" : "unter der Vorauswahl");
            }
            JsonArray perRun = new JsonArray();
            for (int r = 0; r < runs.size(); r++) {
                BookRepeatCheck.Result result = runs.get(r).get(i);
                JsonObject run = new JsonObject();
                run.addProperty("modellUrteil", verdictOf(result));
                SameStatementJudge.Judgement judgement = result.getJudged().isEmpty() ? null
                        : result.getJudged().get(0).getJudgement();
                run.addProperty("grund", judgement == null ? "" : judgement.getReason());
                run.add("rohantworten", RepeatStock.strings(judgement == null ? new ArrayList<String>()
                        : judgement.getAnswers()));
                run.addProperty("versuche", judgement == null ? 0 : judgement.getAttempts());
                run.addProperty("gemeldet", !result.getPairs().isEmpty());
                BookRepeatCheck.Reason found = foundReason(result);
                run.addProperty("meldung", found == null ? "" : found == BookRepeatCheck.Reason.PARTLY_SAME
                        ? "Hinweis TEILWEISE" : "streichen");
                run.addProperty("fehler", judgement == null ? "" : judgement.getProblem());
                run.addProperty("dauerMs", millis.get(r)[i]);
                perRun.add(run);
            }
            o.add("laeufe", perRun);
            out.add(o);
        }
        return out;
    }

    private static JsonObject pair(JsonObject result, String sheet) {
        for (JsonElement e : result.getAsJsonArray("paarwerte")) {
            if (sheet.equals(e.getAsJsonObject().get("blatt").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no pair " + sheet + " in " + Arrays.toString(new Object[]{result}));
    }
}
