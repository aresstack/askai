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
import com.google.gson.JsonElement;
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
import java.util.Map;
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
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answerAsking("weiter",
                "Es gibt noch viel zu tun.", new String[]{questionsFor("thema-0001", "Grund für thema-0001",
                        "Wie viele Städte haben Schulstraßen eingerichtet?",
                        "Wie reagieren Anwohner auf Schulstraßen?")},
                decision("F02", "strategie_wechseln", "andere_quellenart", "Richtlinien der Länder zu geschützten "
                        + "Radfahrstreifen"),
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
        assertEquals("F01", tasks.get(1).getTargetId());
        assertEquals(SearchStrategy.STANDARD, tasks.get(1).getStrategy());
        assertEquals("new questions take no slot but get a free one", Target.THEMA, tasks.get(2).getTarget());
        assertEquals("thema-0001", tasks.get(2).getTargetId());
        assertEquals(Arrays.asList("leitung-r2-1", "leitung-r2-2"), tasks.get(2).getQuestionIds());
        assertEquals(LeadTask.Origin.MODELL, tasks.get(2).getOrigin());
        assertEquals("Grund für thema-0001", tasks.get(2).getReason());
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
        assertEquals(Action.WEITER_VERFOLGEN, decisions.get(1).getAction());
        assertEquals(Action.NEUE_FRAGEN, decisions.get(2).getAction());
        assertEquals(Arrays.asList("leitung-r2-1", "leitung-r2-2"), decisions.get(2).getQuestionIds());
        assertEquals("Grund für thema-0001", decisions.get(2).getReason());
        assertEquals("", decisions.get(3).getModel());
        assertEquals(Target.LAUF, decisions.get(4).getTarget());
        assertEquals("Es gibt noch viel zu tun.", decisions.get(4).getReason());
        ResearchQuestion taken = memory.question("leitung-r2-1");
        assertEquals(ResearchQuestion.Origin.LEITUNG, taken.getOrigin());
        assertEquals("thema-0001", taken.getTopicId());
        assertEquals(2, plan.getNewQuestions().size());

        assertEquals(1, chat.calls.get());
        assertEquals(0.0, chat.temperature, 0.0);
        assertEquals(8192, chat.tokens);
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        JsonArray targets = schema.getAsJsonObject("properties").getAsJsonObject("entscheidungen")
                .getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("ziel")
                .getAsJsonArray("enum");
        assertEquals("[\"F03\",\"F04\",\"F02\",\"F01\",\"thema-0001\",\"karte-tempo\",\"karte-rad\",\"karte-park\"]",
                targets.toString());
        assertEquals("[\"weiter_verfolgen\",\"strategie_wechseln\",\"zurueckstellen\"]", schema
                .getAsJsonObject("properties").getAsJsonObject("entscheidungen").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("handlung").getAsJsonArray("enum").toString());
        JsonObject asked = schema.getAsJsonObject("properties").getAsJsonObject("neue_fragen");
        assertEquals("a found topic without questions must get some", "[\"thema-0001\"]",
                asked.getAsJsonArray("required").toString());
        assertEquals("[thema-0001, karte-tempo, karte-rad, karte-park]",
                asked.getAsJsonObject("properties").keySet().toString());
        assertEquals(1, asked.getAsJsonObject("properties").getAsJsonObject("thema-0001")
                .getAsJsonObject("properties").getAsJsonObject("fragen").get("minItems").getAsInt());
        assertEquals(0, asked.getAsJsonObject("properties").getAsJsonObject("karte-rad")
                .getAsJsonObject("properties").getAsJsonObject("fragen").get("minItems").getAsInt());
        assertEquals("[\"lage\",\"entscheidungen\",\"neue_fragen\",\"lauf\"]",
                schema.getAsJsonArray("required").toString());
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
    public void theRoleHasItsOwnSlotUnderSourcesAndDoesNotThink() {
        assertEquals("phase.research.lead", ResearchLead.ROLE.getSlot().getName());
        assertEquals("thinking ran without end in the stock test", ThinkingMode.OFF,
                ResearchLead.ROLE.getThinking());
        assertTrue(ResearchLead.ROLE.getOutput().isSchemaPerRequest());
        assertEquals("the lead needs a fixed context, not Ollama's default", 32768,
                ResearchLead.ROLE.getContextTokens());
        assertTrue("prompt and answer fit the context", ResearchLead.Options.defaults().getAnswerTokens() + 6000
                <= ResearchLead.ROLE.getContextTokens());
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
        assertEquals(plan.getNotes().toString(), 4, plan.getNotes().size());
        assertTrue(plan.getNotes().get(1), plan.getNotes().get(1).contains("andere_wortwahl ist die bisherige "
                + "Strategie, Wechsel zu andere_quellenart"));
        assertTrue(plan.getNotes().get(2), plan.getNotes().get(2).contains("alle Strategien versucht"));
        assertEquals("the answer gave the found topic no questions", "Leitplanke: Thema thema-0001 innerhalb des "
                + "Weidezauns blieb ohne Fragen; die nächste Runde legt es wieder vor", plan.getNotes().get(3));
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
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answerAsking("weiter", "neue Themen",
                new String[]{questionsFor("karte-park", "Gebühren",
                        "Welche Städte verlangen mehr als 100 Euro im Jahr fürs Anwohnerparken?",
                        "Wie begründen Städte die Höhe der Gebühr?"),
                        questionsFor("thema-0001", "Erfahrungen der Städte",
                                "wie wirkt TEMPO 30 auf die Zahl der Unfälle", "Wo?",
                                "Wie viele Städte haben Schulstraßen eingerichtet?",
                                "Wie reagieren Anwohner auf Schulstraßen?",
                                "Welche Regeln gelten für Lieferverkehr in Schulstraßen?",
                                "Wer kontrolliert die Sperrung einer Schulstraße?"),
                        questionsFor("thema-0002", "Seilbahnen", "Wie schnell fahren Seilbahnen?"),
                        questionsFor("F03", "keine Kennung eines Themas", "Was kostet ein Parkausweis?")})));

        LeadPlan plan = new ResearchLead(chat, "de", ResearchLead.Options.defaults().withNewQuestions(3, 4),
                ResearchLead.Budget.defaults(), null, CLOCK).plan(memory, MISSION, "");

        assertEquals(1, plan.getRound());
        assertEquals(4, plan.getNewQuestions().size());
        assertEquals("the topic without questions comes first, then one question of each topic in turn",
                "leitung-r1-1", plan.getNewQuestions().get(0).getId());
        assertEquals("Wie viele Städte haben Schulstraßen eingerichtet?", plan.getNewQuestions().get(0).getText());
        assertEquals("thema-0001", plan.getNewQuestions().get(0).getTopicId());
        assertEquals("karte-park", plan.getNewQuestions().get(1).getTopicId());
        assertEquals("thema-0001", plan.getNewQuestions().get(2).getTopicId());
        assertEquals("Wie reagieren Anwohner auf Schulstraßen?", plan.getNewQuestions().get(2).getText());
        assertEquals("karte-park", plan.getNewQuestions().get(3).getTopicId());
        assertEquals(ResearchQuestion.Origin.LEITUNG, memory.question("leitung-r1-4").getOrigin());
        assertNull(memory.question("leitung-r1-5"));
        List<String> notes = plan.getNotes();
        assertTrue(notes.toString(), notes.get(0).startsWith("Neue Fragen nur zu einem Thema innerhalb des Weidezauns "
                + "aus dem Lagebild") && notes.get(0).endsWith("thema-0002 ist keines"));
        assertTrue(notes.toString(), notes.get(1).endsWith("F03 ist keines"));
        assertTrue(notes.toString(), notes.get(2).startsWith("Leitplanke: 4 neue Fragen zu thema-0001 verworfen"));
        List<LeadTask> tasks = plan.getTasks();
        assertEquals(4, tasks.size());
        assertEquals(Arrays.asList("leitung-r1-1", "leitung-r1-3"), tasks.get(0).getQuestionIds());
        assertEquals("karte-park", tasks.get(1).getTargetId());
        assertEquals(Arrays.asList("leitung-r1-2", "leitung-r1-4"), tasks.get(1).getQuestionIds());
        assertEquals("F01", tasks.get(2).getTargetId());
        assertEquals("F02", tasks.get(3).getTargetId());
        LeadDecision taken = memory.decisions().get(0);
        assertEquals(Action.NEUE_FRAGEN, taken.getAction());
        assertEquals(Target.THEMA, taken.getTarget());
        assertEquals(Integer.valueOf(1), taken.getCounts().get("fundstellen"));
    }

    @Test
    public void newQuestionsOfFoundTopicsGetTasksBeyondTheSlots() throws IOException {
        Scripted chat = new Scripted(messages -> MainModelChatResult.ok(answerAsking("weiter", "alle Plätze belegt",
                new String[]{questionsFor("karte-park", "Gebühren", "Wie begründen Städte die Höhe der Gebühr?"),
                        questionsFor("thema-0001", "Schulstraßen sind neu",
                                "Wie viele Städte haben Schulstraßen eingerichtet?"),
                        questionsFor("thema-0004", "Elterntaxis gefährden Kinder",
                                "Wie viele Kinder werden mit dem Auto zur Schule gebracht?"),
                        questionsFor("thema-0005", "Schulwegpläne helfen", "Welche Städte haben Schulwegpläne?")},
                decision("F01", "weiter_verfolgen", "standard", "Unfallzahlen"),
                decision("F02", "weiter_verfolgen", "standard", "Radwege"),
                decision("F03", "weiter_verfolgen", "standard", "Gebühren"),
                decision("F04", "weiter_verfolgen", "standard", "Zuständigkeit"))));
        ResearchMemory memory = schoolTopics(memory());

        LeadPlan plan = lead(chat).plan(memory, MISSION, "");

        List<LeadTask> tasks = plan.getTasks();
        assertEquals(tasks.toString(), 6, tasks.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(LeadTask.Origin.MODELL, tasks.get(i).getOrigin());
        }
        assertEquals("thema-0001", tasks.get(4).getTargetId());
        assertEquals(Collections.singletonList("leitung-r1-1"), tasks.get(4).getQuestionIds());
        assertEquals(LeadTask.Origin.REGEL, tasks.get(4).getOrigin());
        assertEquals("Schulstraßen sind neu [Leitplanke: neue Fragen eines gefundenen Themas, noch nie gesucht; "
                + "Aufgabe über die Plätze hinaus]", tasks.get(4).getReason());
        assertEquals("thema-0004", tasks.get(5).getTargetId());
        assertEquals(Collections.singletonList("leitung-r1-2"), tasks.get(5).getQuestionIds());
        assertEquals(18, plan.getSearchBudget());
        assertEquals(4, plan.getNewQuestions().size());
        List<String> notes = plan.getNotes();
        assertTrue(notes.toString(), notes.contains("Fragen zu thema-0005 werden in einer späteren Runde gesucht (kein "
                + "Platz mehr)"));
        assertTrue("a card's new questions wait for a free slot", notes.contains("Fragen zu karte-park werden in "
                + "einer späteren Runde gesucht (kein Platz mehr)"));
        assertTrue(notes.toString(), notes.contains("Fragen zu thema-0006 werden in einer späteren Runde gesucht (kein "
                + "Platz mehr)"));
        LeadDecision rule = null;
        for (LeadDecision decision : memory.decisions()) {
            if (decision.getTargetId().equals("thema-0001") && decision.getAction() == Action.WEITER_VERFOLGEN) {
                rule = decision;
            }
        }
        assertEquals("the extra task is recorded as a decision of the guardrail", "", rule.getModel());
        assertEquals("standard", rule.getStrategy());
        assertEquals(Integer.valueOf(1), rule.getCounts().get("fundstellen"));

        LeadPlan tight = new ResearchLead(chat, "de", ResearchLead.Options.defaults(), new ResearchLead.Budget(30, 5,
                4, 3), null, CLOCK).plan(schoolTopics(memory()), MISSION, "");
        assertEquals("never more tasks than searches are left", 5, tight.getTasks().size());
        assertEquals(5, tight.getSearchBudget());

        Scripted broken = new Scripted(messages -> MainModelChatResult.ok("kein JSON"));
        LeadPlan failed = lead(broken).plan(schoolTopics(memory()), MISSION, "");
        assertEquals(LeadPlan.Status.FEHLER, failed.getStatus());
        LeadTask first = failed.getTasks().get(0);
        assertEquals("a question of a found topic never searched gets a free slot without the model too",
                "thema-0006", first.getTargetId());
        assertEquals(Collections.singletonList("F06"), first.getQuestionIds());
        assertEquals("Leitplanke: freier Platz für neue Fragen, noch nie gesucht", first.getReason());
        assertEquals(LeadTask.Origin.REGEL, first.getOrigin());
    }

    @Test
    public void theModelMayEndOnlyWhenTheLastRoundBroughtNothingNew() throws IOException {
        ResearchMemory memory = memory();
        searched(memory, "S1", "F01", "standard", 1, 1);
        Scripted early = new Scripted(messages -> MainModelChatResult.ok(answer("beenden", "Genug gefunden.")));

        LeadPlan going = lead(early).plan(memory, MISSION, "");

        assertFalse(going.isEnd());
        assertTrue(going.getNotes().toString(), going.getNotes().get(0).startsWith("Leitplanke: Thema thema-0001 "
                + "innerhalb des Weidezauns blieb ohne Fragen"));
        assertTrue(going.getNotes().toString(), going.getNotes().get(1).startsWith("Leitplanke: kein Ende, die letzte "
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
        assertEquals("decision 1: \"handlung\" must be one of [weiter_verfolgen, strategie_wechseln, "
                + "zurueckstellen]", ResearchLead.parse("{\"entscheidungen\":[{\"grund\":\"x\","
                + "\"handlung\":\"neue_fragen\"}],\"lauf\":{\"grund\":\"g\",\"handlung\":\"weiter\"}}").problem);
        assertEquals("decision 1: \"grund\" must give the reason", ResearchLead.parse("{\"entscheidungen\":[{\"grund\":"
                + "\" \",\"handlung\":\"zurueckstellen\"}],\"lauf\":{\"grund\":\"g\",\"handlung\":\"weiter\"}}")
                .problem);
        ResearchLead.Answer asking = ResearchLead.parse("{\"entscheidungen\":[],\"neue_fragen\":{\" thema-1 \":"
                + "{\"grund\":\"neu\",\"fragen\":[\"Was gilt?\",3]}},\"lauf\":{\"grund\":\"g\","
                + "\"handlung\":\"weiter\"}}");
        assertEquals("", asking.problem);
        assertEquals("neu", asking.asked.get("thema-1").reason);
        assertEquals(Arrays.asList("Was gilt?", "3"), asking.asked.get("thema-1").questions);
        assertEquals("\"neue_fragen\" must be an object with a topic id per entry", ResearchLead.parse(
                "{\"entscheidungen\":[],\"neue_fragen\":[],\"lauf\":{\"grund\":\"g\",\"handlung\":\"weiter\"}}")
                .problem);
        assertTrue("an answer without new questions is read", ResearchLead.parse("{\"entscheidungen\":[],"
                + "\"lauf\":{\"grund\":\"g\",\"handlung\":\"weiter\"}}").asked.isEmpty());
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
    /** Two more found topics inside the fence without questions, and one with a question of the finder. */
    private static ResearchMemory schoolTopics(ResearchMemory memory) throws IOException {
        memory.addTopic(ResearchTopic.found("thema-0004", "Elterntaxis", "Eltern fahren ihre Kinder bis vor die "
                + "Schule.", null, "m", 2L).withFence(Fence.INNEN, "gehört zur Schulwegsicherheit", "m", 3L));
        memory.addTopic(ResearchTopic.found("thema-0005", "Schulwegpläne", "Pläne der Städte mit sicheren Wegen zur "
                + "Schule.", null, "m", 2L).withFence(Fence.INNEN, "gehört zur Schulwegsicherheit", "m", 3L));
        memory.addTopic(ResearchTopic.found("thema-0006", "Schülerlotsen", "Lotsen sichern Übergänge vor Schulen.",
                null, "m", 2L).withFence(Fence.INNEN, "gehört zur Schulwegsicherheit", "m", 3L));
        memory.addQuestion(ResearchQuestion.open("F06", "Wo setzen Städte Schülerlotsen ein?", "thema-0006",
                ResearchQuestion.Origin.FINDER, 2L));
        return memory;
    }

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
        return answerAsking(run, reason, new String[0], decisions);
    }

    /** An answer with new questions: each entry is {@link #questionsFor}. */
    static String answerAsking(String run, String reason, String[] asked, String... decisions) {
        StringBuilder json = new StringBuilder("{\"lage\":\"Lage: zwei Fragen ohne Beleg.\",\"entscheidungen\":[");
        for (int i = 0; i < decisions.length; i++) {
            json.append(i == 0 ? "" : ",").append(decisions[i]);
        }
        JsonObject questions = new JsonObject();
        for (String entry : asked) {
            for (Map.Entry<String, JsonElement> topic : JsonParser.parseString(entry).getAsJsonObject().entrySet()) {
                questions.add(topic.getKey(), topic.getValue());
            }
        }
        JsonObject lauf = new JsonObject();
        lauf.addProperty("grund", reason);
        lauf.addProperty("handlung", run);
        return json.append("],\"neue_fragen\":").append(questions).append(",\"lauf\":").append(lauf).append('}')
                .toString();
    }

    /** New questions for one topic, as one entry of {@code neue_fragen}. */
    static String questionsFor(String topic, String reason, String... questions) {
        JsonObject entry = new JsonObject();
        entry.addProperty("grund", reason);
        JsonArray list = new JsonArray();
        for (String question : questions) {
            list.add(question);
        }
        entry.add("fragen", list);
        JsonObject wrapper = new JsonObject();
        wrapper.add(topic, entry);
        return wrapper.toString();
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
