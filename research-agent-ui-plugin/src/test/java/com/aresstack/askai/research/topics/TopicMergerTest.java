package com.aresstack.askai.research.topics;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.topics.TopicFinderTest.Scripted;
import com.aresstack.askai.research.topics.TopicMerger.Comparison;
import com.aresstack.askai.research.topics.TopicMerger.Mapping;
import com.aresstack.askai.research.topics.TopicMerger.MergedQuestion;
import com.aresstack.askai.research.topics.TopicMerger.MergedTopic;
import com.aresstack.askai.research.topics.TopicMerger.Options;
import com.aresstack.askai.research.topics.TopicMerger.Relation;
import com.aresstack.askai.research.topics.TopicMerger.Result;
import com.aresstack.askai.research.topics.TopicProposal.FindingCheck;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 15: proposals of one topic from many sources become one topic with all origins, a known topic is
 * recognized, only the doubtful cases go to the model, and questions of the same meaning become one.
 */
public class TopicMergerTest {

    private static final String MISSION = "Ein Praxisbuch über Verkehr in Städten: Tempo, Radwege, Parken.";

    // ------------------------------------------------------------------------------------------- step 1

    @Test
    public void proposalsOfOneTopicFromManySourcesBecomeOneTopicWithAllOrigins() throws Exception {
        Table vectors = new Table()
                .put("Tempo 30 in Wohngebieten", 1, 0, 0, 0)
                .put("Tempo-30-Zonen", 0.98, 0.199, 0, 0)
                .put("Tempo 30 innerorts", 0.97, 0, 0.243, 0)
                .put("Tempo 30 in der Stadt", 0.96, 0.28, 0, 0)
                .put("Tempo 30 in Wohngebieten: Wie Städte es begründen", 0, 0, 0, 1);
        List<TopicProposal> proposals = Arrays.asList(
                proposal("p1", "s1", "Tempo 30 in Wohngebieten", true),
                proposal("p2", "s2", "Tempo-30-Zonen", true),
                proposal("p3", "s3", "Tempo 30 innerorts", false),
                proposal("p4", "s2", "Tempo 30 in der Stadt", true),
                proposal("p5", "s4", "Tempo 30 in Wohngebieten", "Wie Städte es begründen", true));
        Scripted chat = new Scripted(m -> {
            throw new AssertionError("no doubtful case here");
        });
        ResearchMemory memory = new ResearchMemory();

        Result result = merger(chat, vectors).merge(proposals, memory, null);

        assertEquals(0, chat.calls.get());
        assertEquals("the same name joins even with another description", 1, result.getGroups());
        assertEquals(1, result.getTopics().size());
        MergedTopic topic = result.getTopics().get(0);
        assertEquals("thema-0001", topic.getId());
        assertEquals("the member closest to the group's mean names it", "Tempo-30-Zonen", topic.getName());
        assertEquals(Mapping.NEU, topic.getMapping());
        assertEquals(Arrays.asList("p1", "p2", "p3", "p4", "p5"), topic.getProposalIds());
        assertEquals(Arrays.asList("s1", "s2", "s3", "s4"), topic.getSourceIds());
        assertEquals("p3 has no finding", 4, topic.getFindings().size());
        assertTrue(result.getHeldBack().isEmpty());

        result.applyTo(memory);
        assertEquals(Collections.singletonList("thema-0001"), ids(memory.topics()));
        assertEquals(Arrays.asList("p1", "p2", "p3", "p4", "p5"), memory.topic("thema-0001").getMergedFrom());
        assertEquals("thema-0001", memory.currentTopic("p3").getId());
        assertTrue("the topic holds no copy of the findings", memory.topic("thema-0001").getFindings().isEmpty());
        assertEquals(4, memory.findingsOfTopic("thema-0001").size());
        assertEquals(ResearchTopic.Fence.UNGEPRUEFT, memory.topic("thema-0001").getFence());
        assertEquals(ResearchTopic.Origin.GEFUNDEN, memory.topic("thema-0001").getOrigin());
        assertTrue("nothing is left to place", TopicMerger.unplaced(proposals, memory).isEmpty());
    }

