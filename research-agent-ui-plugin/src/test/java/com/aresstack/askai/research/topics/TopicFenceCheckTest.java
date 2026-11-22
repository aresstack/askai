package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.ResearchTopic.Fence;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.topics.TopicFenceCheck.AuthorQuestion;
import com.aresstack.askai.research.topics.TopicFenceCheck.Brief;
import com.aresstack.askai.research.topics.TopicFenceCheck.Options;
import com.aresstack.askai.research.topics.TopicFenceCheck.Subject;
import com.aresstack.askai.research.topics.TopicFenceCheck.Verdict;
import com.aresstack.askai.research.topics.TopicFinderTest.Scripted;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

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
import static org.junit.Assert.fail;

/**
 * Component 14 with a scripted model and self-written road traffic texts: one verdict per topic with a reason, the
 * prompt, repair and failure, the breaker, when a topic is judged again, the memory and the questions to the author.
 */
public class TopicFenceCheckTest {

    static final String MISSION = "Ein Praxisbuch über Verkehr in Städten: Tempo, Radwege, Parken. Für Planer in "
            + "Gemeinden.";
    static final List<String> CARDS = Arrays.asList("Tempo und Verkehrsberuhigung", "Radverkehr", "Parken");

    @Test
    public void eachTopicGetsAVerdictWithAReasonInTheOrderGiven() {
        Scripted chat = new Scripted(byTopic());
        TopicFenceCheck check = check(chat, Options.defaults().withParallel(3));
        Brief brief = Brief.of(MISSION, CARDS);
        List<Verdict> verdicts = check.check(Arrays.asList(
                new Subject("thema-0001", "Lieferverkehr in Fußgängerzonen", "Wann Lieferwagen fahren dürfen.", null),
                new Subject("thema-0002", "Bahnlärm an Güterstrecken", "", null),
                new Subject("thema-0003", "Elektroroller", "Leihroller auf Gehwegen.", null)), brief);

        assertEquals(3, verdicts.size());
        assertEquals("thema-0001", verdicts.get(0).getTopicId());
        assertEquals(Fence.INNEN, verdicts.get(0).getFence());
        assertEquals("Lieferverkehr gehört zum Verkehr in Städten.", verdicts.get(0).getReason());
        assertEquals(Fence.AUSSEN, verdicts.get(1).getFence());
        assertEquals(Fence.UNKLAR, verdicts.get(2).getFence());
        for (Verdict verdict : verdicts) {
            assertTrue(verdict.isOk());
            assertEquals(Verdict.Origin.MODELL, verdict.getOrigin());
            assertEquals(brief.basis(), verdict.getBasis());
            assertEquals("test-model", verdict.getModel());
            assertEquals(1, verdict.getAnswers().size());
            assertEquals(10_000L, verdict.getTimeMillis());
        }
        assertEquals(new Subject("x", "Lieferverkehr in Fußgängerzonen", "Wann Lieferwagen fahren dürfen.", null)
                .key(), verdicts.get(0).getTopicKey());
        assertEquals(0.0, chat.temperature, 0.0);
        assertEquals(400, chat.tokens);
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        assertEquals("the statement and subject checks and the reason come first", "[aussage, gegenstand, grund, "
                + "urteil]", schema.getAsJsonObject("properties").keySet().toString());
        assertEquals("[\"ja\",\"nein\"]",
                schema.getAsJsonObject("properties").getAsJsonObject("aussage").get("enum").toString());
        assertEquals("[\"ja\",\"nein\"]",
                schema.getAsJsonObject("properties").getAsJsonObject("gegenstand").get("enum").toString());
        assertEquals("[\"aussage\",\"gegenstand\",\"grund\",\"urteil\"]", schema.get("required").toString());
        assertEquals("[\"innen\",\"aussen\",\"unklar\",\"kein_thema\"]",
                schema.getAsJsonObject("properties").getAsJsonObject("urteil").get("enum").toString());
        assertEquals(ResearchModelSlots.PHASE_RESEARCH_FENCE.getName(), TopicFenceCheck.ROLE.getSlot().getName());
        try {
            check.check(Arrays.asList(new Subject("a", "A", "", null), new Subject("a", "B", "", null)), brief);
            fail("a topic twice");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("twice"));
        }
    }

    @Test
    public void thePromptHoldsMissionEdgeScopeCardsAndTheTopicWithItsFindings() {
        ResearchScopeDraft scope = ResearchScopeDraft.builder().mission(MISSION)
                .putFacet(new ScopeFacet("f1", "Lieferverkehr", ScopeFacet.Status.CONFIRMED, ""))
                .putFacet(new ScopeFacet("f2", "Seilbahnen", ScopeFacet.Status.PROVISIONAL, ""))
                .putFacet(new ScopeFacet("f3", "Flugverkehr", ScopeFacet.Status.EXCLUDED, "zu weit"))
                .addExclusion("Autobahnen").geographicScope("Deutschland").temporalScope("ab 2015").build();
        Brief brief = Brief.of(scope, "Erfahrungen aus Dörfern zählen, wenn sie sich auf Städte übertragen lassen.",
                CARDS);
        assertEquals(Collections.singletonList("Lieferverkehr"), brief.getIncluded());
        assertEquals(Arrays.asList("Flugverkehr", "Autobahnen"), brief.getExcluded());
        Subject subject = new Subject("thema-0007", "Schulstraßen", "Straßen vor Schulen, die zeitweise gesperrt "
                + "werden.", Arrays.asList("Die Schulstraße ist morgens für Autos gesperrt.", "Eltern bringen  ihre\n"
                + "Kinder zu Fuß.", "Dritter Fund", "Vierter Fund", "Fünfter Fund wird nicht gezeigt"));

        String prompt = TopicFenceCheck.prompt(brief, subject, true, Options.defaults().withExcerpts(4, 300));
        assertTrue(prompt, prompt.startsWith("Buchauftrag:\n" + MISSION + "\n\nRand des Themas:\nErfahrungen aus "
                + "Dörfern zählen"));
        assertTrue(prompt, prompt.contains("Dazu gehört ausdrücklich:\n- Lieferverkehr\n\n"));
        assertFalse("a provisional facet is no boundary", prompt.contains("Seilbahnen"));
        assertTrue(prompt, prompt.contains("Ausgeschlossen:\n- Flugverkehr\n- Autobahnen\n\n"));
        assertTrue(prompt, prompt.contains("Räumlicher Rahmen: Deutschland\nZeitlicher Rahmen: ab 2015\n\n"));
        assertTrue(prompt, prompt.contains("Zu prüfendes Thema:\nSchulstraßen\nWorum es geht: Straßen vor Schulen, "
                + "die zeitweise gesperrt werden.\nAus den Quellen:\n- „Die Schulstraße ist morgens für Autos "
                + "gesperrt.“\n- „Eltern bringen ihre Kinder zu Fuß.“\n- „Dritter Fund“\n- „Vierter Fund“\n\n"
                + "Karten der Mindmap, nur zur Orientierung:\n- Tempo und Verkehrsberuhigung\n- Radverkehr\n- Parken\n"
                + "Die Karten geben die Richtung vor, nicht den Inhalt des Buchs. Jede Karte meint ihren Bereich nur, "
                + "soweit er den Gegenstand des Auftrags betrifft. Dass ein Thema in den Bereich einer Karte fällt, "
                + "ist kein Grund für \"innen\".\n\nGehört das Thema in dieses Buch? Maßstab ist der Buchauftrag mit "
                + "seinem Rand, nicht die Karten."));
        assertFalse(prompt.contains("Fünfter"));
        assertTrue(prompt, prompt.contains("Es muss keiner Karte entsprechen"));
        assertTrue(prompt, prompt.contains("Dass es in den Bereich einer Karte fällt, reicht dafür nicht."));
        assertTrue(prompt, prompt.contains("macht es noch nicht zu einem Thema des Buchs"));
        assertTrue("the subject itself, not its surroundings", prompt.contains("Entscheidend ist, ob das Thema den "
                + "Gegenstand des Auftrags selbst betrifft, nicht nur sein Umfeld: Derselbe Ort, dieselbe Branche oder "
                + "dasselbe Rechtsgebiet stiftet noch keinen Bezug, und eine Verbindung, die erst du herstellst (etwa "
                + "„kann damit überwacht werden“, „spielt dabei eine Rolle“, „ist ein Bauteil davon“), auch nicht."));
        assertFalse("c14e: a missing place of use no longer opens the door", prompt.contains("schließt umgekehrt"));
        assertTrue("the statement first", prompt.contains("Prüfe zuerst \"aussage\": \"ja\", wenn die Auszüge etwas "
                + "zur Sache sagen; \"nein\", wenn sie nur verweisen, Titel oder Links aufzählen, navigieren oder "
                + "Fragen stellen, ohne sie zu beantworten.\n\nPrüfe dann \"gegenstand\": \"ja\", wenn die Auszüge "
                + "vom Gegenstand des Auftrags selbst sprechen, unter seinem Namen oder einem gleichbedeutenden "
                + "Begriff, oder wenn das Thema zu einer der ausdrücklich genannten Klassen gehört; \"nein\", wenn nur "
                + "du eine Verbindung zum Gegenstand herstellst.\n"));
        assertTrue("Angelo's rules of 22 Nov as explicit classes, the inclusions with them", prompt.contains("Die "
                + "ausdrücklich genannten Klassen sind: Regeln und Pflichten, die für den Gegenstand selbst oder "
                + "seinen Einsatz gelten; die Technik, aus der der Gegenstand besteht oder mit der er arbeitet, mit "
                + "ihren Grenzen und Risiken, nicht aber dieselbe Technik in anderen Anlagen oder Geräten; Geräte und "
                + "Anwendungen derselben Art und ihre Märkte, deren Erkenntnisse sich auf den Gegenstand übertragen "
                + "lassen; was oben unter \"Dazu gehört ausdrücklich\" steht.\n\nDann wähle ein Urteil:\n- "
                + "\"innen\": nur bei \"gegenstand\": \"ja\". Das Thema handelt vom Gegenstand des Auftrags selbst "
                + "oder gehört zu einer der ausdrücklich genannten Klassen. Nur für diese Klassen genügt es auch, wenn "
                + "die Quelle den Gegenstand nicht nennt oder Einsatzort und Zielgruppe des Auftrags fehlen."));
        assertTrue("without the subject outside or unclear", prompt.contains("Bei \"gegenstand\": \"nein\" ist das "
                + "Urteil \"aussen\", oder \"unklar\", wenn offen ist, ob eine Regel beim Einsatz des Gegenstands "
                + "gilt.\n"));
        assertTrue("doubt is unclear", prompt.contains("Dann entscheidet der Autor. Im Zweifel ist das Urteil "
                + "\"unklar\", nie \"innen\".\n"));
        assertTrue("a rule must apply", prompt.contains("Eine Regel, Norm oder Pflicht, die beim Einsatz des "
                + "Gegenstands nicht gilt, weil sie andere Anlagen, Maschinen, Geräte oder Tätigkeiten betrifft, ist "
                + "\"aussen\"."));
        assertTrue("the surroundings are outside", prompt.contains("Allgemeine Pflichten des Einsatzorts oder der "
                + "Branche, die unabhängig vom Gegenstand gelten, und Tätigkeiten ohne den Gegenstand sind "
                + "\"aussen\"."));
        assertTrue("doubt is no inside", prompt.contains("- \"unklar\": Es ist offen, ob das Thema den Gegenstand "
                + "betrifft, etwa ob eine Regel beim Einsatz des Gegenstands gilt"));
        assertTrue(prompt, prompt.contains("eine Übersichtsseite, die nur Titel anderer Beiträge aufzählt"));
        assertTrue(prompt, prompt.contains("Bei \"aussage\": \"nein\" ist das Urteil immer \"kein_thema\"."));
        assertTrue(prompt, prompt.contains("- \"kein_thema\": Das ist kein Sachthema, sondern ein Rest der Webseite "
                + "oder des Dokuments"));
        assertTrue(prompt, prompt.endsWith("\"gegenstand\" (\"ja\" oder \"nein\"), \"grund\" (ein oder zwei "
                + "Sätze: wo der Gegenstand oder die Klasse vorkommt und was das Thema mit ihm zu tun hat, oder warum "
                + "nichts; eine Karte ist kein Grund) und \"urteil\" (\"innen\", \"aussen\", \"unklar\" oder "
                + "\"kein_thema\")."));
        assertFalse("no subtopics, no line", prompt.contains("Teilthemen"));

        String plain = TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), new Subject("t", "Schulstraßen", "", null),
                true, Options.defaults());
        assertFalse(plain, plain.contains("Rand des Themas") || plain.contains("Ausgeschlossen")
                || plain.contains("Rahmen") || plain.contains("Aus den Quellen") || plain.contains("Worum es geht"));
        assertTrue("the cards come after the topic", plain.indexOf("Karten der Mindmap")
                > plain.indexOf("Zu prüfendes Thema:"));
        assertTrue("without excerpts the statement is about name and description", plain.contains("Prüfe zuerst "
                + "\"aussage\": Auszüge fehlen hier; \"ja\", wenn Name und Beschreibung eine Sache benennen;"));
        assertTrue("without excerpts the subject too", plain.contains("Prüfe dann \"gegenstand\": \"ja\", wenn "
                + "Name oder Beschreibung den Gegenstand des Auftrags selbst nennen"));
        assertTrue("no inclusions, no reference to them", plain.contains("sich auf den Gegenstand übertragen "
                + "lassen.\n\nDann wähle ein Urteil:"));
        String english = TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), subject, false, Options.defaults());
        assertTrue(english, english.startsWith("Book mission:\n") && english.contains("Topic to check:\nSchulstraßen"));
        assertTrue(english, english.contains("Cards of the mind map, for orientation only:\n- Tempo und "
                + "Verkehrsberuhigung\n") && english.contains("is no reason for \"innen\".\n\nDoes the topic belong "
                + "in this book?"));
        assertTrue(english, english.contains("- \"kein_thema\": This is no subject matter but a remnant of the web "
                + "page or document") && english.contains("A rule, standard or duty that does not apply when the "
                + "subject is used"));
        assertTrue(english, english.contains("What counts is whether the topic concerns the subject of the mission "
                + "itself, not only its surroundings") && english.contains("First check \"aussage\": \"ja\" if the "
                + "excerpts say something about the matter;") && english.contains("general duties of the place of use "
                + "or the industry that hold regardless of the subject"));
        assertTrue(english, english.contains("and neither does a link that only you draw (such as \"can be "
                + "monitored with it\"") && english.contains("Then check \"gegenstand\": \"ja\" if the excerpts speak "
                + "of the subject of the mission itself") && english.contains("- \"innen\": only with \"gegenstand\": "
                + "\"ja\".") && english.contains("In doubt the verdict is \"unklar\", never \"innen\"."));
        assertTrue(english, english.endsWith("Answer only with JSON with the fields \"aussage\" (\"ja\" or "
                + "\"nein\", first), \"gegenstand\" (\"ja\" or \"nein\"), \"grund\" (one or two sentences: where "
                + "the subject or the class occurs and what the topic has to do with it, or why nothing; a card is no "
                + "reason) and \"urteil\" (\"innen\", \"aussen\", \"unklar\" or \"kein_thema\")."));
        assertTrue(TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), new Subject("t", "Schulstraßen", "", null), false,
                Options.defaults()).contains("First check \"aussage\": there are no excerpts here;"));

        String withoutCards = TopicFenceCheck.prompt(brief, subject, true, Options.defaults().withCards(false));
        assertFalse(withoutCards, withoutCards.contains("Karten der Mindmap") || withoutCards.contains("Radverkehr"));
        assertTrue(withoutCards, withoutCards.contains("„Vierter Fund“\n\nGehört das Thema in dieses Buch?"));
        String noCards = TopicFenceCheck.prompt(Brief.of(MISSION, Collections.<String>emptyList()), subject, true,
                Options.defaults());
        assertFalse("no cards, no card block", noCards.contains("Karten der Mindmap"));

        assertFalse("another card is another basis", brief.basis().equals(new Brief(MISSION, brief.getEdge(),
                brief.getIncluded(), brief.getExcluded(), "Deutschland", "ab 2015", CARDS.subList(0, 2)).basis()));
        assertEquals(brief.basis(), new Brief(" " + MISSION + " ", brief.getEdge(), brief.getIncluded(),
                brief.getExcluded(), "Deutschland", "ab 2015", CARDS).basis());
    }

    @Test
    public void anInvalidAnswerGetsOneRepairAndAFailureIsNoVerdict() {
        AtomicInteger n = new AtomicInteger();
        Scripted chat = new Scripted(messages -> {
            String topic = topicOf(messages);
            if (topic.startsWith("Reparatur")) {
                return MainModelChatResult.ok(messages.size() == 2 ? "{\"grund\":\"x\",\"urteil\":\"vielleicht\"}"
                        : "{\"grund\":\"Gehört zum Parken.\",\"urteil\":\"Innen\"}");
            }
            if (topic.startsWith("Ohne Grund")) {
                return MainModelChatResult.ok("{\"grund\":\" \",\"urteil\":\"aussen\"}");
            }
            n.incrementAndGet();
            return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow");
        });
        List<Verdict> verdicts = check(chat, Options.defaults().withParallel(1)).check(Arrays.asList(
                new Subject("t1", "Reparatur Parkhaus", "", null),
                new Subject("t2", "Ohne Grund", "", null),
                new Subject("t3", "Zeitüberschreitung", "", null)), Brief.of(MISSION, CARDS));

        Verdict repaired = verdicts.get(0);
        assertTrue(repaired.isOk());
        assertEquals(Fence.INNEN, repaired.getFence());
        assertEquals(2, repaired.getAnswers().size());
        List<ChatMessage> second = chat.requests.get(1);
        assertEquals(4, second.size());
        assertTrue(second.get(3).getContent().startsWith("Deine Antwort war ungültig: \"urteil\" must be innen, "
                + "aussen, unklar or kein_thema"));

        Verdict noReason = verdicts.get(1);
        assertFalse(noReason.isOk());
        assertNull(noReason.getFence());
        assertTrue(noReason.getProblem(), noReason.getProblem().contains("\"grund\" must give the reason"));
        Verdict failed = verdicts.get(2);
        assertFalse(failed.isOk());
        assertEquals("model call failed (TIMEOUT): slow", failed.getProblem());
        assertEquals(Arrays.asList("", ""), failed.getAnswers());
        assertEquals("a failed call is tried once more", 2, n.get());
        assertEquals(Fence.AUSSEN, TopicFenceCheck.fenceOf(" AUßEN "));
        assertNull(TopicFenceCheck.fenceOf("drinnen"));
    }

    @Test
    public void failuresInARowStopTheBatch() {
        Scripted chat = new Scripted(m -> MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                "connection refused"));
        List<Subject> subjects = new ArrayList<Subject>();
        for (int i = 1; i <= 8; i++) {
            subjects.add(new Subject("t" + i, "Thema " + i, "", null));
        }
        List<Verdict> verdicts = check(chat, Options.defaults().withParallel(1)).check(subjects,
                Brief.of(MISSION, CARDS));
        assertEquals(8, verdicts.size());
        assertEquals("five topics with two calls each", 10, chat.calls.get());
        for (int i = 5; i < 8; i++) {
            assertFalse(verdicts.get(i).isOk());
            assertTrue(verdicts.get(i).getProblem(), verdicts.get(i).getProblem().startsWith("not asked: the model "
                    + "failed 5 topics in a row; last: model call failed (PROVIDER_FAILURE): connection refused"));
        }
    }

    @Test
    public void aTopicIsJudgedOnceAndAgainWhenTheBriefOrItsOwnTextChanges() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("konzept-1", "Radverkehr", "karte-1", 0, 1L));
        memory.addTopic(ResearchTopic.found("thema-0001", "Lastenräder", "Räder für Einkäufe.", null, "m", 2L)
                .withParent("konzept-1", "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Bahnlärm", "", null, "m", 2L));
        Brief brief = Brief.of(MISSION, CARDS);
        TopicFenceCheck check = check(new Scripted(byTopic()), Options.defaults());

        List<ResearchTopic> todo = TopicFenceCheck.unchecked(memory, brief, null);
        assertEquals("the card is inside by definition", Arrays.asList("thema-0001", "thema-0002"), ids(todo));
        List<Verdict> verdicts = check.check(subjects(memory, todo), brief);
        assertEquals(Fence.INNEN, verdicts.get(0).getFence());
        assertTrue(TopicFenceCheck.unchecked(memory, brief, verdicts).isEmpty());

        Brief changed = Brief.of(MISSION, Arrays.asList("Radverkehr", "Parken"));
        assertEquals("new cards, new check", 2, TopicFenceCheck.unchecked(memory, changed, verdicts).size());
        Verdict first = verdicts.get(0);
        Verdict olderPrompt = new Verdict(first.getTopicId(), first.getTopicName(), first.getTopicKey(),
                first.getBasis(), Verdict.Status.OK, first.getFence(), first.getReason(), "", null,
                Verdict.Origin.MODELL, "m", 3L, 1L, false, TopicFenceCheck.PROMPT_VERSION - 1);
        assertEquals("a verdict under an older prompt is asked again", Collections.singletonList("thema-0001"),
                ids(TopicFenceCheck.unchecked(memory, brief, Arrays.asList(olderPrompt, verdicts.get(1)))));
        assertEquals(TopicFenceCheck.PROMPT_VERSION, first.getVersion());

        ResearchTopic old = memory.topic("thema-0002");
        memory.updateTopic(new ResearchTopic(old.getId(), "Bahnlärm in Städten", old.getDescription(), old.getOrigin(),
                old.getCardAnchorId(), old.getMergedFrom(), old.getFindings(), old.getFence(), old.getFenceReason(),
                old.getWeight(), "m", 3L, old.getParentId()));
        assertEquals(Collections.singletonList("thema-0002"),
                ids(TopicFenceCheck.unchecked(memory, brief, verdicts)));

        List<Verdict> withFailure = new ArrayList<Verdict>(verdicts);
        Subject renamed = TopicFenceCheck.subjectOf(memory, memory.topic("thema-0002"), null);
        withFailure.add(new Verdict("thema-0002", renamed.getName(), renamed.key(), brief.basis(),
                Verdict.Status.FEHLER, null, "", "slow", null, Verdict.Origin.MODELL, "m", 4L, 1L));
        assertEquals("a failed check is offered again", Collections.singletonList("thema-0002"),
                ids(TopicFenceCheck.unchecked(memory, brief, withFailure)));

        List<Verdict> decided = new ArrayList<Verdict>(withFailure);
        decided.add(TopicFenceCheck.byAuthor(renamed, brief, Fence.INNEN, "Gehört dazu, Lärm betrifft Planer.", 5L));
        assertTrue(TopicFenceCheck.unchecked(memory, brief, decided).isEmpty());
        assertEquals("the author's decision holds on a new basis", Collections.singletonList("thema-0001"),
                ids(TopicFenceCheck.unchecked(memory, changed, decided)));
        decided.add(new Verdict("thema-0002", renamed.getName(), renamed.key(), changed.basis(),
                Verdict.Status.OK, Fence.AUSSEN, "Model", "", null, Verdict.Origin.MODELL, "m", 6L, 1L));
        assertEquals("the model does not replace the author", Verdict.Origin.NUTZER,
                TopicFenceCheck.latest(decided).get("thema-0002").getOrigin());
    }

    @Test
    public void verdictsGoToTheMemoryAndUnclearOnesBecomeQuestions() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("konzept-1", "Radverkehr", "karte-1", 0, 1L));
        memory.addTopic(ResearchTopic.found("thema-0001", "Lieferverkehr", "", null, "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Elektroroller", "", null, "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0003", "Bahnlärm", "", null, "m", 2L));
        memory.addTopic(ResearchTopic.found("thema-0004", "Bahnlärm an Strecken", "", null, "m", 2L));
        Brief brief = Brief.of(MISSION, CARDS);
        List<Verdict> verdicts = check(new Scripted(byTopic()), Options.defaults())
                .check(subjects(memory, TopicFenceCheck.unchecked(memory, brief, null)), brief);
        memory.updateTopic(memory.topic("thema-0003").withMerged(
                Collections.singletonList(memory.topic("thema-0004")), "m", 3L));

        assertEquals("thema-0004 was merged away meanwhile", 3, TopicFenceCheck.apply(memory, verdicts));
        assertEquals(Fence.INNEN, memory.topic("thema-0001").getFence());
        assertEquals("Lieferverkehr gehört zum Verkehr in Städten.", memory.topic("thema-0001").getFenceReason());
        assertEquals("test-model", memory.topic("thema-0001").getModel());
        assertEquals(Fence.UNKLAR, memory.topic("thema-0002").getFence());
        assertEquals(Fence.AUSSEN, memory.topic("thema-0003").getFence());
        assertEquals(Fence.UNGEPRUEFT, memory.topic("thema-0004").getFence());
        assertEquals("a card is inside by definition and not judged", "Karte des Konzepts",
                memory.topic("konzept-1").getFenceReason());
        assertEquals("nothing new to write", 0, TopicFenceCheck.apply(memory, verdicts));

        List<AuthorQuestion> questions = TopicFenceCheck.questions(verdicts);
        assertEquals(1, questions.size());
        assertEquals("thema-0002", questions.get(0).getTopicId());
        assertEquals("Gehört das Thema „Elektroroller“ ins Buch? Roller sind Verkehr, aber kaum Thema der "
                + "Planung.", questions.get(0).text(true));

        Subject roller = TopicFenceCheck.subjectOf(memory, memory.topic("thema-0002"), null);
        TopicFenceCheck.apply(memory, Collections.singletonList(TopicFenceCheck.byAuthor(roller, brief, Fence.INNEN,
                "Gehört dazu.", 9L)));
        assertEquals(Fence.INNEN, memory.topic("thema-0002").getFence());
        assertEquals("nutzer", memory.topic("thema-0002").getModel());
    }

    @Test
    public void aSubjectTakesTheDistinctFindingsOfTheTopicAndItsMergedRecords() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        ResearchTopic a = ResearchTopic.found("vs-1", "Lastenräder", "", Arrays.asList(
                new ResearchTopic.Finding("s1", "p1", "Lastenräder ersetzen  Lieferwagen."),
                new ResearchTopic.Finding("s2", "p4", "lastenräder ersetzen lieferwagen")), "m", 1L);
        ResearchTopic b = ResearchTopic.found("vs-2", "Lastenradverleih", "", Collections.singletonList(
                new ResearchTopic.Finding("s3", "p2", "Viele Städte verleihen Lastenräder kostenlos an Bürger, die "
                        + "einen Einkauf erledigen wollen.")), "m", 1L);
        memory.addTopic(a);
        memory.addTopic(b);
        memory.addTopic(ResearchTopic.found("thema-0001", "Lastenräder", "Räder für Lasten.", null, "m", 2L)
                .withMerged(Arrays.asList(a, b), "m", 2L));

        Subject subject = TopicFenceCheck.subjectOf(memory, memory.topic("thema-0001"),
                Options.defaults().withExcerpts(3, 40));
        assertEquals("thema-0001", subject.getTopicId());
        assertEquals("Räder für Lasten.", subject.getDescription());
        assertEquals(Arrays.asList("Lastenräder ersetzen Lieferwagen.",
                "Viele Städte verleihen Lastenräder …"), subject.getExcerpts());
        String prompt = TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), subject, true, Options.defaults());
        assertFalse("the parent is not named", prompt.contains("Unterthema"));
    }

    @Test
    public void aRemnantOfThePageIsNoSubjectAndGoesToTheMemoryAsAussen() throws Exception {
        Function<List<ChatMessage>, MainModelChatResult> answers = messages -> {
            if (messages.get(1).getContent().contains("Startseite")) {
                return MainModelChatResult.ok("{\"grund\":\"Das ist die Navigation einer Webseite.\","
                        + "\"urteil\":\"kein_thema\"}");
            }
            return byTopic().apply(messages);
        };
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.found("thema-0001", "Startseite Kontakt Impressum", "", null, "m", 1L));
        memory.addTopic(ResearchTopic.found("thema-0002", "Bahnlärm", "", null, "m", 1L));
        Brief brief = Brief.of(MISSION, CARDS);
        List<Verdict> verdicts = check(new Scripted(answers), Options.defaults()).check(TopicFenceCheck.subjects(
                memory, TopicFenceCheck.unchecked(memory, brief, null), null), brief);

        Verdict menu = verdicts.get(0);
        assertTrue(menu.isOk());
        assertTrue(menu.isNoTopic());
        assertEquals("the outline and the report treat it as outside", Fence.AUSSEN, menu.getFence());
        assertEquals("Kein Sachthema: Das ist die Navigation einer Webseite.", menu.getReason());
        assertTrue(menu.toString(), menu.toString().contains("AUSSEN, kein Sachthema"));
        assertFalse("a plain AUSSEN keeps its reason", verdicts.get(1).isNoTopic());
        assertEquals("Bahnlärm ist kein Thema der Straßenplanung.", verdicts.get(1).getReason());
        assertEquals(2, TopicFenceCheck.apply(memory, verdicts));
        assertEquals(Fence.AUSSEN, memory.topic("thema-0001").getFence());
        assertEquals("Kein Sachthema: Das ist die Navigation einer Webseite.",
                memory.topic("thema-0001").getFenceReason());
        assertTrue("no question to the author", TopicFenceCheck.questions(verdicts).isEmpty());

        Verdict english = new TopicFenceCheck(new Scripted(answers), "en", Options.defaults(), null).check(
                Collections.singletonList(new Subject("t", "Startseite", "", null)), brief).get(0);
        assertEquals("Not a subject: Das ist die Navigation einer Webseite.", english.getReason());
        for (String word : new String[]{"kein_thema", "Kein Thema", "kein-thema", " KEINTHEMA "}) {
            assertTrue(word, TopicFenceCheck.isNoTopicWord(word));
        }
        assertFalse(TopicFenceCheck.isNoTopicWord("kein"));
        assertNull("not a fence of the memory", TopicFenceCheck.fenceOf("kein_thema"));
    }

    @Test
    public void excerptsWithoutAStatementMakeNoSubjectWhateverTheVerdictSays() {
        Scripted chat = new Scripted(messages -> {
            String topic = topicOf(messages);
            if (topic.startsWith("Fragen")) {
                return MainModelChatResult.ok("{\"aussage\":\"nein\",\"grund\":\"Die Auszüge fragen nur nach "
                        + "Radwegen.\",\"urteil\":\"innen\"}");
            }
            if (topic.startsWith("Linkliste")) {
                return MainModelChatResult.ok("{\"aussage\":\"nein\",\"grund\":\"Nur Titel anderer Beiträge.\","
                        + "\"urteil\":\"kein_thema\"}");
            }
            if (topic.startsWith("Ungültig")) {
                return MainModelChatResult.ok(messages.size() == 2
                        ? "{\"aussage\":\"vielleicht\",\"grund\":\"x\",\"urteil\":\"innen\"}"
                        : "{\"aussage\":\" Ja \",\"grund\":\"Parkhäuser gehören zum Parken.\",\"urteil\":\"innen\"}");
            }
            if (topic.startsWith("Bahnlärm")) {
                return MainModelChatResult.ok("{\"aussage\":\"nein\",\"grund\":\"Nur ein Verweis.\","
                        + "\"urteil\":\"aussen\"}");
            }
            return MainModelChatResult.ok("{\"aussage\":\"ja\",\"grund\":\"Schulstraßen beruhigen den "
                    + "Verkehr.\",\"urteil\":\"innen\"}");
        });
        List<Verdict> verdicts = check(chat, Options.defaults().withParallel(1)).check(Arrays.asList(
                new Subject("t1", "Fragen zu Radwegen", "", Collections.singletonList("Wie breit muss ein Radweg "
                        + "sein? Wer zahlt ihn?")),
                new Subject("t2", "Linkliste Verkehr", "", Collections.singletonList("Weitere Beiträge: Parken, "
                        + "Radwege, Tempo 30")),
                new Subject("t3", "Ungültig Parkhaus", "", null),
                new Subject("t4", "Schulstraßen", "", Collections.singletonList("Die Schulstraße ist morgens für "
                        + "Autos gesperrt.")),
                new Subject("t5", "Bahnlärm", "", null)), Brief.of(MISSION, CARDS));

        Verdict questions = verdicts.get(0);
        assertTrue(questions.isOk());
        assertTrue("nein outweighs innen", questions.isNoTopic());
        assertEquals(Fence.AUSSEN, questions.getFence());
        assertEquals("Kein Sachthema: Die Auszüge tragen keine Aussage. Die Auszüge fragen nur nach Radwegen.",
                questions.getReason());
        assertEquals("no question to the author", 0, TopicFenceCheck.questions(verdicts).size());
        assertEquals("no note where the verdict agrees", "Kein Sachthema: Nur Titel anderer Beiträge.",
                verdicts.get(1).getReason());
        assertTrue(verdicts.get(1).isNoTopic());

        Verdict repaired = verdicts.get(2);
        assertTrue(repaired.isOk());
        assertEquals(Fence.INNEN, repaired.getFence());
        assertFalse(repaired.isNoTopic());
        assertEquals("Parkhäuser gehören zum Parken.", repaired.getReason());
        assertEquals(2, repaired.getAnswers().size());
        assertEquals("Deine Antwort war ungültig: \"aussage\" must be ja or nein\nAntworte noch einmal, nur mit "
                + "dem JSON-Objekt.", chat.requests.get(3).get(3).getContent());

        Verdict inside = verdicts.get(3);
        assertEquals(Fence.INNEN, inside.getFence());
        assertFalse(inside.isNoTopic());
        assertEquals("Schulstraßen beruhigen den Verkehr.", inside.getReason());
        assertTrue("nein turns aussen into kein_thema", verdicts.get(4).isNoTopic());
        assertEquals("Kein Sachthema: Die Auszüge tragen keine Aussage. Nur ein Verweis.",
                verdicts.get(4).getReason());
        for (Verdict verdict : verdicts) {
            assertEquals(TopicFenceCheck.PROMPT_VERSION, verdict.getVersion());
        }

        Verdict english = new TopicFenceCheck(new Scripted(m -> MainModelChatResult.ok("{\"aussage\":\"nein\","
                + "\"grund\":\"Only questions.\",\"urteil\":\"innen\"}")), "en", Options.defaults(), null)
                .check(Collections.singletonList(new Subject("t", "Questions on cycle paths", "", null)),
                        Brief.of(MISSION, CARDS)).get(0);
        assertEquals("Not a subject: The excerpts carry no statement. Only questions.", english.getReason());
        TopicFenceCheck.Answer before = TopicFenceCheck.parse("{\"grund\":\"Gehört zum Parken.\","
                + "\"urteil\":\"innen\"}");
        assertEquals("an answer without the field reads as before", Fence.INNEN, before.fence);
        assertFalse(before.noTopic);
        assertEquals("\"aussage\" must be ja or nein", TopicFenceCheck.parse("{\"aussage\":1,\"grund\":\"x\","
                + "\"urteil\":\"innen\"}").problem);
    }

    @Test
    public void aBridgeOnlyTheModelBuildsMakesNoInside() {
        Scripted chat = new Scripted(messages -> {
            String topic = topicOf(messages);
            if (topic.startsWith("Laser")) {
                return MainModelChatResult.ok("{\"aussage\":\"ja\",\"gegenstand\":\"nein\",\"grund\":\"Blendung "
                        + "kann den Verkehr stören.\",\"urteil\":\"innen\"}");
            }
            if (topic.startsWith("Gleisbau")) {
                return MainModelChatResult.ok("{\"aussage\":\"ja\",\"gegenstand\":\"nein\",\"grund\":\"Gleisbau ist "
                        + "kein Straßenverkehr.\",\"urteil\":\"aussen\"}");
            }
            if (topic.startsWith("Ampeln")) {
                return MainModelChatResult.ok("{\"aussage\":\"ja\",\"gegenstand\":\"nein\",\"grund\":\"Offen, ob "
                        + "die Regel in Städten gilt.\",\"urteil\":\"unklar\"}");
            }
            if (topic.startsWith("Menü")) {
                return MainModelChatResult.ok("{\"aussage\":\"nein\",\"gegenstand\":\"nein\",\"grund\":\"Nur "
                        + "Navigation.\",\"urteil\":\"innen\"}");
            }
            if (topic.startsWith("Ungültig")) {
                return MainModelChatResult.ok(messages.size() == 2
                        ? "{\"aussage\":\"ja\",\"gegenstand\":\"teils\",\"grund\":\"x\",\"urteil\":\"innen\"}"
                        : "{\"aussage\":\"ja\",\"gegenstand\":\"JA\",\"grund\":\"Parkhäuser sind Parken.\","
                        + "\"urteil\":\"innen\"}");
            }
            return MainModelChatResult.ok("{\"aussage\":\"ja\",\"gegenstand\":\"ja\",\"grund\":\"Die Auszüge "
                    + "sprechen von Schulstraßen.\",\"urteil\":\"innen\"}");
        });
        List<Verdict> verdicts = check(chat, Options.defaults().withParallel(1)).check(Arrays.asList(
                new Subject("t1", "Laserpointer", "", Collections.singletonList("Laserpointer blenden.")),
                new Subject("t2", "Gleisbau", "", null),
                new Subject("t3", "Ampeln auf dem Land", "", null),
                new Subject("t4", "Menü", "", null),
                new Subject("t5", "Ungültig Parkhaus", "", null),
                new Subject("t6", "Schulstraßen", "", Collections.singletonList("Die Schulstraße ist morgens für "
                        + "Autos gesperrt."))), Brief.of(MISSION, CARDS));

        Verdict bridge = verdicts.get(0);
        assertTrue(bridge.isOk());
        assertEquals("no subject, no inside: the author decides", Fence.UNKLAR, bridge.getFence());
        assertFalse(bridge.isNoTopic());
        assertEquals("Der Gegenstand selbst kommt nicht vor, darum unklar statt innen. Blendung kann den Verkehr "
                + "stören.", bridge.getReason());
        assertTrue(TopicFenceCheck.turnedUnclear(bridge));
        assertEquals("both unclear ones go to the author", 2, TopicFenceCheck.questions(verdicts).size());
        assertEquals("aussen stays aussen, without a note", Fence.AUSSEN, verdicts.get(1).getFence());
        assertEquals("Gleisbau ist kein Straßenverkehr.", verdicts.get(1).getReason());
        assertFalse(TopicFenceCheck.turnedUnclear(verdicts.get(1)));
        assertEquals("unklar stays unklar, without a note", Fence.UNKLAR, verdicts.get(2).getFence());
        assertEquals("Offen, ob die Regel in Städten gilt.", verdicts.get(2).getReason());
        assertFalse(TopicFenceCheck.turnedUnclear(verdicts.get(2)));
        assertTrue("no statement outweighs the subject field", verdicts.get(3).isNoTopic());
        assertEquals("Kein Sachthema: Die Auszüge tragen keine Aussage. Nur Navigation.", verdicts.get(3).getReason());

        Verdict repaired = verdicts.get(4);
        assertEquals(Fence.INNEN, repaired.getFence());
        assertEquals("Parkhäuser sind Parken.", repaired.getReason());
        assertEquals("Deine Antwort war ungültig: \"gegenstand\" must be ja or nein\nAntworte noch einmal, nur mit "
                + "dem JSON-Objekt.", chat.requests.get(5).get(3).getContent());
        assertEquals("the subject named, inside", Fence.INNEN, verdicts.get(5).getFence());
        assertEquals("Die Auszüge sprechen von Schulstraßen.", verdicts.get(5).getReason());

        Verdict english = new TopicFenceCheck(new Scripted(m -> MainModelChatResult.ok("{\"aussage\":\"ja\","
                + "\"gegenstand\":\"no\",\"grund\":\"Glare can disturb traffic.\",\"urteil\":\"innen\"}")), "en",
                Options.defaults(), null).check(Collections.singletonList(new Subject("t", "Laser", "", null)),
                Brief.of(MISSION, CARDS)).get(0);
        assertEquals(Fence.UNKLAR, english.getFence());
        assertEquals("The subject itself does not occur, so unclear instead of inside. Glare can disturb traffic.",
                english.getReason());
        TopicFenceCheck.Answer before = TopicFenceCheck.parse("{\"aussage\":\"ja\",\"grund\":\"Gehört zum "
                + "Parken.\",\"urteil\":\"innen\"}");
        assertEquals("an answer without the field reads as before", Fence.INNEN, before.fence);
        assertFalse(before.noSubject);
        assertEquals("\"gegenstand\" must be ja or nein", TopicFenceCheck.parse("{\"aussage\":\"ja\","
                + "\"gegenstand\":[],\"grund\":\"x\",\"urteil\":\"innen\"}").problem);
    }

    @Test
    public void aParentShowsItsSubtopicsAndTheirFindingsWhenItHasTooFewOfItsOwn() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.found("thema-0010", "Radwege", "Wege für den Radverkehr.", null, "m", 1L));
        memory.addTopic(ResearchTopic.found("thema-0009", "Radinfrastruktur", "", null, "m", 1L));
        String[][] parts = {
                {"thema-0011", "Fahrradstraßen", "In Fahrradstraßen haben Räder Vorrang.", "Autos dürfen nur zu Gast "
                        + "sein."},
                {"thema-0012", "Radschnellwege", "Radschnellwege verbinden Vororte mit der Innenstadt."},
                {"thema-0013", "Radwegbreite", "Ein Radweg sollte mindestens zwei Meter breit sein."},
                {"thema-0014", "Schutzstreifen", "Schutzstreifen liegen auf der Fahrbahn."},
                {"thema-0015", "Abstellanlagen", "Bügel am Bahnhof sind schnell belegt."},
                {"thema-0016", "Radwegepflicht"}};
        for (String[] part : parts) {
            List<ResearchTopic.Finding> findings = new ArrayList<ResearchTopic.Finding>();
            for (int i = 2; i < part.length; i++) {
                findings.add(new ResearchTopic.Finding("s-" + part[0], "p" + i, part[i]));
            }
            memory.addTopic(ResearchTopic.found(part[0], part[1], "", findings, "m", 2L)
                    .withParent("thema-0010", "m", 2L));
        }
        memory.addTopic(ResearchTopic.found("thema-0017", "Radverkehrsanlagen", "", null, "m", 2L)
                .withParent("thema-0009", "m", 2L));
        memory.updateTopic(memory.topic("thema-0010").withMerged(
                Collections.singletonList(memory.topic("thema-0009")), "m", 3L));

        Options options = Options.defaults().withExcerpts(4, 300);
        Subject formed = TopicFenceCheck.subjects(memory, Collections.singletonList(memory.topic("thema-0010")),
                options).get(0);
        assertEquals("the subtopic of the merged-in parent counts too", Arrays.asList("Fahrradstraßen",
                "Radschnellwege", "Radwegbreite", "Schutzstreifen", "Abstellanlagen", "Radwegepflicht",
                "Radverkehrsanlagen"), formed.getParts());
        assertEquals("one finding of each subtopic in turn", Arrays.asList("In Fahrradstraßen haben Räder Vorrang.",
                "Radschnellwege verbinden Vororte mit der Innenstadt.", "Ein Radweg sollte mindestens zwei Meter breit "
                        + "sein.", "Schutzstreifen liegen auf der Fahrbahn."), formed.getExcerpts());
        assertEquals("the key stays name and description", new Subject("x", "Radwege", "Wege für den Radverkehr.",
                null).key(), formed.key());
        String prompt = TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), formed, true, options);
        assertTrue(prompt, prompt.contains("Zu prüfendes Thema:\nRadwege\nWorum es geht: Wege für den Radverkehr.\n"
                + "Teilthemen: Fahrradstraßen; Radschnellwege; Radwegbreite; Schutzstreifen; Abstellanlagen und 2 "
                + "weitere\nAus den Quellen:\n- „In Fahrradstraßen haben Räder Vorrang.“\n"));
        assertTrue(TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), formed, false, options)
                .contains("Subtopics: Fahrradstraßen; Radschnellwege; Radwegbreite; Schutzstreifen; Abstellanlagen "
                        + "and 2 more\n"));
        Subject few = TopicFenceCheck.subjectOf(memory, memory.topic("thema-0010"), options.withExcerpts(6, 300));
        assertEquals("the second round takes the second finding", "Autos dürfen nur zu Gast sein.",
                few.getExcerpts().get(5));

        memory.addTopic(ResearchTopic.found("thema-0020", "Parken", "Ruhender Verkehr in der Stadt.",
                Collections.singletonList(new ResearchTopic.Finding("s9", "p1", "Parkplätze kosten Geld.")), "m", 4L));
        memory.addTopic(ResearchTopic.found("thema-0021", "Anwohnerparken", "", Arrays.asList(
                new ResearchTopic.Finding("s8", "p1", "Anwohner zahlen für den Ausweis."),
                new ResearchTopic.Finding("s8", "p2", "Der Ausweis gilt für eine Zone.")), "m", 4L)
                .withParent("thema-0020", "m", 4L));
        List<Subject> both = TopicFenceCheck.subjects(memory, Arrays.asList(memory.topic("thema-0020"),
                memory.topic("thema-0021")), options.withExcerpts(2, 300));
        assertEquals("its own findings first", Arrays.asList("Parkplätze kosten Geld.",
                "Anwohner zahlen für den Ausweis."), both.get(0).getExcerpts());
        assertTrue("a subtopic has no subtopics", both.get(1).getParts().isEmpty());
        String child = TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), both.get(1), true, options);
        assertFalse("the parent is still not named", child.contains("Ruhender Verkehr")
                || child.contains("Teilthemen"));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static TopicFenceCheck check(Scripted chat, Options options) {
        return new TopicFenceCheck(chat, "de", options, null,
                Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC));
    }

    /** INNEN for traffic topics, AUSSEN for railway noise, UNKLAR for scooters, by the name in the prompt. */
    static Function<List<ChatMessage>, MainModelChatResult> byTopic() {
        return messages -> {
            String topic = topicOf(messages);
            if (topic.startsWith("Bahnlärm")) {
                return MainModelChatResult.ok("{\"grund\":\"Bahnlärm ist kein Thema der Straßenplanung.\","
                        + "\"urteil\":\"aussen\"}");
            }
            if (topic.startsWith("Elektroroller")) {
                return MainModelChatResult.ok("{\"grund\":\"Roller sind Verkehr, aber kaum Thema der Planung.\","
                        + "\"urteil\":\"unklar\"}");
            }
            String first = topic.split(" ")[0];
            return MainModelChatResult.ok("{\"grund\":\"" + first + " gehört zum Verkehr in Städten.\","
                    + "\"urteil\":\"innen\"}");
        };
    }

    static String topicOf(List<ChatMessage> messages) {
        String prompt = messages.get(1).getContent();
        int at = prompt.indexOf("Zu prüfendes Thema:\n");
        return at < 0 ? "" : prompt.substring(at + "Zu prüfendes Thema:\n".length()).split("\n")[0];
    }

    private static List<Subject> subjects(ResearchMemory memory, List<ResearchTopic> topics) {
        List<Subject> subjects = new ArrayList<Subject>();
        for (ResearchTopic topic : topics) {
            subjects.add(TopicFenceCheck.subjectOf(memory, topic, null));
        }
        return subjects;
    }

    private static List<String> ids(List<ResearchTopic> topics) {
        List<String> ids = new ArrayList<String>();
        for (ResearchTopic topic : topics) {
            ids.add(topic.getId());
        }
        return ids;
    }
}
