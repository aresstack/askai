package com.aresstack.askai.research.lead;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.LeadDecision;
import com.aresstack.askai.research.memory.LeadDecision.Action;
import com.aresstack.askai.research.memory.LeadDecision.Target;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.ResearchTopic.Fence;
import com.aresstack.askai.research.memory.SearchRecord;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
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
 * Component 8 with a scripted model and self-written road traffic examples: the model's plan through the guardrails,
 * the ends by rule, strategy changes after fruitless searches, new questions only inside the fence, the end only when
 * nothing new came, a failed call, and the situation the model sees.
 */
public class ResearchLeadTest {

    static final String MISSION = "Ein Praxisbuch über sichere Straßen in Städten: Tempo, Radverkehr und Parken.";
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(50_000L), ZoneOffset.UTC);
    static final String TEMPO_TEXT = "In Zonen mit Tempo 30 sank die Zahl der schweren Unfälle um ein Viertel, "
            + "berichtet die Stadt nach drei Jahren.";
    static final String RAD_TEXT = "Geschützte Radfahrstreifen mit Pollern werden in mehreren Städten erprobt; "
            + "Zahlen zur Sicherheit fehlen noch.";

    @Test
    public void theModelPlanBecomesTasksAndEveryDecisionIsRecorded() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F01", "standard", 1, 1);
        memory.record(EvidenceVerdict.judged("F01", "p7", "s7", "https://example.org/tempo", TEMPO_TEXT,
                EvidenceVerdict.Kind.BELEGT, "sank die Zahl der schweren Unfälle um ein Viertel", "nennt die Zahl",
                "m", 1, 5L));
        searched(memory, "S2", "F02", "standard", 1, 0);
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("weiter", "Es gibt noch viel zu tun.",
                decision("F02", "strategie_wechseln", "andere_quellenart", "Richtlinien der Länder zu geschützten "
                        + "Radfahrstreifen"),
                decision("thema-0001", "neue_fragen", "standard", "Erfahrungen der Städte",
                        "Wie viele Städte haben Schulstraßen eingerichtet?",
                        "Wie reagieren Anwohner auf Schulstraßen?"),
                decision("F01", "weiter_verfolgen", "standard", "weitere Unfallzahlen"))));

        LeadPlan plan = lead(chat).plan(memory, MISSION, "");

        assertEquals(LeadPlan.Status.MODELL, plan.getStatus());
        assertEquals(2, plan.getRound());
        assertFalse(plan.isEnd());
        assertEquals("Lage: zwei Fragen ohne Beleg.", plan.getSituation());
        assertEquals("test-model", plan.getModel());
        List<LeadTask> tasks = plan.getTasks();
        assertEquals(tasks.toString(), 4, tasks.size());
        assertEquals("F02", tasks.get(0).getTargetId());
        assertEquals(SearchStrategy.ANDERE_QUELLENART, tasks.get(0).getStrategy());
        assertEquals("Richtlinien der Länder zu geschützten Radfahrstreifen", tasks.get(0).getInstruction());
        assertEquals(LeadTask.Origin.MODELL, tasks.get(0).getOrigin());
        assertEquals(Target.THEMA, tasks.get(1).getTarget());
        assertEquals("thema-0001", tasks.get(1).getTargetId());
        assertEquals(Arrays.asList("leitung-r2-1", "leitung-r2-2"), tasks.get(1).getQuestionIds());
        assertEquals("F01", tasks.get(2).getTargetId());
        assertEquals(SearchStrategy.STANDARD, tasks.get(2).getStrategy());
        assertEquals("a free slot goes to a question never searched", "F03", tasks.get(3).getTargetId());
        assertEquals(LeadTask.Origin.REGEL, tasks.get(3).getOrigin());
        assertEquals(12, plan.getSearchBudget());

        List<LeadDecision> decisions = memory.decisions();
        assertEquals(decisions, plan.getDecisions());
        assertEquals(5, decisions.size());
        assertEquals(Action.STRATEGIE_WECHSELN, decisions.get(0).getAction());
        assertEquals("andere_quellenart", decisions.get(0).getStrategy());
        assertEquals("test-model", decisions.get(0).getModel());
        assertEquals(Integer.valueOf(1), decisions.get(0).getCounts().get("suchen"));
        assertEquals(Action.NEUE_FRAGEN, decisions.get(1).getAction());
        assertEquals(Arrays.asList("leitung-r2-1", "leitung-r2-2"), decisions.get(1).getQuestionIds());
        assertEquals(Action.WEITER_VERFOLGEN, decisions.get(2).getAction());
        assertEquals("", decisions.get(3).getModel());
        assertEquals(Target.LAUF, decisions.get(4).getTarget());
        assertEquals("Es gibt noch viel zu tun.", decisions.get(4).getReason());
        ResearchQuestion taken = memory.question("leitung-r2-1");
        assertEquals(ResearchQuestion.Origin.LEITUNG, taken.getOrigin());
        assertEquals("thema-0001", taken.getTopicId());
        assertEquals(2, plan.getNewQuestions().size());

        assertEquals(1, chat.calls.get());
        assertEquals(0.0, chat.temperature, 0.0);
        assertEquals(16384, chat.tokens);
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        JsonArray targets = schema.getAsJsonObject("properties").getAsJsonObject("entscheidungen")
                .getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("ziel")
                .getAsJsonArray("enum");
        assertEquals("[\"F03\",\"F04\",\"F02\",\"F01\",\"thema-0001\",\"karte-tempo\",\"karte-rad\",\"karte-park\"]",
                targets.toString());
        String prompt = prompt(chat, 0);
        assertTrue(prompt, prompt.startsWith("Buchauftrag:\n" + MISSION + "\n\nStand vor Runde 2: 1 von höchstens 30 "
                + "Runden gelaufen, 2 von höchstens 120 Suchen verbraucht. In dieser Runde sind bis zu 4 Suchaufgaben "
                + "möglich.\nLetzte Runde mit Suchen (Runde 1): 1 neue direkte Belege, 0 neue Themen."));
        assertTrue(prompt, prompt.contains("- F02 [Radverkehr] Welche Radwege trennen Rad- und Autoverkehr sicher?\n"
                + "  Kein Beleg gefunden; Passagen geprüft: 0; Suchen: 1, zuletzt standard, davon 1 in Folge ohne "
                + "neuen Beleg; seit dem letzten neuen Beleg ohne Ertrag versucht: standard\n"));
        assertTrue(prompt, prompt.contains("  Beleg: „sank die Zahl der schweren Unfälle um ein Viertel“\n"));
        assertTrue(prompt, prompt.contains("- F03 [Parken] Wie viel kostet Anwohnerparken in deutschen Städten?\n"
                + "  Kein Beleg gefunden; Passagen geprüft: 0; noch nie gesucht\n"));
        assertTrue(prompt, prompt.contains("- thema-0001 Schulstraßen: Straßen vor Schulen, die zeitweise für Autos "
                + "gesperrt werden.\n  Fundstellen: 1; offene Fragen: 0 (noch ohne Fragen)\n  Aus den Quellen: „Die "
                + "Schulstraße ist morgens für Autos gesperrt.“\n"));
        assertTrue(prompt, prompt.contains("Themen, über die der Autor noch entscheidet (keine neuen Fragen dazu):\n"
                + "- Lieferzonen\n"));
        assertFalse("an AUSSEN topic is not shown", prompt.contains("Seilbahnen"));
        assertTrue(prompt, prompt.contains("- Runde 1, F02, standard: „Suche S2“, Ertrag: 3 Quellen, 10 Passagen, 0 "
                + "neue Belege, 0 neue Themen\n"));
        assertTrue(prompt, prompt.contains("Das Konzept gibt die Richtung vor, nicht den fertigen Inhalt des Buchs."));
        assertTrue(prompt, prompt.contains("Beenden heißt nie, dass alles beantwortet ist."));
        assertEquals(ResearchLead.SYSTEM_DE, chat.requests.get(0).get(0).getContent());
    }

    @Test
    public void theRoleHasItsOwnSlotUnderSourcesAndThinks() {
        assertEquals("phase.research.lead", ResearchLead.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.ON, ResearchLead.ROLE.getThinking());
        assertTrue(ResearchLead.ROLE.getOutput().isSchemaPerRequest());
    }

    @Test
    public void theBudgetEndsTheResearchWithoutAModelAndLeavesAnOpenList() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F01", "standard", 1, 1);
        searched(memory, "S2", "F02", "standard", 1, 0);
        Scripted chat = new Scripted(messages -> {
            throw new AssertionError("no model call when the budget is used up");
        });

        LeadPlan plan = new ResearchLead(chat, "de", ResearchLead.Options.defaults(),
                new ResearchLead.Budget(30, 2, 4, 3), null, CLOCK).plan(memory, MISSION, "");

        assertTrue(plan.isEnd());
        assertEquals(LeadPlan.Status.REGEL, plan.getStatus());
        assertTrue(plan.getTasks().isEmpty());
        assertEquals(0, plan.getSearchBudget());
        assertEquals("", plan.getModel());
        LeadDecision end = memory.decisions().get(0);
        assertEquals(Action.BEENDEN, end.getAction());
        assertEquals(Target.LAUF, end.getTarget());
        assertEquals("", end.getModel());
        assertEquals("Budget verbraucht: 2 von 2 Suchen", end.getReason());
        OpenList open = plan.getOpenList();
        assertEquals(4, open.getOpen().size());
        assertEquals("F02", open.getOpen().get(1).getQuestionId());
        assertEquals("Kein Beleg gefunden", open.getOpen().get(1).getLabel());
        assertEquals(1, open.getOpen().get(1).getSearches());
        assertEquals("Radverkehr", open.getOpen().get(1).getTopicName());
        assertEquals(1, open.getForTheAuthor().size());
        assertEquals("Lieferzonen", open.getForTheAuthor().get(0).getName());
        String text = open.text(true);
        assertTrue(text, text.startsWith("Offene Liste nach Runde 2. Die Recherche ist nicht vollständig; der Autor "
                + "gibt die Quellen frei.\nEnde: Budget verbraucht: 2 von 2 Suchen\n"));
        assertTrue(text, text.contains("Themen, über die der Autor entscheidet:\n- Lieferzonen (Rand des Themas)\n"));
        assertFalse("nothing is ever called answered", text.toLowerCase(java.util.Locale.ROOT).contains("beantwortet"));

        ResearchMemory rounds = memory();
        LeadPlan last = new ResearchLead(chat, "de", ResearchLead.Options.defaults(),
                new ResearchLead.Budget(1, 100, 4, 3), null, CLOCK).plan(withDecisionInRound(rounds, 1), MISSION, "");
        assertTrue(last.isEnd());
        assertEquals("Budget verbraucht: alle 1 Runden sind gelaufen", last.getOpenList().getReason());
    }

    @Test
    public void aStandstillEndsTheResearch() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F01", "standard", 1, 0);
        searched(memory, "S2", "F02", "standard", 2, 0);
        searched(memory, "S3", "F03", "standard", 3, 0);
        Scripted chat = new Scripted(messages -> {
            throw new AssertionError("no model call at a standstill");
        });

        LeadPlan plan = lead(chat).plan(memory, MISSION, "");

        assertTrue(plan.isEnd());
        assertEquals("Stillstand: die letzten 3 Runden mit Suchen brachten keinen neuen Beleg und kein neues Thema",
                plan.getOpenList().getReason());
        assertEquals(Integer.valueOf(3), memory.decisions().get(0).getCounts().get("rundenOhneNeues"));
    }

    @Test
    public void afterTwoFruitlessSearchesTheStrategyMustChange() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F02", "standard", 1, 0);
        searched(memory, "S2", "F02", "standard", 2, 0);
        searched(memory, "S3", "F04", "standard", 1, 0);
        searched(memory, "S4", "F04", "andere_wortwahl", 2, 0);
        searched(memory, "S5", "F04", "andere_wortwahl", 3, 0);
        String[] all = {"standard", "andere_wortwahl", "andere_quellenart", "gezielte_herausgeber", "andere_sprache"};
        for (int i = 0; i < all.length; i++) {
            searched(memory, "S" + (6 + i), "F03", all[i], 1 + i % 3, 0);
        }
        searched(memory, "S11", "F01", "standard", 3, 1);
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("weiter", "weiter suchen",
                decision("F02", "weiter_verfolgen", "standard", "noch einmal"),
                decision("F04", "strategie_wechseln", "andere_wortwahl", "Fachbegriffe der Straßenverkehrsbehörden"),
                decision("F03", "strategie_wechseln", "andere_sprache", "englische Quellen"))));

        LeadPlan plan = lead(chat).plan(memory, MISSION, "");

        List<LeadTask> tasks = plan.getTasks();
        assertEquals(tasks.toString(), 2, tasks.size());
        assertEquals("F02", tasks.get(0).getTargetId());
        assertEquals(SearchStrategy.ANDERE_WORTWAHL, tasks.get(0).getStrategy());
        assertEquals("F04", tasks.get(1).getTargetId());
        assertEquals("the strategy so far is no change", SearchStrategy.ANDERE_QUELLENART, tasks.get(1).getStrategy());
        List<LeadDecision> decisions = memory.decisions();
        assertEquals(Action.STRATEGIE_WECHSELN, decisions.get(0).getAction());
        assertEquals("Grund für F02 [Leitplanke: 2 Suchen ohne neuen Beleg mit standard, Wechsel zu andere_wortwahl]",
                decisions.get(0).getReason());
        assertEquals("andere_quellenart", decisions.get(1).getStrategy());
        assertEquals("every strategy tried: the question is set aside", Action.ZURUECKSTELLEN,
                decisions.get(2).getAction());
        assertEquals(ResearchQuestion.State.ZURUECKGESTELLT, memory.question("F03").getState());
        assertEquals(Target.LAUF, decisions.get(3).getTarget());
        assertEquals(3, plan.getNotes().size());
        assertTrue(plan.getNotes().get(1), plan.getNotes().get(1).contains("andere_wortwahl ist die bisherige "
                + "Strategie, Wechsel zu andere_quellenart"));
        assertTrue(plan.getNotes().get(2), plan.getNotes().get(2).contains("alle Strategien versucht"));
        String prompt = prompt(chat, 0);
        assertTrue(prompt, prompt.contains("Suchen: 3, zuletzt andere_wortwahl, davon 2 in Folge ohne neuen Beleg; "
                + "seit dem letzten neuen Beleg ohne Ertrag versucht: andere_wortwahl, standard\n"));
    }

    @Test
    public void aTopicTaskLeavesOutTheQuestionsItsStrategyStayedFruitlessFor() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F01", "standard", 1, 0);
        searched(memory, "S2", "F01", "standard", 2, 0);
        searched(memory, "S3", "F03", "andere_quellenart", 1, 0);
        searched(memory, "S4", "F03", "standard", 2, 0);
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("weiter", "weiter",
                decision("karte-tempo", "weiter_verfolgen", "standard", "Studien zu Tempo 30"),
                decision("karte-park", "strategie_wechseln", "andere_quellenart", "Satzungen der Städte"))));

        LeadPlan plan = lead(chat).plan(memory, MISSION, "");

        LeadTask topic = plan.getTasks().get(0);
        assertEquals(Target.THEMA, topic.getTarget());
        assertEquals("karte-tempo", topic.getTargetId());
        assertEquals("F01 had two standard searches without new evidence", Collections.singletonList("F04"),
                topic.getQuestionIds());
        assertTrue(plan.getNotes().toString(), plan.getNotes().contains("Leitplanke bei Thema karte-tempo: F01 nicht "
                + "mit standard (blieb damit ohne neuen Beleg)"));
        assertTrue(plan.getNotes().toString(), plan.getNotes().contains("Thema karte-park hat keine offene Frage, die "
                + "mit andere_quellenart gesucht werden darf; dafür wären neue Fragen oder ein Wechsel nötig"));
        for (LeadTask task : plan.getTasks()) {
            assertFalse(task.toString(), task.getQuestionIds().contains("F01")
                    || task.getQuestionIds().contains("F03"));
        }
        assertEquals("the free slot goes to the question never searched", "F02", plan.getTasks().get(1).getTargetId());
    }

    @Test
    public void newQuestionsOnlyForTopicsInsideTheFenceAndNeverTwice() throws IOException {
        ResearchMemory memory = memory();
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("weiter", "neue Themen",
                decision("thema-0001", "neue_fragen", "standard", "Erfahrungen der Städte",
                        "wie wirkt TEMPO 30 auf die Zahl der Unfälle", "Wo?",
                        "Wie viele Städte haben Schulstraßen eingerichtet?",
                        "Wie reagieren Anwohner auf Schulstraßen?",
                        "Welche Regeln gelten für Lieferverkehr in Schulstraßen?",
                        "Wer kontrolliert die Sperrung einer Schulstraße?"),
                decision("F03", "neue_fragen", "standard", "Gebühren",
                        "Welche Städte verlangen mehr als 100 Euro im Jahr fürs Anwohnerparken?",
                        "Wie begründen Städte die Höhe der Gebühr?"),
                decision("thema-0002", "neue_fragen", "standard", "", "Wie schnell fahren Seilbahnen?"))));

        LeadPlan plan = new ResearchLead(chat, "de", ResearchLead.Options.defaults().withNewQuestions(3, 4),
                ResearchLead.Budget.defaults(), null, CLOCK).plan(memory, MISSION, "");

        assertEquals(1, plan.getRound());
        assertEquals(4, plan.getNewQuestions().size());
        assertEquals("leitung-r1-1", plan.getNewQuestions().get(0).getId());
        assertEquals("Wie viele Städte haben Schulstraßen eingerichtet?", plan.getNewQuestions().get(0).getText());
        assertEquals("thema-0001", plan.getNewQuestions().get(2).getTopicId());
        assertEquals("a question of a card brings new questions to its card", "karte-park",
                plan.getNewQuestions().get(3).getTopicId());
        assertEquals(ResearchQuestion.Origin.LEITUNG, memory.question("leitung-r1-4").getOrigin());
        assertNull(memory.question("leitung-r1-5"));
        List<String> notes = plan.getNotes();
        assertTrue(notes.toString(), notes.get(0).startsWith("Leitplanke: 3 neue Fragen zu thema-0001 verworfen"));
        assertTrue(notes.toString(), notes.get(1).startsWith("Leitplanke: 1 neue Fragen zu karte-park verworfen"));
        assertTrue(notes.toString(), notes.get(2).startsWith("Ziel „thema-0002“ steht nicht im Lagebild"));
        List<LeadTask> tasks = plan.getTasks();
        assertEquals(4, tasks.size());
        assertEquals(Arrays.asList("leitung-r1-1", "leitung-r1-2", "leitung-r1-3"), tasks.get(0).getQuestionIds());
        assertEquals("karte-park", tasks.get(1).getTargetId());
        assertEquals("F01", tasks.get(2).getTargetId());
        assertEquals("F02", tasks.get(3).getTargetId());
        LeadDecision taken = memory.decisions().get(0);
        assertEquals(Action.NEUE_FRAGEN, taken.getAction());
        assertEquals(Target.THEMA, taken.getTarget());
        assertEquals(Integer.valueOf(1), taken.getCounts().get("fundstellen"));
    }

    @Test
    public void theModelMayEndOnlyWhenTheLastRoundBroughtNothingNew() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F01", "standard", 1, 1);
        Scripted early = new Scripted(messages -> MainModelChatResult.ok(answer("beenden", "Genug gefunden.")));

        LeadPlan going = lead(early).plan(memory, MISSION, "");

        assertFalse(going.isEnd());
        assertTrue(going.getNotes().toString(), going.getNotes().get(0).startsWith("Leitplanke: kein Ende, die letzte "
                + "Runde brachte noch 1 neue Belege und 0 neue Themen"));
        assertEquals("with no task from the model the guardrails fill every slot", 4, going.getTasks().size());
        assertEquals("F01", going.getTasks().get(3).getTargetId());
        LeadDecision run = memory.decisions().get(memory.decisions().size() - 1);
        assertEquals(Action.WEITER_VERFOLGEN, run.getAction());
        assertEquals("Genug gefunden. [Leitplanke: die Recherche läuft weiter]", run.getReason());

        Scripted done = new Scripted(messages -> MainModelChatResult.ok(answer("beenden", "Zwei Runden ohne Neues.",
                decision("F02", "strategie_wechseln", "andere_sprache", "englische Quellen"))));
        LeadPlan untried = lead(done).plan(quiet(false), MISSION, "");
        assertFalse("F03 has no direct evidence and strategies left", untried.isEnd());
        assertTrue(untried.getNotes().toString(), untried.getNotes().contains("Leitplanke: kein Ende, 1 offene Fragen "
                + "ohne direkten Beleg haben noch eine Suchstrategie, die nicht versucht ist (F03); die Recherche "
                + "läuft weiter"));
        assertTrue(prompt(done, 0), prompt(done, 0).contains("Solange eine Frage ohne direkten Beleg noch eine "
                + "Strategie hat, die sie nicht versucht hat, läuft die Recherche weiter"));

        ResearchMemory quiet = quiet(true);
        LeadPlan ended = lead(done).plan(quiet, MISSION, "");

        assertTrue(ended.isEnd());
        assertEquals(LeadPlan.Status.MODELL, ended.getStatus());
        assertTrue(ended.getTasks().isEmpty());
        assertTrue(ended.getNotes().get(0), ended.getNotes().get(0).contains("die Aufgaben dieser Runde entfallen"));
        LeadDecision end = quiet.decisions().get(quiet.decisions().size() - 1);
        assertEquals(Action.BEENDEN, end.getAction());
        assertEquals("test-model", end.getModel());
        assertEquals("Zwei Runden ohne Neues.", ended.getOpenList().getReason());
        assertEquals(5, ended.getOpenList().getOpen().size());
    }

    @Test
    public void aFailedCallLeavesOnlyTheGuardrailsAndIsNoted() throws IOException {
        ResearchMemory memory = memory();
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok("kein JSON"));

        LeadPlan plan = lead(chat).plan(memory, MISSION, "");

        assertEquals(2, chat.calls.get());
        assertEquals(LeadPlan.Status.FEHLER, plan.getStatus());
        assertEquals("invalid answer: the answer is no JSON", plan.getProblem());
        assertEquals(Arrays.asList("kein JSON", "kein JSON"), plan.getAnswers());
        assertEquals(4, plan.getTasks().size());
        for (LeadTask task : plan.getTasks()) {
            assertEquals(LeadTask.Origin.REGEL, task.getOrigin());
        }
        assertTrue(plan.getNotes().get(0), plan.getNotes().get(0).startsWith("Modellaufruf gescheitert (invalid "
                + "answer: the answer is no JSON); diese Runde planen nur die Leitplanken"));
        LeadDecision run = memory.decisions().get(memory.decisions().size() - 1);
        assertEquals("Leitplanke: Modellaufruf gescheitert, nur Leitplanken in dieser Runde", run.getReason());
        assertEquals("", run.getModel());

        Scripted down = new Scripted(messages -> MainModelChatResult.failure(
                MainModelChatResult.Status.PROVIDER_FAILURE, "connection refused"));
        LeadPlan offline = lead(down).plan(memory(), MISSION, "");
        assertEquals("model call failed (PROVIDER_FAILURE): connection refused", offline.getProblem());

        AtomicInteger n = new AtomicInteger();
        Scripted repaired = new Scripted(messages -> MainModelChatResult.ok(n.incrementAndGet() == 1 ? "{}"
                : answer("weiter", "jetzt richtig")));
        LeadPlan second = lead(repaired).plan(memory(), MISSION, "");
        assertEquals(LeadPlan.Status.MODELL, second.getStatus());
        assertEquals(2, repaired.calls.get());
        List<ChatMessage> repair = repaired.requests.get(1);
        assertEquals(4, repair.size());
        assertTrue(repair.get(3).getContent(), repair.get(3).getContent().startsWith("Deine Antwort war ungültig: "
                + "\"lauf\" must be an object"));
    }

    @Test
    public void aQuestionIsSetAsideOnlyAfterASearch() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F02", "standard", 1, 0);
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("weiter", "aufräumen",
                decision("F03", "zurueckstellen", "standard", ""),
                decision("F02", "zurueckstellen", "standard", ""),
                decision("thema-0001", "zurueckstellen", "standard", ""),
                decision("F02", "weiter_verfolgen", "standard", ""))));

        LeadPlan plan = lead(chat).plan(memory, MISSION, "");

        assertEquals(ResearchQuestion.State.OFFEN, memory.question("F03").getState());
        assertEquals(ResearchQuestion.State.ZURUECKGESTELLT, memory.question("F02").getState());
        List<String> notes = plan.getNotes();
        assertEquals("Leitplanke: F03 wurde noch nie gesucht und wird nicht zurückgestellt", notes.get(0));
        assertEquals("Nur Fragen werden zurückgestellt; Thema thema-0001 bleibt", notes.get(1));
        assertTrue(notes.get(2), notes.get(2).startsWith("Ziel F02 war in dieser Runde schon entschieden"));
        List<String> targets = new ArrayList<String>();
        for (LeadTask task : plan.getTasks()) {
            targets.add(task.getTargetId());
        }
        assertEquals(Arrays.asList("F01", "F03", "F04"), targets);
    }

    @Test
    public void theSituationShowsCountsAndQuotesAndOnlyShownIdsAreTargets() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F02", "standard", 1, 0);
        memory.record(EvidenceVerdict.judged("F02", "p8", "s8", "", RAD_TEXT, EvidenceVerdict.Kind.HINWEIS,
                "Geschützte Radfahrstreifen mit Pollern werden in mehreren Städten erprobt", "nur ein Hinweis", "m",
                1, 6L));
        searched(memory, "S2", "F01", "standard", 1, 1);
        memory.record(EvidenceVerdict.judged("F01", "p7", "s7", "", TEMPO_TEXT, EvidenceVerdict.Kind.BELEGT,
                "sank die Zahl der schweren Unfälle um ein Viertel", "nennt die Zahl", "m", 1, 5L));
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answer("weiter", "los",
                decision("F01", "weiter_verfolgen", "standard", ""))));

        LeadPlan plan = new ResearchLead(chat, "de", ResearchLead.Options.defaults().withShown(3, 1, 0)
                .withQuoteChars(30), ResearchLead.Budget.defaults(), null, CLOCK).plan(memory, MISSION, "Nur Städte.");

        String prompt = prompt(chat, 0);
        assertTrue(prompt, prompt.contains("\n\nRand des Themas:\nNur Städte.\n\n"));
        assertTrue(prompt, prompt.contains("  Hinweis: „Geschützte Radfahrstreifen …“\n"));
        assertTrue(prompt, prompt.contains("(1 weitere offene Fragen sind nicht gezeigt.)"));
        assertFalse(prompt, prompt.contains("Lieferzonen"));
        assertTrue(plan.getNotes().get(0), plan.getNotes().get(0).startsWith("Ziel „F01“ steht nicht im Lagebild"));
        JsonArray targets = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject().getAsJsonObject("properties")
                .getAsJsonObject("entscheidungen").getAsJsonObject("items").getAsJsonObject("properties")
                .getAsJsonObject("ziel").getAsJsonArray("enum");
        assertEquals("[\"F03\",\"F04\",\"F02\",\"thema-0001\",\"karte-tempo\",\"karte-rad\",\"karte-park\"]",
                targets.toString());

        Scripted english = new Scripted(messages -> MainModelChatResult.ok(answer("weiter", "go")));
        new ResearchLead(english, "en", ResearchLead.Options.defaults(), ResearchLead.Budget.defaults(), null, CLOCK)
                .plan(memory(), MISSION, "");
        String text = prompt(english, 0);
        assertTrue(text, text.startsWith("Book mission:\n" + MISSION
                + "\n\nBefore round 1: 0 of at most 30 rounds run"));
        assertTrue(text, text.contains("No search has finished yet."));
        assertTrue(text, text.contains("Ending never means that everything is answered."));
        assertEquals(ResearchLead.SYSTEM_EN, english.requests.get(0).get(0).getContent());
    }

    @Test
    public void theAnswerIsReadLeniently() {
        ResearchLead.Answer answer = ResearchLead.parse("{\"lage\":\" zwei  Sätze \",\"entscheidungen\":[{\"grund\":"
                + "\"x\",\"ziel\":\"F01\",\"handlung\":\"Strategie wechseln\",\"strategie\":\"andere_sprache\","
                + "\"vorgabe\":\"v\",\"fragen\":[]}],\"lauf\":{\"grund\":\"g\",\"handlung\":\"Weiter\"}}");
        assertEquals("", answer.problem);
        assertEquals("zwei Sätze", answer.situation);
        assertEquals("strategie_wechseln", answer.decisions.get(0).action);
        assertFalse(answer.end);
        assertEquals("decision 1: \"handlung\" must be one of [weiter_verfolgen, strategie_wechseln, neue_fragen, "
                + "zurueckstellen]", ResearchLead.parse("{\"entscheidungen\":[{\"grund\":\"x\","
                + "\"handlung\":\"suchen\"}],\"lauf\":{\"grund\":\"g\",\"handlung\":\"weiter\"}}").problem);
        assertEquals("decision 1: \"grund\" must give the reason", ResearchLead.parse("{\"entscheidungen\":[{\"grund\":"
                + "\" \",\"handlung\":\"neue_fragen\"}],\"lauf\":{\"grund\":\"g\",\"handlung\":\"weiter\"}}").problem);
        assertEquals("\"lauf.handlung\" must be weiter or beenden", ResearchLead.parse("{\"entscheidungen\":[],"
                + "\"lauf\":{\"grund\":\"g\",\"handlung\":\"stopp\"}}").problem);
        assertEquals(SearchStrategy.STANDARD, SearchStrategy.of("keine"));
        assertEquals(SearchStrategy.GEZIELTE_HERAUSGEBER, SearchStrategy.of("Gezielte Herausgeber"));
        assertNull(SearchStrategy.of("Wortwahl"));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static ResearchLead lead(MainModelChat chat) {
        return new ResearchLead(chat, "de", ResearchLead.Options.defaults(), ResearchLead.Budget.defaults(), null,
                CLOCK);
    }

    /** Three cards with four concept questions, a found topic inside the fence, one outside, one for the author. */
    static ResearchMemory memory() throws IOException {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("karte-tempo", "Tempo und Verkehrsberuhigung", "anchor-concept-tempo", 0,
                1L));
        memory.addTopic(ResearchTopic.ofCard("karte-rad", "Radverkehr", "anchor-concept-rad", 0, 1L));
        memory.addTopic(ResearchTopic.ofCard("karte-park", "Parken", "anchor-concept-park", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("F01", "Wie wirkt Tempo 30 auf die Zahl der Unfälle?", "karte-tempo",
                ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F02", "Welche Radwege trennen Rad- und Autoverkehr sicher?",
                "karte-rad", ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F03", "Wie viel kostet Anwohnerparken in deutschen Städten?",
                "karte-park", ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("F04", "Wer entscheidet über Tempo 30 auf Hauptstraßen?",
                "karte-tempo", ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addTopic(ResearchTopic.found("thema-0001", "Schulstraßen", "Straßen vor Schulen, die zeitweise für "
                        + "Autos gesperrt werden.", Collections.singletonList(new ResearchTopic.Finding("s1", "p1",
                        "Die Schulstraße ist morgens für Autos gesperrt.")), "m", 2L)
                .withFence(Fence.INNEN, "gehört zur Verkehrsberuhigung", "m", 3L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Seilbahnen im Nahverkehr", "Gondeln über der Stadt.",
                null, "m", 2L).withFence(Fence.AUSSEN, "anderer Gegenstand", "m", 3L));
        memory.addTopic(ResearchTopic.found("thema-0003", "Lieferzonen", "Halteplätze für Lieferwagen.", null, "m",
                2L).withFence(Fence.UNKLAR, "Rand des Themas", "m", 3L));
        return memory;
    }

    /** Two rounds: the first found direct evidence for every question ({@code F03} only with {@code all}), the
     *  second, with other words, nothing new. */
    private static ResearchMemory quiet(boolean all) throws IOException {
        ResearchMemory quiet = memory();
        quiet.addQuestion(ResearchQuestion.open("F05", "Wie sicher sind Schulwege in Schulstraßen?", "thema-0001",
                ResearchQuestion.Origin.FINDER, 2L));
        int n = 0;
        for (String id : Arrays.asList("F01", "F02", "F03", "F04", "F05")) {
            boolean found = all || !id.equals("F03");
            searched(quiet, "Q" + n++, id, "standard", 1, found ? 1 : 0);
            if (found) {
                quiet.record(EvidenceVerdict.judged(id, "p-" + id, "s-" + id, "", "Zu " + id + " steht hier die "
                        + "Antwort mit einer Zahl aus dem Jahr 2024.", EvidenceVerdict.Kind.BELEGT, "steht hier die "
                        + "Antwort", "nennt die Antwort", "m", 1, 5L));
            }
            searched(quiet, "Q" + n++, id, "andere_wortwahl", 2, 0);
        }
        return quiet;
    }

    static void searched(ResearchMemory memory, String id, String question, String strategy, int round,
                         int newEvidence) throws IOException {
        SearchRecord search = new SearchRecord(id, "Suche " + id, Collections.singletonList(question), null, strategy,
                "PLANER", round, null, 10L);
        memory.record(search);
        memory.record(search.withYield(new SearchRecord.Yield(3, 10, newEvidence, 0), 11L));
    }

    private static ResearchMemory withDecisionInRound(ResearchMemory memory, int round) throws IOException {
        memory.record(new LeadDecision(round, Target.LAUF, "", Action.WEITER_VERFOLGEN, "los", null, "", 1L));
        return memory;
    }

    static String decision(String target, String action, String strategy, String instruction, String... questions) {
        JsonObject json = new JsonObject();
        json.addProperty("grund", "Grund für " + target);
        json.addProperty("ziel", target);
        json.addProperty("handlung", action);
        json.addProperty("strategie", strategy);
        json.addProperty("vorgabe", instruction);
        JsonArray asked = new JsonArray();
        for (String question : questions) {
            asked.add(question);
        }
        json.add("fragen", asked);
        return json.toString();
    }

    static String answer(String run, String reason, String... decisions) {
        StringBuilder json = new StringBuilder("{\"lage\":\"Lage: zwei Fragen ohne Beleg.\",\"entscheidungen\":[");
        for (int i = 0; i < decisions.length; i++) {
            json.append(i == 0 ? "" : ",").append(decisions[i]);
        }
        JsonObject lauf = new JsonObject();
        lauf.addProperty("grund", reason);
        lauf.addProperty("handlung", run);
        return json.append("],\"lauf\":").append(lauf).append('}').toString();
    }

    static String prompt(Scripted chat, int call) {
        return chat.requests.get(call).get(1).getContent();
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
