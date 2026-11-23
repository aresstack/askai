package com.aresstack.askai.research.sourcerating;

import com.aresstack.askai.research.knowledge.processing.Utf8Properties;
import com.aresstack.askai.research.runtime.sourcerating.KnownDomains;
import com.aresstack.askai.research.runtime.sourcerating.SourceRater;
import com.aresstack.askai.research.runtime.sourcerating.SourceRating;
import com.aresstack.askai.research.runtime.sourcerating.SourceRatingLog;
import com.aresstack.askai.research.runtime.sourcerating.SourceSample;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 16 on the stock of the book of 17 Nov: the source rating against the architect's verdict on the 82 domains
 * the book cites ({@code quellen-1711.json}, 1613 citations, 61 sure domains with 1214). The test hands the rater every
 * source of the corpus whose domain the file names, in the order of the source numbers, with address and title from
 * the workspace and the first passage from the corpus; the rater reads each domain once (its first source with text),
 * as in the product. Two arms: "mit" with the shipped list of known domains, "ohne" without it. Three readings: "mit"
 * and "ohne" over all domains, and "mitAusserhalbListe" over the domains the list does not know. The list was written
 * after the file had been seen, so on the listed domains "mit" measures the list, not the model; outside the list the
 * prompts of both arms are the same. {@code .entscheidet} names the reading that decides (ohne).
 *
 * <p>Thresholds of the pre-registration ({@code c16-prereg.json}, 22.11.2026 00:14), each a property: Q1 the right kind
 * for at least {@code .minQuellenart} (0.85) of the citations of sure domains; Q2 at most {@code .maxHerstellerFalsch}
 * (1) sure manufacturer or dealer domain rated as science, authority or law; Q3 the right jurisdiction for at least
 * {@code .minRechtsraum} (0.95) of the citations of sure domains whose kind carries one (law, authority, law firm,
 * association); Q4 of the sure domains with self-interest JA at most {@code .maxJaAlsNein} (2) rated NEIN; Q5 at most
 * {@code .maxOhneUrteil} (2) domains with FEHLER or without a rating, never counted as rated; Q6 every domain of the
 * file stands in the result (the component filters nothing). Unsure domains count for the report only.</p>
 *
 * <p>{@link #sampleStock()} runs the whole path on a self-written stock with a scripted model in every build;
 * {@link #storedStock()} needs Ollama and is skipped without {@code .chatUrl}:</p>
 * <pre>
 * ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*SourceRatingStockTest*'
 *   -Daskai.stock.sources.chatUrl=http://127.0.0.1:11434 -Daskai.stock.sources.chatModel=gemma4:12b
 *   -Daskai.stock.sources.corpus=replay-corpus.json (format of the outline replay, #255)
 *   -Daskai.stock.sources.workspace=(the workspace of the stock; read only: sources/*.properties, address and title)
 *   -Daskai.stock.sources.out=build/component-stock/source-rating
 *   optional: .expected (another file in the form of quellen-1711.json), .arme (beide|mit|ohne),
 *             .entscheidet (ohne|mit|mitAusserhalbListe), .minQuellenart (0.85), .maxHerstellerFalsch (1),
 *             .minRechtsraum (0.95), .maxJaAlsNein (2), .maxOhneUrteil (2), .parallel (4), .passageChars (1200),
 *             .numCtx (only when set), .think=omit (else think:false is sent), .timeoutSeconds (120)
 * </pre>
 * {@code result.json} holds per domain the verdict of the file, the list's suggestion, the source read, and per arm the
 * rating, its reason, every raw answer and whether kind, jurisdiction and self-interest agree; per reading the measures
 * Q1 to Q6 and what is only reported; no passage text. Each arm also writes its ratings through
 * {@link SourceRatingLog} ({@code <out>/<arm>/source-ratings/log.jsonl}) and reads them back.
 */
public class SourceRatingStockTest {

    private static final String P = "askai.stock.sources.";
    static final String WITH = "mit";
    static final String WITHOUT = "ohne";
    static final String WITH_OUTSIDE_LIST = "mitAusserhalbListe";
    static final String BOTH = "beide";
    static final String EXPECTED = "/source-rating-stock/quellen-1711.json";
    /** The kinds whose jurisdiction the file judges; for the others OHNE and the publisher's country are both right. */
    static final Set<SourceRating.Kind> JURISDICTION_KINDS = Collections.unmodifiableSet(EnumSet.of(
            SourceRating.Kind.GESETZ_NORM, SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG,
            SourceRating.Kind.KANZLEI_BERATUNG, SourceRating.Kind.VERBAND));
    /** What a manufacturer or dealer must not become (Q2). */
    static final Set<SourceRating.Kind> NEUTRAL_KINDS = Collections.unmodifiableSet(EnumSet.of(
            SourceRating.Kind.WISSENSCHAFT, SourceRating.Kind.BEHOERDE_UNFALLVERSICHERUNG,
            SourceRating.Kind.GESETZ_NORM));
    /** UTF-8 read as Latin-1 ("Ã¤", "â€", "Â§"): a damaged copy of the corpus looks like this. */
    static final Pattern MOJIBAKE = Pattern.compile("\\u00C3[\\u0080-\\u00BF]|\\u00E2\\u20AC|\\u00C2[\\u00A0-\\u00BF]");
    private static final Pattern PASSAGE_INDEX = Pattern.compile("#p(\\d{1,9})");
    private static final Pattern NUMBER_AT_END = Pattern.compile("(\\d{1,18})$");
    static final String NOT_IN_STOCK = "NICHT_IM_BESTAND";
    private static final String FAILED = "FEHLER";

    @Rule
    public TemporaryFolder scratch = new TemporaryFolder();

    /** One domain of the expected file. */
    static final class Expected {
        final String domain;
        final int citations;
        final SourceRating.Kind kind;
        final SourceRating.Jurisdiction jurisdiction;
        final SourceRating.SelfInterest selfInterest;
        final boolean sure;

        Expected(String domain, int citations, SourceRating.Kind kind, SourceRating.Jurisdiction jurisdiction,
                 SourceRating.SelfInterest selfInterest, boolean sure) {
            this.domain = domain;
            this.citations = citations;
            this.kind = kind;
            this.jurisdiction = jurisdiction;
            this.selfInterest = selfInterest;
            this.sure = sure;
        }
    }

    /** One source of the corpus with its first passage: the lowest p index, on a tie the earlier in the file. */
    static final class CorpusSource {
        final String sourceId;
        final String domain;
        int passages;
        String passageId = "";
        int passageIndex = Integer.MAX_VALUE;
        String text;

        CorpusSource(String sourceId, String domain) {
            this.sourceId = sourceId;
            this.domain = domain;
        }
    }

    /** What the test reads of the corpus: the sources and how many passages look damaged. */
    static final class Stock {
        final Map<String, CorpusSource> sources = new LinkedHashMap<String, CorpusSource>();
        int passages;
        int withoutSource;
        int mojibake;

        void add(String id, String sourceId, String domain, String text) {
            passages++;
            if (MOJIBAKE.matcher(text).find()) {
                mojibake++;
            }
            if (sourceId.isEmpty()) {
                withoutSource++;
                return;
            }
            CorpusSource source = sources.get(sourceId);
            if (source == null) {
                source = new CorpusSource(sourceId, SourceSample.hostOf(domain));
                sources.put(sourceId, source);
            }
            source.passages++;
            int index = passageIndex(id);
            if (source.text == null || index < source.passageIndex) {
                source.passageId = id;
                source.passageIndex = index;
                source.text = text;
            }
        }
    }

    /** The settings of one stock run. */
    static final class Settings {
        int parallel = SourceRater.Options.DEFAULT_PARALLEL;
        int passageChars = SourceRater.Options.DEFAULT_PASSAGE_CHARS;
        double minKind = 0.85;
        int maxMakerAsNeutral = 1;
        double minJurisdiction = 0.95;
        int maxJaAsNein = 2;
        int maxUnrated = 2;
        String arms = BOTH;
        String decides = WITHOUT;

        List<String> armList() {
            return BOTH.equals(arms) ? Arrays.asList(WITH, WITHOUT) : Collections.singletonList(arms);
        }

        void check() {
            if (!BOTH.equals(arms) && !WITH.equals(arms) && !WITHOUT.equals(arms)) {
                throw new IllegalArgumentException("-D" + P + "arme must be beide, mit or ohne");
            }
            if (!WITH.equals(decides) && !WITHOUT.equals(decides) && !WITH_OUTSIDE_LIST.equals(decides)) {
                throw new IllegalArgumentException("-D" + P + "entscheidet must be ohne, mit or mitAusserhalbListe");
            }
            String needs = WITHOUT.equals(decides) ? WITHOUT : WITH;
            if (!armList().contains(needs)) {
                throw new IllegalArgumentException("the reading " + decides + " needs the arm " + needs);
            }
        }
    }

    /** Counts and times the calls of an arm. */
    static final class TimedChat implements MainModelChat {
        private final MainModelChat inner;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
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
            MainModelChatResult result;
            try {
                result = inner.completeJson(messages, temperature, maxOutputTokens, schemaJson);
            } finally {
                nanos.addAndGet(System.nanoTime() - start);
            }
            if (result == null || !result.isOk()) {
                failed.incrementAndGet();
            }
            return result;
        }

        @Override
        public String modelName() {
            return inner.modelName();
        }
    }

    /** A model from a script: the domain line of the prompt picks the answer, with or without the list's hint. */
    static final class Scripted implements MainModelChat {
        final Map<String, String[]> byDomain = new LinkedHashMap<String, String[]>();
        final AtomicInteger calls = new AtomicInteger();
        final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());
        volatile boolean down;

        Scripted answer(String domain, String withHint, String withoutHint) {
            byDomain.put(domain, new String[]{withHint, withoutHint});
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
            String prompt = messages.get(1).getContent();
            prompts.add(prompt);
            if (down) {
                return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "model down");
            }
            int from = prompt.indexOf("Domain: ") + "Domain: ".length();
            String domain = prompt.substring(from, prompt.indexOf('\n', from));
            String[] answers = byDomain.get(domain);
            if (answers == null) {
                return MainModelChatResult.ok(rating("Unbekannt", "SONSTIGE", "OHNE", "UNKLAR", ""));
            }
            return MainModelChatResult.ok(prompt.contains("Hinweis aus der Liste bekannter Anbieter") ? answers[0]
                    : answers[1]);
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }

    static String rating(String publisher, String kind, String jurisdiction, String selfInterest, String date) {
        JsonObject o = new JsonObject();
        o.addProperty("anbieter", publisher);
        o.addProperty("grund", "Grund zu " + publisher + ".");
        o.addProperty("quellenart", kind);
        o.addProperty("rechtsraum", jurisdiction);
        o.addProperty("eigeninteresse", selfInterest);
        o.addProperty("datum", date);
        return o.toString();
    }

    @Test
    public void theRepoCopyOfTheVerdictIsComplete() throws Exception {
        List<Expected> expected = expected(resource(EXPECTED));
        int citations = 0;
        int sure = 0;
        int sureCitations = 0;
        int sureJa = 0;
        for (Expected e : expected) {
            citations += e.citations;
            if (e.sure) {
                sure++;
                sureCitations += e.citations;
                sureJa += e.selfInterest == SourceRating.SelfInterest.JA ? 1 : 0;
            }
        }
        assertEquals(82, expected.size());
        assertEquals(1613, citations);
        assertEquals(61, sure);
        assertEquals(1214, sureCitations);
        assertEquals("Q4 counts the sure domains with JA", 23, sureJa);
    }

    @Test
    public void sampleStock() throws Exception {
        File corpus = scratch.newFile("corpus.json");
        Files.write(corpus.toPath(), sampleCorpus().getBytes(StandardCharsets.UTF_8));
        File workspace = scratch.newFolder("workspace");
        File sources = new File(workspace, "sources");
        assertTrue(sources.mkdirs());
        property(sources, "source-1", "https://publikationen.dguv.de/widgets/pdf/download/article/123",
                "Wearables im Betrieb – Gefährdungsbeurteilung");
        property(sources, "source-2", "https://www.wearable-firma.example/shop/weste", "Sensorweste kaufen");
        property(sources, "source-3", "https://www.gesetze-im-internet.de/arbschg/__5.html", "§ 5 ArbSchG");
        property(sources, "source-4", "https://publikationen.dguv.de/2023/05/schrift.pdf", "Weitere Schrift");
        property(sources, "source-6", "https://blog-unbekannt.example/2022/03/eintrag", "Eintrag");
        property(sources, "source-7", "https://andere-seite.example/", "Andere");
        property(sources, "source-9", "https://institut-forschung.example/studie", "Studie");
        property(sources, "source-12", "https://forschung-institut.example/spaeter", "Spätere Studie");
        Stock stock;
        try (Reader in = new InputStreamReader(new FileInputStream(corpus), StandardCharsets.UTF_8)) {
            stock = readCorpus(in);
        }
        Map<String, String[]> meta = workspaceSources(workspace);
        List<Expected> expected = expected(sampleExpected());
        Settings settings = new Settings();
        settings.minKind = 0.6;
        settings.minJurisdiction = 0.75;
        settings.decides = WITH;
        Scripted model = sampleModel();
        File out = scratch.newFolder("out");

        JsonObject result = run(stock, meta, expected, model, settings, "corpus.json", "workspace", "sample", out,
                "test", "");

        assertEquals(11, stock.passages);
        assertEquals(10, stock.sources.size());
        assertEquals("six domains with sources in two arms; andere-seite.example is not in the file", 12,
                model.calls.get());
        JsonObject arms = result.getAsJsonObject("arme");
        assertEquals(6, arms.getAsJsonObject(WITH).get("aufrufe").getAsInt());
        assertEquals(6, arms.getAsJsonObject(WITH).get("gruppen").getAsInt());
        assertEquals("eight sources of the file's domains", 8, arms.getAsJsonObject(WITH)
                .getAsJsonObject("ablage").get("zeilen").getAsInt());
        assertTrue(arms.getAsJsonObject(WITH).getAsJsonObject("ablage").get("gleichGelesen").getAsBoolean());
        assertTrue(new File(out, "mit/source-ratings/log.jsonl").isFile());
        assertEquals(1, result.get("andereDomainsUebersprungen").getAsInt());
        assertEquals(1, result.get("quellenOhneDomain").getAsInt());

        JsonObject with = result.getAsJsonObject("lesarten").getAsJsonObject(WITH);
        JsonObject kind = with.getAsJsonObject("Q1quellenart");
        assertEquals(103, kind.get("sichereZitate").getAsInt());
        assertEquals("dguv 40, gesetze 10, kanzlei 15, forschung 5", 70, kind.get("richtigeZitate").getAsInt());
        assertEquals(0.6796, kind.get("anteil").getAsDouble(), 1e-9);
        assertEquals(4, kind.get("richtigeDomains").getAsInt());
        assertEquals(6, kind.get("sichereDomains").getAsInt());
        JsonObject makers = with.getAsJsonObject("Q2herstellerAlsNeutral");
        assertEquals(1, makers.get("anzahl").getAsInt());
        assertTrue(makers.getAsJsonArray("domains").get(0).getAsString().startsWith("wearable-firma.example"));
        JsonObject jurisdiction = with.getAsJsonObject("Q3rechtsraum");
        assertEquals(65, jurisdiction.get("gewerteteZitate").getAsInt());
        assertEquals("kanzlei says AT", 50, jurisdiction.get("richtigeZitate").getAsInt());
        JsonObject self = with.getAsJsonObject("Q4eigeninteresse");
        assertEquals(1, self.get("sichereJa").getAsInt());
        assertEquals(1, self.get("jaAlsNein").getAsInt());
        JsonObject unrated = with.getAsJsonObject("Q5ohneUrteil");
        assertEquals(1, unrated.get("anzahl").getAsInt());
        assertEquals("[\"fehlt.example: nicht im Bestand\"]", unrated.get("domains").toString());
        JsonObject all = with.getAsJsonObject("Q6alleGruppen");
        assertEquals(6, all.get("imErgebnis").getAsInt());
        assertEquals(7, all.get("domains").getAsInt());
        assertFalse(all.get("erfuellt").getAsBoolean());
        assertEquals(1, with.getAsJsonObject("verwechslung").getAsJsonObject("HERSTELLER_HANDEL")
                .get("WISSENSCHAFT").getAsInt());
        assertEquals("[\"blog-unbekannt.example: SONSTIGE -> SONSTIGE\"]",
                with.getAsJsonArray("unsicher").toString());
        assertEquals(1, with.getAsJsonObject("eigeninteresseMatrix").getAsJsonObject("JA").get("NEIN")
                .getAsInt());
        JsonObject criteria = with.getAsJsonObject("kriterien");
        assertTrue(criteria.get("Q1").getAsBoolean());
        assertTrue(criteria.get("Q2").getAsBoolean());
        assertTrue(criteria.get("Q3").getAsBoolean());
        assertTrue(criteria.get("Q4").getAsBoolean());
        assertTrue(criteria.get("Q5").getAsBoolean());
        assertFalse("fehlt.example is not in the stock", criteria.get("Q6").getAsBoolean());
        assertFalse(with.get("bestanden").getAsBoolean());

        JsonObject without = result.getAsJsonObject("lesarten").getAsJsonObject(WITHOUT);
        assertEquals("gesetze-im-internet.de is a reference work without the hint", 60,
                without.getAsJsonObject("Q1quellenart").get("richtigeZitate").getAsInt());
        assertFalse(without.getAsJsonObject("kriterien").get("Q1").getAsBoolean());
        JsonObject outside = result.getAsJsonObject("lesarten").getAsJsonObject(WITH_OUTSIDE_LIST);
        assertEquals("dguv and gesetze are listed", 5, outside.get("domains").getAsInt());
        assertEquals(53, outside.getAsJsonObject("Q1quellenart").get("sichereZitate").getAsInt());
        assertEquals(20, outside.getAsJsonObject("Q1quellenart").get("richtigeZitate").getAsInt());
        assertEquals(15, outside.getAsJsonObject("Q3rechtsraum").get("gewerteteZitate").getAsInt());
        assertEquals(0, outside.getAsJsonObject("Q3rechtsraum").get("richtigeZitate").getAsInt());
        JsonObject agreement = result.getAsJsonObject("uebereinstimmungAusserhalbListe");
        assertEquals(4, agreement.get("verglichen").getAsInt());
        assertEquals(4, agreement.get("gleich").getAsInt());
        JsonObject verdict = result.getAsJsonObject("ergebnis");
        assertEquals(WITH, verdict.get("entscheidet").getAsString());
        assertFalse(verdict.get("bestanden").getAsBoolean());
        assertTrue(verdict.get("befund").getAsString(), verdict.get("befund").getAsString().contains("Q6"));

        JsonObject dguv = domain(result, "publikationen.dguv.de");
        assertEquals("source-1", dguv.get("gelesen").getAsString());
        assertEquals("the lowest p index, not the first passage in the file", "cap-1#p0@seg-a",
                dguv.get("passage").getAsString());
        assertEquals("UTF-8 from the workspace", "Wearables im Betrieb – Gefährdungsbeurteilung",
                dguv.get("titel").getAsString());
        assertEquals(2, dguv.get("quellen").getAsInt());
        assertTrue(dguv.get("liste").getAsString().startsWith("dguv.de = BEHOERDE_UNFALLVERSICHERUNG"));
        assertTrue(dguv.getAsJsonObject("arme").getAsJsonObject(WITH).get("quellenartRichtig").getAsBoolean());
        JsonObject institute = domain(result, "forschung-institut.example");
        assertEquals("source numbers, not their text order", "source-9", institute.get("gelesen").getAsString());
        assertEquals("2023", institute.getAsJsonObject("arme").getAsJsonObject(WITH).get("datum").getAsString());
        assertEquals("https://kanzlei-recht.example/", domain(result, "kanzlei-recht.example").get("adresse")
                .getAsString());
        JsonObject law = domain(result, "gesetze-im-internet.de").getAsJsonObject("arme");
        assertEquals("GESETZ_NORM", law.getAsJsonObject(WITH).get("quellenart").getAsString());
        assertEquals("NACHSCHLAGEWERK", law.getAsJsonObject(WITHOUT).get("quellenart").getAsString());
        assertFalse(law.getAsJsonObject(WITHOUT).get("quellenartRichtig").getAsBoolean());
        assertTrue("jurisdiction is judged by the file's kind", law.getAsJsonObject(WITHOUT)
                .get("rechtsraumRichtig").getAsBoolean());
        assertFalse("no jurisdiction judged for a manufacturer", domain(result, "wearable-firma.example")
                .getAsJsonObject("arme").getAsJsonObject(WITH).has("rechtsraumRichtig"));
        assertEquals(NOT_IN_STOCK, domain(result, "fehlt.example").getAsJsonObject("arme").getAsJsonObject(WITH)
                .get("status").getAsString());
        boolean hinted = false;
        for (String prompt : model.prompts) {
            hinted |= prompt.contains("Domain: publikationen.dguv.de\n") && prompt.contains(
                    "Die Broschüre der Unfallversicherung") && prompt.contains("Hinweis aus der Liste");
        }
        assertTrue("the mit arm reads the first passage with the list's hint", hinted);

        String warnings = result.getAsJsonArray("warnungen").toString();
        assertTrue(warnings, warnings.contains("1 Passagen mit kaputten Umlauten"));
        assertTrue(warnings, warnings.contains("1 Quellen ohne Adresse"));
        assertTrue(warnings, warnings.contains("1 Quellen: Host der Adresse"));
        assertTrue(warnings, warnings.contains("1 Domains der Datei nicht im Bestand"));
        String written = new String(Files.readAllBytes(new File(out, "result.json").toPath()),
                StandardCharsets.UTF_8);
        assertFalse("no passage text in the result", written.contains("Die Broschüre der Unfallversicherung"));
        assertFalse(written.contains("Sensorweste schützt"));

        Settings passing = new Settings();
        passing.minKind = 0.65;
        passing.minJurisdiction = 0.75;
        passing.decides = WITH;
        List<Expected> inStock = new ArrayList<Expected>(expected.subList(0, 6));
        JsonObject complete = run(stock, meta, inStock, sampleModel(), passing, "corpus.json", "workspace", "sample",
                scratch.newFolder("complete"), "test", "");
        assertTrue(complete.getAsJsonObject("ergebnis").get("bestanden").getAsBoolean());
        assertEquals(100, complete.getAsJsonObject("lesarten").getAsJsonObject(WITH).getAsJsonObject("Q1quellenart")
                .get("sichereZitate").getAsInt());
        Settings byDefault = new Settings();
        byDefault.minKind = 0.65;
        byDefault.minJurisdiction = 0.75;
        JsonObject decidedWithout = run(stock, meta, inStock, sampleModel(), byDefault, "corpus.json", "workspace",
                "sample", scratch.newFolder("default"), "test", "");
        assertEquals("ohne decides by default", WITHOUT, decidedWithout.getAsJsonObject("ergebnis")
                .get("entscheidet").getAsString());
        assertFalse("ohne: 60 of 100 citations", decidedWithout.getAsJsonObject("ergebnis").get("bestanden")
                .getAsBoolean());

        Scripted down = sampleModel();
        down.down = true;
        Settings one = new Settings();
        one.arms = WITH;
        one.decides = WITH;
        one.parallel = 1;
        JsonObject failed = run(stock, meta, expected, down, one, "corpus.json", "workspace", "sample",
                scratch.newFolder("down"), "test", "");
        assertEquals("three groups with two calls each, then the rest is not asked", 6, down.calls.get());
        JsonObject failedReading = failed.getAsJsonObject("lesarten").getAsJsonObject(WITH);
        assertEquals("six FEHLER and the domain missing from the stock", 7,
                failedReading.getAsJsonObject("Q5ohneUrteil").get("anzahl").getAsInt());
        assertEquals(0, failedReading.getAsJsonObject("Q1quellenart").get("richtigeZitate").getAsInt());
        assertFalse(failed.getAsJsonObject("ergebnis").get("bestanden").getAsBoolean());
        assertFalse(failed.getAsJsonObject("lesarten").has(WITHOUT));
        assertTrue(domain(failed, "blog-unbekannt.example").getAsJsonObject("arme").getAsJsonObject(WITH)
                .get("fehler").getAsString().contains("not asked"));

        Settings wrong = new Settings();
        wrong.arms = WITH;
        try {
            wrong.check();
            fail("the reading ohne needs the arm ohne");
        } catch (IllegalArgumentException expectedRefusal) {
            assertTrue(expectedRefusal.getMessage().contains("ohne"));
        }
    }

    @Test
    public void storedStock() throws Exception {
        String chatUrl = System.getProperty(P + "chatUrl", "");
        Assume.assumeTrue("source rating stock test skipped (no -D" + P + "chatUrl)", !chatUrl.isEmpty());
        String corpusPath = System.getProperty(P + "corpus", "");
        if (corpusPath.isEmpty()) {
            throw new IllegalArgumentException("the source rating stock test needs -D" + P + "corpus");
        }
        String workspacePath = System.getProperty(P + "workspace", "");
        String expectedPath = System.getProperty(P + "expected", "");
        File out = new File(System.getProperty(P + "out", "build/component-stock/source-rating"));
        Files.createDirectories(out.toPath());
        Settings settings = new Settings();
        settings.parallel = Integer.getInteger(P + "parallel", settings.parallel);
        settings.passageChars = Integer.getInteger(P + "passageChars", settings.passageChars);
        settings.minKind = Double.parseDouble(System.getProperty(P + "minQuellenart",
                String.valueOf(settings.minKind)));
        settings.maxMakerAsNeutral = Integer.getInteger(P + "maxHerstellerFalsch", settings.maxMakerAsNeutral);
        settings.minJurisdiction = Double.parseDouble(System.getProperty(P + "minRechtsraum",
                String.valueOf(settings.minJurisdiction)));
        settings.maxJaAsNein = Integer.getInteger(P + "maxJaAlsNein", settings.maxJaAsNein);
        settings.maxUnrated = Integer.getInteger(P + "maxOhneUrteil", settings.maxUnrated);
        settings.arms = System.getProperty(P + "arme", settings.arms);
        settings.decides = System.getProperty(P + "entscheidet", settings.decides);
        settings.check();
        String chatModel = System.getProperty(P + "chatModel", "gemma4:12b");
        String numCtx = System.getProperty(P + "numCtx", "");
        boolean think = !"omit".equalsIgnoreCase(System.getProperty(P + "think", "false"));
        OllamaStockChat chat = new OllamaStockChat(chatUrl, chatModel, numCtx.isEmpty() ? null
                : Integer.valueOf(numCtx), think, 1000 * Integer.getInteger(P + "timeoutSeconds", 120));
        Stock stock;
        try (Reader in = new InputStreamReader(new FileInputStream(corpusPath), StandardCharsets.UTF_8)) {
            stock = readCorpus(in);
        }
        Map<String, String[]> workspace = workspacePath.isEmpty() ? null : workspaceSources(new File(workspacePath));
        List<Expected> expected = expected(expectedPath.isEmpty() ? resource(EXPECTED) : read(expectedPath));
        System.out.println("[source-rating] " + stock.passages + " passages of " + stock.sources.size()
                + " sources, " + expected.size() + " domains expected, model " + chatModel + " at " + chatUrl + ", "
                + settings.parallel + " at a time, arms " + settings.armList() + ", decides " + settings.decides);

        JsonObject result = run(stock, workspace, expected, chat, settings, corpusPath, workspacePath,
                expectedPath.isEmpty() ? "Repo-Kopie quellen-1711.json" : expectedPath, out, chatUrl,
                (numCtx.isEmpty() ? "" : "num_ctx " + numCtx + ", ") + (think ? "think:false" : "think weggelassen"));
        System.out.println("[source-rating] result " + new File(out, "result.json").getAbsolutePath());
        JsonObject verdict = result.getAsJsonObject("ergebnis");
        assertTrue(verdict.get("befund").getAsString(), verdict.get("bestanden").getAsBoolean());
    }

    /** Rates the sources of the expected domains in each arm, writes {@code result.json} and returns it. */
    static JsonObject run(Stock stock, Map<String, String[]> workspace, List<Expected> expected, MainModelChat chat,
                          Settings settings, String corpusSource, String workspaceSource, String expectedSource,
                          File out, String chatUrl, String chatOptions) throws IOException {
        settings.check();
        Set<String> wanted = new HashSet<String>();
        for (Expected e : expected) {
            wanted.add(e.domain);
        }
        KnownDomains list = KnownDomains.shipped();
        List<CorpusSource> ordered = new ArrayList<CorpusSource>(stock.sources.values());
        ordered.sort(Comparator.comparingLong((CorpusSource s) -> number(s.sourceId)).thenComparing(s -> s.sourceId));
        List<SourceSample> samples = new ArrayList<SourceSample>();
        Map<String, Integer> sourcesPerDomain = new HashMap<String, Integer>();
        Set<String> otherDomains = new HashSet<String>();
        int withoutDomain = 0;
        int withoutAddress = 0;
        JsonArray hostDiffers = new JsonArray();
        for (CorpusSource source : ordered) {
            if (!wanted.contains(source.domain)) {
                if (source.domain.isEmpty()) {
                    withoutDomain++;
                } else {
                    otherDomains.add(source.domain);
                }
                continue;
            }
            String[] meta = workspace == null ? null : workspace.get(source.sourceId);
            String address = meta == null ? "" : meta[0];
            if (address.isEmpty()) {
                address = "https://" + source.domain + "/";
                withoutAddress++;
            } else if (!SourceSample.hostOf(address).equals(source.domain)) {
                hostDiffers.add(source.sourceId + ": " + SourceSample.hostOf(address) + " / " + source.domain);
            }
            samples.add(new SourceSample(source.sourceId, address, meta == null ? "" : meta[1], source.domain,
                    source.text == null ? "" : source.text));
            sourcesPerDomain.merge(source.domain, 1, Integer::sum);
        }

        JsonObject result = new JsonObject();
        result.addProperty("stand", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()));
        result.addProperty("korpus", corpusSource);
        result.addProperty("passagen", stock.passages);
        result.addProperty("quellenImKorpus", stock.sources.size());
        result.addProperty("arbeitsbereich", workspaceSource);
        result.addProperty("erwartet", expectedSource);
        result.addProperty("domains", expected.size());
        result.addProperty("bewerteteQuellen", samples.size());
        result.addProperty("andereDomainsUebersprungen", otherDomains.size());
        result.addProperty("quellenOhneDomain", withoutDomain);
        JsonObject model = new JsonObject();
        model.addProperty("modell", chat.modelName());
        model.addProperty("url", chatUrl);
        model.addProperty("optionen", chatOptions);
        model.addProperty("gleichzeitig", settings.parallel);
        model.addProperty("passageZeichen", settings.passageChars);
        model.addProperty("temperatur", 0);
        model.addProperty("nachdenken", "aus");
        result.add("modell", model);
        JsonObject targets = new JsonObject();
        targets.addProperty("entscheidet", settings.decides);
        targets.addProperty("Q1quellenartMindestens", settings.minKind);
        targets.addProperty("Q2herstellerAlsNeutralHoechstens", settings.maxMakerAsNeutral);
        targets.addProperty("Q3rechtsraumMindestens", settings.minJurisdiction);
        targets.addProperty("Q4jaAlsNeinHoechstens", settings.maxJaAsNein);
        targets.addProperty("Q5ohneUrteilHoechstens", settings.maxUnrated);
        targets.addProperty("Q6", "alle Domains der Datei im Ergebnis");
        result.add("vorgaben", targets);

        JsonObject arms = new JsonObject();
        Map<String, Map<String, SourceRating>> ratingsByArm = new LinkedHashMap<String, Map<String, SourceRating>>();
        for (final String arm : settings.armList()) {
            TimedChat timed = new TimedChat(chat);
            SourceRater rater = new SourceRater(timed, WITH.equals(arm) ? list : KnownDomains.none(), "de",
                    new SourceRater.Options(settings.parallel, settings.passageChars),
                    line -> System.out.println("[source-rating] " + arm + ": " + line));
            long start = System.nanoTime();
            List<SourceRating> ratings = rater.rateAll(samples, Collections.<String, SourceRating>emptyMap());
            double wall = (System.nanoTime() - start) / 1e9;
            SourceRatingLog log = new SourceRatingLog(new File(out, arm).toPath());
            Files.deleteIfExists(log.getFile());
            log.append(ratings);
            List<SourceRating> back = log.readAll();
            boolean same = back.size() == ratings.size();
            for (int i = 0; same && i < back.size(); i++) {
                same = back.get(i).getSourceId().equals(ratings.get(i).getSourceId())
                        && back.get(i).getStatus() == ratings.get(i).getStatus()
                        && back.get(i).getKind() == ratings.get(i).getKind()
                        && back.get(i).getJurisdiction() == ratings.get(i).getJurisdiction();
            }
            Map<String, SourceRating> perDomain = new LinkedHashMap<String, SourceRating>();
            int dated = 0;
            int unrated = 0;
            for (SourceRating rating : ratings) {
                if (rating.getSourceId().equals(rating.getSampleId()) || !perDomain.containsKey(rating.getHost())) {
                    perDomain.put(rating.getHost(), rating);
                }
            }
            for (SourceRating rating : perDomain.values()) {
                dated += rating.getDate().isEmpty() ? 0 : 1;
                unrated += rating.isOk() ? 0 : 1;
            }
            ratingsByArm.put(arm, perDomain);
            JsonObject report = new JsonObject();
            report.addProperty("liste", WITH.equals(arm) ? "mitgeliefert, " + list.domains().size() + " Domains"
                    : "keine");
            report.addProperty("gruppen", perDomain.size());
            report.addProperty("aufrufe", timed.calls.get());
            report.addProperty("fehlgeschlageneAufrufe", timed.failed.get());
            report.addProperty("modellzeitSekunden", round(timed.nanos.get() / 1e9));
            report.addProperty("wanduhrSekunden", round(wall));
            report.addProperty("ohneUrteil", unrated);
            report.addProperty("mitDatum", dated);
            JsonObject storage = new JsonObject();
            storage.addProperty("datei", log.getFile().toString());
            storage.addProperty("zeilen", back.size());
            storage.addProperty("gleichGelesen", same);
            report.add("ablage", storage);
            arms.add(arm, report);
            System.out.println("[source-rating] arm " + arm + ": " + timed.calls.get() + " calls, "
                    + round(timed.nanos.get() / 1e9) + " s model time, " + round(wall) + " s wall, " + unrated
                    + " groups without a rating");
        }
        result.add("arme", arms);

        JsonObject readings = new JsonObject();
        if (ratingsByArm.containsKey(WITH)) {
            readings.add(WITH, measure(expected, ratingsByArm.get(WITH), settings, null));
            readings.add(WITH_OUTSIDE_LIST, measure(expected, ratingsByArm.get(WITH), settings, list));
        }
        if (ratingsByArm.containsKey(WITHOUT)) {
            readings.add(WITHOUT, measure(expected, ratingsByArm.get(WITHOUT), settings, null));
        }
        result.add("lesarten", readings);
        for (Map.Entry<String, JsonElement> reading : readings.entrySet()) {
            System.out.println("[source-rating] " + reading.getKey() + ": "
                    + reading.getValue().getAsJsonObject().get("befund").getAsString());
        }

        if (ratingsByArm.size() == 2) {
            JsonObject agreement = new JsonObject();
            agreement.addProperty("hinweis", "ausserhalb der Liste sind die Prompts beider Arme gleich");
            int compared = 0;
            int allSame = 0;
            JsonArray differ = new JsonArray();
            for (Expected e : expected) {
                SourceRating a = ratingsByArm.get(WITH).get(e.domain);
                SourceRating b = ratingsByArm.get(WITHOUT).get(e.domain);
                if (list.suggest(e.domain) != null || a == null || b == null) {
                    continue;
                }
                compared++;
                if (a.getStatus() == b.getStatus() && a.getKind() == b.getKind()
                        && a.getJurisdiction() == b.getJurisdiction() && a.getSelfInterest() == b.getSelfInterest()) {
                    allSame++;
                } else {
                    differ.add(e.domain + ": " + brief(a) + " / " + brief(b));
                }
            }
            agreement.addProperty("verglichen", compared);
            agreement.addProperty("gleich", allSame);
            agreement.add("abweichend", differ);
            result.add("uebereinstimmungAusserhalbListe", agreement);
        }

        JsonArray warnings = new JsonArray();
        if (stock.mojibake > 0) {
            warnings.add(stock.mojibake + " Passagen mit kaputten Umlauten (UTF-8 als Latin-1 gelesen): falsche oder"
                    + " beschaedigte Korpusdatei?");
        }
        if (stock.withoutSource > 0) {
            warnings.add(stock.withoutSource + " Passagen ohne sourceId");
        }
        if (withoutDomain > 0) {
            warnings.add(withoutDomain + " Quellen ohne Domain im Korpus");
        }
        if (workspace == null) {
            warnings.add("ohne Arbeitsbereich: Adresse aus der Domain gebaut, kein Titel");
        } else if (withoutAddress > 0) {
            warnings.add(withoutAddress + " Quellen ohne Adresse im Arbeitsbereich: Adresse aus der Domain gebaut");
        }
        if (hostDiffers.size() > 0) {
            warnings.add(hostDiffers.size() + " Quellen: Host der Adresse weicht von der Domain im Korpus ab "
                    + hostDiffers);
        }
        int missing = 0;
        for (Expected e : expected) {
            missing += sourcesPerDomain.containsKey(e.domain) ? 0 : 1;
        }
        if (missing > 0) {
            warnings.add(missing + " Domains der Datei nicht im Bestand (zaehlen als ohne Urteil)");
        }
        result.add("warnungen", warnings);

        JsonArray domains = new JsonArray();
        for (Expected e : expected) {
            JsonObject d = new JsonObject();
            d.addProperty("domain", e.domain);
            d.addProperty("zitate", e.citations);
            d.addProperty("sicher", e.sure);
            JsonObject wantedVerdict = new JsonObject();
            wantedVerdict.addProperty("quellenart", e.kind.name());
            wantedVerdict.addProperty("rechtsraum", e.jurisdiction.name());
            wantedVerdict.addProperty("eigeninteresse", e.selfInterest.name());
            d.add("erwartet", wantedVerdict);
            KnownDomains.Entry suggestion = list.suggest(e.domain);
            d.addProperty("liste", suggestion == null ? "" : suggestion.toString());
            Integer count = sourcesPerDomain.get(e.domain);
            d.addProperty("quellen", count == null ? 0 : count);
            SourceRating read = null;
            JsonObject perArm = new JsonObject();
            for (Map.Entry<String, Map<String, SourceRating>> arm : ratingsByArm.entrySet()) {
                SourceRating rating = arm.getValue().get(e.domain);
                perArm.add(arm.getKey(), describe(e, rating));
                read = read == null ? rating : read;
            }
            String readId = read == null ? "" : read.getSampleId();
            CorpusSource source = stock.sources.get(readId);
            d.addProperty("gelesen", readId);
            d.addProperty("passage", source == null ? "" : source.passageId);
            d.addProperty("adresse", read == null ? "" : read.getAddress());
            d.addProperty("titel", read == null ? "" : read.getTitle());
            d.add("arme", perArm);
            domains.add(d);
        }
        result.add("domainsEinzeln", domains);

        JsonObject decisive = readings.getAsJsonObject(settings.decides);
        JsonObject verdict = new JsonObject();
        verdict.addProperty("entscheidet", settings.decides);
        verdict.addProperty("bestanden", decisive.get("bestanden").getAsBoolean());
        verdict.addProperty("befund", settings.decides + ": " + decisive.get("befund").getAsString());
        result.add("ergebnis", verdict);
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(result).getBytes(StandardCharsets.UTF_8));
        return result;
    }

    /** Q1 to Q6 and the report of one reading; {@code outsideOf} keeps only the domains that list does not know. */
    static JsonObject measure(List<Expected> expected, Map<String, SourceRating> ratings, Settings settings,
                              KnownDomains outsideOf) {
        int domains = 0;
        int sureDomains = 0;
        int sureCitations = 0;
        int kindRight = 0;
        int kindRightDomains = 0;
        int jurisdictionCitations = 0;
        int jurisdictionRight = 0;
        int sureJa = 0;
        int inResult = 0;
        int makersWithUnsure = 0;
        JsonArray kindWrong = new JsonArray();
        JsonArray makers = new JsonArray();
        JsonArray jurisdictionWrong = new JsonArray();
        JsonArray jaAsNein = new JsonArray();
        JsonArray unrated = new JsonArray();
        JsonArray unsure = new JsonArray();
        Map<String, Map<String, Integer>> confusion = new TreeMap<String, Map<String, Integer>>();
        Map<String, Map<String, Integer>> selfMatrix = new TreeMap<String, Map<String, Integer>>();
        for (Expected e : expected) {
            if (outsideOf != null && outsideOf.suggest(e.domain) != null) {
                continue;
            }
            domains++;
            SourceRating r = ratings.get(e.domain);
            boolean ok = r != null && r.isOk();
            inResult += r == null ? 0 : 1;
            String kind = r == null ? NOT_IN_STOCK : ok ? r.getKind().name() : FAILED;
            if (!ok) {
                unrated.add(e.domain + ": " + (r == null ? "nicht im Bestand" : r.getProblem()));
            }
            if (e.kind == SourceRating.Kind.HERSTELLER_HANDEL && ok && NEUTRAL_KINDS.contains(r.getKind())) {
                makersWithUnsure++;
            }
            if (!e.sure) {
                unsure.add(e.domain + ": " + e.kind + " -> " + kind);
                continue;
            }
            sureDomains++;
            sureCitations += e.citations;
            if (ok && r.getKind() == e.kind) {
                kindRight += e.citations;
                kindRightDomains++;
            } else {
                kindWrong.add(e.domain + " (" + e.citations + "): " + e.kind + " -> " + kind);
            }
            confusion.computeIfAbsent(e.kind.name(), k -> new TreeMap<String, Integer>()).merge(kind, 1,
                    Integer::sum);
            if (e.kind == SourceRating.Kind.HERSTELLER_HANDEL && ok && NEUTRAL_KINDS.contains(r.getKind())) {
                makers.add(e.domain + " (" + e.citations + "): " + r.getKind());
            }
            if (JURISDICTION_KINDS.contains(e.kind)) {
                jurisdictionCitations += e.citations;
                if (ok && r.getJurisdiction() == e.jurisdiction) {
                    jurisdictionRight += e.citations;
                } else {
                    jurisdictionWrong.add(e.domain + " (" + e.citations + "): " + e.jurisdiction + " -> "
                            + (ok ? r.getJurisdiction().name() : kind));
                }
            }
            String self = ok ? r.getSelfInterest().name() : kind;
            selfMatrix.computeIfAbsent(e.selfInterest.name(), k -> new TreeMap<String, Integer>()).merge(self, 1,
                    Integer::sum);
            if (e.selfInterest == SourceRating.SelfInterest.JA) {
                sureJa++;
                if (ok && r.getSelfInterest() == SourceRating.SelfInterest.NEIN) {
                    jaAsNein.add(e.domain + " (" + e.citations + ")");
                }
            }
        }
        double kindShare = sureCitations == 0 ? 0 : (double) kindRight / sureCitations;
        double jurisdictionShare = jurisdictionCitations == 0 ? 0 : (double) jurisdictionRight / jurisdictionCitations;
        boolean q1 = sureCitations > 0 && kindShare >= settings.minKind;
        boolean q2 = makers.size() <= settings.maxMakerAsNeutral;
        boolean q3 = jurisdictionCitations > 0 && jurisdictionShare >= settings.minJurisdiction;
        boolean q4 = jaAsNein.size() <= settings.maxJaAsNein;
        boolean q5 = unrated.size() <= settings.maxUnrated;
        boolean q6 = inResult == domains;

        JsonObject o = new JsonObject();
        o.addProperty("domains", domains);
        o.addProperty("sichereDomains", sureDomains);
        JsonObject kind = new JsonObject();
        kind.addProperty("sichereZitate", sureCitations);
        kind.addProperty("richtigeZitate", kindRight);
        kind.addProperty("anteil", round(kindShare));
        kind.addProperty("sichereDomains", sureDomains);
        kind.addProperty("richtigeDomains", kindRightDomains);
        kind.add("falsch", kindWrong);
        o.add("Q1quellenart", kind);
        JsonObject maker = new JsonObject();
        maker.addProperty("anzahl", makers.size());
        maker.add("domains", makers);
        maker.addProperty("mitUnsicherenDomains", makersWithUnsure);
        o.add("Q2herstellerAlsNeutral", maker);
        JsonObject jurisdiction = new JsonObject();
        jurisdiction.addProperty("gewerteteZitate", jurisdictionCitations);
        jurisdiction.addProperty("richtigeZitate", jurisdictionRight);
        jurisdiction.addProperty("anteil", round(jurisdictionShare));
        jurisdiction.add("falsch", jurisdictionWrong);
        o.add("Q3rechtsraum", jurisdiction);
        JsonObject self = new JsonObject();
        self.addProperty("sichereJa", sureJa);
        self.addProperty("jaAlsNein", jaAsNein.size());
        self.add("domains", jaAsNein);
        o.add("Q4eigeninteresse", self);
        JsonObject failures = new JsonObject();
        failures.addProperty("anzahl", unrated.size());
        failures.add("domains", unrated);
        o.add("Q5ohneUrteil", failures);
        JsonObject complete = new JsonObject();
        complete.addProperty("domains", domains);
        complete.addProperty("imErgebnis", inResult);
        complete.addProperty("erfuellt", q6);
        o.add("Q6alleGruppen", complete);
        o.add("verwechslung", matrix(confusion));
        o.add("eigeninteresseMatrix", matrix(selfMatrix));
        o.add("unsicher", unsure);
        JsonObject criteria = new JsonObject();
        criteria.addProperty("Q1", q1);
        criteria.addProperty("Q2", q2);
        criteria.addProperty("Q3", q3);
        criteria.addProperty("Q4", q4);
        criteria.addProperty("Q5", q5);
        criteria.addProperty("Q6", q6);
        o.add("kriterien", criteria);
        boolean passed = q1 && q2 && q3 && q4 && q5 && q6;
        o.addProperty("bestanden", passed);
        o.addProperty("befund", (passed ? "bestanden" : "nicht bestanden") + ": Q1 Quellenart " + round(kindShare)
                + " (" + kindRight + "/" + sureCitations + " Zitate, mindestens " + settings.minKind + ")"
                + ", Q2 Hersteller als Wissenschaft/Behoerde/Norm " + makers.size() + " (hoechstens "
                + settings.maxMakerAsNeutral + "), Q3 Rechtsraum " + round(jurisdictionShare) + " ("
                + jurisdictionRight + "/" + jurisdictionCitations + ", mindestens " + settings.minJurisdiction + ")"
                + ", Q4 JA als NEIN " + jaAsNein.size() + " von " + sureJa + " (hoechstens " + settings.maxJaAsNein
                + "), Q5 ohne Urteil " + unrated.size() + " (hoechstens " + settings.maxUnrated + "), Q6 "
                + inResult + " von " + domains + " Domains im Ergebnis");
        return o;
    }

    private static JsonObject matrix(Map<String, Map<String, Integer>> counts) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> row : counts.entrySet()) {
            JsonObject cells = new JsonObject();
            for (Map.Entry<String, Integer> cell : row.getValue().entrySet()) {
                cells.addProperty(cell.getKey(), cell.getValue());
            }
            o.add(row.getKey(), cells);
        }
        return o;
    }

    /** The rating of one domain in one arm, without passage text. */
    static JsonObject describe(Expected e, SourceRating r) {
        JsonObject o = new JsonObject();
        if (r == null) {
            o.addProperty("status", NOT_IN_STOCK);
            return o;
        }
        o.addProperty("status", r.getStatus().name());
        o.addProperty("quellenart", r.isOk() ? r.getKind().name() : "");
        o.addProperty("rechtsraum", r.isOk() ? r.getJurisdiction().name() : "");
        o.addProperty("eigeninteresse", r.isOk() ? r.getSelfInterest().name() : "");
        o.addProperty("datum", r.getDate());
        o.addProperty("anbieter", r.getPublisher());
        o.addProperty("grund", r.getReason());
        o.addProperty("fehler", r.getProblem());
        o.addProperty("quellenartRichtig", r.isOk() && r.getKind() == e.kind);
        if (JURISDICTION_KINDS.contains(e.kind)) {
            o.addProperty("rechtsraumRichtig", r.isOk() && r.getJurisdiction() == e.jurisdiction);
        }
        o.addProperty("eigeninteresseGleich", r.isOk() && r.getSelfInterest() == e.selfInterest);
        JsonArray answers = new JsonArray();
        for (String answer : r.getAnswers()) {
            answers.add(answer);
        }
        o.add("rohantworten", answers);
        return o;
    }

    private static String brief(SourceRating r) {
        return r.isOk() ? r.getKind() + "/" + r.getJurisdiction() + "/" + r.getSelfInterest() : FAILED;
    }

    /** {@code {"domains": [{"domain", "zitate", "quellenart", "rechtsraum", "eigeninteresse", "sicher"}, ...]}}. */
    static List<Expected> expected(String json) {
        List<Expected> out = new ArrayList<Expected>();
        Set<String> seen = new HashSet<String>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("domains")) {
            JsonObject o = element.getAsJsonObject();
            String domain = SourceSample.hostOf(o.get("domain").getAsString());
            if (domain.isEmpty() || !seen.add(domain)) {
                throw new IllegalArgumentException("empty or repeated domain in the expected file: " + domain);
            }
            out.add(new Expected(domain, o.get("zitate").getAsInt(),
                    SourceRating.Kind.valueOf(o.get("quellenart").getAsString()),
                    SourceRating.Jurisdiction.valueOf(o.get("rechtsraum").getAsString()),
                    SourceRating.SelfInterest.valueOf(o.get("eigeninteresse").getAsString()),
                    !o.has("sicher") || o.get("sicher").getAsBoolean()));
        }
        return out;
    }

    /** The corpus of the outline replay ({@code {"passages": [{"id", "sourceId", "domain", "text", ...}]}}). */
    static Stock readCorpus(Reader in) throws IOException {
        Stock stock = new Stock();
        JsonReader reader = new JsonReader(in);
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            readPassages(reader, stock);
            return stock;
        }
        reader.beginObject();
        while (reader.hasNext()) {
            if ("passages".equals(reader.nextName())) {
                readPassages(reader, stock);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return stock;
    }

    private static void readPassages(JsonReader reader, Stock stock) throws IOException {
        reader.beginArray();
        while (reader.hasNext()) {
            String id = "";
            String sourceId = "";
            String domain = "";
            String text = "";
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull();
                } else if ("id".equals(field)) {
                    id = reader.nextString();
                } else if ("sourceId".equals(field)) {
                    sourceId = reader.nextString().trim();
                } else if ("domain".equals(field)) {
                    domain = reader.nextString();
                } else if ("text".equals(field)) {
                    text = reader.nextString();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            stock.add(id, sourceId, domain, text);
        }
        reader.endArray();
    }

    /** Address and title per source id from {@code sources/*.properties} of a workspace; read only, as UTF-8. */
    static Map<String, String[]> workspaceSources(File workspace) throws IOException {
        File[] files = new File(workspace, "sources").listFiles();
        if (files == null) {
            throw new IllegalArgumentException("no folder sources/ in " + workspace);
        }
        Map<String, String[]> out = new HashMap<String, String[]>();
        for (File file : files) {
            if (!file.getName().endsWith(".properties")) {
                continue;
            }
            Properties p = Utf8Properties.load(file);
            String id = p.getProperty("sourceId", "").trim();
            if (!id.isEmpty()) {
                out.put(id, new String[]{p.getProperty("url", "").trim(), p.getProperty("title", "").trim()});
            }
        }
        return out;
    }

    static int passageIndex(String passageId) {
        Matcher m = PASSAGE_INDEX.matcher(passageId);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE - 1;
    }

    static long number(String sourceId) {
        Matcher m = NUMBER_AT_END.matcher(sourceId);
        return m.find() ? Long.parseLong(m.group(1)) : Long.MAX_VALUE;
    }

    static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    static String resource(String path) throws IOException {
        try (InputStream in = SourceRatingStockTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String read(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static JsonObject domain(JsonObject result, String domain) {
        for (JsonElement element : result.getAsJsonArray("domainsEinzeln")) {
            if (domain.equals(element.getAsJsonObject().get("domain").getAsString())) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("no domain " + domain);
    }

    private static void property(File sources, String id, String url, String title) throws IOException {
        Properties p = new Properties();
        p.setProperty("sourceId", id);
        p.setProperty("url", url);
        p.setProperty("title", title);
        StringBuilder text = new StringBuilder();
        for (String key : new String[]{"sourceId", "url", "title"}) {
            text.append(key).append('=').append(p.getProperty(key)).append('\n');
        }
        Files.write(new File(sources, id + ".properties").toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("the sample file reads back as UTF-8", title, Utf8Properties.load(new File(sources,
                id + ".properties")).getProperty("title"));
    }

    private static Scripted sampleModel() {
        return new Scripted()
                .answer("publikationen.dguv.de", answer("DGUV", "BEHOERDE_UNFALLVERSICHERUNG", "DE", "NEIN", ""),
                        answer("DGUV", "BEHOERDE_UNFALLVERSICHERUNG", "DE", "NEIN", ""))
                .answer("wearable-firma.example", answer("Wearable Firma", "WISSENSCHAFT", "OHNE", "NEIN", ""),
                        answer("Wearable Firma", "WISSENSCHAFT", "OHNE", "NEIN", ""))
                .answer("gesetze-im-internet.de", answer("Bundesamt für Justiz", "GESETZ_NORM", "DE", "NEIN", ""),
                        answer("Bundesamt für Justiz", "NACHSCHLAGEWERK", "DE", "NEIN", ""))
                .answer("kanzlei-recht.example", answer("Kanzlei", "KANZLEI_BERATUNG", "AT", "UNKLAR", ""),
                        answer("Kanzlei", "KANZLEI_BERATUNG", "AT", "UNKLAR", ""))
                .answer("forschung-institut.example", answer("Institut", "WISSENSCHAFT", "OHNE", "NEIN", "2023"),
                        answer("Institut", "WISSENSCHAFT", "OHNE", "NEIN", "2023"))
                .answer("blog-unbekannt.example", answer("Privat", "SONSTIGE", "OHNE", "UNKLAR", ""),
                        answer("Privat", "SONSTIGE", "OHNE", "UNKLAR", ""));
    }

    private static String answer(String publisher, String kind, String jurisdiction, String selfInterest,
                                 String date) {
        return rating(publisher, kind, jurisdiction, selfInterest, date);
    }

    /** Self-written passages; one of another domain carries broken umlauts. */
    private static String sampleCorpus() {
        String[][] rows = {
            {"cap-1#p1@seg-b", "source-1", "publikationen.dguv.de", "Zweiter Absatz der Broschüre."},
            {"cap-1#p0@seg-a", "source-1", "publikationen.dguv.de",
                "Die Broschüre der Unfallversicherung erklärt den Einsatz von Wearables."},
            {"cap-2#p0@seg-a", "source-2", "wearable-firma.example",
                "Sensorweste schützt Ihre Beschäftigten. Jetzt bestellen."},
            {"cap-3#p0@seg-a", "source-3", "gesetze-im-internet.de",
                "Der Paragraf regelt, dass der Arbeitgeber die Arbeitsbedingungen beurteilt."},
            {"cap-4#p0@seg-a", "source-4", "publikationen.dguv.de", "Eine weitere Schrift der Unfallversicherung."},
            {"cap-5#p0@seg-a", "source-5", "kanzlei-recht.example", "Unsere Kanzlei berät Betriebsräte."},
            {"cap-6#p0@seg-a", "source-6", "blog-unbekannt.example", "Mein Tagebuch über Technik."},
            {"cap-7#p0@seg-a", "source-7", "andere-seite.example", "GefÃ¤hrdung ohne Bezug."},
            {"cap-12#p0@seg-a", "source-12", "forschung-institut.example", "Spätere Studie des Instituts."},
            {"cap-9#p0@seg-a", "source-9", "forschung-institut.example", "Studie des Instituts zu Sensoren."},
            {"cap-11#p0@seg-a", "source-11", "", "Eine Passage ohne Domain."},
        };
        JsonArray passages = new JsonArray();
        for (String[] row : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("id", row[0]);
            o.addProperty("captureId", row[0].substring(0, row[0].indexOf('#')));
            o.addProperty("sourceId", row[1]);
            o.addProperty("domain", row[2]);
            o.addProperty("heading", "");
            o.addProperty("text", row[3]);
            passages.add(o);
        }
        JsonObject corpus = new JsonObject();
        corpus.addProperty("comment", "selbst geschrieben");
        corpus.addProperty("dim", 0);
        corpus.add("passages", passages);
        return corpus.toString();
    }

    private static String sampleExpected() {
        Object[][] rows = {
            {"publikationen.dguv.de", 40, "BEHOERDE_UNFALLVERSICHERUNG", "DE", "NEIN", true},
            {"wearable-firma.example", 30, "HERSTELLER_HANDEL", "OHNE", "JA", true},
            {"gesetze-im-internet.de", 10, "GESETZ_NORM", "DE", "NEIN", true},
            {"kanzlei-recht.example", 15, "KANZLEI_BERATUNG", "DE", "UNKLAR", true},
            {"forschung-institut.example", 5, "WISSENSCHAFT", "OHNE", "NEIN", true},
            {"blog-unbekannt.example", 7, "SONSTIGE", "DE", "UNKLAR", false},
            {"fehlt.example", 3, "FACHPRESSE", "DE", "UNKLAR", true},
        };
        JsonArray domains = new JsonArray();
        for (Object[] row : rows) {
            JsonObject o = new JsonObject();
            o.addProperty("domain", (String) row[0]);
            o.addProperty("zitate", (Integer) row[1]);
            o.addProperty("quellenart", (String) row[2]);
            o.addProperty("rechtsraum", (String) row[3]);
            o.addProperty("eigeninteresse", (String) row[4]);
            o.addProperty("sicher", (Boolean) row[5]);
            domains.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("domains", domains);
        return root.toString();
    }

    @Test
    public void theCorpusMayBeABareListAndSkipsVectors() throws Exception {
        Stock stock = readCorpus(new StringReader("[{\"id\":\"cap-1#p3\",\"sourceId\":\"source-1\",\"domain\":"
                + "\"www.a.example\",\"vector\":[0.1,0.2],\"text\":\"Drei\"},{\"id\":\"cap-1#p2\",\"sourceId\":"
                + "\"source-1\",\"domain\":\"a.example\",\"text\":\"Zwei\",\"heading\":null}]"));
        assertEquals(2, stock.passages);
        CorpusSource source = stock.sources.get("source-1");
        assertEquals("a.example", source.domain);
        assertEquals("Zwei", source.text);
        assertEquals(2, source.passages);
        assertEquals(3L, number("source-3"));
        assertEquals(Long.MAX_VALUE, number("quelle"));
    }
}
