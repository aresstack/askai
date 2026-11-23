package com.aresstack.askai.research.topics;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.memory.PassageNoise;
import com.aresstack.askai.research.memory.PassageText;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.team.MainModelChatResult.Status;
import com.aresstack.askai.research.topics.TopicFinder.Options;
import com.aresstack.askai.research.topics.TopicFinder.Source;
import com.aresstack.askai.research.topics.TopicFinder.SourcePassage;
import com.aresstack.askai.research.topics.TopicFinder.Window;
import com.aresstack.askai.research.topics.TopicFinder.WindowResult;
import com.aresstack.askai.research.topics.TopicProposal.FindingCheck;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 13 with a scripted model: windows, the request, the lookup of every quote, nothing dropped silently,
 * repair, FEHLER, the breaker, the parallel batch and the windows still to read. The texts are made up for this test
 * (road traffic), not taken from any source.
 */
public class TopicFinderTest {

    private static final String MISSION = "Ein Sachbuch für Stadtplaner über Verkehrssicherheit in Innenstädten.";
    private static final String URL = "https://www.stadt.example/verkehr/bericht-2025";
    private static final String P1 = "Nach Einführung von Tempo 30 in der Innenstadt sank die Zahl der schweren "
            + "Unfälle im ersten Jahr deutlich. Die Polizei kontrollierte in dieser Zeit doppelt so oft.";
    private static final String P2 = "Lieferverkehr in der Fußgängerzone ist nur bis zehn Uhr erlaubt. Danach "
            + "übernehmen Lastenräder die letzte Meile zu den Geschäften.";
    private static final String P3 = "Ampeln mit Restzeitanzeige verringern das Queren bei Rot, berichten "
            + "Verkehrsplaner aus mehreren Städten.";
    private static final List<String> KNOWN = Arrays.asList("Tempo 30 und Unfallzahlen", "Radwege");

    @Test
    public void theRoleHasItsOwnSlotAndAFixedSchema() {
        assertEquals("phase.research.topics", TopicFinder.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.OFF, TopicFinder.ROLE.getThinking());
        JsonObject schema = JsonParser.parseString(TopicFinder.schema()).getAsJsonObject();
        assertEquals("[\"vorschlaege\"]", schema.get("required").toString());
        assertFalse(schema.get("additionalProperties").getAsBoolean());
        JsonObject list = schema.getAsJsonObject("properties").getAsJsonObject("vorschlaege");
        assertEquals(5, list.get("maxItems").getAsInt());
        JsonObject item = list.getAsJsonObject("items");
        assertEquals("[passage, zitat, name, beschreibung, fragen]",
                item.getAsJsonObject("properties").keySet().toString());
        assertEquals("[\"passage\",\"zitat\",\"name\",\"beschreibung\",\"fragen\"]", item.get("required").toString());
        assertEquals("integer", item.getAsJsonObject("properties").getAsJsonObject("passage").get("type")
                .getAsString());
        assertEquals(2, item.getAsJsonObject("properties").getAsJsonObject("fragen").get("maxItems").getAsInt());
        assertFalse(item.get("additionalProperties").getAsBoolean());
        assertEquals(schema, JsonParser.parseString(TopicFinder.ROLE.getOutput().getSchemaJson()));
    }

