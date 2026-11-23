package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.topics.TopicFinder.Options;
import com.aresstack.askai.research.topics.TopicFinder.Source;
import com.aresstack.askai.research.topics.TopicFinder.SourcePassage;
import com.aresstack.askai.research.topics.TopicFinder.Window;
import com.aresstack.askai.research.topics.TopicFinder.WindowResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Component 13 on the stock of 17 Nov: the topic finder reads every source of the stock ({@code replay-corpus.json}
 * on the owner's machine: 109 sources, 3199 passages; foreign source text, never in the repository) as in the product,
 * window by window, with the mission of the stock and the four cards of its concept as known topics. Its proposals are
 * set against the 17 topics the architect named for the topic finder ({@code erwartete-themen-1711.json}: topics that
 * stand in these sources and were missing from the concept; 8 INNEN, 4 UNKLAR, 5 AUSSEN, since the finder decides
 * nothing about the Weidezaun). A proposal hits a topic by the key words of {@code stichworte-1711.json} in its name or
 * description; the result names the hits of every topic, so a wrong hit can be struck by hand.
 *
 * <p>Proposed criteria (the architect fixes them before the run), each a property: K1 at least {@code .minTreffer}
 * (14) of the 17 topics are hit by a proposal whose quote was found in the passages the call read; K2 at least
 * {@code .minFundstellen} (0.8) of all proposals have such a finding; K3 at most {@code .maxFehler} (0.01) of the
 * windows end in FEHLER after the one repeat the product makes ({@code .wiederholen=false} switches it off).
 * Everything else is report: windows, calls and times, proposals per window, dropped proposals, hits per expected
 * verdict and whether a hit comes from a source the architect's sample names, proposals close to a known topic, and
 * every proposal name with its count.</p>
 *
 * <p>Several models run one after the other ({@code .chatModel=gemma4:e2b,gemma4:12b}); the first decides, every
 * later one is compared with it ("nicht schlechter": at most {@code .toleranzThemen} (1) topic fewer and a finding
 * share at most {@code .toleranz} (0.02) lower). The known topics stay the four cards for the whole run; in the
 * product the merge (15) adds topics between rounds.</p>
 *
 * <p>{@link #sampleStock()} runs the whole path on a self-written stock with a scripted model in every build;
 * {@link #storedStock()} needs Ollama and is skipped without {@code .chatUrl}:</p>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*TopicFinderStockTest*'
 *   -Daskai.stock.topics.chatUrl=http://127.0.0.1:11434 -Daskai.stock.topics.chatModel=gemma4:e2b,gemma4:12b
 *   -Daskai.stock.topics.corpus=replay-corpus.json
 *   -Daskai.stock.topics.out=build/component-stock/topic-finder
 *   optional: .erwartet, .stichworte, .blaetter (other files in the form of the repo copies),
 *             .bekannteThemen (UTF-8 file, one name per line; "keine" for none; default the four themes of
 *             fragen-1711.json, the cards of the concept of 17 Nov), .minTreffer (14), .minFundstellen (0.8),
 *             .maxFehler (0.01), .toleranz (0.02), .toleranzThemen (1), .wiederholen (true), .parallel (4),
 *             .fensterZeichen (6000), .passageZeichen (1500), .quellen (source-1,source-7), .limit (0 = all
 *             sources), .numCtx (only when set), .think=omit (else think:false is sent), .timeoutSeconds (180)
 * </pre>
 * {@code result.json} holds counts, proposal names and descriptions, no passage text and no quote. Per model,
 * {@code <out>/<model>/topic-proposals/log.jsonl} is the finder's own log with quotes and raw answers; it carries words
 * of the sources and stays on the machine.
 */
public class TopicFinderStockTest {

    private static final String P = "askai.stock.topics.";
    static final String EXPECTED = "/topic-finder-stock/erwartete-themen-1711.json";
    static final String KEYWORDS = "/topic-finder-stock/stichworte-1711.json";
    static final String SHEETS = "/topic-finder-stock/themen-1711.json";
    static final String QUESTIONS = "/topic-finder-stock/fragen-1711.json";
    static final Pattern MOJIBAKE = Pattern.compile("\\u00C3[\\u0080-\\u00BF]|\\u00E2\\u20AC|\\u00C2[\\u00A0-\\u00BF]");
    private static final Pattern PASSAGE_NUMBER = Pattern.compile("#p(\\d+)");
    private static final List<String> VERDICTS = Arrays.asList("INNEN", "UNKLAR", "AUSSEN");
    private static final Set<String> STOP_WORDS = new LinkedHashSet<String>(Arrays.asList("oder", "fuer", "eine",
            "einer", "eines", "beim", "sowie", "ueber", "durch", "nach", "unter", "ihre", "seine", "deren"));

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The stock: mission and sources with their passages in reading order. */
    static final class Stock {
        String title = "";
        String mission = "";
        String language = "de";
        final List<Source> sources = new ArrayList<Source>();
        final Map<String, String> sourceOfPassage = new HashMap<String, String>();
        final Map<String, String> sourceOfCapture = new HashMap<String, String>();
        int passages;
        int damaged;
    }

    /** One topic the finder should propose. */
    static final class Expected {
        String name;
        String verdict;
        final List<String> sheets = new ArrayList<String>();
        final List<String> keywords = new ArrayList<String>();
    }

    /** Criteria and how the run works. */
    static final class Settings {
        List<String> models = Collections.singletonList("gemma4:e2b");
        int parallel = Options.DEFAULT_PARALLEL;
        int windowChars = Options.DEFAULT_WINDOW_CHARS;
        int passageChars = Options.DEFAULT_PASSAGE_CHARS;
        int limit;
        final Set<String> onlySources = new LinkedHashSet<String>();
        boolean retry = true;
        int minHits = 14;
        double minFindings = 0.8;
        double maxFailed = 0.01;
        double tolerance = 0.02;
        int toleranceTopics = 1;

        Options options() {
            return new Options(parallel, windowChars, passageChars);
        }

        void check() {
            if (models.isEmpty()) {
                throw new IllegalArgumentException("at least one model");
            }
            options();
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
    public void theRepoCopiesFitTogether() throws IOException {
        List<Expected> expected = readExpected(resource(EXPECTED), resource(KEYWORDS));
        assertEquals(17, expected.size());
        Map<String, Integer> verdicts = new TreeMap<String, Integer>();
        for (Expected topic : expected) {
            verdicts.merge(topic.verdict, 1, Integer::sum);
            assertFalse(topic.name, topic.keywords.isEmpty());
            for (String keyword : topic.keywords) {
                String bare = keyword.replaceFirst("^[=^]", "");
                assertEquals("key words are written folded: " + keyword, fold(bare), bare);
            }
        }
        assertEquals("{AUSSEN=5, INNEN=8, UNKLAR=4}", verdicts.toString());
        JsonObject sheets = JsonParser.parseString(resource(SHEETS)).getAsJsonObject();
        Map<String, List<String>> samples = samplesOf(resource(SHEETS));
        assertEquals(81, samples.size());
        assertEquals(81, sheets.getAsJsonObject("zaehlung").entrySet().stream()
                .mapToInt(e -> e.getValue().getAsInt()).sum());
        for (Expected topic : expected) {
            for (String sheet : topic.sheets) {
                assertTrue(topic.name + " names an unknown sheet " + sheet, samples.containsKey(sheet));
            }
        }
        assertEquals(Arrays.asList("Technologien und Sensortypen", "Arbeitsschutz-Gefährdungen und Wirksamkeit",
                "Recht, Datenschutz und Betriebsrat", "Implementierung und Praxis"), knownOf(resource(QUESTIONS)));
    }

    @Test
    public void keyWordsMatchAsTheFileSays() {
        assertTrue(hits("Gassensoren für Tunnelbaustellen", "sensor"));
        assertTrue(hits("IT-Sicherheitslücken bei Wearables", "^it sicherheit"));
        assertFalse(hits("Daten mit Sicherheit löschen", "^it sicherheit"));
        assertTrue(hits("GPS-Ortung von Beschäftigten", "^ortung"));
        assertFalse(hits("Verantwortung des Arbeitgebers", "^ortung"));
        assertTrue(hits("BEM-Verfahren nach Unfällen", "=bem"));
        assertFalse(hits("Bemessung von Prüffristen", "=bem"));
        assertTrue(hits("Intelligente Warnwesten", "weste"));
        assertTrue(hits("Pflichten nach § 26 BDSG", "26 bdsg"));
        assertTrue(hits("E-Privacy und Endgeräte", "e privacy"));
        assertTrue(hits("Hitzestress im Sommer", "hitze"));
        assertTrue(hits("Prüfung nach DGUV V3", "dguv v3"));
        assertTrue(hits("5G-Netze auf der Baustelle", "=5g"));
        assertEquals(" strasse aerger ueber oel e mail ", " " + fold("Straße/Ärger über Öl – E-Mail") + " ");
    }

    @Test
    public void theStockIsReadInReadingOrder() throws IOException {
        Stock stock = readStock(new StringReader(sampleCorpus()));
        assertEquals(3, stock.sources.size());
        assertEquals(5, stock.passages);
        Source first = stock.sources.get(0);
        assertEquals("source-1", first.getSourceId());
        assertEquals("stadt.example", first.getDomain());
        assertEquals(Arrays.asList("cap-1#p0@seg", "cap-1#p2@seg", "cap-1#p10@seg"), idsOf(first));
        assertEquals("source-2", stock.sourceOfCapture.get("cap-2"));
        assertEquals("source-1", stock.sourceOfPassage.get("cap-1#p10@seg"));
        assertEquals(SAMPLE_MISSION, stock.mission);
        try {
            readStock(new StringReader("{\"passages\":[]}"));
            throw new AssertionError("a stock without mission must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("mission"));
        }
    }

    @Test
    public void sampleStock() throws Exception {
        Stock stock = readStock(new StringReader(sampleCorpus()));
        List<Expected> expected = readExpected(SAMPLE_EXPECTED, SAMPLE_KEYWORDS);
        Map<String, List<String>> samples = samplesOf(SAMPLE_SHEETS);
        Settings settings = new Settings();
        settings.models = Arrays.asList("richtig", "fehlerhaft");
        settings.minHits = 2;
        settings.parallel = 2;
        File out = tmp.newFolder("out");
        Map<String, Scripted> scripted = new ConcurrentHashMap<String, Scripted>();
        Function<String, MainModelChat> chats = model -> scripted.computeIfAbsent(model, Scripted::new);
        JsonObject result = run(stock, expected, samples, Collections.singletonList("Tempo 30"), chats, settings, out,
                null);

        JsonObject right = result.getAsJsonArray("modelle").get(0).getAsJsonObject();
        assertEquals("richtig", right.get("modell").getAsString());
        JsonObject windows = right.getAsJsonObject("fenster");
        assertEquals(3, windows.get("gesamt").getAsInt());
        assertEquals(1, windows.get("fehlerErsterDurchgang").getAsInt());
        assertEquals(1, windows.get("wiederholt").getAsInt());
        assertEquals(0, windows.get("fehler").getAsInt());
        JsonObject proposals = right.getAsJsonObject("vorschlaege");
        assertEquals(4, proposals.get("gesamt").getAsInt());
        assertEquals(3, proposals.get("mitFundstelle").getAsInt());
        assertEquals(1, proposals.get("nahBekannt").getAsInt());
        assertEquals("[\"Tempo 30 in Wohnstraßen\"]", proposals.get("nahBekanntNamen").toString());
        JsonArray topics = right.getAsJsonArray("themen");
        assertEquals("Lastenräder in der Innenstadt", topics.get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(topics.get(0).getAsJsonObject().get("getroffen").getAsBoolean());
        assertEquals(1, topics.get(0).getAsJsonObject().get("trefferAusStichprobenquellen").getAsInt());
        assertTrue(topics.get(1).getAsJsonObject().get("getroffen").getAsBoolean());
        assertFalse("no hit without a finding", topics.get(2).getAsJsonObject().get("getroffen").getAsBoolean());
        assertEquals(1, topics.get(2).getAsJsonObject().get("trefferOhneFundstelle").getAsInt());
        assertEquals("2 von 2", right.getAsJsonObject("trefferNachUrteil").get("INNEN").getAsString());
        assertEquals("0 von 1", right.getAsJsonObject("trefferNachUrteil").get("AUSSEN").getAsString());
        JsonObject criteria = right.getAsJsonObject("kriterien");
        assertTrue(criteria.getAsJsonObject("K1").get("erfuellt").getAsBoolean());
        assertFalse("3 of 4 proposals with a finding", criteria.getAsJsonObject("K2").get("erfuellt").getAsBoolean());
        assertTrue(criteria.getAsJsonObject("K3").get("erfuellt").getAsBoolean());
        assertFalse(right.get("bestanden").getAsBoolean());

        JsonObject wrong = result.getAsJsonArray("modelle").get(1).getAsJsonObject();
        assertEquals(3, wrong.getAsJsonObject("fenster").get("fehler").getAsInt());
        assertFalse(wrong.getAsJsonObject("kriterien").getAsJsonObject("K3").get("erfuellt").getAsBoolean());
        JsonObject comparison = result.getAsJsonArray("vergleich").get(0).getAsJsonObject();
        assertFalse(comparison.get("nichtSchlechter").getAsBoolean());
        assertEquals("richtig", result.getAsJsonObject("ergebnis").get("modell").getAsString());

        String written = new String(Files.readAllBytes(new File(out, "result.json").toPath()), StandardCharsets.UTF_8);
        assertFalse("no passage text in result.json", written.contains("Lastenräder übernehmen die letzte Meile"));
        assertFalse("no quote in result.json", written.contains("die letzte Meile zu den Geschäften"));
        List<WindowResult> log = new TopicProposalLog(new File(out, "richtig")).open();
        assertEquals("three windows and one repeat", 4, log.size());
        assertEquals(2, log.get(3).getRound());
    }

    @Test
    public void storedStock() throws Exception {
        String chatUrl = System.getProperty(P + "chatUrl", "");
        Assume.assumeTrue("topic finder stock test skipped (no -D" + P + "chatUrl)", !chatUrl.isEmpty());
        String corpusPath = required("corpus");
        String expectedPath = System.getProperty(P + "erwartet", "");
        String keywordsPath = System.getProperty(P + "stichworte", "");
        String sheetsPath = System.getProperty(P + "blaetter", "");
        String knownPath = System.getProperty(P + "bekannteThemen", "");
        File out = new File(System.getProperty(P + "out", "build/component-stock/topic-finder"));
        Files.createDirectories(out.toPath());
        Settings settings = new Settings();
        settings.models = list(System.getProperty(P + "chatModel", "gemma4:e2b"));
        settings.parallel = Integer.getInteger(P + "parallel", settings.parallel);
        settings.windowChars = Integer.getInteger(P + "fensterZeichen", settings.windowChars);
        settings.passageChars = Integer.getInteger(P + "passageZeichen", settings.passageChars);
        settings.limit = Integer.getInteger(P + "limit", 0);
        settings.onlySources.addAll(list(System.getProperty(P + "quellen", "")));
        settings.retry = !"false".equalsIgnoreCase(System.getProperty(P + "wiederholen", "true"));
        settings.minHits = Integer.getInteger(P + "minTreffer", settings.minHits);
        settings.minFindings = number("minFundstellen", settings.minFindings);
        settings.maxFailed = number("maxFehler", settings.maxFailed);
        settings.tolerance = number("toleranz", settings.tolerance);
        settings.toleranceTopics = Integer.getInteger(P + "toleranzThemen", settings.toleranceTopics);
        settings.check();
        String numCtx = System.getProperty(P + "numCtx", "");
        boolean think = !"omit".equalsIgnoreCase(System.getProperty(P + "think", "false"));
        int timeout = 1000 * Integer.getInteger(P + "timeoutSeconds", 180);

        Stock stock;
        try (Reader in = new InputStreamReader(new FileInputStream(corpusPath), StandardCharsets.UTF_8)) {
            stock = readStock(in);
        }
        List<Expected> expected = readExpected(expectedPath.isEmpty() ? resource(EXPECTED) : read(expectedPath),
                keywordsPath.isEmpty() ? resource(KEYWORDS) : read(keywordsPath));
        Map<String, List<String>> samples = samplesOf(sheetsPath.isEmpty() ? resource(SHEETS) : read(sheetsPath));
        List<String> known;
        if (knownPath.isEmpty()) {
            known = knownOf(resource(QUESTIONS));
        } else if ("keine".equalsIgnoreCase(knownPath)) {
            known = Collections.emptyList();
        } else {
            known = new ArrayList<String>();
            for (String line : read(knownPath).split("\r?\n")) {
                if (!line.trim().isEmpty()) {
                    known.add(line.trim());
                }
            }
        }
        JsonObject sources = new JsonObject();
        sources.addProperty("korpus", corpusPath);
        sources.addProperty("erwartet", expectedPath.isEmpty() ? "Repo-Kopie erwartete-themen-1711.json"
                : expectedPath);
        sources.addProperty("stichworte", keywordsPath.isEmpty() ? "Repo-Kopie stichworte-1711.json" : keywordsPath);
        sources.addProperty("blaetter", sheetsPath.isEmpty() ? "Repo-Kopie themen-1711.json" : sheetsPath);
        sources.addProperty("bekannteThemen", knownPath.isEmpty() ? "Themen von fragen-1711.json (Karten des Konzepts)"
                : knownPath);
        sources.addProperty("chatUrl", chatUrl);
        sources.addProperty("aufrufOptionen", (numCtx.isEmpty() ? "" : "num_ctx " + numCtx + ", ")
                + (think ? "think:false" : "think weggelassen") + ", Zeitlimit " + timeout / 1000 + " s");
        System.out.println("[topic-finder] " + stock.sources.size() + " sources, " + stock.passages + " passages, "
                + "models " + settings.models + " at " + chatUrl + ", " + settings.parallel + " at a time");

        Function<String, MainModelChat> chats = model -> new OllamaStockChat(chatUrl, model,
                numCtx.isEmpty() ? null : Integer.valueOf(numCtx), think, timeout);
        JsonObject result = run(stock, expected, samples, known, chats, settings, out, sources);
        System.out.println("[topic-finder] result " + new File(out, "result.json").getAbsolutePath());
        JsonObject verdict = result.getAsJsonObject("ergebnis");
        assertTrue(verdict.get("befund").getAsString(), verdict.get("bestanden").getAsBoolean());
    }

    // ------------------------------------------------------------------------------------------------- run

    /** Reads the stock with each model, writes {@code result.json} and the finder's logs, returns the result. */
    static JsonObject run(Stock stock, List<Expected> expected, Map<String, List<String>> samples, List<String> known,
                          Function<String, MainModelChat> chats, Settings settings, File out, JsonObject sources)
            throws IOException {
        settings.check();
        List<Source> read = new ArrayList<Source>();
        for (Source source : stock.sources) {
            if (settings.onlySources.isEmpty() || settings.onlySources.contains(source.getSourceId())) {
                read.add(source);
            }
        }
        if (settings.limit > 0 && read.size() > settings.limit) {
            read = new ArrayList<Source>(read.subList(0, settings.limit));
        }
        int windowCount = 0;
        int passageCount = 0;
        for (Source source : read) {
            windowCount += TopicFinder.windowsOf(source, settings.options()).size();
            passageCount += source.getPassages().size();
        }

        JsonObject result = new JsonObject();
        result.addProperty("komponente", "13 Themenfinder");
        result.addProperty("erstellt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
                .format(new Date()));
        result.add("quellen", sources == null ? new JsonObject() : sources);
        JsonObject stockJson = new JsonObject();
        stockJson.addProperty("titel", stock.title);
        stockJson.addProperty("quellenImBestand", stock.sources.size());
        stockJson.addProperty("passagenImBestand", stock.passages);
        stockJson.addProperty("quellenGelesen", read.size());
        stockJson.addProperty("passagenGelesen", passageCount);
        stockJson.addProperty("fenster", windowCount);
        stockJson.addProperty("beschaedigtePassagen", stock.damaged);
        result.add("bestand", stockJson);
        JsonObject task = new JsonObject();
        task.addProperty("zeichen", stock.mission.length());
        task.addProperty("sprache", stock.language);
        result.add("auftrag", task);
        JsonArray warnings = new JsonArray();
        if (MOJIBAKE.matcher(stock.mission).find()) {
            warnings.add("Der Auftrag sieht beschädigt aus (UTF-8 als Latin-1 gelesen).");
        }
        if (stock.damaged > 0) {
            warnings.add(stock.damaged + " Passagen sehen beschädigt aus (UTF-8 als Latin-1 gelesen).");
        }
        result.add("warnungen", warnings);
        result.add("einstellungen", settingsJson(settings));
        result.add("bekannteThemen", strings(known));
        JsonArray expectedJson = new JsonArray();
        Map<String, Set<String>> sampleSources = new LinkedHashMap<String, Set<String>>();
        for (Expected topic : expected) {
            Set<String> where = new LinkedHashSet<String>();
            for (String sheet : topic.sheets) {
                List<String> passages = samples.get(sheet);
                for (String passage : passages == null ? Collections.<String>emptyList() : passages) {
                    String source = stock.sourceOfPassage.get(passage);
                    if (source == null) {
                        source = stock.sourceOfCapture.get(passage.split("#", 2)[0]);
                    }
                    if (source != null) {
                        where.add(source);
                    }
                }
            }
            sampleSources.put(topic.name, where);
            JsonObject t = new JsonObject();
            t.addProperty("name", topic.name);
            t.addProperty("erwartet", topic.verdict);
            t.add("blaetter", strings(topic.sheets));
            t.add("stichprobenQuellen", strings(new ArrayList<String>(where)));
            expectedJson.add(t);
        }
        result.add("erwartet", expectedJson);

        JsonArray models = new JsonArray();
        for (String model : settings.models) {
            TimedChat chat = new TimedChat(chats.apply(model));
            TopicFinder finder = new TopicFinder(chat, stock.mission, stock.language, settings.options(),
                    line -> System.out.println("[topic-finder] " + model + ": " + line));
            File dir = new File(out, model.replaceAll("[^A-Za-z0-9._-]", "_"));
            TopicProposalLog log = new TopicProposalLog(dir);
            Files.deleteIfExists(log.logFile().toPath());
            try {
                log.open();
            } catch (Exception unreadable) {
                throw new IOException("cannot open " + log.logFile(), unreadable);
            }
            long start = System.nanoTime();
            List<WindowResult> first = finder.readAll(read, known, 1);
            log.appendAll(first);
            List<WindowResult> all = new ArrayList<WindowResult>(first);
            int repeated = 0;
            if (settings.retry) {
                List<Window> open = finder.unread(read, first);
                repeated = open.size();
                if (!open.isEmpty()) {
                    List<WindowResult> second = finder.readWindows(open, known, 2);
                    log.appendAll(second);
                    all.addAll(second);
                }
            }
            long wall = System.nanoTime() - start;
            JsonObject measured = measure(model, first, all, repeated, expected, sampleSources, known, settings);
            JsonObject time = new JsonObject();
            time.addProperty("wandSekunden", Math.round(wall / 1e8) / 10.0);
            time.addProperty("aufrufe", chat.calls.get());
            time.addProperty("sekundenJeAufruf", chat.calls.get() == 0 ? 0.0
                    : Math.round(chat.nanos.get() / 1e8 / chat.calls.get()) / 10.0);
            measured.add("zeit", time);
            models.add(measured);
            System.out.println("[topic-finder] " + model + ": " + measured.get("befund").getAsString());
        }
        result.add("modelle", models);

        JsonObject decisive = models.get(0).getAsJsonObject();
        JsonArray comparisons = new JsonArray();
        for (int i = 1; i < models.size(); i++) {
            comparisons.add(compare(decisive, models.get(i).getAsJsonObject(), settings));
        }
        result.add("vergleich", comparisons);
        JsonObject verdict = new JsonObject();
        verdict.addProperty("modell", decisive.get("modell").getAsString());
        verdict.addProperty("bestanden", decisive.get("bestanden").getAsBoolean());
        verdict.addProperty("befund", decisive.get("befund").getAsString());
        result.add("ergebnis", verdict);
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(result).getBytes(StandardCharsets.UTF_8));
        return result;
    }

    /** The numbers of one model. */
    static JsonObject measure(String model, List<WindowResult> first, List<WindowResult> all, int repeated,
                              List<Expected> expected, Map<String, Set<String>> sampleSources, List<String> known,
                              Settings settings) {
        List<WindowResult> latest = TopicProposalLog.latest(all);
        int failed = 0;
        int notAsked = 0;
        int failedFirst = 0;
        Map<String, Integer> reasons = new TreeMap<String, Integer>();
        for (WindowResult result : first) {
            failedFirst += result.isOk() ? 0 : 1;
        }
        int empty = 0;
        int full = 0;
        Map<String, Integer> dropped = new TreeMap<String, Integer>();
        for (WindowResult result : latest) {
            if (!result.isOk()) {
                failed++;
                notAsked += result.getProblem().startsWith("not asked") ? 1 : 0;
                reasons.merge(reasonOf(result.getProblem()), 1, Integer::sum);
                continue;
            }
            empty += result.getProposals().isEmpty() ? 1 : 0;
            full += result.getProposals().size() >= TopicFinder.MAX_PROPOSALS ? 1 : 0;
            for (String reason : result.getDropped()) {
                dropped.merge(reason.replaceFirst("^(\"[^\"]*\"|proposal \\d+): ", ""), 1, Integer::sum);
            }
        }
        List<TopicProposal> proposals = TopicProposalLog.proposals(all);
        Map<TopicProposal.FindingCheck, Integer> checks = new TreeMap<TopicProposal.FindingCheck, Integer>();
        int withFinding = 0;
        int questions = 0;
        int withoutQuestions = 0;
        for (TopicProposal proposal : proposals) {
            checks.merge(proposal.getFindingCheck(), 1, Integer::sum);
            withFinding += proposal.hasFinding() ? 1 : 0;
            questions += proposal.getQuestions().size();
            withoutQuestions += proposal.getQuestions().isEmpty() ? 1 : 0;
        }

        JsonObject json = new JsonObject();
        json.addProperty("modell", model);
        JsonObject windows = new JsonObject();
        windows.addProperty("gesamt", latest.size());
        windows.addProperty("fehlerErsterDurchgang", failedFirst);
        windows.addProperty("wiederholt", repeated);
        windows.addProperty("fehler", failed);
        windows.addProperty("nichtGefragt", notAsked);
        windows.add("fehlerGruende", countsJson(reasons));
        windows.addProperty("ohneVorschlag", empty);
        windows.addProperty("mitHoechstzahl", full);
        json.add("fenster", windows);

        JsonObject proposalsJson = new JsonObject();
        proposalsJson.addProperty("gesamt", proposals.size());
        int okWindows = latest.size() - failed;
        proposalsJson.addProperty("jeFenster", okWindows == 0 ? 0.0
                : Math.round(100.0 * proposals.size() / okWindows) / 100.0);
        proposalsJson.addProperty("mitFundstelle", withFinding);
        proposalsJson.addProperty("anteilMitFundstelle", round(share(withFinding, proposals.size())));
        JsonObject checksJson = new JsonObject();
        for (TopicProposal.FindingCheck check : TopicProposal.FindingCheck.values()) {
            checksJson.addProperty(check.name(), checks.getOrDefault(check, 0));
        }
        proposalsJson.add("fundstellenPruefung", checksJson);
        proposalsJson.addProperty("fragenJeVorschlag", proposals.isEmpty() ? 0.0
                : Math.round(100.0 * questions / proposals.size()) / 100.0);
        proposalsJson.addProperty("ohneFrage", withoutQuestions);
        proposalsJson.add("verworfen", countsJson(dropped));
        List<String> nearKnown = new ArrayList<String>();
        Map<String, JsonObject> names = new LinkedHashMap<String, JsonObject>();
        Map<String, Set<String>> nameSources = new HashMap<String, Set<String>>();
        for (TopicProposal proposal : proposals) {
            if (nearKnown(proposal.getName(), known) && !nearKnown.contains(proposal.getName())) {
                nearKnown.add(proposal.getName());
            }
            String key = fold(proposal.getName());
            JsonObject entry = names.get(key);
            if (entry == null) {
                entry = new JsonObject();
                entry.addProperty("name", proposal.getName());
                entry.addProperty("beschreibung", proposal.getDescription());
                entry.addProperty("anzahl", 0);
                entry.addProperty("mitFundstelle", 0);
                names.put(key, entry);
                nameSources.put(key, new LinkedHashSet<String>());
            }
            entry.addProperty("anzahl", entry.get("anzahl").getAsInt() + 1);
            entry.addProperty("mitFundstelle", entry.get("mitFundstelle").getAsInt()
                    + (proposal.hasFinding() ? 1 : 0));
            nameSources.get(key).add(proposal.getSourceId());
        }
        int near = 0;
        for (TopicProposal proposal : proposals) {
            near += nearKnown(proposal.getName(), known) ? 1 : 0;
        }
        proposalsJson.addProperty("nahBekannt", near);
        proposalsJson.add("nahBekanntNamen", strings(nearKnown));
        proposalsJson.addProperty("verschiedeneNamen", names.size());
        json.add("vorschlaege", proposalsJson);

        JsonArray topics = new JsonArray();
        Map<String, int[]> byVerdict = new LinkedHashMap<String, int[]>();
        for (String verdict : VERDICTS) {
            byVerdict.put(verdict, new int[2]);
        }
        int hitTopics = 0;
        for (Expected topic : expected) {
            List<String> hitNames = new ArrayList<String>();
            Set<String> hitSources = new LinkedHashSet<String>();
            int verified = 0;
            int unverified = 0;
            int fromSample = 0;
            Set<String> where = sampleSources.getOrDefault(topic.name, Collections.<String>emptySet());
            for (TopicProposal proposal : proposals) {
                if (!hitsAny(proposal.getName() + " " + proposal.getDescription(), topic.keywords)) {
                    continue;
                }
                if (!proposal.hasFinding()) {
                    unverified++;
                    continue;
                }
                verified++;
                hitSources.add(proposal.getSourceId());
                fromSample += where.contains(proposal.getSourceId()) ? 1 : 0;
                if (!hitNames.contains(proposal.getName()) && hitNames.size() < 20) {
                    hitNames.add(proposal.getName());
                }
            }
            boolean hit = verified > 0;
            hitTopics += hit ? 1 : 0;
            int[] count = byVerdict.computeIfAbsent(topic.verdict, v -> new int[2]);
            count[0] += hit ? 1 : 0;
            count[1]++;
            JsonObject t = new JsonObject();
            t.addProperty("name", topic.name);
            t.addProperty("erwartet", topic.verdict);
            t.addProperty("getroffen", hit);
            t.addProperty("treffer", verified);
            t.addProperty("trefferOhneFundstelle", unverified);
            t.addProperty("quellenMitTreffer", hitSources.size());
            t.addProperty("trefferAusStichprobenquellen", fromSample);
            t.add("trefferNamen", strings(hitNames));
            topics.add(t);
        }
        json.add("themen", topics);
        JsonObject verdicts = new JsonObject();
        for (Map.Entry<String, int[]> entry : byVerdict.entrySet()) {
            verdicts.addProperty(entry.getKey(), entry.getValue()[0] + " von " + entry.getValue()[1]);
        }
        json.add("trefferNachUrteil", verdicts);

        List<JsonObject> sorted = new ArrayList<JsonObject>(names.values());
        for (Map.Entry<String, JsonObject> entry : names.entrySet()) {
            entry.getValue().addProperty("quellen", nameSources.get(entry.getKey()).size());
        }
        sorted.sort((a, b) -> {
            int byCount = Integer.compare(b.get("anzahl").getAsInt(), a.get("anzahl").getAsInt());
            return byCount != 0 ? byCount : a.get("name").getAsString().compareTo(b.get("name").getAsString());
        });
        JsonArray namesJson = new JsonArray();
        for (JsonObject entry : sorted) {
            namesJson.add(entry);
        }
        json.add("namen", namesJson);

        JsonObject criteria = new JsonObject();
        List<String> missed = new ArrayList<String>();
        criterion(criteria, missed, "K1", "erwartete Themen mit Treffer und Fundstelle", hitTopics,
                settings.minHits, true, expected.size());
        double findingShare = share(withFinding, proposals.size());
        criterion(criteria, missed, "K2", "Anteil der Vorschläge mit gefundener Fundstelle", findingShare,
                settings.minFindings, true, -1);
        double failedShare = share(failed, latest.size());
        criterion(criteria, missed, "K3", "Anteil der Fenster mit FEHLER nach der Wiederholung", failedShare,
                settings.maxFailed, false, -1);
        json.add("kriterien", criteria);
        json.addProperty("bestanden", missed.isEmpty());
        json.addProperty("befund", (missed.isEmpty() ? "bestanden" : "nicht bestanden: " + String.join(", ", missed))
                + "; " + hitTopics + " von " + expected.size() + " erwarteten Themen getroffen, " + proposals.size()
                + " Vorschläge, " + Math.round(100 * findingShare) + " % mit Fundstelle, " + failed + " von "
                + latest.size() + " Fenstern FEHLER");
        return json;
    }

    private static void criterion(JsonObject criteria, List<String> missed, String name, String what, double value,
                                  double limit, boolean atLeast, int of) {
        boolean met = atLeast ? value >= limit : value <= limit;
        JsonObject c = new JsonObject();
        c.addProperty("was", what);
        c.addProperty("wert", of >= 0 ? (Object) (int) value + " von " + of : String.valueOf(round(value)));
        c.addProperty("grenze", (atLeast ? ">= " : "<= ") + (of >= 0 ? String.valueOf((int) limit)
                : String.valueOf(limit)));
        c.addProperty("erfuellt", met);
        criteria.add(name, c);
        if (!met) {
            missed.add(name);
        }
    }

    /** A later model against the deciding one. */
    static JsonObject compare(JsonObject decisive, JsonObject other, Settings settings) {
        int hitsA = hitCount(decisive);
        int hitsB = hitCount(other);
        double shareA = decisive.getAsJsonObject("vorschlaege").get("anteilMitFundstelle").getAsDouble();
        double shareB = other.getAsJsonObject("vorschlaege").get("anteilMitFundstelle").getAsDouble();
        int failedA = decisive.getAsJsonObject("fenster").get("fehler").getAsInt();
        int failedB = other.getAsJsonObject("fenster").get("fehler").getAsInt();
        JsonObject c = new JsonObject();
        c.addProperty("modell", other.get("modell").getAsString());
        c.addProperty("gegen", decisive.get("modell").getAsString());
        c.addProperty("treffer", hitsB + " gegen " + hitsA);
        c.addProperty("anteilMitFundstelle", round(shareB) + " gegen " + round(shareA));
        c.addProperty("fensterFehler", failedB + " gegen " + failedA);
        c.addProperty("nichtSchlechter", hitsB >= hitsA - settings.toleranceTopics
                && shareB >= shareA - settings.tolerance && failedB <= failedA);
        return c;
    }

    private static int hitCount(JsonObject model) {
        int hits = 0;
        for (JsonElement topic : model.getAsJsonArray("themen")) {
            hits += topic.getAsJsonObject().get("getroffen").getAsBoolean() ? 1 : 0;
        }
        return hits;
    }

    private static String reasonOf(String problem) {
        if (problem.startsWith("not asked")) {
            return "nicht gefragt (Schutzschalter)";
        }
        Matcher status = Pattern.compile("^model call failed \\((\\w+)\\)").matcher(problem);
        if (status.find()) {
            return "Aufruf " + status.group(1);
        }
        return problem.startsWith("invalid answer") ? "ungültige Antwort" : "anderes";
    }

    private static JsonObject settingsJson(Settings settings) {
        JsonObject json = new JsonObject();
        json.add("modelle", strings(settings.models));
        json.addProperty("gleichzeitig", settings.parallel);
        json.addProperty("fensterZeichen", settings.windowChars);
        json.addProperty("passageZeichen", settings.passageChars);
        json.addProperty("limit", settings.limit);
        json.add("nurQuellen", strings(new ArrayList<String>(settings.onlySources)));
        json.addProperty("wiederholen", settings.retry);
        json.addProperty("minTreffer", settings.minHits);
        json.addProperty("minFundstellen", settings.minFindings);
        json.addProperty("maxFehler", settings.maxFailed);
        json.addProperty("toleranz", settings.tolerance);
        json.addProperty("toleranzThemen", settings.toleranceTopics);
        return json;
    }

    // ------------------------------------------------------------------------------------------------- matching

    /** Lower case, umlauts as two letters, accents off, every other sign a blank, blanks single. */
    static String fold(String text) {
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue").replace("ß", "ss");
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** {@code =word} whole word, {@code ^word} at a word start, anything else anywhere. */
    static boolean hits(String text, String keyword) {
        String folded = " " + fold(text) + " ";
        if (keyword.startsWith("=")) {
            return folded.contains(" " + fold(keyword.substring(1)) + " ");
        }
        if (keyword.startsWith("^")) {
            return folded.contains(" " + fold(keyword.substring(1)));
        }
        return folded.contains(fold(keyword));
    }

    static boolean hitsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (hits(text, keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Half of the longer words of the name or more shared with the words of a known topic. */
    static boolean nearKnown(String name, List<String> known) {
        Set<String> words = words(name);
        if (words.isEmpty()) {
            return false;
        }
        for (String topic : known) {
            Set<String> other = words(topic);
            Set<String> both = new LinkedHashSet<String>(words);
            both.retainAll(other);
            Set<String> either = new LinkedHashSet<String>(words);
            either.addAll(other);
            if (!either.isEmpty() && 2 * both.size() >= either.size()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<String>();
        for (String word : fold(text).split(" ")) {
            if ((word.length() >= 4 || word.matches("\\d+")) && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** The stock as the replay corpus holds it; the passages of a source sorted by their number ({@code #pN}). */
    static Stock readStock(Reader in) {
        JsonObject corpus = JsonParser.parseReader(in).getAsJsonObject();
        Stock stock = new Stock();
        JsonElement mission = corpus.get("mission");
        if (mission == null || !mission.isJsonPrimitive() || mission.getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException("the corpus holds no mission");
        }
        stock.mission = mission.getAsString().trim();
        stock.title = text(corpus, "title");
        String language = text(corpus, "language");
        stock.language = language.isEmpty() ? "de" : language;
        Map<String, List<JsonObject>> bySource = new LinkedHashMap<String, List<JsonObject>>();
        Map<String, String> domains = new HashMap<String, String>();
        JsonArray passages = corpus.getAsJsonArray("passages");
        for (JsonElement element : passages == null ? new JsonArray() : passages) {
            JsonObject passage = element.getAsJsonObject();
            String source = text(passage, "sourceId");
            String id = text(passage, "id");
            if (source.isEmpty() || id.isEmpty()) {
                throw new IllegalArgumentException("a passage without id or source: " + id);
            }
            bySource.computeIfAbsent(source, s -> new ArrayList<JsonObject>()).add(passage);
            domains.putIfAbsent(source, text(passage, "domain"));
            stock.sourceOfPassage.put(id, source);
            String capture = text(passage, "captureId");
            if (!capture.isEmpty()) {
                stock.sourceOfCapture.put(capture, source);
            }
            stock.passages++;
            stock.damaged += MOJIBAKE.matcher(text(passage, "text")).find() ? 1 : 0;
        }
        for (Map.Entry<String, List<JsonObject>> entry : bySource.entrySet()) {
            List<JsonObject> list = new ArrayList<JsonObject>(entry.getValue());
            list.sort((a, b) -> Integer.compare(numberOf(text(a, "id")), numberOf(text(b, "id"))));
            List<SourcePassage> ordered = new ArrayList<SourcePassage>();
            for (JsonObject passage : list) {
                ordered.add(new SourcePassage(text(passage, "id"), text(passage, "text")));
            }
            String domain = domains.get(entry.getKey());
            stock.sources.add(new Source(entry.getKey(), domain.isEmpty() ? "" : "https://" + domain + "/", "", domain,
                    ordered));
        }
        return stock;
    }

    private static int numberOf(String passageId) {
        Matcher number = PASSAGE_NUMBER.matcher(passageId);
        return number.find() ? Integer.parseInt(number.group(1)) : Integer.MAX_VALUE;
    }

    /** The expected topics with the key words of each; every topic needs key words, every key word a topic. */
    static List<Expected> readExpected(String expectedJson, String keywordsJson) {
        JsonObject keywords = JsonParser.parseString(keywordsJson).getAsJsonObject().getAsJsonObject("themen");
        List<Expected> topics = new ArrayList<Expected>();
        Set<String> names = new LinkedHashSet<String>();
        for (JsonElement element : JsonParser.parseString(expectedJson).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject t = element.getAsJsonObject();
            Expected topic = new Expected();
            topic.name = text(t, "name");
            topic.verdict = text(t, "erwartet");
            for (JsonElement sheet : t.getAsJsonArray("blaetter")) {
                topic.sheets.add(sheet.getAsString());
            }
            JsonElement words = keywords.get(topic.name);
            if (words == null || !words.isJsonArray() || words.getAsJsonArray().size() == 0) {
                throw new IllegalArgumentException("no key words for '" + topic.name + "'");
            }
            for (JsonElement word : words.getAsJsonArray()) {
                String keyword = word.getAsString().trim();
                if (keyword.replaceFirst("^[=^]", "").isEmpty()) {
                    throw new IllegalArgumentException("an empty key word for '" + topic.name + "'");
                }
                topic.keywords.add(keyword);
            }
            if (!names.add(topic.name)) {
                throw new IllegalArgumentException("'" + topic.name + "' twice");
            }
            topics.add(topic);
        }
        for (String name : keywords.keySet()) {
            if (!names.contains(name)) {
                throw new IllegalArgumentException("key words for an unknown topic '" + name + "'");
            }
        }
        return topics;
    }

    /** Sheet id to the passages of its sample. */
    static Map<String, List<String>> samplesOf(String sheetsJson) {
        Map<String, List<String>> samples = new LinkedHashMap<String, List<String>>();
        for (JsonElement element : JsonParser.parseString(sheetsJson).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject sheet = element.getAsJsonObject();
            List<String> passages = new ArrayList<String>();
            JsonArray sample = sheet.getAsJsonArray("stichprobe");
            for (JsonElement passage : sample == null ? new JsonArray() : sample) {
                passages.add(passage.getAsString());
            }
            samples.put(text(sheet, "blatt"), passages);
        }
        return samples;
    }

    /** The themes of a question file in the form of fragen-1711.json, in the order of their numbers. */
    static List<String> knownOf(String questionsJson) {
        JsonObject themes = JsonParser.parseString(questionsJson).getAsJsonObject().getAsJsonObject("themen");
        Map<Integer, String> ordered = new TreeMap<Integer, String>();
        for (Map.Entry<String, JsonElement> theme : themes.entrySet()) {
            ordered.put(Integer.valueOf(theme.getKey()), theme.getValue().getAsString());
        }
        return new ArrayList<String>(ordered.values());
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static JsonObject countsJson(Map<String, Integer> counts) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            json.addProperty(count.getKey(), count.getValue());
        }
        return json;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> idsOf(Source source) {
        List<String> ids = new ArrayList<String>();
        for (SourcePassage passage : source.getPassages()) {
            ids.add(passage.getPassageId());
        }
        return ids;
    }

    private static double share(int part, int whole) {
        return whole == 0 ? 0.0 : (double) part / whole;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static String required(String name) {
        String value = System.getProperty(P + name, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("the topic finder stock test needs -D" + P + name);
        }
        return value;
    }

    private static double number(String name, double fallback) {
        String value = System.getProperty(P + name, "");
        return value.isEmpty() ? fallback : Double.parseDouble(value);
    }

    private static List<String> list(String value) {
        List<String> values = new ArrayList<String>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        InputStream in = TopicFinderStockTest.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("resource " + name + " missing");
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------------------------------------- sample

    static final String SAMPLE_MISSION = "Ein Sachbuch für Stadtplaner über Verkehrssicherheit in Innenstädten.";
    private static final String S1A = "Nach Einführung von Tempo 30 in den Wohnstraßen sank die Zahl der schweren "
            + "Unfälle im ersten Jahr deutlich.";
    private static final String S1B = "Lastenräder übernehmen die letzte Meile zu den Geschäften, seit der "
            + "Lieferverkehr nur bis zehn Uhr fahren darf.";
    private static final String S1C = "Die Stadt plant weitere Ladezonen am Rand der Fußgängerzone.";
    private static final String S2 = "Feste Blitzer an Schulwegen senken die Geschwindigkeit, berichtet die Polizei "
            + "nach zwei Jahren Messung.";
    private static final String S3 = "Nachtbaustellen auf Hauptstraßen werden mit gelben Leuchten und Baken "
            + "gesichert.";

    /** Three sources; the passages of the first are out of order in the file. */
    static String sampleCorpus() {
        JsonObject corpus = new JsonObject();
        corpus.addProperty("title", "Beispielbestand");
        corpus.addProperty("mission", SAMPLE_MISSION);
        corpus.addProperty("language", "de");
        JsonArray passages = new JsonArray();
        passages.add(passage("cap-1#p10@seg", "source-1", "cap-1", "stadt.example", S1C));
        passages.add(passage("cap-1#p0@seg", "source-1", "cap-1", "stadt.example", S1A));
        passages.add(passage("cap-2#p0@seg", "source-2", "cap-2", "polizei.example", S2));
        passages.add(passage("cap-1#p2@seg", "source-1", "cap-1", "stadt.example", S1B));
        passages.add(passage("cap-3#p0@seg", "source-3", "cap-3", "bau.example", S3));
        corpus.add("passages", passages);
        return corpus.toString();
    }

    private static JsonObject passage(String id, String source, String capture, String domain, String text) {
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("sourceId", source);
        p.addProperty("captureId", capture);
        p.addProperty("domain", domain);
        p.addProperty("heading", "");
        p.addProperty("text", text);
        return p;
    }

    static final String SAMPLE_EXPECTED = "{\"themen\":["
            + "{\"name\":\"Lastenräder in der Innenstadt\",\"blaetter\":[\"T01\"],\"erwartet\":\"INNEN\"},"
            + "{\"name\":\"Blitzer an Schulwegen\",\"blaetter\":[\"T02\"],\"erwartet\":\"INNEN\"},"
            + "{\"name\":\"Nachtbaustellen\",\"blaetter\":[\"T03\"],\"erwartet\":\"AUSSEN\"}]}";
    static final String SAMPLE_KEYWORDS = "{\"themen\":{"
            + "\"Lastenräder in der Innenstadt\":[\"lastenra\"],"
            + "\"Blitzer an Schulwegen\":[\"blitzer\",\"geschwindigkeitskontrolle\"],"
            + "\"Nachtbaustellen\":[\"nachtbaustelle\"]}}";
    static final String SAMPLE_SHEETS = "{\"themen\":["
            + "{\"blatt\":\"T01\",\"stichprobe\":[\"cap-1#p2@seg\"]},"
            + "{\"blatt\":\"T02\",\"stichprobe\":[\"cap-9#p1@seg\"]},"
            + "{\"blatt\":\"T03\",\"stichprobe\":[\"cap-3#p0@seg\"]}]}";

    /**
     * "richtig" answers by the source in the prompt: source 1 gives a proposal with a verbatim quote and one close to
     * the known topic, source 2 fails twice and answers on the repeat, source 3 gives a proposal whose quote stands
     * nowhere. "fehlerhaft" fails every call.
     */
    static final class Scripted implements MainModelChat {
        private final String model;
        private final AtomicInteger blitzerCalls = new AtomicInteger();

        Scripted(String model) {
            this.model = model;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            if ("fehlerhaft".equals(model)) {
                return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow");
            }
            String prompt = messages.get(1).getContent();
            if (prompt.contains("(stadt.example)")) {
                return MainModelChatResult.ok("{\"vorschlaege\":["
                        + item(2, "Lastenräder übernehmen die letzte Meile zu den Geschäften",
                        "Lastenräder in der Innenstadt", "Wie Lastenräder Lieferwagen ersetzen.") + ","
                        + item(1, "sank die Zahl der schweren Unfälle im ersten Jahr", "Tempo 30 in Wohnstraßen",
                        "Wie Tempo 30 in Wohnstraßen wirkt.") + "]}");
            }
            if (prompt.contains("(polizei.example)")) {
                if (blitzerCalls.incrementAndGet() <= 2) {
                    return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow");
                }
                return MainModelChatResult.ok("{\"vorschlaege\":[" + item(1, "Feste Blitzer an Schulwegen senken "
                        + "die Geschwindigkeit", "Feste Blitzer an Schulwegen", "Wie Blitzer wirken.") + "]}");
            }
            return MainModelChatResult.ok("{\"vorschlaege\":[" + item(1, "Nachtbaustellen werden rot beleuchtet "
                    + "und gesperrt", "Sicherung von Nachtbaustellen", "Wie Nachtbaustellen gesichert werden.")
                    + "]}");
        }

        @Override
        public String modelName() {
            return model;
        }

        private static String item(int passage, String quote, String name, String description) {
            return "{\"passage\":" + passage + ",\"zitat\":\"" + quote + "\",\"name\":\"" + name + "\","
                    + "\"beschreibung\":\"" + description + "\",\"fragen\":[\"Was zeigt die Quelle?\"]}";
        }
    }
}
