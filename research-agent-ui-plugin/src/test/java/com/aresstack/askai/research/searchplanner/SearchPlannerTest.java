package com.aresstack.askai.research.searchplanner;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.lead.LeadTask;
import com.aresstack.askai.research.lead.SearchStrategy;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.LeadDecision.Target;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.SearchRecord;
import com.aresstack.askai.research.plan.SourcePreference;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Component 3: a task of the research lead becomes searches with a question, the strategy and a purpose; no earlier
 * search is repeated, {@code site:} only for named publishers, a failed task stays without a made-up search.
 */
public class SearchPlannerTest {

    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(70_000L), ZoneOffset.UTC);
    static final String MISSION = "Ein Praxisbuch über Verkehr in Städten: Tempo, Radwege und Parken.";
    private static final String TEMPO_TEXT = "In der Tempo-30-Zone sank die Zahl der schweren Unfälle um ein Viertel.";

    @Test
    public void aTaskBecomesSearchesWithQuestionStrategyAndPurposeInTheMemory() throws IOException {
        ResearchMemory memory = memory();
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer(
                search("F01", "Unfallzahlen nach Einführung von Tempo 30", "Tempo 30 Unfallstatistik Großstadt 2023"),
                search("F01", "Studien mit Vorher-Nachher-Vergleich", "Tempo 30 Vorher Nachher Studie Unfälle"))));

        RoundPlan round = planner(chat).plan(memory, Collections.singletonList(task("F01",
                SearchStrategy.ANDERE_WORTWAHL, "Unfallforschung der Versicherer")), MISSION, 12);

        TaskPlan plan = round.getTasks().get(0);
        assertEquals(TaskPlan.Status.MODELL, plan.getStatus());
        assertEquals(2, plan.getSearches().size());
        PlannedSearch first = plan.getSearches().get(0);
        assertEquals("suche-r2-1", first.getId());
        assertEquals("F01", first.getQuestionId());
        assertEquals("andere_wortwahl", first.getStrategy());
        assertEquals("Unfallzahlen nach Einführung von Tempo 30", first.getPurpose());
        SearchRecord stored = memory.searches().get(memory.searches().size() - 2);
        assertEquals("suche-r2-1", stored.getId());
        assertEquals("Tempo 30 Unfallstatistik Großstadt 2023", stored.getQuery());
        assertEquals(Collections.singletonList("F01"), stored.getQuestionIds());
        assertTrue(stored.getTopicIds().isEmpty());
        assertEquals("PLANER", stored.getOrigin());
        assertEquals(2, stored.getRound());
        assertNull("the yield comes after the search", stored.getYield());
        assertEquals("test-model", plan.getModel());

        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt, prompt.startsWith("Buchauftrag:\n" + MISSION + "\n\nAufgabe der Rechercheleitung "
                + "(Runde 2):\nStrategie: andere_wortwahl (andere Begriffe"));
        assertTrue(prompt, prompt.contains("Vorgabe: Unfallforschung der Versicherer\n"));
        assertTrue(prompt, prompt.contains("- F01 [Tempo und Verkehrsberuhigung] Wie wirkt Tempo 30 auf die Zahl der "
                + "Unfälle?\n  Belegt"));
        assertTrue(prompt, prompt.contains("; Suchen bisher: 1\n  Aus einem Fund: „sank die Zahl der schweren Unfälle "
                + "um ein Viertel“\n"));
        assertTrue(prompt, prompt.contains("Quellen der bisherigen Funde: bast.de\n"));
        assertTrue(prompt, prompt.contains("Bisherige Suchen dazu (nicht wiederholen):\n- „Tempo 30 Unfälle“ "
                + "(standard, Runde 1): 3 Quellen, 10 Passagen, 1 neue Belege\n"));
        assertTrue(prompt, prompt.contains("- Strategie: andere Wörter als die bisherigen Suchen"));
        assertTrue(prompt, prompt.contains("- Keine Operatoren wie site:, Anführungszeichen oder OR.\n"));
        assertFalse("no preferred sources for this strategy", prompt.contains("Bevorzugte Quellen"));
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        JsonObject list = schema.getAsJsonObject("properties").getAsJsonObject("suchen");
        assertEquals(3, list.get("maxItems").getAsInt());
        assertEquals("[\"F01\"]", list.getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("frage")
                .getAsJsonArray("enum").toString());
        assertEquals("[\"frage\",\"zweck\",\"anfrage\"]", list.getAsJsonObject("items").getAsJsonArray("required")
                .toString());
        assertEquals(0.3, chat.temperature, 1e-9);
        assertEquals(1024, chat.tokens);
    }

    @Test
    public void theRoleIsThePlannerSlotWithoutThinking() {
        assertEquals(ResearchModelSlots.PHASE_RESEARCH_PLANNER, SearchPlanner.ROLE.getSlot());
        assertEquals(ThinkingMode.OFF, SearchPlanner.ROLE.getThinking());
        assertEquals("search-planner", SearchPlanner.ROLE.getId());
    }

    @Test
    public void noEarlierSearchIsRepeatedInAnyOrderOrCase() throws IOException {
        ResearchMemory memory = memory();
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer(
                search("F01", "dieselbe Suche noch einmal", "unfälle, TEMPO 30"),
                search("F01", "eine neue Suche zu Tempo 30", "Tempo 30 Hauptstraße Unfallbilanz"),
                search("F01", "dieselbe Suche zweimal in der Antwort", "Unfallbilanz Hauptstraße Tempo 30"))));

        TaskPlan plan = planner(chat).plan(memory, Collections.singletonList(task("F01", SearchStrategy.STANDARD,
                "")), MISSION, 12).getTasks().get(0);

        assertEquals(1, plan.getSearches().size());
        assertEquals("Tempo 30 Hauptstraße Unfallbilanz", plan.getSearches().get(0).getQuery());
        assertEquals("wiederholt die Suche „Tempo 30 Unfälle“", plan.getRejected().get(0).getReason());
        assertEquals("doppelt in dieser Antwort", plan.getRejected().get(1).getReason());
        assertEquals(SearchPlanner.key("Tempo 30 Unfälle"), SearchPlanner.key("unfälle, TEMPO 30"));
        assertEquals(SearchPlanner.key("Radweg Kosten site:www.bast.de/x"), SearchPlanner.key("Kosten Radweg "
                + "site:bast.de"));
        assertFalse("another site: is another search", SearchPlanner.key("Radweg Kosten").equals(
                SearchPlanner.key("Radweg Kosten site:bast.de")));
    }

    @Test
    public void siteOnlyForNamedPublishersAndThenTheMissionsPreferredSourcesAreShown() throws IOException {
        ResearchMemory memory = memory();
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer(
                search("F03", "Kosten aus den Berichten der Bundesanstalt", "Kosten geschützter Radweg site:bast.de"),
                search("F03", "Kosten aus Leitfäden der Kommunen", "Kosten geschützter Radweg Leitfaden Kommunen"))));
        SourcePreference preference = SourcePreference.of(MISSION + " Bevorzuge Quellen von Behörden (BASt, "
                + "Difu, Studien).", new SourcePreference.Settings(true, Arrays.asList("bevorzuge"),
                Collections.singletonMap("BASt", "bast.de"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), 1, 2));
        SearchPlanner planner = new SearchPlanner(chat, "de", SearchPlanner.Options.defaults(), preference, null,
                CLOCK);

        TaskPlan publishers = planner.plan(memory, Collections.singletonList(task("F03",
                SearchStrategy.GEZIELTE_HERAUSGEBER, "Bundesanstalt und Städtetag")), MISSION, 12).getTasks().get(0);
        TaskPlan words = planner.plan(memory, Collections.singletonList(task("F03", SearchStrategy.ANDERE_WORTWAHL,
                "")), MISSION, 12).getTasks().get(0);

        assertEquals(2, publishers.getSearches().size());
        assertEquals("Kosten geschützter Radweg site:bast.de", publishers.getSearches().get(0).getQuery());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("Bevorzugte Quellen laut Auftrag: BASt, Difu, Studien, bast.de\n"));
        assertTrue(prompt, prompt.contains("- \"site:\" mit der Adresse eines Herausgebers ist erlaubt"));
        assertEquals("the second plan repeats both searches of the first", TaskPlan.Status.FEHLER, words.getStatus());
        assertEquals(3, chat.calls.get());
        boolean siteRefused = false;
        for (Rejection rejection : words.getRejected()) {
            siteRefused |= rejection.getReason().equals("site: nur bei der Strategie gezielte_herausgeber");
        }
        assertTrue(words.getRejected().toString(), siteRefused);
        assertFalse(chat.requests.get(1).get(1).getContent().contains("Bevorzugte Quellen"));
    }

    @Test
    public void wrongQuestionNoPurposeTooLongAndTooManyAreNotTaken() throws IOException {
        ResearchMemory memory = memory();
        StringBuilder longQuery = new StringBuilder();
        while (longQuery.length() < 170) {
            longQuery.append("Parken ");
        }
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer(
                search("F09", "eine Frage, die es nicht gibt", "Parken Kosten Hamburg"),
                search("F02", "", "Radweg Trennung Poller"),
                search("F02", "viel zu lang", longQuery.toString()),
                search("F02", "erste brauchbare Suche", "Radweg Trennung Poller Sicherheit"),
                search("F02", "zweite brauchbare Suche", "geschützter Radfahrstreifen Unfallforschung"),
                search("F02", "dritte brauchbare Suche", "Protected Bike Lane Deutschland Erfahrungen"),
                search("F02", "vierte ist eine zu viel", "Radweg Bordstein Breite Empfehlungen"))));

        TaskPlan plan = planner(chat).plan(memory, Collections.singletonList(task("F02", SearchStrategy.STANDARD,
                "")), MISSION, 12).getTasks().get(0);

        assertEquals(3, plan.getSearches().size());
        List<String> reasons = new ArrayList<String>();
        for (Rejection rejection : plan.getRejected()) {
            reasons.add(rejection.getReason());
        }
        assertEquals(Arrays.asList("nennt keine Frage der Aufgabe („F09“)", "ohne Satz, was sie finden soll",
                "Länge außerhalb von 3 bis 160 Zeichen", "mehr als 3 Suchen"), reasons);
    }

    @Test
    public void anUnusableAnswerIsRepairedOnceAndThenTheTaskFails() throws IOException {
        AtomicInteger n = new AtomicInteger();
        Scripted repaired = new Scripted(messages -> MainModelChatResult.ok(n.incrementAndGet() == 1
                ? answer(search("F01", "dieselbe Suche noch einmal", "Tempo 30 Unfälle"))
                : answer(search("F01", "jetzt eine neue Suche", "Tempo 30 Unfallkommission Bericht"))));
        TaskPlan plan = planner(repaired).plan(memory(), Collections.singletonList(task("F01",
                SearchStrategy.STANDARD, "")), MISSION, 12).getTasks().get(0);
        assertEquals(TaskPlan.Status.MODELL, plan.getStatus());
        assertEquals(2, repaired.calls.get());
        String repair = repaired.requests.get(1).get(3).getContent();
        assertTrue(repair, repair.startsWith("Deine Antwort war nicht brauchbar: keine brauchbare Suche: „Tempo 30 "
                + "Unfälle“ wiederholt die Suche „Tempo 30 Unfälle“\nSchreibe neue Suchen, nur das JSON-Objekt."));
        assertEquals(2, plan.getAnswers().size());

        ResearchMemory memory = memory();
        int before = memory.searches().size();
        Scripted broken = new Scripted(messages -> MainModelChatResult.ok("kein JSON"));
        TaskPlan failed = planner(broken).plan(memory, Collections.singletonList(task("F01",
                SearchStrategy.STANDARD, "")), MISSION, 12).getTasks().get(0);
        assertEquals(TaskPlan.Status.FEHLER, failed.getStatus());
        assertEquals("invalid answer: the answer is no JSON", failed.getProblem());
        assertTrue(failed.getSearches().isEmpty());
        assertEquals("nothing made up in its place", before, memory.searches().size());

        Scripted down = new Scripted(messages -> MainModelChatResult.failure(
                MainModelChatResult.Status.PROVIDER_FAILURE, "connection refused"));
        RoundPlan offline = planner(down).plan(memory(), Collections.singletonList(task("F01",
                SearchStrategy.STANDARD, "")), MISSION, 12);
        assertEquals("model call failed (PROVIDER_FAILURE): connection refused",
                offline.getFailed().get(0).getProblem());
    }

    @Test
    public void theRoundTakesSearchesInTaskOrderUnderItsBudget() throws IOException {
        ResearchMemory memory = memory();
        Scripted chat = new Scripted(messages -> {
            String prompt = messages.get(1).getContent();
            if (prompt.contains("- F02 ")) {
                return MainModelChatResult.ok(answer(search("F02", "Trennung von Rad und Auto", "Radweg Poller "
                        + "Trennung"), search("F02", "dieselbe Suche wie bei F04", "Hauptstraße Tempo 30 Behörde")));
            }
            return MainModelChatResult.ok(answer(search("F04", "Zuständigkeit für Tempo 30", "Hauptstraße Tempo 30 "
                    + "Behörde"), search("F04", "Regeln der StVO", "StVO Tempo 30 Hauptstraße Anordnung")));
        });

        RoundPlan round = planner(chat).plan(memory, Arrays.asList(task("F04", SearchStrategy.STANDARD, ""),
                task("F02", SearchStrategy.STANDARD, "")), MISSION, 3);

        assertEquals(3, round.getSearches().size());
        TaskPlan second = round.getTasks().get(1);
        assertEquals(1, second.getSearches().size());
        assertEquals("wiederholt die Suche „Hauptstraße Tempo 30 Behörde“ einer anderen Aufgabe dieser Runde",
                second.getRejected().get(0).getReason());
        assertEquals(Arrays.asList("suche-r2-1", "suche-r2-2", "suche-r2-3"), Arrays.asList(
                round.getSearches().get(0).getId(), round.getSearches().get(1).getId(),
                round.getSearches().get(2).getId()));

        RoundPlan tight = planner(chat).plan(memory(), Arrays.asList(task("F04", SearchStrategy.STANDARD, ""),
                task("F02", SearchStrategy.STANDARD, "")), MISSION, 1);
        assertEquals(1, tight.getSearches().size());
        assertEquals(TaskPlan.Status.FEHLER, tight.getTasks().get(1).getStatus());
        assertEquals("über dem Suchbudget der Runde", tight.getTasks().get(1).getRejected().get(0).getReason());
    }

    @Test
    public void aTopicTaskNamesItsTopicAndSeesItsSearches() throws IOException {
        ResearchMemory memory = memory();
        memory.addQuestion(ResearchQuestion.open("leitung-r1-1", "Wie viele Städte haben Schulstraßen eingerichtet?",
                "thema-0001", ResearchQuestion.Origin.LEITUNG, 2L));
        SearchRecord earlier = new SearchRecord("S9", "Schulstraße Modellversuch", null,
                Collections.singletonList("thema-0001"), "standard", "PLANER", 1, null, 10L);
        memory.record(earlier);
        memory.record(earlier.withYield(new SearchRecord.Yield(2, 5, 0, 1), 11L));
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer(search("leitung-r1-1",
                "Zahl der Städte mit Schulstraßen", "Schulstraßen Städte Anzahl Deutschland"))));

        TaskPlan plan = planner(chat).plan(memory, Collections.singletonList(new LeadTask(2, Target.THEMA,
                "thema-0001", Collections.singletonList("leitung-r1-1"), SearchStrategy.STANDARD, "Erfahrungen der "
                + "Städte", "neue Fragen", LeadTask.Origin.MODELL)), MISSION, 12).getTasks().get(0);

        assertEquals(Collections.singletonList("thema-0001"), plan.getSearches().get(0).getRecord().getTopicIds());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt, prompt.contains("Thema: Schulstraßen\n"));
        assertTrue(prompt, prompt.contains("- „Schulstraße Modellversuch“ (standard, Runde 1): 2 Quellen, 5 "
                + "Passagen, 0 neue Belege\n"));
    }

    @Test
    public void anotherLanguageAndTheEnglishPrompt() throws IOException {
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer(search("F05",
                "Residential parking fees in German cities", "residential parking permit fees Germany cities"))));
        SearchPlanner planner = new SearchPlanner(chat, "en", SearchPlanner.Options.defaults(), null, null, CLOCK);

        TaskPlan plan = planner.plan(memory(), Collections.singletonList(task("F05", SearchStrategy.ANDERE_SPRACHE,
                "")), MISSION, 12).getTasks().get(0);

        assertEquals(1, plan.getSearches().size());
        String prompt = chat.requests.get(0).get(1).getContent();
        assertTrue(prompt, prompt.startsWith("Book mission:\n"));
        assertTrue(prompt, prompt.contains("- Strategy: write the searches in German, or in the language the "
                + "instruction names.\n"));
        assertEquals(SearchPlanner.SYSTEM_EN, chat.requests.get(0).get(0).getContent());
        assertTrue(planner.prompt(planner.brief(memory(), task("F05", SearchStrategy.ANDERE_SPRACHE, ""), MISSION),
                true, SearchPlanner.Options.defaults()).contains("- Strategie: schreibe die Suchen auf Englisch"));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static SearchPlanner planner(MainModelChat chat) {
        return new SearchPlanner(chat, "de", SearchPlanner.Options.defaults(), SourcePreference.NONE, null, CLOCK);
    }

    static LeadTask task(String question, SearchStrategy strategy, String instruction) {
        return new LeadTask(2, Target.FRAGE, question, Collections.singletonList(question), strategy, instruction,
                "Grund der Leitung", LeadTask.Origin.MODELL);
    }

    static ResearchMemory memory() throws IOException {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("karte-tempo", "Tempo und Verkehrsberuhigung", "anchor-tempo", 0, 1L));
        memory.addTopic(ResearchTopic.ofCard("karte-rad", "Radverkehr", "anchor-rad", 0, 1L));
        memory.addTopic(ResearchTopic.ofCard("karte-park", "Parken", "anchor-park", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("F01", "Wie wirkt Tempo 30 auf die Zahl der Unfälle?", "karte-tempo",
                ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F02", "Welche Radwege trennen Rad- und Autoverkehr sicher?",
                "karte-rad", ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F03", "Was kostet ein Kilometer geschützter Radweg?", "karte-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F04", "Wer entscheidet über Tempo 30 auf Hauptstraßen?",
                "karte-tempo", ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F05", "Wie viel kostet Anwohnerparken in deutschen Städten?",
                "karte-park", ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addTopic(ResearchTopic.found("thema-0001", "Schulstraßen", "Straßen vor Schulen, die zeitweise für "
                + "Autos gesperrt werden.", null, "m", 2L).withFence(ResearchTopic.Fence.INNEN, "gehört dazu", "m",
                3L));
        SearchRecord search = new SearchRecord("S1", "Tempo 30 Unfälle", Collections.singletonList("F01"), null,
                "standard", "PLANER", 1, null, 10L);
        memory.record(search);
        memory.record(search.withYield(new SearchRecord.Yield(3, 10, 1, 0), 11L));
        memory.record(EvidenceVerdict.judged("F01", "p7", "s7", "https://www.bast.de/tempo30", TEMPO_TEXT,
                EvidenceVerdict.Kind.BELEGT, "sank die Zahl der schweren Unfälle um ein Viertel", "nennt die Zahl",
                "m", 1, 5L));
        return memory;
    }

    static String search(String question, String purpose, String query) {
        JsonObject json = new JsonObject();
        json.addProperty("frage", question);
        json.addProperty("zweck", purpose);
        json.addProperty("anfrage", query);
        return json.toString();
    }

    static String answer(String... searches) {
        StringBuilder json = new StringBuilder("{\"suchen\":[");
        for (int i = 0; i < searches.length; i++) {
            json.append(i == 0 ? "" : ",").append(searches[i]);
        }
        return json.append("]}").toString();
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
