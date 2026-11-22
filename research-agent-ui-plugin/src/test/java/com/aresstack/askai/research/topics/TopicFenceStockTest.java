package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchMemoryStore;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.topics.TopicFenceCheck.Brief;
import com.aresstack.askai.research.topics.TopicFenceCheck.Options;
import com.aresstack.askai.research.topics.TopicFenceCheck.Subject;
import com.aresstack.askai.research.topics.TopicFenceCheck.Verdict;
import com.aresstack.askai.research.topics.TopicFinder.Source;
import com.aresstack.askai.research.topics.TopicFinder.SourcePassage;
import com.aresstack.askai.research.topics.TopicFinderStockTest.Expected;
import com.aresstack.askai.research.topics.TopicFinderStockTest.Stock;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 14 on stored data: the fence check with gemma4:12b against the mission of the stock of 17.11.2026 and the
 * four cards of its concept, on up to three sets of topics with a known verdict.
 *
 * <ul>
 *   <li>{@code 81}: the 81 significant topics of the app's topic analysis (themen-1711.json), each with the
 *       architect's verdict (INNEN, AUSSEN, UNKLAR, KEIN_THEMA) and the four sample passages he judged from, shown as
 *       the topic's findings.</li>
 *   <li>{@code 17}: the 17 named topics (erwartete-themen-1711.json, 8 INNEN, 4 UNKLAR, 5 AUSSEN), with the sample
 *       passages of their sheets that hold one of their key words.</li>
 *   <li>{@code 15} (when {@code gedaechtnis} names the project of a topic merge run): the merged topics as the app
 *       will hand them over, with their quotes; a topic whose proposals mostly carry one of the 17 names (key words)
 *       takes that topic's verdict as silver reference.</li>
 * </ul>
 *
 * <p>The edge of the subject (decision E12) is not in the stored mission; {@code rand} names a text file with it, and
 * the run is then kept apart ({@code -rand}). Proposed criteria, fixed by the architect before the run counts: F1 of
 * the AUSSEN and KEIN_THEMA topics of the 81 at most 10 % INNEN and none of the 5 AUSSEN names; F2 of the INNEN topics
 * of the 81 at most 10 % AUSSEN or KEIN_THEMA (both keep a topic out of the book) and none of the 8 INNEN names; F3 at
 * least 70 % of the INNEN topics of 81 and 17 together INNEN; F4 at most 30 % of the 81 UNKLAR; F5 at most 1 % FEHLER
 * after one repeat. The verdict KEIN_THEMA is counted on its own ({@code keinThema}: how many of the KEIN_THEMA topics
 * of the 81 it found, how many others it hit). In set 15 a topic with subtopics shows them, and a parent that the
 * merge formed is judged on its subtopics' findings ({@code mitTeilthemen}, {@code teilthemen}). {@code result.json}
 * holds names, references and verdicts, no passage text, no quote and no reason (the reasons stay in
 * {@code projekt/topic-fence/log.jsonl}).</p>
 *
 * <pre>
 *   ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*TopicFenceStockTest*'
 *     -Daskai.stock.fence.korpus=&lt;replay-corpus.json&gt; -Daskai.stock.fence.out=&lt;folder&gt;
 *     -Daskai.stock.fence.chatUrl=http://127.0.0.1:11434 -Daskai.stock.fence.chatModel=gemma4:12b
 *   optional: rand (text file), gedaechtnis (project folder of a topic merge run), arme (81,17,15), parallel,
 *   auszug (characters per finding, 600), fundstellen (4), numCtx, think, timeoutSeconds, wiederholen (true),
 *   themen, erwartet, stichworte, karten (fragen file), kartenZeigen (true; false leaves the cards out of the
 *   prompt and the out folder gets -ohne-karten), nurThemen (a file of topic ids of the memory: set 15 judges only
 *   the topics they stand in now; one id per line, # starts a comment line, commas allowed, or JSON: an array or an
 *   object with ids, themen or topics, each element an id or an object with id or themaId)
 * </pre>
 */
public class TopicFenceStockTest {

    private static final String P = "askai.stock.fence.";
    private static final List<String> VERDICTS = Arrays.asList("INNEN", "AUSSEN", "KEIN_THEMA", "UNKLAR", "FEHLER");
    /** The verdicts that keep a topic out of the book. */
    private static final List<String> OUT = Arrays.asList("AUSSEN", "KEIN_THEMA");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** One topic of a set with its reference ({@code ""} for none) and its sample passages the corpus lacks. */
    static final class Case {
        final String set;
        final Subject subject;
        final String reference;
        final int missing;

        Case(String set, Subject subject, String reference, int missing) {
            this.set = set;
            this.subject = subject;
            this.reference = reference == null ? "" : reference;
            this.missing = missing;
        }
    }