    @Test
    public void aSourceIsReadInWindowsOfConsecutivePassages() {
        String a = repeat('a', 900);
        String b = repeat('b', 900);
        String c = repeat('c', 1500); // counted as 1000, the cut a call shows
        String d = repeat('d', 300);
        Source source = new Source("s1", URL, "Bericht", "", Arrays.asList(new SourcePassage("p1", a),
                new SourcePassage("p2", b), new SourcePassage("leer", "   "), new SourcePassage("p3", c),
                new SourcePassage("p4", d)));
        List<Window> windows = TopicFinder.windowsOf(source, new Options(1, 2000, 1000));

        assertEquals(2, windows.size());
        assertEquals(ids("p1", "p2"), idsOf(windows.get(0)));
        assertEquals(ids("p3", "p4"), idsOf(windows.get(1)));
        assertEquals(1, windows.get(0).getIndex());
        assertEquals(2, windows.get(1).getIndex());
        assertEquals(2, windows.get(1).getCount());
        assertEquals("s1\np3\np4", windows.get(1).key());
        assertEquals("stadt.example", source.getDomain());
        // one passage longer than a window still gets a window of its own
        assertEquals(1, TopicFinder.windowsOf(new Source("s2", URL, "", "", Collections.singletonList(
                new SourcePassage("lang", repeat('x', 9000)))), new Options(1, 2000, 2000)).size());
        assertTrue(TopicFinder.windowsOf(new Source("s3", URL, "", "", Collections.singletonList(
                new SourcePassage("leer", ""))), Options.defaults()).isEmpty());
        try {
            new Options(1, 1999, 300);
            fail("a window needs room");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void theRequestCarriesMissionKnownTopicsSourceAndNumberedPassages() {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok("{\"vorschlaege\":[]}"));
        TopicFinder finder = finder(chat, "de", null);
        WindowResult result = finder.read(window(source("s1", P1, P2)), KNOWN, 1);

        assertTrue(result.isOk());
        assertTrue(result.getProposals().isEmpty());
        assertEquals(1, chat.calls.get());
        assertEquals(0.0, chat.temperature, 0.0);
        assertEquals(1200, chat.tokens);
        assertEquals(TopicFinder.schema(), chat.schemas.get(0));
        List<ChatMessage> request = chat.requests.get(0);
        assertEquals(TopicFinder.SYSTEM_DE, request.get(0).getContent());
        String prompt = request.get(1).getContent();
        assertTrue(prompt.contains("Buchauftrag:\n" + MISSION));
        assertTrue(prompt.contains("Bekannte Themen des Buchs:\n- Tempo 30 und Unfallzahlen\n- Radwege\n"));
        assertTrue(prompt.contains("Quelle: Bericht s1 (stadt.example)"));
        assertTrue(prompt.contains("Passagen der Quelle:\n[1] " + P1 + "\n\n[2] " + P2 + "\n\n"));
        assertFalse(prompt.contains("Teil 1"));
        assertTrue(prompt.contains("Schlage höchstens 5 Themen vor"));
        assertTrue(prompt.contains("Ob ein Thema ins Buch gehört, entscheidet später eine eigene Prüfung."));
        assertTrue(prompt.contains("Übernimm die Wörter genau so, wie sie dastehen."));
        assertTrue(prompt.contains("Schlage kein bekanntes Thema noch einmal vor"));
        assertTrue(prompt.indexOf("1. passage") < prompt.indexOf("2. zitat"));
        assertTrue(prompt.indexOf("2. zitat") < prompt.indexOf("3. name"));

        String first = TopicFinder.prompt(MISSION, Collections.<String>emptyList(), "", "", 2, 3,
                Collections.singletonList(P3), true);
        assertTrue(first.contains("(noch keine)"));
        assertTrue(first.contains("Quelle: (unbekannt)"));
        assertTrue(first.contains("Passagen der Quelle (Teil 2 von 3):"));
    }

    @Test
    public void englishProjectsGetEnglishPrompts() {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok("{\"vorschlaege\":[]}"));
        finder(chat, "en", null).read(window(source("s1", P1)), KNOWN, 1);
        List<ChatMessage> request = chat.requests.get(0);
        assertEquals(TopicFinder.SYSTEM_EN, request.get(0).getContent());
        assertTrue(request.get(1).getContent().contains("Known topics of the book:\n- Tempo 30 und Unfallzahlen"));
        assertTrue(request.get(1).getContent().contains("Propose at most 5 topics"));
    }

    @Test
    public void everyQuoteIsLookedUpInThePassagesTheCallRead() {
        String answer = "{\"vorschlaege\":["
                + item(1, "Die Polizei  kontrollierte in dieser Zeit doppelt so oft", "Polizeikontrollen") + ","
                + item(1, "übernehmen Lastenräder die letzte Meile", "Lastenräder in der Innenstadt") + ","
                + item(2, "Lieferwagen fahren nachts in die Fußgängerzone", "Nachtlieferungen") + ","
                + item(7, "steht in keiner Passage", "Unbekannte Stelle") + ","
                + "{\"passage\":\"[3]\",\"zitat\":\"Restzeitanzeige verringern das Queren bei Rot\","
                + "\"name\":\"Ampeln mit Restzeit\",\"beschreibung\":\"Wie Ampeln das Queren bei Rot senken.\","
                + "\"fragen\":[\"Wie stark sinkt das Queren bei Rot?\"]}]}";
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(answer));
        WindowResult result = finder(chat, "de", null).read(window(source("s1", P1, P2, P3)), KNOWN, 3);

