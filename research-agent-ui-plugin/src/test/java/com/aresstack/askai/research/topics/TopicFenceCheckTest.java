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
        assertEquals("the reason comes first", "[grund, urteil]",
                schema.getAsJsonObject("properties").keySet().toString());
        assertEquals("[\"innen\",\"aussen\",\"unklar\"]",
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
        assertTrue(prompt, prompt.endsWith("eine Karte ist kein Grund) und \"urteil\" (\"innen\", \"aussen\" oder "
                + "\"unklar\")."));

        String plain = TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), new Subject("t", "Schulstraßen", "", null),
                true, Options.defaults());
        assertFalse(plain, plain.contains("Rand des Themas") || plain.contains("Ausgeschlossen")
                || plain.contains("Rahmen") || plain.contains("Aus den Quellen") || plain.contains("Worum es geht"));
        assertTrue("the cards come after the topic", plain.indexOf("Karten der Mindmap")
                > plain.indexOf("Zu prüfendes Thema:"));
        String english = TopicFenceCheck.prompt(Brief.of(MISSION, CARDS), subject, false, Options.defaults());
        assertTrue(english, english.startsWith("Book mission:\n") && english.contains("Topic to check:\nSchulstraßen"));
        assertTrue(english, english.contains("Cards of the mind map, for orientation only:\n- Tempo und "
                + "Verkehrsberuhigung\n") && english.contains("is no reason for \"innen\".\n\nDoes the topic belong "
                + "in this book?"));

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
                + "aussen or unklar"));

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