    /** Proposed thresholds and how the run works. */
    static final class Settings {
        Options options = Options.defaults().withExcerpts(4, 600);
        boolean repeat = true;
        double f1Drift = 0.10;
        double f2Blocked = 0.10;
        double f3Found = 0.70;
        double f4Unclear = 0.30;
        double f5Failed = 0.01;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    public void sampleStock() throws Exception {
        String corpus = "{\"mission\":\"Ein Praxisbuch über Verkehr in Städten: Tempo, Radwege, Parken.\","
                + "\"passages\":["
                + "{\"id\":\"s1#p1\",\"sourceId\":\"s1\",\"text\":\"Lieferwagen parken in zweiter Reihe.\"},"
                + "{\"id\":\"s1#p2\",\"sourceId\":\"s1\",\"text\":\"Ladezonen helfen dem Lieferverkehr.\"},"
                + "{\"id\":\"s2#p1\",\"sourceId\":\"s2\",\"text\":\"Güterzüge sind nachts laut.\"},"
                + "{\"id\":\"s3#p1\",\"sourceId\":\"s3\",\"text\":\"Menü Startseite Kontakt Impressum\"}]}";
        Stock stock = TopicFinderStockTest.readStock(new StringReader(corpus));
        String themen = "{\"themen\":["
                + "{\"blatt\":\"T01\",\"titel\":\"Ladezonen\",\"urteil\":\"INNEN\","
                + "\"stichprobe\":[\"s1#p1\",\"s1#p2\"]},"
                + "{\"blatt\":\"T02\",\"titel\":\"Güterzüge\",\"urteil\":\"AUSSEN\",\"stichprobe\":[\"s2#p1\"]},"
                + "{\"blatt\":\"T03\",\"titel\":\"Menü\",\"urteil\":\"KEIN_THEMA\",\"stichprobe\":[\"s3#p1\","
                + "\"fehlt#p9\"]}]}";
        List<Expected> expected = TopicFinderStockTest.readExpected(
                "{\"themen\":[{\"name\":\"Lieferverkehr\",\"blaetter\":[\"T01\"],\"erwartet\":\"INNEN\"},"
                        + "{\"name\":\"Bahnlärm\",\"blaetter\":[\"T02\"],\"erwartet\":\"AUSSEN\"}]}",
                "{\"themen\":{\"Lieferverkehr\":[\"lieferverkehr\"],\"Bahnlärm\":[\"güterzüge\"]}}");
        Settings settings = new Settings();
        Map<String, String> texts = passages(stock);
        List<Case> cases = new ArrayList<Case>(themen81(themen, texts, settings.options));
        cases.addAll(named(expected, themen, texts, settings.options));
        assertEquals(5, cases.size());
        assertEquals("only the passages that hold a key word", 1, cases.get(3).subject.getExcerpts().size());

        TopicFinderTest.Scripted chat = new TopicFinderTest.Scripted(messages -> {
            String topic = TopicFenceCheckTest.topicOf(messages);
            String verdict = topic.startsWith("Güter") || topic.startsWith("Bahn") ? "aussen"
                    : topic.startsWith("Menü") ? "kein_thema" : "innen";
            return MainModelChatResult.ok("{\"grund\":\"Weil es so ist.\",\"urteil\":\"" + verdict + "\"}");
        });
        File out = tmp.newFolder("out");
        Brief brief = Brief.of(stock.mission, Arrays.asList("Tempo", "Radverkehr", "Parken"));
        JsonObject result = run(cases, brief, chat, settings, out, new JsonObject());

        JsonObject t81 = result.getAsJsonObject("saetze").getAsJsonObject("81");
        assertEquals(3, t81.get("themen").getAsInt());
        assertEquals(1, t81.getAsJsonObject("urteile").get("KEIN_THEMA").getAsInt());
        assertEquals("KEIN_THEMA is not counted as AUSSEN", 1, t81.getAsJsonObject("urteile").get("AUSSEN").getAsInt());
        assertEquals(1, t81.getAsJsonObject("matrix").getAsJsonObject("KEIN_THEMA").get("KEIN_THEMA").getAsInt());
        assertEquals(1, t81.get("fehlendePassagen").getAsInt());
        assertEquals(0, t81.get("mitTeilthemen").getAsInt());
        JsonObject criteria = result.getAsJsonObject("kriterien");
        for (String name : new String[]{"F1", "F2", "F3", "F4", "F5"}) {
            assertTrue(name + " " + criteria.get(name), criteria.getAsJsonObject(name).get("erfuellt").getAsBoolean());
        }
        assertTrue(result.get("bestanden").getAsBoolean());
        assertEquals(1, result.getAsJsonObject("keinThema").get("erkannt").getAsInt());
        assertEquals(1, result.getAsJsonObject("keinThema").get("von").getAsInt());
        assertEquals(0, result.getAsJsonObject("keinThema").get("beiAnderen").getAsInt());
        assertEquals(5, chat.calls.get());
        String written = new String(Files.readAllBytes(new File(out, "result.json").toPath()), StandardCharsets.UTF_8);
        assertFalse("no reason in result.json", written.contains("Weil es so ist"));
        assertFalse("no passage text in result.json", written.contains("zweiter Reihe"));
        assertEquals(5, new TopicFenceLog(new File(out, "projekt")).open().size());

        TopicFinderTest.Scripted remnants = new TopicFinderTest.Scripted(messages -> MainModelChatResult.ok(
                "{\"grund\":\"Nur Seitenreste.\",\"urteil\":\"kein_thema\"}"));
        JsonObject all = run(cases, brief, remnants, settings, tmp.newFolder("out2"), new JsonObject());
        JsonObject f2 = all.getAsJsonObject("kriterien").getAsJsonObject("F2");
        assertFalse("KEIN_THEMA on an INNEN topic keeps it out of the book", f2.get("erfuellt").getAsBoolean());
        assertTrue(f2.get("was").getAsString(), f2.get("was").getAsString().startsWith("INNEN-Themen der 81 als "
                + "AUSSEN oder KEIN_THEMA (1 von 1)"));
        assertEquals(2, all.getAsJsonObject("keinThema").get("beiAnderen").getAsInt());
    }