    @Test
    public void aKnownTopicIsRecognized() throws Exception {
        Table vectors = new Table()
                .put("Radverkehr", 1, 0, 0, 0)
                .put("Radverkehr in Städten", 0.95, 0.312, 0, 0)
                .put("Parkhäuser", 0, 0, 0, 1);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0.4, 1L));
        List<TopicProposal> proposals = Arrays.asList(
                proposal("r1", "s1", "Radverkehr in Städten", true),
                proposal("r2", "s2", "Radverkehr", true),
                proposal("r3", "s3", "Parkhäuser", true));
        Scripted chat = new Scripted(m -> {
            assertTrue("no doubtful pair here, only the card step", isCard(m));
            return MainModelChatResult.ok("{\"grund\":\"Parkhäuser sind kein Radverkehr.\",\"zuordnung\":\"keine\","
                    + "\"karte\":\"keine\"}");
        });

        Result result = merger(chat, vectors).merge(proposals, memory, null);

        assertEquals("Parkhäuser, the one head, against the cards", 1, chat.calls.get());
        Comparison card = result.getAsked().get(0);
        assertEquals(Comparison.Kind.KARTE, card.getKind());
        assertEquals("r3", card.getAId());
        assertEquals("T-rad: Radverkehr", card.getBText());
        assertEquals("", card.getBId());
        assertEquals(Relation.VERSCHIEDEN, card.getRelation());
        assertEquals(2, result.getTopics().size());
        MergedTopic known = result.getTopics().get(0);
        assertEquals("T-rad", known.getId());
        assertEquals(Mapping.GLEICH, known.getMapping());
        assertEquals("T-rad", known.getKnownId());
        assertEquals(Arrays.asList("r1", "r2"), known.getProposalIds());
        MergedTopic fresh = result.getTopics().get(1);
        assertEquals("thema-0001", fresh.getId());
        assertEquals(Mapping.NEU, fresh.getMapping());
        assertEquals("", fresh.getKnownId());

        result.applyTo(memory);
        assertEquals(Arrays.asList("T-rad", "thema-0001"), ids(memory.topics()));
        assertEquals(Arrays.asList("r1", "r2"), memory.topic("T-rad").getMergedFrom());
        assertEquals(ResearchTopic.Origin.KONZEPT, memory.topic("T-rad").getOrigin());
        assertEquals(ResearchTopic.Fence.INNEN, memory.topic("T-rad").getFence());
        assertEquals(0.4, memory.topic("T-rad").getWeight(), 1e-9);
        assertEquals(2, memory.findingsOfTopic("T-rad").size());
    }

    // ------------------------------------------------------------------------------------------- step 2

    @Test
    public void onlyTheDoubtfulCasesGoToTheModel() throws Exception {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(prompt(m).contains("Thema A:\nVerkehrsberuhigung")
                ? "{\"beziehung\":\"gleich\",\"grund\":\"Tempo 30 ist Verkehrsberuhigung.\"}"
                : "{\"beziehung\":\"verschieden\",\"grund\":\"Lärm ist ein anderes Thema.\"}"));

        Result result = merger(chat, doubtfulVectors()).merge(doubtfulProposals(), new ResearchMemory(), null);

        assertEquals("B and D are in the band, C is far from all", 2, chat.calls.get());
        assertEquals(TopicMerger.ROLE.getSlot().getName(), "phase.research.merge");
        assertEquals(4, result.getGroups());
        assertEquals(Arrays.asList("thema-0001", "thema-0002", "thema-0003"), ids2(result.getTopics()));
        assertEquals(Arrays.asList("a1", "a2", "b1"), result.getTopics().get(0).getProposalIds());
        assertEquals(Collections.singletonList("c1"), result.getTopics().get(1).getProposalIds());
        assertEquals(Collections.singletonList("d1"), result.getTopics().get(2).getProposalIds());
        assertEquals(2, result.getAsked().size());
        Comparison first = result.getAsked().get(0);
        assertEquals(Comparison.Kind.THEMA, first.getKind());
        assertEquals("b1", first.getAId());
        assertEquals("a1", first.getBId());
        assertEquals("Verkehrsberuhigung", first.getAText());
        assertEquals("Tempo 30\nAuch so genannt: Tempo-30-Zonen", first.getBText());
        assertEquals(Relation.GLEICH, first.getRelation());
        assertTrue(first.getSimilarity() >= 0.65 && first.getSimilarity() < 0.85);
        String request = prompt(chat.requests.get(0));
        assertTrue(request, request.startsWith("Buchauftrag:\n" + MISSION));
        assertTrue(request, request.contains("\"a_teil_von_b\": A ist ein Teilaspekt von B"));
        assertFalse("B is no known topic", request.contains("bekanntes Thema"));
        assertTrue(chat.schemas.get(0).contains("b_teil_von_a"));
        assertEquals(0.0, chat.temperature, 0);
    }

    @Test
    public void aPartOfAnotherTopicBecomesItsSubtopic() throws Exception {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(prompt(m).contains("Thema A:\nVerkehrsberuhigung")
                ? "{\"beziehung\":\"a_teil_von_b\",\"grund\":\"Ein Teil davon.\"}"
                : "{\"beziehung\":\"verschieden\",\"grund\":\"Anderes Thema.\"}"));

        Result result = merger(chat, doubtfulVectors()).merge(doubtfulProposals(), new ResearchMemory(), null);

        assertEquals(Arrays.asList("thema-0001", "thema-0002", "thema-0003", "thema-0004"),
                ids2(result.getTopics()));
        MergedTopic part = result.topic("thema-0002");
        assertEquals(Collections.singletonList("b1"), part.getProposalIds());
        assertEquals(Mapping.UNTERTHEMA, part.getMapping());
        assertEquals("thema-0001", part.getParentId());
        assertEquals("no known topic above it", "", part.getKnownId());
        assertEquals(Mapping.NEU, result.topic("thema-0001").getMapping());

        ResearchMemory memory = new ResearchMemory();
        result.applyTo(memory);
        assertEquals("thema-0001", memory.topic("thema-0002").getParentId());
        assertEquals("a subtopic stands on its own", 4, memory.topics().size());
    }

    @Test
    public void aSubtopicOfAKnownTopicIsATopicOfItsOwn() throws Exception {
        Table vectors = new Table().put("Radverkehr", 1, 0, 0, 0).put("Radwege an Landstraßen", 0.8, 0.6, 0, 0);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.found("thema-0007", "Radverkehr", "",
                Collections.<ResearchTopic.Finding>emptyList(), "gemma4:12b", 1L));
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(
                "{\"beziehung\":\"a_teil_von_b\",\"grund\":\"Radwege gehören zum Radverkehr.\"}"));

        Result result = merger(chat, vectors).merge(Collections.singletonList(
                proposal("w1", "s1", "Radwege an Landstraßen", true)), memory, null);

        assertEquals("a found topic is no card: the pair goes to the model, and there is no card step", 1,
                chat.calls.get());
        assertTrue(prompt(chat.requests.get(0)).contains("Thema B, ein bekanntes Thema des Buchs:\nRadverkehr"));
        assertTrue(result.getAsked().get(0).isBKnown());
        MergedTopic topic = result.getTopics().get(0);
        assertEquals("the numbers go on after the known ones", "thema-0008", topic.getId());
        assertEquals(Mapping.UNTERTHEMA, topic.getMapping());
        assertEquals("thema-0007", topic.getParentId());
        assertEquals("thema-0007", topic.getKnownId());
        result.applyTo(memory);
        assertEquals("thema-0007", memory.topic("thema-0008").getParentId());
        assertTrue(memory.topic("thema-0007").getMergedFrom().isEmpty());
    }

    @Test
    public void withoutTheCardStepACardIsComparedLikeAnyKnownTopic() throws Exception {
        Table vectors = new Table().put("Radverkehr", 1, 0, 0, 0).put("Radwege an Landstraßen", 0.8, 0.6, 0, 0);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(
                "{\"beziehung\":\"a_teil_von_b\",\"grund\":\"Radwege gehören zum Radverkehr.\"}"));
        TopicMerger merger = new TopicMerger(chat, vectors, MISSION, "de",
                Options.defaults().withParallel(1).withCardStep(false), null, CLOCK);

        Result result = merger.merge(Collections.singletonList(proposal("w1", "s1", "Radwege an Landstraßen", true)),
                memory, null);

        assertEquals(1, chat.calls.get());
        assertEquals(Comparison.Kind.THEMA, result.getAsked().get(0).getKind());
        assertTrue(prompt(chat.requests.get(0)).contains("Thema B, ein bekanntes Thema des Buchs:\nRadverkehr"));
        assertEquals("T-rad", result.topic("thema-0001").getParentId());
        assertEquals("T-rad", result.topic("thema-0001").getKnownId());
    }

    // ------------------------------------------------------------------------------------------- the card step

    @Test
    public void theCardStepPutsEachHeadOfAFamilyOnceToTheModel() throws Exception {
        ResearchMemory memory = cardMemory();
        Scripted chat = cardChat(false);

        Result result = merger(chat, cardVectors()).merge(cardProposals(), memory, null);

        assertEquals("two doubtful pairs, three heads against the cards; the cards are far from every proposal", 5,
                chat.calls.get());
        List<Comparison> cards = new ArrayList<Comparison>();
        for (Comparison comparison : result.getAsked()) {
            if (comparison.getKind() == Comparison.Kind.KARTE) {
                cards.add(comparison);
            }
        }
        assertEquals(3, cards.size());
        assertEquals(Arrays.asList("f1", "p1", "l1"), Arrays.asList(cards.get(0).getAId(), cards.get(1).getAId(),
                cards.get(2).getAId()));
        assertEquals(Arrays.asList("T-rad", "T-park", ""), Arrays.asList(cards.get(0).getBId(), cards.get(1).getBId(),
                cards.get(2).getBId()));
        assertEquals(Arrays.asList(Relation.A_TEIL_VON_B, Relation.GLEICH, Relation.VERSCHIEDEN), Arrays.asList(
                cards.get(0).getRelation(), cards.get(1).getRelation(), cards.get(2).getRelation()));
        assertEquals("T-rad: Radverkehr\nT-park: Parken", cards.get(0).getBText());
        assertEquals(0.0, cards.get(0).getSimilarity(), 1e-6);
        String request = null;
        String schema = null;
        for (int i = 0; i < chat.requests.size(); i++) {
            if (isCard(chat.requests.get(i)) && prompt(chat.requests.get(i)).contains("Thema:\nFahrradstraßen\n")) {
                request = prompt(chat.requests.get(i));
                schema = chat.schemas.get(i);
            }
        }
        assertTrue(request, request.startsWith("Buchauftrag:\n" + MISSION));
        assertTrue(request, request.contains("Karten der Mindmap (sie geben die Richtung des Buchs vor, nicht seinen "
                + "ganzen Inhalt):\nT-rad: Radverkehr\nT-park: Parken\n"));
        assertTrue(request, request.contains("\"keine\": Das Thema passt zu keiner Karte. Das ist erlaubt"));
        JsonObject properties = JsonParser.parseString(schema).getAsJsonObject().getAsJsonObject("properties");
        assertEquals("the reason first", Arrays.asList("grund", "zuordnung", "karte"),
                new ArrayList<String>(properties.keySet()));
        assertEquals("[\"T-rad\",\"T-park\",\"keine\"]", properties.getAsJsonObject("karte").getAsJsonArray("enum")
                .toString());

        MergedTopic streets = result.topic("thema-0001");
        assertEquals("Fahrradstraßen", streets.getName());
        assertEquals(Mapping.UNTERTHEMA, streets.getMapping());
        assertEquals("T-rad", streets.getParentId());
        assertEquals("T-rad", streets.getKnownId());
        MergedTopic berlin = result.topic("thema-0002");
        assertEquals("a subtopic of a head is not asked, it hangs under the card through its head", "thema-0001",
                berlin.getParentId());
        assertEquals("T-rad", berlin.getKnownId());
        MergedTopic parking = result.topic("T-park");
        assertEquals(Mapping.GLEICH, parking.getMapping());
        assertEquals(Collections.singletonList("p1"), parking.getProposalIds());
        MergedTopic residents = result.topic("thema-0003");
        assertEquals("Anwohnerparken", residents.getName());
        assertEquals("its head became the card, so the card is its parent", "T-park", residents.getParentId());
        assertEquals("T-park", residents.getKnownId());
        MergedTopic noise = result.topic("thema-0004");
        assertEquals(Mapping.NEU, noise.getMapping());
        assertEquals("", noise.getKnownId());
        assertTrue(result.getHeldBack().isEmpty());

        result.applyTo(memory);
        assertEquals(Collections.singletonList("p1"), memory.topic("T-park").getMergedFrom());
        assertEquals("T-park", memory.topic("thema-0003").getParentId());
        assertEquals("T-rad", memory.topic("thema-0001").getParentId());
        assertEquals("thema-0001", memory.topic("thema-0002").getParentId());
        assertEquals("", memory.topic("thema-0004").getParentId());
    }

    @Test
    public void aFailedCardCaseHoldsItsFamilyBackAndACardVerdictIsReused() throws Exception {
        ResearchMemory memory = cardMemory();
        Result first = merger(cardChat(true), cardVectors()).merge(cardProposals(), memory, null);

        assertEquals("the head and its subtopic wait for the next run", Arrays.asList("f1", "f2"),
                new ArrayList<String>(first.getHeldBack().keySet()));
        assertTrue(first.getHeldBack().get("f2"), first.getHeldBack().get("f2").startsWith("card step failed: "));
        assertEquals(Arrays.asList("p1", "p2", "l1"), placedIds(first));
        assertEquals("the held-back ones get no number", "Anwohnerparken", first.topic("thema-0001").getName());
        first.applyTo(memory);

        Scripted again = cardChat(false);
        Result second = merger(again, cardVectors()).merge(cardProposals(), memory, first.getAsked());

        assertEquals("the pair is reused, only the card case is asked again", 1, again.calls.get());
        assertTrue(isCard(again.requests.get(0)));
        assertEquals(Arrays.asList("f1", "f2"), placedIds(second));
        assertEquals("T-rad", second.topic(second.getTopics().get(0).getId()).getParentId());

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("reused, not asked");
        });
        List<Comparison> earlier = new ArrayList<Comparison>(first.getAsked());
        earlier.addAll(second.getAsked());
        Result third = merger(silent, cardVectors()).merge(cardProposals(), cardMemory(), earlier);
        assertEquals(0, silent.calls.get());
        assertEquals("T-rad", third.topic("thema-0001").getParentId());
        assertEquals(Mapping.GLEICH, third.topic("T-park").getMapping());
    }

    @Test
    public void eachGroupIsComparedWithSeveralCandidates() throws Exception {
        Table vectors = new Table()
                .put("Tempo 30", 1, 0, 0, 0)
                .put("Tempo-30-Zonen", 0.99, 0.141, 0, 0)
                .put("Schulwege", 0.5, 0.866, 0, 0)
                .put("Tempo 30 vor Schulen", 0.7697, 0.4563, 0.4466, 0);
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Tempo 30", true),
                proposal("a2", "s2", "Tempo-30-Zonen", true), proposal("b1", "s3", "Schulwege", true),
                proposal("c1", "s4", "Tempo 30 vor Schulen", true));
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(prompt(m).contains("Schulwege")
                ? "{\"beziehung\":\"gleich\",\"grund\":\"Es geht um sichere Schulwege.\"}"
                : "{\"beziehung\":\"verschieden\",\"grund\":\"Tempo 30 allgemein ist breiter.\"}"));

        Result result = merger(chat, vectors).merge(proposals, new ResearchMemory(), null);

        assertEquals("C against the group Tempo 30 and against Schulwege", 2, chat.calls.get());
        assertEquals(Arrays.asList("b1", "c1"), result.topic("thema-0002").getProposalIds());
        assertEquals(2, result.getTopics().size());

        Scripted one = new Scripted(chat.answers);
        Result nearestOnly = new TopicMerger(one, vectors, MISSION, "de",
                Options.defaults().withParallel(1).withCandidates(1), null, CLOCK)
                .merge(proposals, new ResearchMemory(), null);
        assertEquals("only the nearest, which is different", 1, one.calls.get());
        assertEquals(3, nearestOnly.getTopics().size());
    }

    @Test
    public void aFailedCaseMergesNothingAndIsOfferedAgain() throws Exception {
        Table vectors = new Table()
                .put("Tempo 30", 1, 0, 0, 0)
                .put("Tempo-30-Zonen", 0.999, 0.0447, 0, 0)
                .put("Verkehrsberuhigung", 0.8, 0.6, 0, 0);
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Tempo 30", true),
                proposal("a2", "s2", "Tempo-30-Zonen", true), proposal("b1", "s3", "Verkehrsberuhigung", true));
        Scripted down = new Scripted(m -> MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow"));
        ResearchMemory memory = new ResearchMemory();

        Result first = merger(down, vectors).merge(proposals, memory, null);

        assertEquals("the call and one repair", 2, down.calls.get());
        assertEquals(1, first.getFailed().size());
        Comparison failed = first.getFailed().get(0);
        assertEquals(Comparison.Status.FEHLER, failed.getStatus());
        assertNull(failed.getRelation());
        assertTrue(failed.getProblem(), failed.getProblem().contains("TIMEOUT"));
        assertEquals(Arrays.asList("", ""), failed.getAnswers());
        assertEquals(Collections.singletonList("b1"), new ArrayList<String>(first.getHeldBack().keySet()));
        assertTrue(first.getHeldBack().get("b1").contains("failed"));
        assertEquals(Arrays.asList("a1", "a2"), placedIds(first));
        first.applyTo(memory);
        assertNull("held back: not placed", memory.topic("b1"));
        assertEquals(Collections.singletonList(proposals.get(2)), TopicMerger.unplaced(proposals, memory));

        Scripted up = new Scripted(m -> MainModelChatResult.ok("{\"beziehung\":\"gleich\",\"grund\":\"Dasselbe.\"}"));
        Result second = merger(up, vectors).merge(proposals, memory, first.getAsked());

        assertEquals("the failed case is asked again, now against the placed topic", 1, up.calls.get());
        assertTrue(second.getAsked().get(0).isBKnown());
        assertEquals("thema-0001", second.getAsked().get(0).getBId());
        assertEquals(1, second.getTopics().size());
        assertEquals("thema-0001", second.getTopics().get(0).getId());
        assertEquals(Mapping.GLEICH, second.getTopics().get(0).getMapping());
        second.applyTo(memory);
        assertEquals(Arrays.asList("a1", "a2", "b1"), memory.topic("thema-0001").getMergedFrom());
    }

    @Test
    public void aVerdictOnTheSameTwoTextsIsReused() throws Exception {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(
                "{\"beziehung\":\"verschieden\",\"grund\":\"Anderes Thema.\"}"));
        Result first = merger(chat, doubtfulVectors()).merge(doubtfulProposals(), new ResearchMemory(), null);
        assertEquals(2, chat.calls.get());

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("reused, not asked");
        });
        Result second = merger(silent, doubtfulVectors()).merge(doubtfulProposals(), new ResearchMemory(),
                first.getAsked());
        assertEquals(0, silent.calls.get());
        assertTrue(second.getAsked().isEmpty());
        assertEquals(2, second.getComparisons().size());
        assertEquals(ids2(first.getTopics()), ids2(second.getTopics()));

        Table vectors = new Table().put("Lieferzonen", 1, 0, 0, 0).put("Ladezonen", 0.8, 0.6, 0, 0);
        List<TopicProposal> two = Arrays.asList(proposal("x1", "s1", "Lieferzonen", true),
                proposal("x2", "s2", "Ladezonen", true));
        Comparison judgedTheOtherWay = new Comparison(Comparison.Kind.THEMA, "x1", "Lieferzonen", "x2", "Ladezonen",
                false, 0.8, Comparison.Status.OK, Relation.A_TEIL_VON_B, "Lieferzonen sind Ladezonen.", "", null,
                "test-model", 1L, 5L);
        Result flipped = merger(silent, vectors).merge(two, new ResearchMemory(),
                Collections.singletonList(judgedTheOtherWay));
        assertEquals(0, silent.calls.get());
        assertEquals("asked as x2 to x1, so the relation is turned", Relation.B_TEIL_VON_A,
                flipped.getComparisons().get(0).getRelation());
        assertEquals(flipped.topic("thema-0002").getId(), flipped.topic("thema-0001").getParentId());
        assertEquals(Collections.singletonList("x1"), flipped.topic("thema-0001").getProposalIds());
    }

    @Test
    public void anInvalidAnswerGetsOneRepairAndFailuresInARowStopTheBatch() throws Exception {
        Table vectors = new Table().put("Verkehr", 1, 0, 0, 0, 0, 0, 0, 0, 0);
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        proposals.add(proposal("v0", "s0", "Verkehr", true));
        for (int i = 1; i <= 7; i++) {
            double[] values = new double[9];
            values[0] = 0.8;
            values[i] = 0.6;
            vectors.put("Thema " + i, values);
            proposals.add(proposal("v" + i, "s" + i, "Thema " + i, true));
        }
        AtomicInteger n = new AtomicInteger();
        Scripted repaired = new Scripted(m -> MainModelChatResult.ok(n.incrementAndGet() % 2 == 1
                ? "{\"beziehung\":\"aehnlich\"}" : "{\"beziehung\":\"verschieden\",\"grund\":\"Nein.\"}"));
        Result ok = merger(repaired, vectors).merge(proposals, new ResearchMemory(), null);
        assertEquals("seven cases, each with one repair", 14, repaired.calls.get());
        Comparison one = ok.getAsked().get(0);
        assertTrue(one.isOk());
        assertEquals(2, one.getAnswers().size());
        List<ChatMessage> repair = repaired.requests.get(1);
        assertTrue(repair.get(repair.size() - 1).getContent().startsWith("Deine Antwort war ungültig: "
                + "\"beziehung\" must be gleich"));
        assertEquals(8, ok.getTopics().size());

        Scripted down = new Scripted(m -> MainModelChatResult.ok("kein json"));
        Result stopped = merger(down, vectors).merge(proposals, new ResearchMemory(), null);
        assertEquals("five cases with a repair each, then the rest is not asked", 10, down.calls.get());
        assertEquals(7, stopped.getFailed().size());
        assertTrue(stopped.getFailed().get(6).getProblem().startsWith("not asked: the model failed 5 cases"));
        assertEquals(7, stopped.getHeldBack().size());
        assertEquals(Collections.singletonList("v0"), placedIds(stopped));
    }

    // ------------------------------------------------------------------------------------------- questions

    @Test
    public void questionsOfTheSameMeaningBecomeOneThatKeepsAllOrigins() throws Exception {
        String unfaelle = "Wie viele Unfälle passieren an Kreuzungen?";
        String geschehen = "Wie viele Unfälle geschehen an Kreuzungen?";
        String gefaehrlich = "Welche Kreuzungen sind am gefährlichsten?";
        String meisten = "Wo passieren die meisten Kreuzungsunfälle?";
        Table vectors = new Table()
                .put("Unfälle an Kreuzungen", 1, 0, 0, 0)
                .put("Kreuzungsunfälle", 0.99, 0.141, 0, 0)
                .put("Sichere Kreuzungen", 0.98, 0, 0.199, 0)
                .put(unfaelle, 1, 0, 0, 0)
                .put(geschehen, 0.95, 0.312, 0, 0)
                .put(gefaehrlich, 0, 0, 1, 0)
                .put(meisten, 0, 0, 0.88, 0.475);
        List<TopicProposal> proposals = Arrays.asList(
                proposal("p1", "s1", "Unfälle an Kreuzungen", "", true, unfaelle),
                proposal("p2", "s2", "Kreuzungsunfälle", "", true, unfaelle, gefaehrlich),
                proposal("p3", "s3", "Sichere Kreuzungen", "", true, geschehen, meisten));
        Scripted chat = new Scripted(m -> MainModelChatResult.ok("{\"beziehung\":\"gleich\",\"grund\":\"Beide fragen "
                + "nach den gefährlichsten Kreuzungen.\"}"));
        ResearchMemory memory = new ResearchMemory();

        Result result = merger(chat, vectors).merge(proposals, memory, null);

        assertEquals("only the one doubtful pair of questions", 1, chat.calls.get());
        String request = prompt(chat.requests.get(0));
        assertTrue(request, request.startsWith("Thema: Unfälle an Kreuzungen\n\nFrage A: " + meisten
                + "\nFrage B: " + gefaehrlich));
        assertTrue(chat.schemas.get(0).contains("\"enum\":[\"gleich\",\"verschieden\"]"));
        assertEquals(Comparison.Kind.FRAGE, result.getAsked().get(0).getKind());
        Map<String, MergedQuestion> byId = new HashMap<String, MergedQuestion>();
        for (MergedQuestion question : result.getQuestions()) {
            byId.put(question.getId(), question);
            assertEquals("thema-0001", question.getTopicId());
        }
        assertEquals(Arrays.asList("p1-f1", "p2-f1", "p2-f2", "p3-f1", "p3-f2"), questionIds(result));
        assertEquals("", byId.get("p1-f1").getInto());
        assertEquals("p1-f1", byId.get("p2-f1").getInto());
        assertEquals(MergedQuestion.How.WORTLAUT, byId.get("p2-f1").getHow());
        assertEquals("", byId.get("p2-f2").getInto());
        assertEquals("p1-f1", byId.get("p3-f1").getInto());
        assertEquals(MergedQuestion.How.AEHNLICHKEIT, byId.get("p3-f1").getHow());
        assertEquals("p2-f2", byId.get("p3-f2").getInto());
        assertEquals(MergedQuestion.How.MODELL, byId.get("p3-f2").getHow());
        assertEquals(Arrays.asList("p1-f1", "p2-f2"), result.getTopics().get(0).getQuestionIds());

        result.applyTo(memory);
        assertEquals(Arrays.asList("p1-f1", "p2-f2"), questionIds(memory.questions()));
        assertEquals(Arrays.asList("p2-f1", "p3-f1"), memory.question("p1-f1").getMergedFrom());
        assertEquals(Collections.singletonList("p3-f2"), memory.question("p2-f2").getMergedFrom());
        assertEquals(ResearchQuestion.Origin.FINDER, memory.question("p3-f2").getOrigin());
        assertEquals("thema-0001", memory.currentTopic(memory.question("p3-f2").getTopicId()).getId());
        assertEquals(Arrays.asList("p1-f1", "p2-f2"), memory.evidenceOfTopic("thema-0001").getQuestionIds());
    }

    @Test
    public void knownQuestionsTakeQuestionsInButNeverMergeWithEachOther() throws Exception {
        String k1 = "Wie sicher sind Radwege?";
        String k2 = "Wie sicher sind Radwege an Straßen?";
        String own = "Wie sicher sind Radwege in Städten?";
        Table vectors = new Table().put("Radverkehr", 1, 0, 0, 0)
                .put(k1, 1, 0, 0, 0).put(k2, 0.95, 0.312, 0, 0).put(own, 0.99, 0.141, 0, 0);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", k1, "T-rad", ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("K2", k2, "T-rad", ResearchQuestion.Origin.KONZEPT, 1L));
        Scripted chat = new Scripted(m -> {
            throw new AssertionError("all certain");
        });

        Result result = merger(chat, vectors).merge(Collections.singletonList(
                proposal("r1", "s1", "Radverkehr", "", true, own)), memory, null);

        MergedQuestion question = result.getQuestions().get(0);
        assertEquals("K1", question.getInto());
        assertEquals(MergedQuestion.How.AEHNLICHKEIT, question.getHow());
        assertEquals("T-rad", question.getTopicId());
        assertEquals(Arrays.asList("K1", "K2"), result.getTopics().get(0).getQuestionIds());
        result.applyTo(memory);
        assertEquals(Collections.singletonList("r1-f1"), memory.question("K1").getMergedFrom());
        assertTrue(memory.question("K2").getMergedFrom().isEmpty());
        assertEquals(Arrays.asList("K1", "K2"), questionIds(memory.questions()));
    }

    // ------------------------------------------------------------------------------------------- over runs

    @Test
    public void topicIdsStayWhenMoreProposalsCome() throws Exception {
        Table vectors = new Table()
                .put("Carsharing", 1, 0, 0, 0)
                .put("Carsharing-Angebote", 0.97, 0.243, 0, 0)
                .put("Carsharing in Quartieren", 0.95, 0, 0.312, 0)
                .put("Busspuren", 0, 0, 0, 1);
        List<TopicProposal> first = Arrays.asList(proposal("x1", "s1", "Carsharing", true),
                proposal("x2", "s2", "Carsharing-Angebote", true));
        Scripted chat = new Scripted(m -> {
            throw new AssertionError("all certain");
        });
        ResearchMemory memory = new ResearchMemory();
        TopicMerger merger = merger(chat, vectors);
        merger.merge(first, memory, null).applyTo(memory);
        assertEquals(Collections.singletonList("thema-0001"), ids(memory.topics()));

        List<TopicProposal> all = new ArrayList<TopicProposal>(first);
        all.add(proposal("x3", "s3", "Carsharing in Quartieren", true));
        all.add(proposal("y1", "s3", "Busspuren", true));
        Result second = merger.merge(all, memory, null);

        assertEquals(Arrays.asList("x3", "y1"), placedIds(second));
        assertEquals(Arrays.asList("thema-0001", "thema-0002"), ids2(second.getTopics()));
        assertEquals(Mapping.GLEICH, second.topic("thema-0001").getMapping());
        assertEquals(Collections.singletonList("x3"), second.topic("thema-0001").getProposalIds());
        assertEquals(Mapping.NEU, second.topic("thema-0002").getMapping());
        second.applyTo(memory);
        assertEquals(Arrays.asList("x1", "x2", "x3"), memory.topic("thema-0001").getMergedFrom());
        assertEquals(Arrays.asList("thema-0001", "thema-0002"), ids(memory.topics()));
    }

    @Test
    public void aMergeCanBeTakenBack() throws Exception {
        Table vectors = new Table().put("Lastenräder", 1, 0, 0, 0).put("Lastenradverkehr", 0.97, 0.243, 0, 0);
        ResearchMemory memory = new ResearchMemory();
        merger(new Scripted(m -> null), vectors).merge(Arrays.asList(proposal("l1", "s1", "Lastenräder", true),
                proposal("l2", "s2", "Lastenradverkehr", true)), memory, null).applyTo(memory);
        assertEquals(2, memory.findingsOfTopic("thema-0001").size());

        memory.updateTopic(memory.topic("thema-0001").withoutMerged(Collections.singletonList("l2"), 9L));

        assertEquals("l2", memory.currentTopic("l2").getId());
        assertEquals("in the order the records came in", Arrays.asList("l2", "thema-0001"), ids(memory.topics()));
        assertEquals("its finding went with it", 1, memory.findingsOfTopic("thema-0001").size());
        assertEquals("s2", memory.findingsOfTopic("l2").get(0).getSourceId());
    }

    @Test
    public void proposalsAreGivenOnceAndEmbeddedInBatches() throws Exception {
        Table vectors = new Table().put("Tempo 30", 1, 0).put("Radwege", 0, 1);
        TopicMerger merger = merger(new Scripted(m -> null), vectors);
        try {
            merger.merge(Arrays.asList(proposal("d1", "s1", "Tempo 30", true), proposal("d1", "s2", "Radwege", true)),
                    new ResearchMemory(), null);
            fail("one id twice");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("d1"));
        }
        Result empty = merger.merge(Collections.<TopicProposal>emptyList(), new ResearchMemory(), null);
        assertTrue(empty.getTopics().isEmpty());

        Table broken = new Table();
        try {
            merger(new Scripted(m -> null), broken).merge(Collections.singletonList(
                    proposal("e1", "s1", "Tempo 30", true)), new ResearchMemory(), null);
            fail("no vector");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("the embedding model failed"));
        }
    }

    // ------------------------------------------------------------------------------------------- the answer

    @Test
    public void theCardAnswerNamesAnAssignmentAndForTwoOfThemACard() {
        List<String> cards = Arrays.asList("T-rad", "T-park");
        TopicMerger.Verdict part = TopicMerger.parseCard("{\"grund\":\"Ein  Teil.\",\"zuordnung\":\" Teil_von \","
                + "\"karte\":\"T-rad\"}", cards);
        assertEquals("", part.problem);
        assertEquals(Relation.A_TEIL_VON_B, part.relation);
        assertEquals("T-rad", part.card);
        assertEquals("Ein Teil.", part.reason);
        TopicMerger.Verdict none = TopicMerger.parseCard("{\"grund\":\"Nichts.\",\"zuordnung\":\"keine\","
                + "\"karte\":\"T-rad\"}", cards);
        assertEquals("keine names no card, whatever karte says", Relation.VERSCHIEDEN, none.relation);
        assertEquals("", none.card);
        assertTrue(TopicMerger.parseCard("{\"zuordnung\":\"gleich\",\"karte\":\"keine\"}", cards).problem
                .startsWith("\"karte\" must name one of the cards (T-rad, T-park)"));
        assertTrue(TopicMerger.parseCard("{\"zuordnung\":\"aehnlich\",\"karte\":\"T-rad\"}", cards).problem
                .startsWith("\"zuordnung\" must be"));
        assertEquals("the answer is no JSON", TopicMerger.parseCard("kein json", cards).problem);
        String english = TopicMerger.cardPrompt("A book on city traffic.", "Cycle streets", "T-rad: Cycling", false);
        assertTrue(english, english.contains("Cards of the mind map (they give the book its direction, not all of its "
                + "content):\nT-rad: Cycling\n\nTopic:\nCycle streets"));
        assertTrue(english.contains("\"keine\": The topic fits no card. That is allowed"));
    }

    @Test
    public void theAnswerIsOneOfTheRelationsWithAReason() {
        TopicMerger.Verdict verdict = TopicMerger.parse("{\"beziehung\":\" Gleich \",\"grund\":\"Beide  meinen "
                + "Tempo 30.\"}", true);
        assertEquals("", verdict.problem);
        assertEquals(Relation.GLEICH, verdict.relation);
        assertEquals("Beide meinen Tempo 30.", verdict.reason);
        assertTrue(TopicMerger.parse("{\"beziehung\":\"a_teil_von_b\",\"grund\":\"x\"}", false).problem
                .contains("gleich or verschieden"));
        assertEquals("the answer is no JSON", TopicMerger.parse("kein json", true).problem);
        assertTrue(TopicMerger.parse("{\"beziehung\":\"aehnlich\"}", true).problem.startsWith("\"beziehung\""));
        assertEquals(Relation.B_TEIL_VON_A, Relation.A_TEIL_VON_B.flipped());
        assertEquals(Relation.A_TEIL_VON_B, Relation.B_TEIL_VON_A.flipped());
        assertEquals(Relation.GLEICH, Relation.GLEICH.flipped());

        JsonObject schema = JsonParser.parseString(TopicMerger.schema(true)).getAsJsonObject();
        assertEquals(4, schema.getAsJsonObject("properties").getAsJsonObject("beziehung").getAsJsonArray("enum")
                .size());
        assertEquals(2, JsonParser.parseString(TopicMerger.schema(false)).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("beziehung").getAsJsonArray("enum").size());
        assertEquals(ThinkingMode.OFF, TopicMerger.ROLE.getThinking());

        String english = TopicMerger.topicPrompt("A book on city traffic.", "Speed limits", "Traffic calming", true,
                false);
        assertTrue(english, english.contains("Topic B, a known topic of the book:\nTraffic calming"));
        assertTrue(english.contains("\"verschieden\": A and B mean different subjects"));
        assertTrue(TopicMerger.questionPrompt("Radwege", "Wie breit?", "Wie schmal?", true)
                .contains("eine ist enger oder weiter als die andere"));
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** B (in the band to A), C (far from all), D (in the band to A) beside the group A of two. */
    private static Table doubtfulVectors() {
        return new Table()
                .put("Tempo 30", 1, 0, 0, 0)
                .put("Tempo-30-Zonen", 0.99, 0.141, 0, 0)
                .put("Verkehrsberuhigung", 0.8, 0.6, 0, 0)
                .put("Parkgebühren", 0, 0, 1, 0)
                .put("Lärmschutz an Straßen", 0.78, 0, 0.6257, 0);
    }

    private static List<TopicProposal> doubtfulProposals() {
        return Arrays.asList(proposal("a1", "s1", "Tempo 30", true), proposal("a2", "s2", "Tempo-30-Zonen", true),
                proposal("b1", "s3", "Verkehrsberuhigung", true), proposal("c1", "s4", "Parkgebühren", true),
                proposal("d1", "s5", "Lärmschutz an Straßen", true));
    }

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);

    private static TopicMerger merger(Scripted chat, Table vectors) {
        return new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null, CLOCK);
    }

    /** A request of the card step. */
    private static boolean isCard(List<ChatMessage> messages) {
        return TopicMerger.CARD_SYSTEM_DE.equals(messages.get(0).getContent());
    }

    /** Two cards, far from every proposal of {@link #cardProposals()}. */
    private static ResearchMemory cardMemory() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addTopic(ResearchTopic.ofCard("T-park", "Parken", "anchor-concept-parken", 0, 1L));
        return memory;
    }

    /** Two families of two (head and part) and one topic alone; the cards point elsewhere. */
    private static Table cardVectors() {
        return new Table()
                .put("Radverkehr", 0, 0, 0, 0, 1, 0, 0)
                .put("Parken", 0, 0, 0, 0, 0, 1, 0)
                .put("Fahrradstraßen", 1, 0, 0, 0, 0, 0, 0)
                .put("Fahrradstraßen in Berlin", 0.8, 0.6, 0, 0, 0, 0, 0)
                .put("Parkraumbewirtschaftung", 0, 0, 1, 0, 0, 0, 0)
                .put("Anwohnerparken", 0, 0, 0.8, 0.6, 0, 0, 0)
                .put("Bahnlärm", 0, 0, 0, 0, 0, 0, 1);
    }

    private static List<TopicProposal> cardProposals() {
        return Arrays.asList(proposal("f1", "s1", "Fahrradstraßen", true),
                proposal("f2", "s2", "Fahrradstraßen in Berlin", true),
                proposal("p1", "s3", "Parkraumbewirtschaftung", true), proposal("p2", "s4", "Anwohnerparken", true),
                proposal("l1", "s5", "Bahnlärm", true));
    }

    /** Parts for the pairs; for the cards: streets part of T-rad, the parking scheme the card T-park, noise none. */
    private static Scripted cardChat(boolean streetsCardFails) {
        return new Scripted(m -> {
            String request = prompt(m);
            if (!isCard(m)) {
                return MainModelChatResult.ok("{\"beziehung\":\"a_teil_von_b\",\"grund\":\"Ein Teil davon.\"}");
            }
            if (request.contains("Thema:\nFahrradstraßen\n")) {
                return streetsCardFails ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow")
                        : MainModelChatResult.ok("{\"grund\":\"Fahrradstraßen sind Radverkehr.\","
                        + "\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\"}");
            }
            if (request.contains("Thema:\nParkraumbewirtschaftung\n")) {
                return MainModelChatResult.ok("{\"grund\":\"Das ist die Karte Parken.\",\"zuordnung\":\"gleich\","
                        + "\"karte\":\"T-park\"}");
            }
            return MainModelChatResult.ok("{\"grund\":\"Keine Karte.\",\"zuordnung\":\"keine\","
                    + "\"karte\":\"keine\"}");
        });
    }

    private static TopicProposal proposal(String id, String source, String name, boolean finding) {
        return proposal(id, source, name, "", finding);
    }

    private static TopicProposal proposal(String id, String source, String name, String description,
                                          boolean finding, String... questions) {
        return new TopicProposal(id, source, "https://" + source + ".example/verkehr", source + "-p1",
                finding ? "In " + source + " steht etwas zu " + name : "",
                finding ? FindingCheck.GEPRUEFT : FindingCheck.NICHT_GEFUNDEN, name, description,
                Arrays.asList(questions), "gemma4:e2b", 1, 5L);
    }

    private static String prompt(List<ChatMessage> messages) {
        return messages.get(1).getContent();
    }

    private static List<String> ids(List<ResearchTopic> topics) {
        List<String> ids = new ArrayList<String>();
        for (ResearchTopic topic : topics) {
            ids.add(topic.getId());
        }
        return ids;
    }

    private static List<String> ids2(List<MergedTopic> topics) {
        List<String> ids = new ArrayList<String>();
        for (MergedTopic topic : topics) {
            ids.add(topic.getId());
        }
        return ids;
    }

    private static List<String> placedIds(Result result) {
        List<String> ids = new ArrayList<String>();
        for (TopicProposal proposal : result.getPlaced()) {
            ids.add(proposal.getId());
        }
        return ids;
    }

    private static List<String> questionIds(Result result) {
        List<String> ids = new ArrayList<String>();
        for (MergedQuestion question : result.getQuestions()) {
            ids.add(question.getId());
        }
        return ids;
    }

    private static List<String> questionIds(List<ResearchQuestion> questions) {
        List<String> ids = new ArrayList<String>();
        for (ResearchQuestion question : questions) {
            ids.add(question.getId());
        }
        return ids;
    }

    /** Vectors by text, as a stand-in for bge-m3; a text without one is an error of the embedding model. */
    static final class Table implements EmbeddingPort {
        final Map<String, float[]> vectors = new HashMap<String, float[]>();
        final AtomicInteger calls = new AtomicInteger();

        Table put(String text, double... values) {
            float[] vector = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                vector[i] = (float) values[i];
            }
            vectors.put(text, vector);
            return this;
        }

        @Override
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            calls.incrementAndGet();
            assertEquals(Role.DOCUMENT, role);
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (String text : texts) {
                float[] vector = vectors.get(text);
                if (vector == null) {
                    throw new IllegalArgumentException("no vector for '" + text + "'");
                }
                out.add(new EmbeddingVector("test-embed", "test-embed", vector));
            }
            return out;
        }
    }
}