        List<TopicProposal> proposals = result.getProposals();
        assertEquals(5, proposals.size());
        // blanks do not matter for the lookup, the words do (as for the evidence judge)
        assertEquals(FindingCheck.GEPRUEFT, proposals.get(0).getFindingCheck());
        assertEquals("s1-p1", proposals.get(0).getPassageId());
        assertEquals(FindingCheck.KORRIGIERT, proposals.get(1).getFindingCheck());
        assertEquals("s1-p2", proposals.get(1).getPassageId());
        assertEquals(FindingCheck.NICHT_GEFUNDEN, proposals.get(2).getFindingCheck());
        assertEquals("the passage the model named stays", "s1-p2", proposals.get(2).getPassageId());
        assertFalse(proposals.get(2).hasFinding());
        assertEquals(FindingCheck.NICHT_GEFUNDEN, proposals.get(3).getFindingCheck());
        assertEquals("", proposals.get(3).getPassageId());
        assertEquals(FindingCheck.GEPRUEFT, proposals.get(4).getFindingCheck());
        assertEquals("s1-p3", proposals.get(4).getPassageId());

        TopicProposal first = proposals.get(0);
        assertEquals("vs-r3-s1-w1-1", first.getId());
        assertEquals("vs-r3-s1-w1-5", proposals.get(4).getId());
        assertEquals("s1", first.getSourceId());
        assertEquals(URL, first.getSourceUrl());
        assertEquals("Polizeikontrollen", first.getName());
        assertEquals("Worum es bei Polizeikontrollen geht.", first.getDescription());
        assertEquals(Arrays.asList("Was zeigt Polizeikontrollen?", "Wo gilt Polizeikontrollen?"),
                first.getQuestions());
        assertEquals("test-model", first.getModel());
        assertEquals(3, first.getRound());
        assertEquals("s1-p1", first.toFinding().getPassageId());
        assertEquals(Arrays.asList("s1-p1", "s1-p2", "s1-p3"), result.getPassageIds());
        assertEquals(Collections.singletonList(answer), result.getAnswers());
        assertTrue(result.getDropped().isEmpty());
    }

    @Test
    public void nothingIsDroppedSilently() {
        String answer = "{\"vorschlaege\":[{\"passage\":1,\"zitat\":\"x\",\"name\":\" \",\"beschreibung\":\"b\","
                + "\"fragen\":[]},\"kein Objekt\","
                + "{\"passage\":1,\"zitat\":\"sank die Zahl der schweren Unfälle\",\"name\":\"Schwere Unfälle\","
                + "\"beschreibung\":\"b\",\"fragen\":[\"Eins?\",\" Eins? \",\"\",\"Zwei?\",\"Drei?\"]},"
                + item(1, "a", "Zwei") + "," + item(1, "a", "Drei") + "," + item(1, "a", "Vier") + ","
                + item(1, "a", "Fünf") + "," + item(1, "a", "Sechs") + "]}";
        WindowResult result = finder(new Scripted(m -> MainModelChatResult.ok(answer)), "de", null)
                .read(window(source("s1", P1)), KNOWN, 1);

        assertEquals(5, result.getProposals().size());
        assertEquals(Arrays.asList("Eins?", "Zwei?"), result.getProposals().get(0).getQuestions());
        assertEquals(Arrays.asList("proposal 1: no name", "proposal 2: not an object",
                "\"Sechs\": over 5 proposals", "\"Schwere Unfälle\": 1 question(s) over 2"), result.getDropped());
    }

    @Test
    public void anUnreadableAnswerGetsOneRepairThenFehler() {
        AtomicInteger n = new AtomicInteger();
        Scripted repaired = new Scripted(m -> MainModelChatResult.ok(n.incrementAndGet() == 1 ? "Thema: Tempo 30"
                : "{\"vorschlaege\":[" + item(1, "sank die Zahl der schweren Unfälle", "Schwere Unfälle") + "]}"));
        WindowResult ok = finder(repaired, "de", null).read(window(source("s1", P1)), KNOWN, 1);
        assertTrue(ok.isOk());
        assertEquals(1, ok.getProposals().size());
        assertEquals(2, ok.getAnswers().size());
        List<ChatMessage> second = repaired.requests.get(1);
        assertEquals(4, second.size());
        assertEquals("Thema: Tempo 30", second.get(2).getContent());
        assertTrue(second.get(3).getContent().startsWith("Deine Antwort war ungültig: the answer is no JSON"));

        Scripted broken = new Scripted(m -> MainModelChatResult.ok("{\"themen\":[]}"));
        WindowResult failed = finder(broken, "de", null).read(window(source("s1", P1)), KNOWN, 1);
        assertEquals(WindowResult.Status.FEHLER, failed.getStatus());
        assertEquals("invalid answer: \"vorschlaege\" missing or not a list", failed.getProblem());
        assertTrue(failed.getProposals().isEmpty());
        assertEquals(2, broken.calls.get());
    }

    @Test
    public void aFailedCallIsAskedOnceMoreAndThenFehlerNeverAnEmptyList() {
        Scripted down = new Scripted(m -> MainModelChatResult.failure(Status.TIMEOUT, "slow"));
        WindowResult failed = finder(down, "de", null).read(window(source("s1", P1)), KNOWN, 1);
        assertFalse(failed.isOk());
        assertEquals("model call failed (TIMEOUT): slow", failed.getProblem());
        assertEquals(Arrays.asList("", ""), failed.getAnswers());
        assertEquals(2, down.calls.get());

        Scripted crashing = new Scripted(m -> {
            throw new IllegalStateException("connection refused");
        });
        WindowResult crashed = finder(crashing, "de", null).read(window(source("s1", P1)), KNOWN, 1);
        assertEquals("model call failed (PROVIDER_FAILURE): connection refused", crashed.getProblem());

        AtomicInteger n = new AtomicInteger();
        Scripted once = new Scripted(m -> n.incrementAndGet() == 1 ? MainModelChatResult.failure(Status.TIMEOUT,
                "slow") : MainModelChatResult.ok("{\"vorschlaege\":[]}"));
        WindowResult second = finder(once, "de", null).read(window(source("s1", P1)), KNOWN, 1);
        assertTrue(second.isOk());
        assertEquals(Arrays.asList("", "{\"vorschlaege\":[]}"), second.getAnswers());
        try {
            new WindowResult("s1", URL, 1, ids("p1"), WindowResult.Status.FEHLER, "x",
                    Collections.singletonList(proposal("vs-1")), null, null, "m", 1, 1L, 1L);
            fail("a failed window holds no proposals");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void theBatchKeepsTheOrderAndRunsCallsAtTheSameTime() {
        final CyclicBarrier together = new CyclicBarrier(2);
        Scripted chat = new Scripted(m -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (Exception notTogether) {
                return MainModelChatResult.failure(Status.PROVIDER_FAILURE, "calls did not overlap");
            }
            String prompt = m.get(1).getContent();
            String quote = prompt.contains("[1] " + P2) ? "Lastenräder die letzte Meile" : "doppelt so oft";
            return MainModelChatResult.ok("{\"vorschlaege\":[" + item(1, quote, "Thema") + "]}");
        });
        Source a = source("a", P1);
        Source b = new Source("b", "https://b.example/x", "B", "", Arrays.asList(new SourcePassage("b1", P1),
                new SourcePassage("b2", P2 + " " + repeat('z', 1800))));
        Source c = source("c", P2);
        List<String> log = Collections.synchronizedList(new ArrayList<String>());
        TopicFinder finder = new TopicFinder(chat, MISSION, "de", new Options(2, 2000, 2000), log::add);
        List<WindowResult> results = finder.readAll(Arrays.asList(a, b, c), KNOWN, 1);

        // two calls meet at the barrier each time; a call alone would time out and fail
        assertEquals(4, results.size());
        for (WindowResult result : results) {
            assertTrue(result.getProblem(), result.isOk());
        }
        assertEquals(Arrays.asList("a", "b", "b", "c"), sourcesOf(results));
        assertEquals(Arrays.asList(1, 1, 2, 1), windowsOf(results));
        assertEquals("doppelt so oft", results.get(1).getProposals().get(0).getQuote());
        assertEquals("Lastenräder die letzte Meile", results.get(2).getProposals().get(0).getQuote());
        assertEquals("b2", results.get(2).getProposals().get(0).getPassageId());
        assertEquals("topic finder: 4 windows, 4 proposals, 0 failed", log.get(log.size() - 1));
        Map<String, List<TopicProposal>> bySource = TopicFinder.bySource(results);
        assertEquals(Arrays.asList("a", "b", "c"), new ArrayList<String>(bySource.keySet()));
        assertEquals(2, bySource.get("b").size());
    }

    @Test
    public void theBreakerStopsAfterFiveFailedWindowsAndNamesTheLastCause() {
        Scripted down = new Scripted(m -> MainModelChatResult.failure(Status.TIMEOUT, "slow"));
        List<Source> sources = new ArrayList<Source>();
        for (int i = 1; i <= 7; i++) {
            sources.add(source("s" + i, P1));
        }
        List<WindowResult> results = new TopicFinder(down, MISSION, "de", new Options(1, 2000, 1500), null)
                .readAll(sources, KNOWN, 1);

        assertEquals(7, results.size());
        assertEquals("five windows, two calls each", 10, down.calls.get());
        for (int i = 0; i < 5; i++) {
            assertEquals("model call failed (TIMEOUT): slow", results.get(i).getProblem());
        }
        for (int i = 5; i < 7; i++) {
            assertEquals("not asked: the model failed 5 windows in a row; last: model call failed (TIMEOUT): slow",
                    results.get(i).getProblem());
            assertEquals(0L, results.get(i).getMillis());
            assertTrue(results.get(i).getAnswers().isEmpty());
        }
    }

    @Test
    public void onlyWindowsWithoutAnOkResultAreStillToRead() {
        Source old = new Source("alt", URL, "", "", Arrays.asList(new SourcePassage("a1", repeat('a', 1500)),
                new SourcePassage("a2", repeat('b', 1500))));
        Source fresh = source("neu", P1);
        TopicFinder finder = finder(new Scripted(m -> MainModelChatResult.ok("{\"vorschlaege\":[]}")), "de",
                new Options(1, 2000, 1500));
        List<WindowResult> done = Arrays.asList(
                new WindowResult("alt", URL, 1, ids("a1"), WindowResult.Status.OK, "", null, null, null, "m", 1, 1L,
                        1L),
                new WindowResult("alt", URL, 2, ids("a2"), WindowResult.Status.FEHLER, "slow", null, null, null, "m",
                        1, 1L, 1L));

        List<Window> open = finder.unread(Arrays.asList(old, fresh), done);
        assertEquals(2, open.size());
        assertEquals("alt\na2", open.get(0).key());
        assertEquals("neu\nneu-p1", open.get(1).key());
        assertEquals(3, finder.unread(Arrays.asList(old, fresh), null).size());
    }

    /** After the page-noise check (#274): passages it marks as leftovers are left out of the windows. */
    @Test
    public void pageLeftoversAreLeftOutOfTheWindows() {
        String leftover = repeat('m', 500);
        Source source = new Source("s1", URL, "", "", Arrays.asList(new SourcePassage("p1", repeat('a', 500)),
                new SourcePassage("menu", leftover), new SourcePassage("p2", repeat('b', 500))));
        PassageNoise noise = key -> key.equals(PassageText.key(leftover));

        assertEquals(ids("p1", "p2"), idsOf(TopicFinder.windowsOf(source, Options.defaults(), noise).get(0)));
        assertEquals(ids("p1", "menu", "p2"), idsOf(TopicFinder.windowsOf(source, Options.defaults()).get(0)));
        TopicFinder finder = finder(new Scripted(m -> MainModelChatResult.ok("{\"vorschlaege\":[]}")), "de",
                Options.defaults());
        assertEquals(ids("p1", "menu", "p2"), idsOf(finder.unread(Arrays.asList(source), null).get(0)));
        assertEquals(ids("p1", "p2"), idsOf(finder.useNoise(noise).unread(Arrays.asList(source), null).get(0)));
    }

    @Test
    public void helpersCleanTheirInput() {
        List<String> many = new ArrayList<String>(Arrays.asList(" Radwege ", "Radwege", "", null, "Tempo  30"));
        for (int i = 0; i < 100; i++) {
            many.add("Thema " + i);
        }
        List<String> known = TopicFinder.known(many);
        assertEquals(80, known.size());
        assertEquals(Arrays.asList("Radwege", "Tempo 30", "Thema 0"), known.subList(0, 3));
        assertEquals(3, TopicFinder.number(JsonParser.parseString("3")));
        assertEquals(3, TopicFinder.number(JsonParser.parseString("\"[3]\"")));
        assertEquals(0, TopicFinder.number(JsonParser.parseString("\"keine\"")));
        assertEquals(0, TopicFinder.number(null));
        assertEquals(0, TopicFinder.number(new JsonArray()));
        assertEquals("stadt.example", TopicFinder.hostOf("https://user@www.stadt.example:8443/a?b#c"));
        assertEquals("", TopicFinder.hostOf(""));
        assertEquals("abc …", TopicFinder.cut("abc def", 4));
        assertEquals("vs-r1-s9-w2-3", TopicFinder.proposalId(0, "s9", 2, 3));
        assertTrue(TopicFinder.parse("[1]").problem.contains("no JSON object"));
        assertTrue(TopicFinder.parse("").problem.contains("no JSON"));
    }

    // ------------------------------------------------------------------------------------------- helpers

    static Source source(String id, String... texts) {
        List<SourcePassage> passages = new ArrayList<SourcePassage>();
        for (int i = 0; i < texts.length; i++) {
            passages.add(new SourcePassage(id + "-p" + (i + 1), texts[i]));
        }
        return new Source(id, URL, "Bericht " + id, "", passages);
    }

    static Window window(Source source) {
        List<Window> windows = TopicFinder.windowsOf(source, Options.defaults());
        assertEquals(1, windows.size());
        return windows.get(0);
    }

    static String item(int passage, String quote, String name) {
        return "{\"passage\":" + passage + ",\"zitat\":\"" + quote + "\",\"name\":\"" + name + "\","
                + "\"beschreibung\":\"Worum es bei " + name + " geht.\",\"fragen\":[\"Was zeigt " + name + "?\","
                + "\"Wo gilt " + name + "?\"]}";
    }

    static TopicProposal proposal(String id) {
        return new TopicProposal(id, "s1", URL, "s1-p1", "sank die Zahl", FindingCheck.GEPRUEFT, "Schwere Unfälle",
                "Wie oft schwere Unfälle geschehen.", Collections.singletonList("Wie oft?"), "m", 1, 1L);
    }

    private static TopicFinder finder(MainModelChat chat, String language, Options options) {
        return new TopicFinder(chat, MISSION, language, options, null);
    }

    private static List<String> ids(String... ids) {
        return Arrays.asList(ids);
    }

    private static List<String> idsOf(Window window) {
        List<String> ids = new ArrayList<String>();
        for (SourcePassage passage : window.getPassages()) {
            ids.add(passage.getPassageId());
        }
        return ids;
    }

    private static List<String> sourcesOf(List<WindowResult> results) {
        List<String> ids = new ArrayList<String>();
        for (WindowResult result : results) {
            ids.add(result.getSourceId());
        }
        return ids;
    }

    private static List<Integer> windowsOf(List<WindowResult> results) {
        List<Integer> windows = new ArrayList<Integer>();
        for (WindowResult result : results) {
            windows.add(result.getWindowIndex());
        }
        return windows;
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    static final class Scripted implements MainModelChat {
        final Function<List<ChatMessage>, MainModelChatResult> answers;
        final AtomicInteger calls = new AtomicInteger();
        final List<List<ChatMessage>> requests = Collections.synchronizedList(new ArrayList<List<ChatMessage>>());
        final List<String> schemas = Collections.synchronizedList(new ArrayList<String>());
        volatile double temperature = -1;
        volatile int tokens = -1;

        Scripted(Function<List<ChatMessage>, MainModelChatResult> answers) {
            this.answers = answers;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            requests.add(new ArrayList<ChatMessage>(messages));
            schemas.add(schemaJson);
            this.temperature = temperature;
            this.tokens = maxOutputTokens;
            return answers.apply(messages);
        }

        @Override
        public String modelName() {
            return "test-model";
        }
    }
}