    @Test
    public void theMergedTopicsOfARunTakeTheVerdictOfTheirNamedTopic() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("konzept-1", "Parken", "karte-1", 0, 1L));
        ResearchTopic a = ResearchTopic.found("v1", "Ladezonen für Lieferwagen", "", null, "m", 1L);
        ResearchTopic b = ResearchTopic.found("v2", "Lieferverkehr am Morgen", "", null, "m", 1L);
        ResearchTopic c = ResearchTopic.found("v3", "Güterzüge", "", null, "m", 1L);
        for (ResearchTopic topic : Arrays.asList(a, b, c)) {
            memory.addTopic(topic);
        }
        memory.addTopic(ResearchTopic.found("thema-0001", "Ladezonen", "", null, "m", 2L)
                .withMerged(Arrays.asList(a, b), "m", 2L).withParent("konzept-1", "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Güterzüge", "", null, "m", 2L)
                .withMerged(Arrays.asList(c), "m", 2L));
        List<Expected> expected = TopicFinderStockTest.readExpected(
                "{\"themen\":[{\"name\":\"Lieferverkehr\",\"blaetter\":[],\"erwartet\":\"INNEN\"},"
                        + "{\"name\":\"Bahnlärm\",\"blaetter\":[],\"erwartet\":\"AUSSEN\"}]}",
                "{\"themen\":{\"Lieferverkehr\":[\"liefer\"],\"Bahnlärm\":[\"güterzüge\"]}}");
        List<Case> cases = fromMerge(memory, expected, Options.defaults());
        assertEquals("the card is not judged", 2, cases.size());
        assertEquals("two proposals name the delivery traffic", "INNEN", cases.get(0).reference);
        assertEquals("one proposal is too few for a reference", "", cases.get(1).reference);
    }

    @Test
    public void aListOfIdsKeepsTheTopicsTheyStandInNow() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("konzept-1", "Parken", "karte-1", 0, 1L));
        ResearchTopic a = ResearchTopic.found("v1", "Ladezonen für Lieferwagen", "", null, "m", 1L);
        ResearchTopic b = ResearchTopic.found("v2", "Lieferverkehr am Morgen", "", null, "m", 1L);
        ResearchTopic c = ResearchTopic.found("v3", "Güterzüge", "", null, "m", 1L);
        for (ResearchTopic topic : Arrays.asList(a, b, c)) {
            memory.addTopic(topic);
        }
        memory.addTopic(ResearchTopic.found("thema-0001", "Ladezonen", "", null, "m", 2L)
                .withMerged(Arrays.asList(a, b), "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Güterzüge", "", null, "m", 2L)
                .withMerged(Arrays.asList(c), "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0003", "Schulstraßen", "", null, "m", 2L));

        List<String> ids = OnlyTopics.readIds("\uFEFF# Stichprobe vom 22.11.\nv1\n\nthema-0001, v2\r\nthema-0003;"
                + " konzept-1\nunbekannt-9\nv1\n");
        assertEquals(Arrays.asList("v1", "thema-0001", "v2", "thema-0003", "konzept-1", "unbekannt-9", "v1"), ids);
        OnlyTopics only = OnlyTopics.resolve(ids, memory);
        assertEquals(Arrays.asList("thema-0001", "thema-0003"), new ArrayList<String>(only.standing));
        assertEquals(6, only.given.size());
        assertEquals(Arrays.asList("v1", "v2"), only.redirected);
        assertEquals(Arrays.asList("thema-0001", "v2"), only.collapsed);
        assertEquals(Collections.singletonList("konzept-1"), only.cards);
        assertEquals(Collections.singletonList("unbekannt-9"), only.unknown);
        JsonObject json = only.json("auswahl.txt");
        assertEquals("{\"datei\":\"auswahl.txt\",\"angegeben\":6,\"gefunden\":2,\"umgeleitet\":2,"
                + "\"zusammengefallen\":2,\"karten\":[\"konzept-1\"],\"unbekannt\":[\"unbekannt-9\"]}",
                json.toString());

        List<Case> cases = fromMerge(memory, Collections.<Expected>emptyList(), Options.defaults(), only.standing);
        assertEquals(2, cases.size());
        assertEquals("thema-0001", cases.get(0).subject.getTopicId());
        assertEquals("thema-0003", cases.get(1).subject.getTopicId());
        assertEquals("without a list every topic", 3, fromMerge(memory, Collections.<Expected>emptyList(),
                Options.defaults()).size());

        assertEquals(Arrays.asList("v3", "thema-0003", "thema-0001"), OnlyTopics.readIds("{\"themen\":[{\"id\":"
                + "\"v3\"},{\"themaId\":\"thema-0003\",\"name\":\"Schulstraßen\"},\"thema-0001\"]}"));
        assertEquals(Arrays.asList("v3", "x"), OnlyTopics.readIds(" [\"v3\", \"x\"]"));
        assertEquals(Collections.singletonList("thema-0002"), new ArrayList<String>(OnlyTopics.resolve(
                OnlyTopics.readIds("{\"ids\":[\"v3\"]}"), memory).standing));
        try {
            OnlyTopics.readIds("{\"liste\":[]}");
            fail("no list");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ids, themen or topics"));
        }
    }

    @Test
    public void storedStock() throws Exception {
        String corpusPath = System.getProperty(P + "korpus", "");
        Assume.assumeTrue("fence check stock test skipped (no -D" + P + "korpus)", !corpusPath.isEmpty());
        String chatUrl = System.getProperty(P + "chatUrl", "http://127.0.0.1:11434");
        String chatModel = System.getProperty(P + "chatModel", "gemma4:12b");
        String numCtx = System.getProperty(P + "numCtx", "");
        boolean think = !"omit".equalsIgnoreCase(System.getProperty(P + "think", "false"));
        int timeout = 1000 * Integer.getInteger(P + "timeoutSeconds", 180);
        String edgePath = System.getProperty(P + "rand", "");
        String memoryPath = System.getProperty(P + "gedaechtnis", "");
        List<String> sets = Arrays.asList(System.getProperty(P + "arme", memoryPath.isEmpty() ? "81,17" : "81,17,15")
                .split("\\s*,\\s*"));
        String onlyPath = System.getProperty(P + "nurThemen", "");
        if (!onlyPath.isEmpty() && (!sets.contains("15") || memoryPath.isEmpty())) {
            throw new IllegalArgumentException("-D" + P + "nurThemen limits set 15: give -D" + P + "gedaechtnis and "
                    + "keep 15 in -D" + P + "arme");
        }
        boolean showCards = !"false".equalsIgnoreCase(System.getProperty(P + "kartenZeigen", "true"));
        File out = new File(System.getProperty(P + "out", "build/component-stock/topic-fence"),
                chatModel.replaceAll("[^A-Za-z0-9._-]", "_") + (edgePath.isEmpty() ? "" : "-rand")
                        + (showCards ? "" : "-ohne-karten"));
        Files.createDirectories(out.toPath());

        Settings settings = new Settings();
        settings.options = Options.defaults().withParallel(Integer.getInteger(P + "parallel", 4))
                .withExcerpts(Integer.getInteger(P + "fundstellen", 4), Integer.getInteger(P + "auszug", 600))
                .withCards(showCards);
        settings.repeat = !"false".equalsIgnoreCase(System.getProperty(P + "wiederholen", "true"));

        Stock stock;
        try (Reader in = new InputStreamReader(new FileInputStream(corpusPath), StandardCharsets.UTF_8)) {
            stock = TopicFinderStockTest.readStock(in);
        }
        String themen = text("themen", "themen-1711.json");
        List<Expected> expected = TopicFinderStockTest.readExpected(text("erwartet", "erwartete-themen-1711.json"),
                text("stichworte", "stichworte-1711.json"));
        List<String> cards = new ArrayList<String>(TopicMergerStockTest.Known.of(text("karten", "fragen-1711.json"))
                .themes.values());
        String edge = edgePath.isEmpty() ? "" : new String(Files.readAllBytes(new File(edgePath).toPath()),
                StandardCharsets.UTF_8).trim();
        Brief brief = new Brief(stock.mission, edge, null, null, "", "", cards);

        Map<String, String> texts = passages(stock);
        List<Case> cases = new ArrayList<Case>();
        if (sets.contains("81")) {
            cases.addAll(themen81(themen, texts, settings.options));
        }
        if (sets.contains("17")) {
            cases.addAll(named(expected, themen, texts, settings.options));
        }
        JsonObject onlyJson = null;
        if (sets.contains("15")) {
            Assume.assumeTrue("set 15 needs -D" + P + "gedaechtnis", !memoryPath.isEmpty());
            ResearchMemory memory = copiedMemory(new File(memoryPath), out);
            OnlyTopics only = null;
            if (!onlyPath.isEmpty()) {
                only = OnlyTopics.resolve(OnlyTopics.readIds(new String(Files.readAllBytes(new File(onlyPath)
                        .toPath()), StandardCharsets.UTF_8)), memory);
                onlyJson = only.json(onlyPath);
                System.out.println("[topic-fence] nurThemen: " + only.given.size() + " ids, " + only.standing.size()
                        + " topics, " + only.unknown.size() + " unknown");
            }
            cases.addAll(fromMerge(memory, expected, settings.options, only == null ? null : only.standing));
        }
        JsonObject sources = new JsonObject();
        sources.addProperty("korpus", corpusPath);
        sources.addProperty("auftragZeichen", stock.mission.length());
        sources.addProperty("rand", edgePath.isEmpty() ? "keiner" : edgePath + " (" + edge.length() + " Zeichen)");
        sources.addProperty("karten", String.join(" | ", cards));
        sources.addProperty("gedaechtnis", memoryPath.isEmpty() ? "keins" : memoryPath);
        if (onlyJson != null) {
            sources.add("nurThemen", onlyJson);
        }
        sources.addProperty("chat", chatModel + " at " + chatUrl + (numCtx.isEmpty() ? "" : ", num_ctx " + numCtx)
                + (think ? ", think:false" : ", think weggelassen") + ", Zeitlimit " + timeout / 1000 + " s");
        System.out.println("[topic-fence] " + cases.size() + " topics, chat " + chatModel + ", "
                + settings.options.getParallel() + " at a time" + (edge.isEmpty() ? "" : ", with the edge"));

        MainModelChat chat = new OllamaStockChat(chatUrl, chatModel, numCtx.isEmpty() ? null : Integer.valueOf(numCtx),
                think, timeout);
        JsonObject result = run(cases, brief, chat, settings, out, sources);
        System.out.println("[topic-fence] result " + new File(out, "result.json").getAbsolutePath());
        assertTrue(result.get("befund").getAsString(), result.get("bestanden").getAsBoolean());
    }

    // ------------------------------------------------------------------------------------------------- run

    /** All cases in one check (a FEHLER asked once more), the log, {@code result.json}. */
    static JsonObject run(List<Case> cases, Brief brief, MainModelChat chat, Settings settings, File out,
                          JsonObject sources) throws Exception {
        File project = new File(out, "projekt");
        deleteTree(new File(project, "topic-fence"));
        TopicFenceLog log = new TopicFenceLog(project);
        log.open();
        log.appendBrief(brief);
        TopicFenceCheck check = new TopicFenceCheck(chat, "de", settings.options,
                line -> System.out.println("[topic-fence] " + line));
        List<Subject> subjects = new ArrayList<Subject>();
        for (Case c : cases) {
            subjects.add(c.subject);
        }
        long start = System.currentTimeMillis();
        List<Verdict> verdicts = new ArrayList<Verdict>(check.check(subjects, brief));
        log.appendAll(verdicts);
        int repeated = 0;
        if (settings.repeat) {
            List<Subject> again = new ArrayList<Subject>();
            for (int i = 0; i < verdicts.size(); i++) {
                if (!verdicts.get(i).isOk()) {
                    again.add(subjects.get(i));
                }
            }
            if (!again.isEmpty()) {
                List<Verdict> second = check.check(again, brief);
                log.appendAll(second);
                Map<String, Verdict> byId = new HashMap<String, Verdict>();
                for (Verdict verdict : second) {
                    byId.put(verdict.getTopicId(), verdict);
                }
                for (int i = 0; i < verdicts.size(); i++) {
                    Verdict newer = byId.get(verdicts.get(i).getTopicId());
                    if (newer != null) {
                        verdicts.set(i, newer);
                    }
                }
                repeated = again.size();
            }
        }
        long millis = System.currentTimeMillis() - start;

        JsonObject json = new JsonObject();
        json.addProperty("geschrieben", java.time.LocalDateTime.now().withNano(0).toString());
        json.add("quellen", sources);
        JsonObject options = new JsonObject();
        options.addProperty("parallel", settings.options.getParallel());
        options.addProperty("fundstellen", settings.options.getExcerpts());
        options.addProperty("auszugZeichen", settings.options.getExcerptChars());
        options.addProperty("kartenZeigen", settings.options.isCards());
        options.addProperty("wiederholen", settings.repeat);
        options.addProperty("grundlage", brief.basis());
        json.add("einstellungen", options);

        Map<String, List<Integer>> bySet = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < cases.size(); i++) {
            bySet.computeIfAbsent(cases.get(i).set, s -> new ArrayList<Integer>()).add(i);
        }
        JsonObject sets = new JsonObject();
        for (Map.Entry<String, List<Integer>> set : bySet.entrySet()) {
            sets.add(set.getKey(), setJson(set.getValue(), cases, verdicts));
        }
        json.add("saetze", sets);
        JsonObject time = new JsonObject();
        time.addProperty("ms", millis);
        time.addProperty("wiederholt", repeated);
        json.add("zeit", time);

        JsonObject criteria = new JsonObject();
        List<String> missed = new ArrayList<String>();
        List<String> inside = Collections.singletonList("INNEN");
        int[] driftA = count(cases, verdicts, "81", Arrays.asList("AUSSEN", "KEIN_THEMA"), inside);
        int[] driftB = count(cases, verdicts, "17", Arrays.asList("AUSSEN"), inside);
        criterion(criteria, missed, "F1", "AUSSEN- und KEIN_THEMA-Themen der 81 als INNEN (" + driftA[0] + " von "
                        + driftA[1] + "), AUSSEN-Namen der 17 als INNEN: " + driftB[0] + " von " + driftB[1],
                share(driftA[0], driftA[1]), settings.f1Drift, false, driftB[0] == 0);
        int[] blockedA = count(cases, verdicts, "81", inside, OUT);
        int[] blockedB = count(cases, verdicts, "17", inside, OUT);
        criterion(criteria, missed, "F2", "INNEN-Themen der 81 als AUSSEN oder KEIN_THEMA (" + blockedA[0] + " von "
                        + blockedA[1] + "), INNEN-Namen der 17 als AUSSEN oder KEIN_THEMA: " + blockedB[0] + " von "
                        + blockedB[1], share(blockedA[0], blockedA[1]), settings.f2Blocked, false, blockedB[0] == 0);
        int[] foundA = count(cases, verdicts, "81", inside, inside);
        int[] foundB = count(cases, verdicts, "17", inside, inside);
        criterion(criteria, missed, "F3", "INNEN-Themen der 81 und 17 als INNEN (" + (foundA[0] + foundB[0])
                        + " von " + (foundA[1] + foundB[1]) + ")", share(foundA[0] + foundB[0], foundA[1] + foundB[1]),
                settings.f3Found, true, true);
        int[] unclear = count(cases, verdicts, "81", null, Collections.singletonList("UNKLAR"));
        criterion(criteria, missed, "F4", "Anteil UNKLAR unter den 81 (" + unclear[0] + " von " + unclear[1] + ")",
                share(unclear[0], unclear[1]), settings.f4Unclear, false, true);
        int failed = 0;
        for (Verdict verdict : verdicts) {
            failed += verdict.isOk() ? 0 : 1;
        }
        criterion(criteria, missed, "F5", "FEHLER nach einer Wiederholung (" + failed + " von " + verdicts.size()
                + ")", share(failed, verdicts.size()), settings.f5Failed, false, true);
        json.add("kriterien", criteria);
        List<String> noTopic = Collections.singletonList("KEIN_THEMA");
        int[] noTopicFound = count(cases, verdicts, "81", noTopic, noTopic);
        int[] noTopicAll = count(cases, verdicts, "81", null, noTopic);
        JsonObject remnants = new JsonObject();
        remnants.addProperty("was", "nur Bericht: KEIN_THEMA-Themen der 81 als KEIN_THEMA erkannt, und wie oft das "
                + "Urteil KEIN_THEMA andere Themen der 81 traf");
        remnants.addProperty("erkannt", noTopicFound[0]);
        remnants.addProperty("von", noTopicFound[1]);
        remnants.addProperty("beiAnderen", noTopicAll[0] - noTopicFound[0]);
        json.add("keinThema", remnants);
        json.addProperty("fragenAnDenAutor", TopicFenceCheck.questions(verdicts).size());
        json.addProperty("bestanden", missed.isEmpty());
        json.addProperty("befund", missed.isEmpty() ? "alle Kriterien erfüllt" : "nicht erfüllt: "
                + String.join(", ", missed));
        Files.write(new File(out, "result.json").toPath(), new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(json).getBytes(StandardCharsets.UTF_8));
        return json;
    }

    private static JsonObject setJson(List<Integer> indexes, List<Case> cases, List<Verdict> verdicts) {
        Map<String, Integer> totals = new LinkedHashMap<String, Integer>();
        for (String verdict : VERDICTS) {
            totals.put(verdict, 0);
        }
        Map<String, Map<String, Integer>> matrix = new TreeMap<String, Map<String, Integer>>();
        JsonArray topics = new JsonArray();
        List<String> problems = new ArrayList<String>();
        int missing = 0;
        int withParts = 0;
        for (int i : indexes) {
            Case c = cases.get(i);
            Verdict verdict = verdicts.get(i);
            String word = word(verdict);
            withParts += c.subject.getParts().isEmpty() ? 0 : 1;
            totals.put(word, totals.get(word) + 1);
            if (!c.reference.isEmpty()) {
                Map<String, Integer> row = matrix.computeIfAbsent(c.reference, r -> {
                    Map<String, Integer> fresh = new LinkedHashMap<String, Integer>();
                    for (String v : VERDICTS) {
                        fresh.put(v, 0);
                    }
                    return fresh;
                });
                row.put(word, row.get(word) + 1);
            }
            if (!verdict.isOk() && problems.size() < 10) {
                problems.add(verdict.getProblem());
            }
            missing += c.missing;
            JsonObject t = new JsonObject();
            t.addProperty("id", c.subject.getTopicId());
            t.addProperty("name", TopicFenceCheck.cut(c.subject.getName(), 80));
            t.addProperty("referenz", c.reference);
            t.addProperty("urteil", word);
            t.addProperty("fundstellen", c.subject.getExcerpts().size());
            if (!c.subject.getParts().isEmpty()) {
                t.addProperty("teilthemen", c.subject.getParts().size());
            }
            topics.add(t);
        }
        JsonObject json = new JsonObject();
        json.addProperty("themen", indexes.size());
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> total : totals.entrySet()) {
            counts.addProperty(total.getKey(), total.getValue());
        }
        json.add("urteile", counts);
        JsonObject table = new JsonObject();
        for (Map.Entry<String, Map<String, Integer>> row : matrix.entrySet()) {
            JsonObject cells = new JsonObject();
            for (Map.Entry<String, Integer> cell : row.getValue().entrySet()) {
                cells.addProperty(cell.getKey(), cell.getValue());
            }
            table.add(row.getKey(), cells);
        }
        json.add("matrix", table);
        json.addProperty("fehlendePassagen", missing);
        json.addProperty("mitTeilthemen", withParts);
        JsonArray failures = new JsonArray();
        for (String problem : problems) {
            failures.add(problem);
        }
        json.add("fehlerBeispiele", failures);
        json.add("liste", topics);
        return json;
    }

    /**
     * [cases whose verdict is one of {@code words}, among the cases of the set with one of the references (any
     * reference for {@code null}); those cases].
     */
    private static int[] count(List<Case> cases, List<Verdict> verdicts, String set, List<String> references,
                               List<String> words) {
        int hits = 0;
        int all = 0;
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            if (!c.set.equals(set) || (references != null && !references.contains(c.reference))) {
                continue;
            }
            all++;
            hits += words.contains(word(verdicts.get(i))) ? 1 : 0;
        }
        return new int[]{hits, all};
    }

    /** INNEN, AUSSEN, UNKLAR, KEIN_THEMA (an AUSSEN the model gave as no subject) or FEHLER. */
    static String word(Verdict verdict) {
        return !verdict.isOk() ? "FEHLER" : verdict.isNoTopic() ? "KEIN_THEMA" : verdict.getFence().name();
    }

    private static void criterion(JsonObject criteria, List<String> missed, String name, String what, double value,
                                  double threshold, boolean atLeast, boolean alsoNeeded) {
        boolean met = (atLeast ? value >= threshold : value <= threshold) && alsoNeeded;
        JsonObject c = new JsonObject();
        c.addProperty("was", what);
        c.addProperty("wert", Math.round(value * 10000) / 10000.0);
        c.addProperty("schwelle", (atLeast ? ">= " : "<= ") + threshold);
        c.addProperty("erfuellt", met);
        criteria.add(name, c);
        if (!met) {
            missed.add(name);
        }
    }

    // ------------------------------------------------------------------------------------------------- sets

    /** The 81 topics of the topic analysis with the architect's verdict and their sample passages. */
    static List<Case> themen81(String themenJson, Map<String, String> texts, Options options) {
        List<Case> cases = new ArrayList<Case>();
        for (JsonElement element : JsonParser.parseString(themenJson).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject t = element.getAsJsonObject();
            int missing = 0;
            List<String> excerpts = new ArrayList<String>();
            for (JsonElement id : t.getAsJsonArray("stichprobe")) {
                String text = texts.get(id.getAsString());
                if (text == null) {
                    missing++;
                } else if (excerpts.size() < options.getExcerpts()) {
                    excerpts.add(TopicFenceCheck.cut(text.replaceAll("\\s+", " ").trim(), options.getExcerptChars()));
                }
            }
            cases.add(new Case("81", new Subject(t.get("blatt").getAsString(), t.get("titel").getAsString(), "",
                    excerpts), t.get("urteil").getAsString(), missing));
        }
        return cases;
    }

    /** The 17 named topics with the sample passages of their sheets that hold one of their key words. */
    static List<Case> named(List<Expected> expected, String themenJson, Map<String, String> texts, Options options) {
        Map<String, List<String>> samples = new HashMap<String, List<String>>();
        for (JsonElement element : JsonParser.parseString(themenJson).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject t = element.getAsJsonObject();
            List<String> ids = new ArrayList<String>();
            for (JsonElement id : t.getAsJsonArray("stichprobe")) {
                ids.add(id.getAsString());
            }
            samples.put(t.get("blatt").getAsString(), ids);
        }
        List<Case> cases = new ArrayList<Case>();
        int number = 0;
        for (Expected topic : expected) {
            number++;
            List<String> excerpts = new ArrayList<String>();
            for (String sheet : topic.sheets) {
                for (String id : samples.getOrDefault(sheet, new ArrayList<String>())) {
                    String text = texts.get(id);
                    if (text != null && excerpts.size() < options.getExcerpts()
                            && TopicFinderStockTest.hitsAny(text, topic.keywords)) {
                        excerpts.add(TopicFenceCheck.cut(text.replaceAll("\\s+", " ").trim(),
                                options.getExcerptChars()));
                    }
                }
            }
            cases.add(new Case("17", new Subject(String.format("E%02d", number), topic.name, "", excerpts),
                    topic.verdict, 0));
        }
        return cases;
    }

    /**
     * The standing topics of a merge run that are not cards, with their quotes; the silver reference is the verdict
     * of the named topic that at least two clear proposals of the topic carry and at least 60 % of its clear ones.
     */
    static List<Case> fromMerge(ResearchMemory memory, List<Expected> expected, Options options) {
        return fromMerge(memory, expected, options, null);
    }

    /** As above, only the standing topics whose ids {@code only} holds; {@code null} for all. */
    static List<Case> fromMerge(ResearchMemory memory, List<Expected> expected, Options options, Set<String> only) {
        List<ResearchTopic> judged = new ArrayList<ResearchTopic>();
        for (ResearchTopic topic : memory.topics()) {
            if (topic.getOrigin() != ResearchTopic.Origin.KONZEPT && (only == null || only.contains(topic.getId()))) {
                judged.add(topic);
            }
        }
        List<Subject> subjects = TopicFenceCheck.subjects(memory, judged, options);
        List<Case> cases = new ArrayList<Case>();
        for (int t = 0; t < judged.size(); t++) {
            ResearchTopic topic = judged.get(t);
            Map<Integer, Integer> labels = new HashMap<Integer, Integer>();
            int clear = 0;
            List<String> members = new ArrayList<String>(topic.getMergedFrom());
            if (members.isEmpty()) {
                members.add(topic.getId());
            }
            for (String id : members) {
                ResearchTopic member = memory.topic(id);
                String text = member == null ? "" : member.getName() + " " + member.getDescription();
                int label = -1;
                for (int e = 0; e < expected.size(); e++) {
                    if (TopicFinderStockTest.hitsAny(text, expected.get(e).keywords)) {
                        label = label == -1 ? e : -2;
                    }
                }
                if (label >= 0) {
                    clear++;
                    labels.merge(label, 1, Integer::sum);
                }
            }
            String reference = "";
            for (Map.Entry<Integer, Integer> label : labels.entrySet()) {
                if (label.getValue() >= 2 && label.getValue() >= 0.6 * clear) {
                    reference = expected.get(label.getKey()).verdict;
                }
            }
            cases.add(new Case("15", subjects.get(t), reference, 0));
        }
        return cases;
    }

    /**
     * The topics a list of ids of the memory names (switch {@code nurThemen}): each id is resolved to the topic it
     * stands in now ({@link ResearchMemory#currentTopic}), so an id of a proposal that the merge took into a topic
     * names that topic. Cards are inside by definition and not judged.
     */
    static final class OnlyTopics {
        /** The distinct ids of the file, in its order. */
        final List<String> given = new ArrayList<String>();
        /** The standing topics to judge, in the order their first id came. */
        final Set<String> standing = new LinkedHashSet<String>();
        /** Ids that stand in another topic now (merged into it). */
        final List<String> redirected = new ArrayList<String>();
        /** Ids that landed on a topic an earlier id of the file already named. */
        final List<String> collapsed = new ArrayList<String>();
        final List<String> cards = new ArrayList<String>();
        final List<String> unknown = new ArrayList<String>();

        /** One id per line ({@code #} starts a comment line; commas, semicolons and blanks part ids) or JSON. */
        static List<String> readIds(String text) {
            String t = text.startsWith("\uFEFF") ? text.substring(1) : text;
            List<String> ids = new ArrayList<String>();
            String trimmed = t.trim();
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                JsonElement root = JsonParser.parseString(trimmed);
                JsonArray list = null;
                if (root.isJsonArray()) {
                    list = root.getAsJsonArray();
                } else {
                    for (String key : Arrays.asList("ids", "themen", "topics")) {
                        if (list == null && root.getAsJsonObject().has(key)) {
                            list = root.getAsJsonObject().getAsJsonArray(key);
                        }
                    }
                }
                if (list == null) {
                    throw new IllegalArgumentException("nurThemen: a JSON array, or an object with ids, themen or "
                            + "topics expected");
                }
                for (JsonElement element : list) {
                    String id = "";
                    if (element.isJsonPrimitive()) {
                        id = element.getAsString();
                    } else if (element.isJsonObject()) {
                        JsonObject object = element.getAsJsonObject();
                        JsonElement value = object.has("id") ? object.get("id") : object.get("themaId");
                        id = value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
                    }
                    if (id.trim().isEmpty()) {
                        throw new IllegalArgumentException("nurThemen: an element without id: " + element);
                    }
                    ids.add(id.trim());
                }
                return ids;
            }
            for (String line : t.split("\\r?\\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                for (String id : l.split("[\\s,;]+")) {
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        }

        static OnlyTopics resolve(List<String> ids, ResearchMemory memory) {
            OnlyTopics only = new OnlyTopics();
            for (String id : new LinkedHashSet<String>(ids)) {
                only.given.add(id);
                ResearchTopic topic = memory.currentTopic(id);
                if (topic == null) {
                    only.unknown.add(id);
                } else if (topic.getOrigin() == ResearchTopic.Origin.KONZEPT) {
                    only.cards.add(id);
                } else {
                    if (!topic.getId().equals(id)) {
                        only.redirected.add(id);
                    }
                    if (!only.standing.add(topic.getId())) {
                        only.collapsed.add(id);
                    }
                }
            }
            return only;
        }

        JsonObject json(String file) {
            JsonObject json = new JsonObject();
            json.addProperty("datei", file);
            json.addProperty("angegeben", given.size());
            json.addProperty("gefunden", standing.size());
            json.addProperty("umgeleitet", redirected.size());
            json.addProperty("zusammengefallen", collapsed.size());
            json.add("karten", strings(cards));
            json.add("unbekannt", strings(unknown));
            return json;
        }

        private static JsonArray strings(List<String> values) {
            JsonArray array = new JsonArray();
            for (String value : values) {
                array.add(value);
            }
            return array;
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static Map<String, String> passages(Stock stock) {
        Map<String, String> texts = new HashMap<String, String>();
        for (Source source : stock.sources) {
            for (SourcePassage passage : source.getPassages()) {
                texts.put(passage.getPassageId(), passage.getText());
            }
        }
        return texts;
    }

    /** The research memory of a merge run copied next to the result, so the run's own files are only read. */
    static ResearchMemory copiedMemory(File project, File out) throws Exception {
        File source = new File(project, "research-memory");
        if (!source.isDirectory()) {
            throw new IOException("no research-memory in " + project);
        }
        File copy = new File(out, "gedaechtnis-kopie");
        deleteTree(copy);
        Path from = source.toPath();
        Path to = new File(copy, "research-memory").toPath();
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return new ResearchMemoryStore(copy).open();
    }

    private static String text(String property, String resource) throws IOException {
        String path = System.getProperty(P + property, "");
        if (!path.isEmpty()) {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        }
        java.io.InputStream in = TopicFenceStockTest.class.getResourceAsStream("/topic-finder-stock/" + resource);
        if (in == null) {
            throw new IOException("resource " + resource + " missing");
        }
        try (java.io.InputStream stream = in) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static double share(int part, int whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        Files.delete(file.toPath());
    }
}
