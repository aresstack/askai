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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

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

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------------------------------- robustness

    /** The architect's run c15c of 22.11.: an embedding timeout after 39 minutes lost every answer of the model. */
    @Test
    public void everyAnswerIsRecordedTheMomentItIsThere() throws Exception {
        List<Comparison> recorded = Collections.synchronizedList(new ArrayList<Comparison>());

        Result result = cardMerger(cardChat(false)).merge(cardProposals(), cardMemory(), null, recorded::add);

        assertFalse(result.getAsked().isEmpty());
        assertEquals(result.getAsked(), recorded);

        try {
            cardMerger(cardChat(false)).merge(cardProposals(), cardMemory(), null, comparison -> {
                throw new IOException("disk full");
            });
            fail("a recorder that fails stops the merge");
        } catch (IllegalStateException expected) {
            assertTrue(String.valueOf(expected.getCause()),
                    expected.getCause() instanceof java.io.UncheckedIOException);
        }
    }

    @Test
    public void aRunThatBreaksOffLosesNoAnswer() throws Exception {
        int all = cardMerger(cardChat(false)).merge(cardProposals(), cardMemory(), null).getAsked().size();
        assertTrue(all >= 3);
        TopicMergeLog log = new TopicMergeLog(tmp.newFolder("project"));
        log.open();
        AtomicInteger answers = new AtomicInteger();
        try {
            cardMerger(cardChat(false)).merge(cardProposals(), cardMemory(), null, comparison -> {
                if (answers.incrementAndGet() == 3) {
                    throw new IOException("the run breaks off");
                }
                log.append(comparison);
            });
            fail("the run breaks off at the third answer");
        } catch (IllegalStateException expected) {
            assertEquals(3, answers.get());
        }

        Scripted again = cardChat(false);
        TopicMergeLog reopened = new TopicMergeLog(log.logFile().getParentFile().getParentFile());
        List<Comparison> earlier = reopened.open();
        Result result = cardMerger(again).merge(cardProposals(), cardMemory(), earlier, reopened::append);

        assertEquals(2, earlier.size());
        assertEquals("the two answers written before the break are reused", all - 2, again.calls.get());
        assertEquals(all - 2, result.getAsked().size());
        assertEquals(all, reopened.open().size());
    }

    @Test
    public void aFailedEmbeddingRequestIsTriedAgain() throws Exception {
        Table table = cardVectors();
        AtomicInteger calls = new AtomicInteger();
        EmbeddingPort slowOnce = (role, texts) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("java.net.SocketTimeoutException: Read timed out");
            }
            return table.embed(role, texts);
        };
        TopicMerger merger = new TopicMerger(cardChat(false), slowOnce, MISSION, "de", Options.defaults()
                .withParallel(1).withSubtopics(1, 2), null, CLOCK);
        merger.embedPauseMillis = 0;

        Result result = merger.merge(cardProposals(), cardMemory(), null);

        assertFalse(result.getTopics().isEmpty());
        assertEquals(1 + table.calls.get(), calls.get());

        AtomicInteger tries = new AtomicInteger();
        TopicMerger down = new TopicMerger(cardChat(false), (role, texts) -> {
            tries.incrementAndGet();
            throw new IllegalStateException("connection refused");
        }, MISSION, "de", Options.defaults(), null, CLOCK);
        down.embedPauseMillis = 0;
        try {
            down.merge(cardProposals(), cardMemory(), null);
            fail("the embedding model is down");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("failed 3 times"));
            assertEquals(TopicMerger.EMBED_ATTEMPTS, tries.get());
        }
    }

    @Test
    public void everyTextIsEmbeddedBeforeTheFirstModelCall() throws Exception {
        ResearchMemory memory = cardMemory();
        memory.addQuestion(ResearchQuestion.open("K1", "Wie sicher sind Fahrradstraßen?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        Table table = cardVectors()
                .put("Wie sicher sind Fahrradstraßen?", 0, 0, 0, 0.6, 0.8, 0, 0)
                .put("Wo gibt es Fahrradstraßen?", 0, 0, 0, 1, 0, 0, 0)
                .put("Wer ordnet Fahrradstraßen an?", 0, 0, 0, 0.8, 0, 0.6, 0)
                .put("Was kostet ein Parkausweis?", 0, 0, 0, 0, 0, 0.6, 0.8);
        List<String> events = Collections.synchronizedList(new ArrayList<String>());
        EmbeddingPort watched = (role, texts) -> {
            events.add("embed");
            return table.embed(role, texts);
        };
        Scripted inner = cardChat(false);
        Scripted chat = new Scripted(m -> {
            events.add("chat");
            return inner.answers.apply(m);
        });
        List<TopicProposal> proposals = Arrays.asList(
                proposal("f1", "s1", "Fahrradstraßen", "", true, "Wo gibt es Fahrradstraßen?"),
                proposal("f2", "s2", "Fahrradstraßen in Berlin", "", true, "Wer ordnet Fahrradstraßen an?"),
                proposal("p1", "s3", "Parkraumbewirtschaftung", "", true, "Was kostet ein Parkausweis?"),
                proposal("p2", "s4", "Anwohnerparken", true), proposal("l1", "s5", "Bahnlärm", true));

        Result result = new TopicMerger(chat, watched, MISSION, "de", Options.defaults().withParallel(1)
                .withSubtopics(1, 2), null, CLOCK).merge(proposals, memory, null);

        assertFalse(result.getQuestions().isEmpty());
        assertTrue(events.toString(), events.contains("chat"));
        assertTrue("no embedding after the first call: " + events,
                events.lastIndexOf("embed") < events.indexOf("chat"));
    }

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
                .put("Parkhäuser", 0, 0, 0, 1)
                .put("Wie sicher ist Radverkehr?", 0, 0, 1, 0);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0.4, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie sicher ist Radverkehr?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        List<TopicProposal> proposals = Arrays.asList(
                proposal("r1", "s1", "Radverkehr in Städten", true),
                proposal("r2", "s2", "Radverkehr", true),
                proposal("r3", "s3", "Parkhäuser", true));
        Scripted chat = new Scripted(m -> {
            assertTrue("no doubtful pair here, only the card step", isCard(m));
            return MainModelChatResult.ok("{\"grund\":\"Parkhäuser sind kein Radverkehr.\","
                    + "\"zuordnung\":\"keine\",\"karte\":\"keine\",\"frage\":\"keine\"}");
        });

        Result result = merger(chat, vectors).merge(proposals, memory, null);

        assertEquals("Parkhäuser, the one head, against the cards", 1, chat.calls.get());
        Comparison card = result.getAsked().get(0);
        assertEquals(Comparison.Kind.KARTE, card.getKind());
        assertEquals("r3", card.getAId());
        assertEquals("T-rad: Radverkehr\n  F1: Wie sicher ist Radverkehr?", card.getBText());
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

        Result result = cardMerger(chat).merge(cardProposals(), memory, null);

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
        assertEquals("T-rad: Radverkehr\n  F1: Wie sicher sind Fahrradstraßen?\nT-park: Parken\n"
                + "  F2: Was kostet Anwohnerparken?", cards.get(0).getBText());
        assertEquals("", cards.get(0).getNote());
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
                + "ganzen Inhalt), jede mit ihren Fragen:\nT-rad: Radverkehr\n  F1: Wie sicher sind Fahrradstraßen?\n"
                + "T-park: Parken\n  F2: Was kostet Anwohnerparken?\n"));
        assertTrue(request, request.contains("Sieht eine der Karten das Thema schon vor?"));
        assertTrue(request, request.contains("\"teil_von\": Das Thema beantwortet oder vertieft eine bestimmte Frage "
                + "einer Karte."));
        assertTrue(request, request.contains("\"keine\": Das Thema bedient keine Frage einer Karte, gehört nur zum "
                + "selben Sachgebiet wie eine Karte, berührt eine Karte nur oder verdient einen eigenen Platz im Buch. "
                + "Das ist erlaubt"));
        assertTrue(request, request.contains("Bei \"gleich\" und \"teil_von\" nennst du die Frage der gewählten "
                + "Karte, die das Thema beantwortet oder vertieft. Dasselbe Sachgebiet reicht nicht: Findest du keine "
                + "solche Frage, ist die Antwort \"keine\"."));
        assertTrue(request, request.contains("Im Zweifel \"keine\". Ob das Thema ins Buch gehört, entscheidet ein "
                + "anderer Schritt"));
        assertFalse("the card step does not ask whether the topic belongs in the book", request.contains("gegenstand"));
        JsonObject properties = JsonParser.parseString(schema).getAsJsonObject().getAsJsonObject("properties");
        assertEquals("the reason first", Arrays.asList("grund", "zuordnung", "karte", "frage"),
                new ArrayList<String>(properties.keySet()));
        assertEquals("[\"T-rad\",\"T-park\",\"keine\"]", properties.getAsJsonObject("karte").getAsJsonArray("enum")
                .toString());
        assertEquals("[\"F1\",\"F2\",\"keine\"]", properties.getAsJsonObject("frage").getAsJsonArray("enum")
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
        Result first = cardMerger(cardChat(true)).merge(cardProposals(), memory, null);

        assertEquals("the head and its subtopic wait for the next run", Arrays.asList("f1", "f2"),
                new ArrayList<String>(first.getHeldBack().keySet()));
        assertTrue(first.getHeldBack().get("f2"), first.getHeldBack().get("f2").startsWith("card step failed: "));
        assertEquals(Arrays.asList("p1", "p2", "l1"), placedIds(first));
        assertEquals("the held-back ones get no number", "Anwohnerparken", first.topic("thema-0001").getName());
        first.applyTo(memory);

        Scripted again = cardChat(false);
        Result second = cardMerger(again).merge(cardProposals(), memory, first.getAsked());

        assertEquals("the pair is reused, only the card case is asked again", 1, again.calls.get());
        assertTrue(isCard(again.requests.get(0)));
        assertEquals(Arrays.asList("f1", "f2"), placedIds(second));
        assertEquals("T-rad", second.topic(second.getTopics().get(0).getId()).getParentId());

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("reused, not asked");
        });
        List<Comparison> earlier = new ArrayList<Comparison>(first.getAsked());
        earlier.addAll(second.getAsked());
        Result third = cardMerger(silent).merge(cardProposals(), cardMemory(), earlier);
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
                Options.defaults().withParallel(1).withCandidates(1).withParentStep(false, 0.65), null, CLOCK)
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
        List<TopicProposal> three = Arrays.asList(proposal("x1", "s1", "Lieferzonen", true),
                proposal("x2", "s2", "Ladezonen", true), proposal("x3", "s3", "Ladezonen", true));
        Comparison judgedTheOtherWay = new Comparison(Comparison.Kind.THEMA, "x2", "Ladezonen", "x1", "Lieferzonen",
                false, 0.8, Comparison.Status.OK, Relation.B_TEIL_VON_A, "Lieferzonen sind Ladezonen.", "", null,
                "test-model", 1L, 5L);
        Result flipped = merger(silent, vectors).merge(three, new ResearchMemory(),
                Collections.singletonList(judgedTheOtherWay));
        assertEquals(0, silent.calls.get());
        assertEquals("asked as x1 to the group of x2, so the relation is turned", Relation.A_TEIL_VON_B,
                flipped.getComparisons().get(0).getRelation());
        assertEquals("Ladezonen comes from two sources: an established topic", flipped.topic("thema-0002").getId(),
                flipped.topic("thema-0001").getParentId());
        assertEquals(Collections.singletonList("x1"), flipped.topic("thema-0001").getProposalIds());

        Comparison olderPrompt = new Comparison(Comparison.Kind.THEMA, "x1", "Lieferzonen", "x2", "Ladezonen",
                false, 0.8, Comparison.Status.OK, Relation.GLEICH, "Gleich.", "", null, "test-model", 1L, 5L, 1);
        Scripted asked = new Scripted(m -> MainModelChatResult.ok(
                "{\"grund\":\"Lieferzonen sind ein Teil der Ladezonen.\",\"beziehung\":\"a_teil_von_b\"}"));
        merger(asked, vectors).merge(three, new ResearchMemory(), Collections.singletonList(olderPrompt));
        assertEquals("a verdict under older prompts is asked again", 1, asked.calls.get());
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
    public void theCardAnswerNamesAnAssignmentAndForTwoOfThemACardAndItsQuestion() {
        List<String> cards = Arrays.asList("T-rad", "T-park");
        Map<String, List<String>> labels = new HashMap<String, List<String>>();
        labels.put("T-rad", Arrays.asList("F1", "F2"));
        labels.put("T-park", Collections.singletonList("F3"));
        TopicMerger.Verdict part = TopicMerger.parseCard("{\"grund\":\"Ein  Teil.\","
                + "\"zuordnung\":\" Teil_von \",\"karte\":\"T-rad\",\"frage\":\" f2 \"}", cards, labels);
        assertEquals("", part.problem);
        assertEquals(Relation.A_TEIL_VON_B, part.relation);
        assertEquals("T-rad", part.card);
        assertEquals("F2", part.question);
        assertEquals("", part.note);
        assertEquals("Ein Teil.", part.reason);
        TopicMerger.Verdict none = TopicMerger.parseCard("{\"grund\":\"Nichts.\","
                + "\"zuordnung\":\"keine\",\"karte\":\"T-rad\",\"frage\":\"F1\"}", cards, labels);
        assertEquals("keine names no card, whatever karte says", Relation.VERSCHIEDEN, none.relation);
        assertEquals("", none.card);
        assertEquals("", none.note);
        TopicMerger.Verdict extra = TopicMerger.parseCard("{\"grund\":\"Gehört zum Radverkehr.\","
                + "\"gegenstand\":\"nein\",\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}",
                cards, labels);
        assertEquals("a field the schema does not ask for changes nothing", Relation.A_TEIL_VON_B, extra.relation);
        assertEquals("T-rad", extra.card);
        TopicMerger.Verdict field = TopicMerger.parseCard("{\"grund\":\"Auch Recht.\","
                + "\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F3\"}", cards, labels);
        assertEquals("a question of another card: the card's field alone counts as keine", Relation.VERSCHIEDEN,
                field.relation);
        assertEquals("", field.card);
        assertEquals("teil_von T-rad without a question of that card (frage: F3): counts as keine", field.note);
        assertEquals("the reason stays the model's", "Auch Recht.", field.reason);
        TopicMerger.Verdict same = TopicMerger.parseCard("{\"grund\":\"Die Karte selbst.\","
                + "\"zuordnung\":\"gleich\",\"karte\":\"T-park\",\"frage\":\"keine\"}", cards, labels);
        assertEquals("the same as a card needs a question of it too", Relation.VERSCHIEDEN, same.relation);
        assertEquals("gleich T-park without a question of that card (frage: none): counts as keine", same.note);
        TopicMerger.Verdict missing = TopicMerger.parseCard("{\"grund\":\"Teil.\","
                + "\"zuordnung\":\"teil_von\",\"karte\":\"T-park\"}", cards, labels);
        assertEquals(Relation.VERSCHIEDEN, missing.relation);
        assertEquals("teil_von T-park without a question of that card (frage: none): counts as keine", missing.note);
        TopicMerger.Verdict labelled = TopicMerger.parseCard("{\"grund\":\"Teil.\","
                + "\"zuordnung\":\"teil_von\",\"karte\":\"T-park\",\"frage\":\"F3: Was kostet es?\"}", cards,
                labels);
        assertEquals("the label before the colon counts", "F3", labelled.question);
        assertTrue(TopicMerger.parseCard("{\"zuordnung\":\"gleich\",\"karte\":\"keine\",\"frage\":\"F1\"}",
                cards, labels).problem.startsWith("\"karte\" must name one of the cards (T-rad, T-park)"));
        assertTrue(TopicMerger.parseCard("{\"zuordnung\":\"aehnlich\",\"karte\":\"T-rad\"}",
                cards, labels).problem.startsWith("\"zuordnung\" must be"));
        assertEquals("the answer is no JSON", TopicMerger.parseCard("kein json", cards, labels).problem);
        String english = TopicMerger.cardPrompt("A book on city traffic.", "Cycle streets",
                "T-rad: Cycling\n  Q1: How safe are cycle streets?", false);
        assertTrue(english, english.contains("Cards of the mind map (they give the book its direction, not all of its "
                + "content), each with its questions:\nT-rad: Cycling\n  Q1: How safe are cycle streets?\n\nTopic:\n"
                + "Cycle streets"));
        assertTrue(english, english.contains("Does one of the cards already foresee the topic?"));
        assertTrue(english, english.contains("\"teil_von\": The topic answers or deepens a particular question of a "
                + "card."));
        assertTrue(english, english.contains("\"keine\": The topic serves no question of a card, only shares a "
                + "card's field, only touches a card or deserves a place of its own in the book. That is allowed: a "
                + "book may hold topics no card foresees."));
        assertTrue(english, english.contains("For \"gleich\" and \"teil_von\" you name the question of the chosen "
                + "card the topic answers or deepens. The same field is not enough"));
        assertTrue(english, english.contains("When in doubt, \"keine\". Whether the topic belongs in the book is "
                + "decided by another step"));
        JsonObject schema = JsonParser.parseString(TopicMerger.cardSchema(cards, Arrays.asList("F1", "F2", "F3")))
                .getAsJsonObject();
        assertEquals(Arrays.asList("grund", "zuordnung", "karte", "frage"), new ArrayList<String>(
                schema.getAsJsonObject("properties").keySet()));
        assertEquals("[\"grund\",\"zuordnung\",\"karte\",\"frage\"]", schema.getAsJsonArray("required").toString());
        assertEquals("[\"gleich\",\"teil_von\",\"keine\"]", schema.getAsJsonObject("properties")
                .getAsJsonObject("zuordnung").getAsJsonArray("enum").toString());
        assertEquals("[\"F1\",\"F2\",\"F3\",\"keine\"]", schema.getAsJsonObject("properties")
                .getAsJsonObject("frage").getAsJsonArray("enum").toString());
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
        assertEquals("the reason first", Arrays.asList("grund", "beziehung"),
                new ArrayList<String>(schema.getAsJsonObject("properties").keySet()));
        assertEquals(2, JsonParser.parseString(TopicMerger.schema(false)).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("beziehung").getAsJsonArray("enum").size());
        assertEquals(ThinkingMode.OFF, TopicMerger.ROLE.getThinking());

        String english = TopicMerger.topicPrompt("A book on city traffic.", "Speed limits", "Traffic calming", true,
                false);
        assertTrue(english, english.contains("Topic B, a known topic of the book:\nTraffic calming"));
        assertTrue(english.contains("\"verschieden\": A and B mean different subjects"));
        assertTrue(english.contains("When in doubt, \"verschieden\"."));
        assertTrue(TopicMerger.questionPrompt("Radwege", "Wie breit?", "Wie schmal?", true)
                .contains("eine ist enger oder weiter als die andere"));
    }

    // ------------------------------------------------------------------------------------------- rules of 22.11.

    @Test
    public void aTopicFedInAgainIsPlacedWithoutTheModel() throws Exception {
        Table vectors = new Table()
                .put("Tempo 30", 1, 0, 0, 0)
                .put("Tempo-30-Zonen", 0.99, 0.141, 0, 0)
                .put("Verkehrsberuhigung", 0.8, 0.6, 0, 0)
                .put("Parkgebühren", 0, 0, 1, 0)
                .put("Lärmschutz an Straßen", 0, 0.6, 0, 0.8)
                .put("Straßenlärm", 0.6, 0.4, 0, 0.6928);
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Tempo 30", true),
                proposal("a2", "s2", "Tempo-30-Zonen", true), proposal("b1", "s3", "Verkehrsberuhigung", true),
                proposal("c1", "s4", "Parkgebühren", true), proposal("d1", "s5", "Lärmschutz an Straßen", true),
                proposal("d2", "s6", "Lärmschutz an Straßen", true), proposal("e1", "s7", "Straßenlärm", true));
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(prompt(m).contains("Thema A:\nVerkehrsberuhigung")
                || prompt(m).contains("Thema A:\nStraßenlärm\n\nThema B:\nLärmschutz")
                ? "{\"grund\":\"Dasselbe.\",\"beziehung\":\"gleich\"}"
                : "{\"grund\":\"Etwas anderes.\",\"beziehung\":\"verschieden\"}"));
        ResearchMemory memory = new ResearchMemory();
        Result first = merger(chat, vectors).merge(proposals, memory, null);
        assertEquals(3, chat.calls.get());
        first.applyTo(memory);
        assertEquals(Arrays.asList("a1", "a2", "b1"), memory.topic("thema-0001").getMergedFrom());
        assertEquals(Arrays.asList("d1", "d2", "e1"), memory.topic("thema-0003").getMergedFrom());
        List<TopicProposal> twins = new ArrayList<TopicProposal>();
        for (TopicProposal proposal : proposals) {
            twins.add(proposal(proposal.getId() + "-z", proposal.getSourceId(), proposal.getName(), true));
        }

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("a certain match asks nothing");
        });
        Result twin = merger(silent, vectors).merge(twins, memory, first.getAsked());

        assertEquals("Straßenlärm meets its own topic for sure; its doubtful pair with the Tempo topic, never asked "
                + "before, is not asked now", 0, silent.calls.get());
        for (TopicProposal proposal : proposals) {
            String original = memory.currentTopic(proposal.getId()).getId();
            assertEquals(proposal.getId(), original, topicOf(twin, proposal.getId() + "-z").getId());
            assertEquals(Mapping.GLEICH, topicOf(twin, proposal.getId() + "-z").getMapping());
        }
    }

    @Test
    public void aPartHangsOnlyUnderAnEstablishedTopicAndNotTooDeep() throws Exception {
        Table vectors = chainVectors();
        List<TopicProposal> proposals = chainProposals();
        Scripted chat = chainChat();

        Result result = merger(chat, vectors).merge(proposals, new ResearchMemory(), null);

        assertEquals("three links of the chain and the parking pair", 4, chat.calls.get());
        MergedTopic lanes = topicOf(result, "q1");
        MergedTopic width = topicOf(result, "r1");
        MergedTopic marking = topicOf(result, "m1");
        assertEquals(topicOf(result, "p1").getId(), lanes.getParentId());
        assertEquals("Radwege comes from two sources, so it takes a part", lanes.getId(), width.getParentId());
        assertEquals("a third level is too deep: the marking stays a topic of its own", Mapping.NEU,
                marking.getMapping());
        assertEquals("", marking.getParentId());
        assertEquals("Abstellanlagen has one source: no established topic, nothing hangs under it", Mapping.NEU,
                topicOf(result, "t1").getMapping());

        Scripted flat = chainChat();
        Result oneLevel = new TopicMerger(flat, vectors, MISSION, "de", Options.defaults().withParallel(1)
                .withSubtopics(2, 1), null, CLOCK).merge(proposals, new ResearchMemory(), null);
        assertEquals(topicOf(oneLevel, "p1").getId(), topicOf(oneLevel, "q1").getParentId());
        assertEquals("one level: the width stays on top", "", topicOf(oneLevel, "r1").getParentId());
        assertEquals("and takes the marking", topicOf(oneLevel, "r1").getId(), topicOf(oneLevel, "m1").getParentId());

        Result anyParent = new TopicMerger(chainChat(), vectors, MISSION, "de", Options.defaults().withParallel(1)
                .withSubtopics(1, 2), null, CLOCK).merge(proposals, new ResearchMemory(), null);
        assertEquals("with one source enough, the parking scheme takes the bike parking",
                topicOf(anyParent, "u1").getId(), topicOf(anyParent, "t1").getParentId());
    }

    @Test
    public void theCardStepShowsTheCardsQuestionsAndKeepsDeepFamiliesOut() throws Exception {
        ResearchMemory memory = cardMemory();
        Table vectors = new Table()
                .put("Radverkehr", 0, 0, 0, 0, 0, 1, 0)
                .put("Parken", 0, 0, 0, 0, 0, 0, 1)
                .put("Fahrradstraßen", 1, 0, 0, 0, 0, 0, 0)
                .put("Fahrradstraßen in Berlin", 0.8, 0.6, 0, 0, 0, 0, 0)
                .put("Fahrradstraßen in Berlin-Mitte", 0.48, 0.6, 0.64, 0, 0, 0, 0)
                .put("Tarifvertrag für Busfahrer", 0, 0, 0, 0, 1, 0, 0)
                .put("Wie sicher sind Fahrradstraßen?", 0, 0, 0, 1, 0, 0, 0)
                .put("Was kostet Anwohnerparken?", 0, 0, 0, 0.6, 0.8, 0, 0);
        List<TopicProposal> proposals = Arrays.asList(proposal("f1", "s1", "Fahrradstraßen", true),
                proposal("f2", "s2", "Fahrradstraßen", true), proposal("b1", "s3", "Fahrradstraßen in Berlin", true),
                proposal("b2", "s4", "Fahrradstraßen in Berlin", true),
                proposal("m1", "s5", "Fahrradstraßen in Berlin-Mitte", true),
                proposal("t1", "s6", "Tarifvertrag für Busfahrer", true));
        Scripted chat = new Scripted(m -> {
            if (!isCard(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Ein Teil davon.\",\"beziehung\":\"a_teil_von_b\"}");
            }
            if (prompt(m).contains("Thema:\nTarifvertrag")) {
                return MainModelChatResult.ok("{\"grund\":\"Keine Karte fragt nach Tarifen.\","
                        + "\"zuordnung\":\"keine\",\"karte\":\"keine\",\"frage\":\"keine\"}");
            }
            return MainModelChatResult.ok("{\"grund\":\"Das beantwortet die Frage nach Fahrradstraßen.\","
                    + "\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, memory, null);

        assertEquals("two links of the chain, two heads against the cards", 4, chat.calls.get());
        String request = null;
        for (List<ChatMessage> messages : chat.requests) {
            if (isCard(messages)) {
                request = prompt(messages);
            }
        }
        assertTrue(request, request.contains("T-rad: Radverkehr\n  F1: Wie sicher sind Fahrradstraßen?\nT-park: "
                + "Parken\n  F2: Was kostet Anwohnerparken?\n"));
        MergedTopic streets = topicOf(result, "f1");
        assertEquals("under the card its family would reach three levels: it stays a topic of its own", Mapping.NEU,
                streets.getMapping());
        assertEquals("", streets.getKnownId());
        assertEquals(streets.getId(), topicOf(result, "b1").getParentId());
        assertEquals(topicOf(result, "b1").getId(), topicOf(result, "m1").getParentId());
        MergedTopic wages = topicOf(result, "t1");
        assertEquals("no card foresees it: a topic of its own, it stays in", Mapping.NEU, wages.getMapping());
        assertEquals("", wages.getParentId());
        assertEquals("", wages.getKnownId());

        Result deeper = new TopicMerger(new Scripted(chat.answers), vectors, MISSION, "de", Options.defaults()
                .withParallel(1).withSubtopics(2, 3), null, CLOCK).merge(proposals, cardMemory(), null);
        assertEquals("three levels allowed: the family hangs under the card", "T-rad",
                topicOf(deeper, "f1").getParentId());
        assertEquals("T-rad", topicOf(deeper, "m1").getKnownId());
    }

    @Test
    public void theCardStepLeavesOutCardsWithoutQuestionsAndACardWithoutItsQuestionCountsAsNone() throws Exception {
        Table vectors = new Table()
                .put("Radverkehr", 0, 1, 0, 0)
                .put("Busverkehr", 0, 0, 1, 0)
                .put("Radschnellwege", 1, 0, 0, 0)
                .put("Wie sicher sind Radwege?", 0, 0, 0, 1);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addTopic(ResearchTopic.ofCard("T-bus", "Busverkehr", "anchor-concept-busverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie sicher sind Radwege?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        Scripted chat = new Scripted(m -> MainModelChatResult.ok("{\"grund\":\"Radschnellwege sind Radverkehr.\","
                + "\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"keine\"}"));
        List<TopicProposal> proposals = Collections.singletonList(proposal("w1", "s1", "Radschnellwege", true));

        Result result = merger(chat, vectors).merge(proposals, memory, null);

        assertEquals(1, chat.calls.get());
        assertTrue(isCard(chat.requests.get(0)));
        String request = prompt(chat.requests.get(0));
        assertTrue(request, request.contains("jede mit ihren Fragen:\nT-rad: Radverkehr\n  F1: Wie sicher sind "
                + "Radwege?\n\nThema:\nRadschnellwege"));
        assertFalse("a card without questions is not listed", request.contains("T-bus"));
        assertEquals("[\"T-rad\",\"keine\"]", JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject()
                .getAsJsonObject("properties").getAsJsonObject("karte").getAsJsonArray("enum").toString());
        Comparison card = result.getAsked().get(0);
        assertEquals("the card's subject alone draws nothing in", Relation.VERSCHIEDEN, card.getRelation());
        assertEquals("", card.getBId());
        assertEquals("teil_von T-rad without a question of that card (frage: none): counts as keine",
                card.getNote());
        assertEquals("Radschnellwege sind Radverkehr.", card.getReason());
        assertEquals(Mapping.NEU, topicOf(result, "w1").getMapping());
        assertEquals("", topicOf(result, "w1").getParentId());

        ResearchMemory bare = new ResearchMemory();
        bare.addTopic(ResearchTopic.ofCard("T-bus", "Busverkehr", "anchor-concept-busverkehr", 0, 1L));
        Scripted silent = new Scripted(m -> {
            throw new AssertionError("no card has a question: no card step");
        });
        Result none = merger(silent, vectors).merge(proposals, bare, null);
        assertEquals(0, silent.calls.get());
        assertEquals(Mapping.NEU, topicOf(none, "w1").getMapping());
    }

    // ------------------------------------------------------------------------------------------- the parent step

    @Test
    public void headsThatStandApartButAreSimilarGetANewParent() throws Exception {
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(isParent(m)
                ? "{\"grund\":\"Drei davon beschreiben, wie ein Radweg gebaut ist.\",\"oberthema\":\"Radwege\","
                + "\"beschreibung\":\"Wie Radwege gebaut und ausgestattet sind.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                : "{\"grund\":\"Zwei Seiten eines Radwegs.\",\"beziehung\":\"verschieden\"}"));

        Result result = parentMerger(chat).merge(parentProposals(), new ResearchMemory(), null);

        assertEquals("six doubtful pairs, then one case of the parent step", 7, chat.calls.get());
        List<ChatMessage> request = chat.requests.get(6);
        assertEquals(TopicMerger.PARENT_SYSTEM_DE, request.get(0).getContent());
        String text = prompt(request);
        assertTrue(text, text.startsWith("Buchauftrag:\n" + MISSION + "\n\nThemen aus der Recherche (jedes steht "
                + "bisher für sich, unter keinem anderen Thema):\nT1: Radwegbreite\nT2: Radwegbelag\n"
                + "T3: Radwegmarkierung\nT4: Radwegbeleuchtung\n\n"));
        assertFalse("Bahnlärm is far from all of them", text.contains("Bahnlärm"));
        assertTrue(text, text.contains("Gehören mindestens drei dieser Themen zu einem gemeinsamen Oberthema"));
        assertTrue(text, text.contains("Ist eines der Themen selbst schon dieses Oberthema, nennst du seine Kennung"));
        assertTrue(text, text.contains("Im Zweifel \"keins\". Ob ein Thema ins Buch gehört, entscheidet ein anderer "
                + "Schritt"));
        JsonObject properties = JsonParser.parseString(chat.schemas.get(6)).getAsJsonObject()
                .getAsJsonObject("properties");
        assertEquals("the reason first", Arrays.asList("grund", "oberthema", "beschreibung", "themen"),
                new ArrayList<String>(properties.keySet()));
        assertEquals("[\"T1\",\"T2\",\"T3\",\"T4\"]", properties.getAsJsonObject("themen").getAsJsonObject("items")
                .getAsJsonArray("enum").toString());
        assertEquals(TopicMerger.PARENT_ANSWER_TOKENS, chat.tokens);

        Comparison asked = result.getAsked().get(6);
        assertEquals(Comparison.Kind.OBERTHEMA, asked.getKind());
        assertEquals("r1", asked.getAId());
        assertEquals(Comparison.PARENT_MARK, asked.getBText());
        assertEquals("", asked.getBId());
        assertEquals(Relation.A_TEIL_VON_B, asked.getRelation());
        assertEquals("new parent \u201eRadwege\u201c of T1, T2, T3", asked.getNote());
        assertEquals(0.60, asked.getSimilarity(), 1e-9);
        assertEquals(5, asked.getVersion());

        assertEquals(Arrays.asList("thema-0001", "thema-0002", "thema-0003", "thema-0004", "thema-0005",
                "thema-0006"), ids2(result.getTopics()));
        MergedTopic parent = result.topic("thema-0006");
        assertTrue(parent.isFormed());
        assertEquals("Radwege", parent.getName());
        assertEquals("Wie Radwege gebaut und ausgestattet sind.", parent.getDescription());
        assertEquals(Mapping.NEU, parent.getMapping());
        assertEquals("", parent.getParentId());
        assertTrue(parent.getProposalIds().isEmpty());
        assertTrue(parent.getFindings().isEmpty());
        assertTrue(parent.getQuestionIds().isEmpty());
        for (String part : new String[]{"r1", "r2", "r3"}) {
            assertEquals("thema-0006", topicOf(result, part).getParentId());
            assertEquals(Mapping.UNTERTHEMA, topicOf(result, part).getMapping());
            assertFalse(topicOf(result, part).isFormed());
        }
        assertEquals("the head the parent left out stays at the top; one head alone is asked no more", "",
                topicOf(result, "r4").getParentId());
        assertEquals("", topicOf(result, "l1").getParentId());
        assertTrue(result.getHeldBack().isEmpty());

        ResearchMemory memory = new ResearchMemory();
        result.applyTo(memory);
        ResearchTopic stored = memory.topic("thema-0006");
        assertEquals(ResearchTopic.Origin.GEFUNDEN, stored.getOrigin());
        assertEquals(ResearchTopic.Fence.UNGEPRUEFT, stored.getFence());
        assertTrue(stored.getMergedFrom().isEmpty());
        assertEquals("", stored.getParentId());
        assertEquals("thema-0006", memory.topic("thema-0001").getParentId());
        assertEquals(0, memory.findingsOfTopic("thema-0006").size());

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("reused, not asked");
        });
        Result again = parentMerger(silent).merge(parentProposals(), new ResearchMemory(), result.getAsked());
        assertEquals(0, silent.calls.get());
        assertEquals("thema-0006", topicOf(again, "r2").getParentId());
        assertTrue(again.topic("thema-0006").isFormed());
    }

    @Test
    public void aHeadCanBeTheParentOfItsSiblings() throws Exception {
        for (String named : new String[]{"T2", "Radwegbelag"}) {
            Scripted chat = new Scripted(m -> MainModelChatResult.ok(isParent(m)
                    ? "{\"grund\":\"Der Belag trägt die anderen.\",\"oberthema\":\"" + named + "\","
                    + "\"beschreibung\":\"\",\"themen\":[\"T1\",\"T2\",\"T3\",\"T4\"]}"
                    : "{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}"));

            Result result = parentMerger(chat).merge(parentProposals(), new ResearchMemory(), null);

            assertEquals(7, chat.calls.get());
            Comparison asked = result.getAsked().get(6);
            assertEquals("the head that became the parent", "r2", asked.getBId());
            assertEquals((named.equals("T2") ? "" : "named like T2, so T2 is the parent; ")
                    + "T2 is the parent of T1, T3, T4", asked.getNote());
            assertEquals("no topic is formed", 5, result.getTopics().size());
            for (String part : new String[]{"r1", "r3", "r4"}) {
                assertEquals(named, "thema-0002", topicOf(result, part).getParentId());
            }
            assertEquals("", topicOf(result, "r2").getParentId());
            assertEquals(Mapping.NEU, topicOf(result, "r2").getMapping());
        }
    }

    @Test
    public void theHeadsAParentLeftOutAreAskedAgainAndANewParentGoesThroughTheCardStep() throws Exception {
        Table vectors = new Table()
                .put("Radverkehr", 0, 0, 0, 0, 0, 0, 0, 1)
                .put("Wie gut sind Radwege ausgebaut?", 0, 0, 0, 0, 0, 0, 0, 1);
        String[] names = {"Radwegbreite", "Radwegbelag", "Radwegmarkierung", "Radwegbeleuchtung", "Radwegreinigung",
                "Radwegwinterdienst"};
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (int i = 0; i < names.length; i++) {
            double[] values = new double[8];
            values[0] = SIBLING_SHARED;
            values[i + 1] = SIBLING_OWN;
            vectors.put(names[i], values);
            proposals.add(proposal("h" + (i + 1), "s" + (i + 1), names[i], true));
        }
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                return MainModelChatResult.ok(request.contains("T6:")
                        ? "{\"grund\":\"Der Bau.\",\"oberthema\":\"Bau der Radwege\",\"beschreibung\":\"Wie "
                        + "Radwege gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                        : "{\"grund\":\"Der Betrieb.\",\"oberthema\":\"Betrieb der Radwege\",\"beschreibung\":"
                        + "\"Wie Radwege nutzbar bleiben.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (isCard(m)) {
                return MainModelChatResult.ok(request.contains("Thema:\nBau der Radwege\n")
                        ? "{\"grund\":\"Der Ausbau ist die Frage der Karte.\",\"zuordnung\":\"teil_von\","
                        + "\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                        : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            return MainModelChatResult.ok("{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, memory, null);

        assertEquals("12 pairs, 6 heads against the cards, 2 parent cases, 2 parents against the cards, the parent at "
                + "the top against the one under the card", 23, chat.calls.get());
        List<Comparison> across = noted(result.getAsked(), TopicMerger.NOTE_ACROSS);
        assertEquals(1, across.size());
        assertEquals("oberthema/h4", across.get(0).getAId());
        assertEquals("oberthema/h1", across.get(0).getBId());
        assertEquals(Relation.VERSCHIEDEN, across.get(0).getRelation());
        List<String> rounds = new ArrayList<String>();
        for (Comparison comparison : result.getAsked()) {
            if (comparison.getKind() == Comparison.Kind.OBERTHEMA) {
                rounds.add(comparison.getAText());
            }
        }
        assertEquals(Arrays.asList("T1: Radwegbreite\nT2: Radwegbelag\nT3: Radwegmarkierung\nT4: Radwegbeleuchtung\n"
                + "T5: Radwegreinigung\nT6: Radwegwinterdienst", "T1: Radwegbeleuchtung\nT2: Radwegreinigung\n"
                + "T3: Radwegwinterdienst"), rounds);
        String parentCard = null;
        for (List<ChatMessage> messages : chat.requests) {
            if (isCard(messages) && prompt(messages).contains("Thema:\nBau der Radwege\n")) {
                parentCard = prompt(messages);
            }
        }
        assertTrue(parentCard, parentCard.contains("Thema:\nBau der Radwege\nWorum es geht: Wie Radwege gebaut "
                + "werden.\nTeilthemen: Radwegbreite; Radwegbelag; Radwegmarkierung\n\n"));
        MergedTopic built = result.topic("thema-0007");
        assertEquals("Bau der Radwege", built.getName());
        assertTrue(built.isFormed());
        assertEquals(Mapping.UNTERTHEMA, built.getMapping());
        assertEquals("T-rad", built.getParentId());
        assertEquals("T-rad", built.getKnownId());
        assertEquals("thema-0007", topicOf(result, "h1").getParentId());
        assertEquals("T-rad", topicOf(result, "h1").getKnownId());
        MergedTopic running = result.topic("thema-0008");
        assertEquals("Betrieb der Radwege", running.getName());
        assertEquals(Mapping.NEU, running.getMapping());
        assertEquals("", running.getKnownId());
        assertEquals("thema-0008", topicOf(result, "h6").getParentId());
        assertEquals("", topicOf(result, "h6").getKnownId());

        result.applyTo(memory);
        assertEquals("T-rad", memory.topic("thema-0007").getParentId());
        assertEquals("thema-0007", memory.topic("thema-0001").getParentId());
    }

    @Test
    public void aFailedParentCaseHoldsItsHeadsBackForTheNextRun() throws Exception {
        Scripted down = new Scripted(m -> isParent(m)
                ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow")
                : MainModelChatResult.ok("{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}"));
        ResearchMemory memory = new ResearchMemory();

        Result first = parentMerger(down).merge(parentProposals(), memory, null);

        assertEquals("six pairs, the parent case and its repair", 8, down.calls.get());
        assertEquals(Arrays.asList("r1", "r2", "r3", "r4"), new ArrayList<String>(first.getHeldBack().keySet()));
        assertTrue(first.getHeldBack().get("r1"), first.getHeldBack().get("r1").startsWith("parent step failed: "
                + "model call failed (TIMEOUT)"));
        assertEquals(Collections.singletonList("l1"), placedIds(first));
        first.applyTo(memory);

        Scripted up = new Scripted(m -> MainModelChatResult.ok(isParent(m)
                ? "{\"grund\":\"Teile eines Radwegs.\",\"oberthema\":\"Radwege\",\"beschreibung\":\"Wie Radwege "
                + "gebaut sind.\",\"themen\":[\"T1\",\"T2\",\"T3\",\"T4\"]}"
                : "{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}"));
        Result second = parentMerger(up).merge(parentProposals(), memory, first.getAsked());

        assertEquals("the pairs are reused, the parent case is asked again", 1, up.calls.get());
        assertTrue(isParent(up.requests.get(0)));
        assertTrue(second.getHeldBack().isEmpty());
        MergedTopic parent = topicOf(second, "r1").getParentId().isEmpty() ? null
                : second.topic(topicOf(second, "r1").getParentId());
        assertTrue(parent != null && parent.isFormed());
        assertEquals("Radwege", parent.getName());
        assertEquals(parent.getId(), topicOf(second, "r4").getParentId());
    }

    @Test
    public void aHeadWithoutRoomForALevelAboveItStaysOutOfTheParentStep() throws Exception {
        Table vectors = parentVectors().put("Radwegbreite in Berlin", 0.8 * SIBLING_SHARED, 0.8 * SIBLING_OWN, 0, 0,
                0, 0, 0.6);
        for (String name : new String[]{"Radwegbreite", "Radwegbelag", "Radwegmarkierung", "Radwegbeleuchtung",
                "Bahnlärm"}) {
            float[] six = vectors.vectors.get(name);
            vectors.vectors.put(name, Arrays.copyOf(six, 7));
        }
        List<TopicProposal> proposals = new ArrayList<TopicProposal>(parentProposals());
        proposals.add(proposal("c1", "s6", "Radwegbreite in Berlin", true));
        Scripted chat = new Scripted(m -> MainModelChatResult.ok(isParent(m)
                ? "{\"grund\":\"Ausstattung.\",\"oberthema\":\"Ausstattung der Radwege\",\"beschreibung\":\"\","
                + "\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                : prompt(m).contains("Thema A:\nRadwegbreite in Berlin\n")
                ? "{\"grund\":\"Ein Fall davon.\",\"beziehung\":\"a_teil_von_b\"}"
                : "{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}"));

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1)
                .withSubtopics(1, 1), null, CLOCK).merge(proposals, new ResearchMemory(), null);

        assertEquals("seven pairs, one parent case", 8, chat.calls.get());
        Comparison asked = result.getAsked().get(7);
        assertEquals(Comparison.Kind.OBERTHEMA, asked.getKind());
        assertEquals("Radwegbreite has a subtopic: one level allowed, no room above it",
                "T1: Radwegbelag\nT2: Radwegmarkierung\nT3: Radwegbeleuchtung", asked.getAText());
        assertEquals(topicOf(result, "r1").getId(), topicOf(result, "c1").getParentId());
        assertEquals("", topicOf(result, "r1").getParentId());
        String parent = topicOf(result, "r2").getParentId();
        assertTrue(result.topic(parent).isFormed());
        assertEquals(parent, topicOf(result, "r4").getParentId());
    }

    @Test
    public void looseSubtopicsOfACardGetANarrowerParentThatStaysUnderTheCard() throws Exception {
        double shared = SIBLING_SHARED;
        double own = SIBLING_OWN;
        Table vectors = new Table()
                .put("Radwegbreite", shared, own, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                .put("Radwegbelag", shared, 0, own, 0, 0, 0, 0, 0, 0, 0, 0)
                .put("Radwegmarkierung", shared, 0, 0, own, 0, 0, 0, 0, 0, 0, 0)
                .put("Radwegbeleuchtung", shared, 0, 0, 0, own, 0, 0, 0, 0, 0, 0)
                .put("Parkgebühren", 0, 0, 0, 0, 0, shared, own, 0, 0, 0, 0)
                .put("Parkdauer", 0, 0, 0, 0, 0, shared, 0, own, 0, 0, 0)
                .put("Parkleitsysteme", 0, 0, 0, 0, 0, shared, 0, 0, own, 0, 0)
                .put("Radverkehr", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)
                .put("Wie gut sind Radwege ausgebaut?", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)
                .put("Parken", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
                .put("Was kostet Anwohnerparken?", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        List<TopicProposal> proposals = Arrays.asList(proposal("r1", "s1", "Radwegbreite", true),
                proposal("r2", "s2", "Radwegbelag", true), proposal("r3", "s3", "Radwegmarkierung", true),
                proposal("r4", "s4", "Radwegbeleuchtung", true), proposal("k1", "s5", "Parkgebühren", true),
                proposal("k2", "s6", "Parkdauer", true), proposal("k3", "s7", "Parkleitsysteme", true));
        ResearchMemory memory = twoCards();
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isCard(m)) {
                String card = request.substring(request.indexOf("\n\nThema:\n")).contains("Radweg") ? "T-rad"
                        : "T-park";
                return MainModelChatResult.ok("{\"grund\":\"Eine Frage der Karte.\",\"zuordnung\":\"teil_von\","
                        + "\"karte\":\"" + card + "\",\"frage\":\"" + questionLabel(request, card) + "\"}");
            }
            if (isParent(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Drei davon gehören zur Planung.\",\"oberthema\":"
                        + "\"Planung\",\"beschreibung\":\"Wie geplant wird.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            return MainModelChatResult.ok("{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, memory, null);

        assertEquals("nine pairs, seven heads against the cards, one case of siblings under each card", 18,
                chat.calls.get());
        List<Comparison> cases = new ArrayList<Comparison>();
        for (Comparison comparison : result.getAsked()) {
            if (comparison.getKind() == Comparison.Kind.OBERTHEMA) {
                cases.add(comparison);
            }
        }
        assertEquals(2, cases.size());
        assertEquals("Unter: Radverkehr\nT1: Radwegbreite\nT2: Radwegbelag\nT3: Radwegmarkierung\n"
                + "T4: Radwegbeleuchtung", cases.get(0).getAText());
        assertEquals(5, cases.get(0).getVersion());
        assertEquals("Unter: Parken\nT1: Parkgebühren\nT2: Parkdauer\nT3: Parkleitsysteme", cases.get(1).getAText());
        String siblings = null;
        for (List<ChatMessage> messages : chat.requests) {
            if (isParent(messages) && prompt(messages).contains("Radwegbreite")) {
                siblings = prompt(messages);
            }
        }
        assertTrue(siblings, siblings.contains("Themen aus der Recherche, alle schon unter dem Thema „Radverkehr"
                + "“ (jedes steht dort bisher für sich):\nT1: Radwegbreite\nT2: Radwegbelag\nT3: Radwegmarkierung\n"
                + "T4: Radwegbeleuchtung\n\nGehören mindestens drei dieser Themen zu einem gemeinsamen Oberthema "
                + "innerhalb von „Radverkehr“, das im Buch einen eigenen Abschnitt tragen könnte?\n"));
        assertTrue(siblings, siblings.contains("- „Radverkehr“ selbst ist hier kein Oberthema: Das "
                + "Oberthema ist enger als „Radverkehr“ und fasst nur einen Teil seiner Themen zusammen.\n"));
        assertFalse(siblings, siblings.contains("Unter: Radverkehr"));

        MergedTopic planning = null;
        for (MergedTopic topic : result.getTopics()) {
            if (topic.isFormed()) {
                assertTrue("one parent is formed", planning == null);
                planning = topic;
            }
        }
        assertEquals("Planung", planning.getName());
        assertEquals("T-rad", planning.getParentId());
        assertEquals("T-rad", planning.getKnownId());
        assertEquals(Mapping.UNTERTHEMA, planning.getMapping());
        for (String part : new String[]{"r1", "r2", "r3"}) {
            assertEquals(planning.getId(), topicOf(result, part).getParentId());
            assertEquals("T-rad", topicOf(result, part).getKnownId());
        }
        assertEquals("the sibling the parent left out stays under the card", "T-rad",
                topicOf(result, "r4").getParentId());
        for (String part : new String[]{"k1", "k2", "k3"}) {
            assertEquals("„Planung“ stands under the other card: here it counts as keins", "T-park",
                    topicOf(result, part).getParentId());
        }
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(0, (int) counts.get("freeHeads"));
        assertEquals(2, (int) counts.get("siblingTopics"));
        assertEquals(7, (int) counts.get("siblingHeads"));
        assertEquals(7, (int) counts.get("siblingInCases"));
        assertEquals(1, (int) counts.get("formedUnder"));
        assertEquals(0, (int) counts.get("formedTop"));

        result.applyTo(memory);
        assertEquals("T-rad", memory.topic(planning.getId()).getParentId());
        assertEquals(planning.getId(), memory.topic(topicOf(result, "r2").getId()).getParentId());

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("reused, not asked");
        });
        Result again = new TopicMerger(silent, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, twoCards(), result.getAsked());
        assertEquals(0, silent.calls.get());
        assertEquals(planning.getId(), topicOf(again, "r1").getParentId());
    }

    /** Twenty heads of one subject: one group at 0.60, more heads than one case of the parent step takes. */
    private static final String[] RADWEG = {"Radwegbreite", "Radwegbelag", "Radwegmarkierung", "Radwegbeleuchtung",
            "Radwegreinigung", "Radwegwinterdienst", "Radwegampeln", "Radwegkreuzungen", "Radwegschilder",
            "Radwegbegrünung", "Radwegentwässerung", "Radwegbordsteine", "Radwegpoller", "Radwegrampen", "Radwegtunnel",
            "Radwegbrücken", "Radwegrastplätze", "Radwegzählstellen", "Radwegbaustellen", "Radwegsanierung"};

    private static List<TopicProposal> radwegProposals(Table vectors) {
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (int i = 0; i < RADWEG.length; i++) {
            double[] values = new double[RADWEG.length + 1];
            values[0] = SIBLING_SHARED;
            values[i + 1] = SIBLING_OWN;
            vectors.put(RADWEG[i], values);
            proposals.add(proposal("h" + (i + 1), "s" + (i + 1), RADWEG[i], true));
        }
        return proposals;
    }

    /**
     * Every case of the parent step gets a parent over all its heads, named "Radwege" in the case with Radwegbreite
     * and "Radwegbetrieb" in the others; two parents are answered with {@code pairAnswer} ({@code null}: the call
     * fails), everything else is "verschieden".
     */
    private static Scripted twoParentsChat(final String pairAnswer) {
        return new Scripted(m -> {
            if (isParent(m)) {
                List<String> labels = new ArrayList<String>();
                for (String line : prompt(m).split("\n")) {
                    if (line.matches("T\\d+: .*")) {
                        labels.add("\"" + line.substring(0, line.indexOf(':')) + "\"");
                    }
                }
                String name = prompt(m).contains("Radwegbreite") ? "Radwege" : "Radwegbetrieb";
                return MainModelChatResult.ok("{\"grund\":\"Alles Radwege.\",\"oberthema\":\"" + name
                        + "\",\"beschreibung\":\"Wie Radwege gebaut und betrieben werden.\",\"themen\":["
                        + String.join(",", labels) + "]}");
            }
            if (prompt(m).split("Teilthemen: ", -1).length == 3) {
                return pairAnswer == null ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow")
                        : MainModelChatResult.ok("{\"grund\":\"Derselbe Gegenstand.\",\"beziehung\":\""
                        + pairAnswer + "\"}");
            }
            return MainModelChatResult.ok("{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}");
        });
    }

    private static List<MergedTopic> formed(Result result) {
        List<MergedTopic> formed = new ArrayList<MergedTopic>();
        for (MergedTopic topic : result.getTopics()) {
            if (topic.isFormed()) {
                formed.add(topic);
            }
        }
        return formed;
    }

    private static List<Comparison> noted(List<Comparison> comparisons, String note) {
        List<Comparison> out = new ArrayList<Comparison>();
        for (Comparison comparison : comparisons) {
            if (note.equals(comparison.getNote())) {
                out.add(comparison);
            }
        }
        return out;
    }

    @Test
    public void twoParentsOfOnePlaceTheModelCallsTheSameBecomeOneWithAllParts() throws Exception {
        Table vectors = new Table();
        List<TopicProposal> proposals = radwegProposals(vectors);

        Result result = new TopicMerger(twoParentsChat("gleich"), vectors, MISSION, "de",
                Options.defaults().withParallel(1), null, CLOCK).merge(proposals, new ResearchMemory(), null);

        Map<String, Integer> counts = result.getParentCounts();
        assertEquals("two names, two parents at the top", 2, (int) counts.get("formedTop"));
        assertEquals(1, (int) counts.get("parentPairs"));
        assertEquals(1, (int) counts.get("parentsMerged"));
        List<MergedTopic> formed = formed(result);
        assertEquals("one parent is left", 1, formed.size());
        for (int i = 1; i <= RADWEG.length; i++) {
            assertEquals("h" + i, formed.get(0).getId(), topicOf(result, "h" + i).getParentId());
        }
        List<Comparison> pairs = noted(result.getAsked(), TopicMerger.NOTE_PAIR);
        assertEquals(1, pairs.size());
        Comparison pair = pairs.get(0);
        assertEquals(Comparison.Kind.THEMA, pair.getKind());
        assertEquals(Relation.GLEICH, pair.getRelation());
        assertTrue(pair.getAId(), pair.getAId().startsWith(TopicMerger.PARENT_ID_PREFIX));
        assertTrue(pair.getAText(), pair.getAText().startsWith("Radwege\nWorum es geht: Wie Radwege gebaut und "
                + "betrieben werden.\nTeilthemen: Radwegbreite; "));
        assertTrue(pair.getBText(), pair.getBText().startsWith("Radwegbetrieb\n"));
        int first = 0;
        int others = 0;
        for (Comparison comparison : result.getAsked()) {
            if (comparison.getKind() == Comparison.Kind.OBERTHEMA) {
                int heads = comparison.getAText().split("\n").length;
                if (comparison.getAText().contains("Radwegbreite")) {
                    first += heads;
                } else if (others == 0) {
                    others = heads;
                }
            }
        }
        assertEquals("the parent with more parts keeps its name", first >= others ? "Radwege" : "Radwegbetrieb",
                formed.get(0).getName());

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("reused, not asked");
        });
        Result again = new TopicMerger(silent, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, new ResearchMemory(), result.getAsked());
        assertEquals(0, silent.calls.get());
        assertEquals(1, formed(again).size());
    }

    @Test
    public void parentsTheModelCallsDifferentStandSideBySideAndAFailedPairHoldsBothBack() throws Exception {
        Table vectors = new Table();
        List<TopicProposal> proposals = radwegProposals(vectors);

        Result apart = new TopicMerger(twoParentsChat("verschieden"), vectors, MISSION, "de",
                Options.defaults().withParallel(1), null, CLOCK).merge(proposals, new ResearchMemory(), null);

        assertEquals(2, formed(apart).size());
        assertEquals(1, (int) apart.getParentCounts().get("parentPairs"));
        assertEquals(0, (int) apart.getParentCounts().get("parentsMerged"));

        Result failed = new TopicMerger(twoParentsChat(null), vectors, MISSION, "de",
                Options.defaults().withParallel(1), null, CLOCK).merge(proposals, new ResearchMemory(), null);

        assertTrue(formed(failed).isEmpty());
        assertEquals("both families wait for the next run", RADWEG.length, failed.getHeldBack().size());
        assertTrue(failed.getHeldBack().get("h1"), failed.getHeldBack().get("h1").startsWith("parent pair failed: "));
        assertEquals(Comparison.Status.FEHLER, noted(failed.getAsked(), TopicMerger.NOTE_PAIR).get(0).getStatus());

        Result off = new TopicMerger(twoParentsChat("gleich"), vectors, MISSION, "de",
                Options.defaults().withParallel(1).withConsolidation(false), null, CLOCK)
                .merge(proposals, new ResearchMemory(), null);
        assertEquals("without the consolidation", 2, formed(off).size());
        assertEquals(0, (int) off.getParentCounts().get("parentPairs"));
    }

    @Test
    public void aHeadNoCaseTookInJoinsTheMostSimilarParentOfItsPlaceUnlessItsCaseJudgedIt() throws Exception {
        double far = 0.68;
        Table vectors = new Table()
                .put("Radwegbreite", SIBLING_SHARED, SIBLING_OWN, 0, 0, 0, 0)
                .put("Radwegbelag", SIBLING_SHARED, 0, SIBLING_OWN, 0, 0, 0)
                .put("Radwegmarkierung", SIBLING_SHARED, 0, 0, SIBLING_OWN, 0, 0)
                .put("Radwegpflege", SIBLING_SHARED, 0, 0, 0, SIBLING_OWN, 0)
                .put("Radwegzählung", far, 0, 0, 0, 0, Math.sqrt(1 - far * far));
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Radwegbreite", true),
                proposal("b1", "s2", "Radwegbelag", true), proposal("c1", "s3", "Radwegmarkierung", true),
                proposal("e1", "s4", "Radwegpflege", true), proposal("d1", "s5", "Radwegzählung", true));
        Scripted chat = new Scripted(m -> {
            if (isParent(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Bau von Radwegen.\",\"oberthema\":\"Radwegbau\","
                        + "\"beschreibung\":\"Wie Radwege gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (prompt(m).contains("Teilthemen: ")) {
                return MainModelChatResult.ok("{\"grund\":\"Ein Teil davon.\",\"beziehung\":\"a_teil_von_b\"}");
            }
            return MainModelChatResult.ok("{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, new ResearchMemory(), null);

        Map<String, Integer> counts = result.getParentCounts();
        assertEquals("Radwegzählung is in no case", 1, (int) counts.get("freeAlone"));
        assertEquals(1, (int) counts.get("formedTop"));
        assertEquals("Radwegpflege was in the parent's case: not asked again", 1, (int) counts.get("looseCases"));
        assertEquals(1, (int) counts.get("looseJoined"));
        String parent = topicOf(result, "a1").getParentId();
        assertTrue(result.topic(parent).isFormed());
        assertEquals(parent, topicOf(result, "d1").getParentId());
        assertEquals("left out by its case, it stays at the top", "", topicOf(result, "e1").getParentId());
        List<Comparison> loose = noted(result.getAsked(), TopicMerger.NOTE_LOOSE);
        assertEquals(1, loose.size());
        assertEquals("d1", loose.get(0).getAId());
        assertEquals("Radwegzählung", loose.get(0).getAText());
        assertTrue(loose.get(0).getBId().startsWith(TopicMerger.PARENT_ID_PREFIX));
        assertTrue(loose.get(0).getBText(), loose.get(0).getBText().startsWith("Radwegbau\nWorum es geht: "));
        assertTrue(loose.get(0).getSimilarity() >= Options.defaults().getParentSimilarity());

        Result off = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1)
                .withConsolidation(false), null, CLOCK).merge(proposals, new ResearchMemory(), null);
        assertEquals(0, (int) off.getParentCounts().get("looseCases"));
        assertEquals("", topicOf(off, "d1").getParentId());
    }

    @Test
    public void aParentTheCardStepJoinedIntoACardIsNoParentToCompareAnyMore() throws Exception {
        Table vectors = new Table();
        List<TopicProposal> proposals = radwegProposals(vectors);
        double[] far = new double[RADWEG.length + 1];
        far[0] = -1;
        vectors.put("Radverkehr", far).put("Wie gut sind Radwege ausgebaut?", far);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        final Scripted pairs = twoParentsChat("gleich");
        Scripted chat = new Scripted(m -> {
            if (!isCard(m)) {
                return pairs.answers.apply(m);
            }
            return MainModelChatResult.ok(prompt(m).contains("Thema:\nRadwege\n")
                    ? "{\"grund\":\"Das ist die Karte.\",\"zuordnung\":\"gleich\",\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                    : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                    + "\"frage\":\"keine\"}");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, memory, null);

        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(2, (int) counts.get("formedTop"));
        assertEquals("„Radwege“ is the card now, no parent beside „Radwegbetrieb“", 0,
                (int) counts.get("parentPairs"));
        assertEquals(0, (int) counts.get("acrossPairs"));
        assertEquals("the parts of „Radwege“ were taken in by a case", 0, (int) counts.get("singleCases"));
        assertTrue(noted(result.getAsked(), TopicMerger.NOTE_PAIR).isEmpty());
        List<MergedTopic> formed = formed(result);
        assertEquals(1, formed.size());
        assertEquals("Radwegbetrieb", formed.get(0).getName());
        assertEquals("", formed.get(0).getParentId());
        Set<String> inTheCard = new HashSet<String>();
        for (Comparison comparison : result.getAsked()) {
            if (comparison.getKind() == Comparison.Kind.OBERTHEMA && comparison.getAText().startsWith("T1: ")
                    && comparison.getAText().contains("Radwegbreite")) {
                for (String line : comparison.getAText().split("\n")) {
                    inTheCard.add("h" + (Arrays.asList(RADWEG).indexOf(line.substring(line.indexOf(": ") + 2)) + 1));
                }
            }
        }
        assertTrue(inTheCard.toString(), inTheCard.size() >= PARENT_MIN_HEADS);
        for (int i = 1; i <= RADWEG.length; i++) {
            String id = "h" + i;
            assertEquals(id, inTheCard.contains(id) ? "T-rad" : formed.get(0).getId(),
                    topicOf(result, id).getParentId());
        }
    }

    /** Heads a case of the parent step takes at least. */
    private static final int PARENT_MIN_HEADS = 3;

    /** Ten heads in three directions of one plane, see {@link #lightProposals}. */
    private static final String[][] LIGHTS = {
            {"Radwegbreite", "Radwegbelag", "Radwegmarkierung", "Radwegbordsteine"},
            {"Radwegleuchten", "Radweglampen", "Radweglichtmasten"},
            {"Straßenleuchten", "Straßenlampen", "Straßenlichtmasten"}};

    /**
     * Ten heads at the top: 0.55 shared, 0.08 along one of three directions of a plane (0.9 between the first two,
     * 0.7 between the last two), the rest their own. Every pair stays below 0.65, so step 1 asks nothing; at 0.60 the
     * first seven form one case and the last three another. The parents over the first four, the next three and the
     * last three meet at 0.84 (first and second), 0.80 (second and third) and 0.78 (first and third).
     */
    private static List<TopicProposal> lightProposals(Table vectors) {
        double second = Math.acos(0.9);
        double third = second + Math.acos(0.7);
        double[][] directions = {{1, 0}, {Math.cos(second), Math.sin(second)}, {Math.cos(third), Math.sin(third)}};
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        int n = 0;
        for (int g = 0; g < LIGHTS.length; g++) {
            for (String name : LIGHTS[g]) {
                double[] values = new double[13];
                values[0] = Math.sqrt(0.55);
                values[1] = Math.sqrt(0.08) * directions[g][0];
                values[2] = Math.sqrt(0.08) * directions[g][1];
                values[3 + n] = Math.sqrt(0.37);
                vectors.put(name, values);
                n++;
                proposals.add(proposal("h" + n, "s" + n, name, true));
            }
        }
        double[] far = new double[13];
        far[0] = -1;
        vectors.put("Radverkehr", far).put("Wie gut sind Radwege ausgebaut?", far);
        return proposals;
    }

    /**
     * The parent step names „Ausstattung der Radwege“ over the first four heads of the case of seven, „Licht an
     * Radwegen“ over the rest, „Licht an Straßen“ over the case of three; a case of two topics goes to {@code pair}
     * with the names of A and B, the card step answers „keine“.
     */
    private static Scripted lightChat(final BiFunction<String, String, MainModelChatResult> pair) {
        return new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                String name = request.contains("T7: ") ? "Ausstattung der Radwege"
                        : request.contains("Radwegleuchten") ? "Licht an Radwegen" : "Licht an Straßen";
                String parts = request.contains("T7: ") ? "\"T1\",\"T2\",\"T3\",\"T4\"" : "\"T1\",\"T2\",\"T3\"";
                return MainModelChatResult.ok("{\"grund\":\"Gemeinsam.\",\"oberthema\":\"" + name
                        + "\",\"beschreibung\":\"Was dazugehört.\",\"themen\":[" + parts + "]}");
            }
            if (isCard(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\","
                        + "\"karte\":\"keine\",\"frage\":\"keine\"}");
            }
            return pair.apply(nameAfter(request, "Thema A:\n"), nameAfter(request, "Thema B:\n"));
        });
    }

    /** The first line after a label of the prompt. */
    private static String nameAfter(String request, String label) {
        return request.substring(request.indexOf(label) + label.length()).split("\n", 2)[0];
    }

    private static MainModelChatResult relation(String relation) {
        return MainModelChatResult.ok("{\"grund\":\"So hängen sie zusammen.\",\"beziehung\":\"" + relation + "\"}");
    }

    @Test
    public void aVerdictOnAParentThatGaveItsPartsAwayIsNotPassedOnAndTheChangedParentIsAskedAgain()
            throws Exception {
        Table vectors = new Table();
        List<TopicProposal> proposals = lightProposals(vectors);
        final List<String> pairs = new ArrayList<String>();
        Scripted chat = lightChat((a, b) -> {
            pairs.add(a + " | " + b);
            return relation(b.equals("Licht an Radwegen") ? "b_teil_von_a" : "verschieden");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, new ResearchMemory(), null);

        assertEquals("the most similar pair first; the light on cycle lanes is part of both others; the equipment "
                + "asked again once it took the light's parts", Arrays.asList(
                "Ausstattung der Radwege | Licht an Radwegen", "Licht an Straßen | Licht an Radwegen",
                "Ausstattung der Radwege | Licht an Straßen", "Ausstattung der Radwege | Licht an Straßen"), pairs);
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(3, (int) counts.get("formedTop"));
        assertEquals(4, (int) counts.get("parentPairs"));
        assertEquals(1, (int) counts.get("parentsMerged"));
        assertEquals("„Licht an Radwegen“ had given its parts away", 1, (int) counts.get("parentPairsStale"));
        assertEquals(2, formed(result).size());
        String equipment = topicOf(result, "h1").getParentId();
        assertEquals("Ausstattung der Radwege", result.topic(equipment).getName());
        String streets = topicOf(result, "h8").getParentId();
        assertEquals("Licht an Straßen", result.topic(streets).getName());
        for (int i = 1; i <= 10; i++) {
            assertEquals("h" + i, i <= 7 ? equipment : streets, topicOf(result, "h" + i).getParentId());
        }
        List<Comparison> asked = noted(result.getAsked(), TopicMerger.NOTE_PAIR);
        assertEquals(4, asked.size());
        assertTrue(asked.get(3).getAText(), asked.get(3).getAText().endsWith("Teilthemen: Radwegbreite; Radwegbelag; "
                + "Radwegmarkierung; Radwegbordsteine; Radwegleuchten; …"));
    }

    @Test
    public void aFailedPairHoldsBackTheParentThatTookTheOtherSidesParts() throws Exception {
        Table vectors = new Table();
        List<TopicProposal> proposals = lightProposals(vectors);
        Scripted chat = lightChat((a, b) -> a.equals("Licht an Straßen")
                ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow") : relation("gleich"));

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, new ResearchMemory(), null);

        assertEquals("„Licht an Radwegen“ gave its parts to „Ausstattung der Radwege“", 1,
                (int) result.getParentCounts().get("parentsMerged"));
        assertTrue("the parent that took them waits with the other side", formed(result).isEmpty());
        assertEquals(10, result.getHeldBack().size());
        for (String reason : result.getHeldBack().values()) {
            assertTrue(reason, reason.startsWith("parent pair failed: "));
        }
    }

    @Test
    public void aFreeParentWhoseTextChangedGoesToTheCardStepAgain() throws Exception {
        Table vectors = new Table();
        List<TopicProposal> proposals = lightProposals(vectors);
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        final Scripted pairs = lightChat((a, b) -> relation(a.equals("Ausstattung der Radwege")
                && b.equals("Licht an Radwegen") ? "b_teil_von_a" : "verschieden"));
        final List<String> cards = new ArrayList<String>();
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (!isCard(m)) {
                return pairs.answers.apply(m);
            }
            String topic = request.substring(request.indexOf("\n\nThema:\n") + 9);
            cards.add(topic.substring(0, topic.indexOf('\n')));
            return MainModelChatResult.ok(topic.startsWith("Ausstattung der Radwege\n")
                    && topic.contains("Radwegleuchten")
                    ? "{\"grund\":\"Der Ausbau.\",\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                    : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                    + "\"frage\":\"keine\"}");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, memory, null);

        assertEquals("ten heads, three parents, the equipment once more with its new parts", 14, cards.size());
        assertEquals("Ausstattung der Radwege", cards.get(13));
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(1, (int) counts.get("cardCasesAgain"));
        assertEquals(1, (int) counts.get("cardPlacedAgain"));
        String equipment = topicOf(result, "h1").getParentId();
        assertEquals("Ausstattung der Radwege", result.topic(equipment).getName());
        assertEquals("T-rad", result.topic(equipment).getParentId());
        assertEquals(equipment, topicOf(result, "h5").getParentId());
        assertEquals("the street lights and the equipment were judged on the same two texts at the top already", 0,
                (int) counts.get("acrossPairs"));

        Scripted again = new Scripted(m -> isCard(m) ? MainModelChatResult.ok("{\"grund\":\"Keine.\","
                + "\"zuordnung\":\"keine\",\"karte\":\"keine\",\"frage\":\"keine\"}") : pairs.answers.apply(m));
        Result off = new TopicMerger(again, vectors, MISSION, "de", Options.defaults().withParallel(1)
                .withAcrossPlaces(false), null, CLOCK).merge(proposals, memory, null);
        assertEquals(0, (int) off.getParentCounts().get("cardCasesAgain"));
        assertEquals("", off.topic(topicOf(off, "h1").getParentId()).getParentId());
    }

    /**
     * The fixture of {@link #theHeadsAParentLeftOutAreAskedAgainAndANewParentGoesThroughTheCardStep}: „Bau der
     * Radwege“ over h1-h3 goes under the card, „Betrieb der Radwege“ over h4-h6 stays at the top; their mean vectors
     * meet at 0.875. Pairs of two parents are answered with {@code pair}.
     */
    private static Result buildAndRun(String pair, Options options) throws Exception {
        return buildAndRun(relation(pair), options);
    }

    private static Result buildAndRun(final MainModelChatResult pair, Options options) throws Exception {
        Table vectors = new Table()
                .put("Radverkehr", 0, 0, 0, 0, 0, 0, 0, 1)
                .put("Wie gut sind Radwege ausgebaut?", 0, 0, 0, 0, 0, 0, 0, 1);
        String[] names = {"Radwegbreite", "Radwegbelag", "Radwegmarkierung", "Radwegbeleuchtung", "Radwegreinigung",
                "Radwegwinterdienst"};
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (int i = 0; i < names.length; i++) {
            double[] values = new double[8];
            values[0] = SIBLING_SHARED;
            values[i + 1] = SIBLING_OWN;
            vectors.put(names[i], values);
            proposals.add(proposal("h" + (i + 1), "s" + (i + 1), names[i], true));
        }
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                return MainModelChatResult.ok(request.contains("T6:")
                        ? "{\"grund\":\"Der Bau.\",\"oberthema\":\"Bau der Radwege\",\"beschreibung\":\"Wie "
                        + "Radwege gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                        : "{\"grund\":\"Der Betrieb.\",\"oberthema\":\"Betrieb der Radwege\",\"beschreibung\":"
                        + "\"Wie Radwege nutzbar bleiben.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (isCard(m)) {
                return MainModelChatResult.ok(request.contains("Thema:\nBau der Radwege\n")
                        ? "{\"grund\":\"Der Ausbau ist die Frage der Karte.\",\"zuordnung\":\"teil_von\","
                        + "\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                        : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            return request.split("Teilthemen: ", -1).length == 3 ? pair : relation("verschieden");
        });
        return new TopicMerger(chat, vectors, MISSION, "de", options, null, CLOCK).merge(proposals, memory, null);
    }

    @Test
    public void aFreeParentAtTheTopGivesItsPartsToTheSameParentOfAnotherPlace() throws Exception {
        Result same = buildAndRun("gleich", Options.defaults().withParallel(1));

        Map<String, Integer> counts = same.getParentCounts();
        assertEquals(1, (int) counts.get("acrossPairs"));
        assertEquals(1, (int) counts.get("acrossMerged"));
        List<Comparison> across = noted(same.getAsked(), TopicMerger.NOTE_ACROSS);
        assertEquals(1, across.size());
        assertTrue(across.get(0).getAText(), across.get(0).getAText().startsWith("Betrieb der Radwege\n"));
        assertTrue(across.get(0).getBText(), across.get(0).getBText().startsWith("Bau der Radwege\n"));
        List<MergedTopic> formed = formed(same);
        assertEquals("„Betrieb der Radwege“ is formed no more", 1, formed.size());
        assertEquals("Bau der Radwege", formed.get(0).getName());
        assertEquals("T-rad", formed.get(0).getParentId());
        for (int i = 1; i <= 6; i++) {
            assertEquals("its parts went flat under the parent under the card", formed.get(0).getId(),
                    topicOf(same, "h" + i).getParentId());
            assertEquals("T-rad", topicOf(same, "h" + i).getKnownId());
        }

        Result wider = buildAndRun("b_teil_von_a", Options.defaults().withParallel(1));
        assertEquals("the parent at the other place stays where its place put it", 0,
                (int) wider.getParentCounts().get("acrossMerged"));
        assertEquals(2, formed(wider).size());

        assertEquals(0, (int) wider.getParentCounts().get("acrossPartOf"));

        Result part = buildAndRun("a_teil_von_b", Options.defaults().withParallel(1));
        assertEquals("across places only the same subject merges (lever (3) after c15h)", 0,
                (int) part.getParentCounts().get("acrossMerged"));
        assertEquals(1, (int) part.getParentCounts().get("acrossPartOf"));
        assertEquals(2, formed(part).size());
        String operation = topicOf(part, "h4").getParentId();
        assertEquals("Betrieb der Radwege", part.topic(operation).getName());
        assertEquals("it stays at the top", "", part.topic(operation).getParentId());
        assertEquals(0, (int) same.getParentCounts().get("acrossPartOf"));

        Result full = buildAndRun("gleich", Options.defaults().withParallel(1).withFullParts(3));
        assertEquals("the same subject merges into a full parent too", 1,
                (int) full.getParentCounts().get("acrossMerged"));
        assertEquals(1, formed(full).size());
    }

    @Test
    public void aFailedPairAcrossPlacesHoldsBackBothParentsWithTheirFamilies() throws Exception {
        Result failed = buildAndRun(MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow"),
                Options.defaults().withParallel(1));

        List<Comparison> across = noted(failed.getAsked(), TopicMerger.NOTE_ACROSS);
        assertEquals(1, across.size());
        assertEquals(Comparison.Status.FEHLER, across.get(0).getStatus());
        for (int i = 1; i <= 6; i++) {
            String reason = failed.getHeldBack().get("h" + i);
            assertTrue("h" + i + ": " + reason, reason != null
                    && reason.startsWith("parent pair across places failed: "));
        }
        assertTrue("the next run asks again", formed(failed).isEmpty());
    }

    /**
     * „Ausstattung der Radwege“ stands free at the top; „Betrieb der Radwege“ under the card Radverkehr meets it at
     * 0.70, „Parkraum“ under the card Parken at 0.65. No place holds two parents, and no two heads of different
     * parents meet at 0.60.
     */
    @Test
    public void aVerdictAcrossPlacesOnAParentThatGaveItsPartsAwayIsCountedAcrossPlaces() throws Exception {
        Table vectors = new Table();
        siblings(vectors, new double[]{1, 0, 0}, 3, 12, "Radwegbreite", "Radwegbelag", "Radwegmarkierung");
        siblings(vectors, new double[]{0.8, 0.6, 0}, 6, 12, "Radwegbeleuchtung", "Radwegreinigung",
                "Radwegwinterdienst");
        siblings(vectors, new double[]{0.742857, -0.157143, 0.650745}, 9, 12, "Parkgebühren", "Parkdauer",
                "Parkleitsysteme");
        for (String card : new String[]{"Radverkehr", "Parken", "Wie gut sind Radwege ausgebaut?",
                "Was kostet Anwohnerparken?"}) {
            vectors.put(card, away(12));
        }
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Radwegbreite", true),
                proposal("a2", "s2", "Radwegbelag", true), proposal("a3", "s3", "Radwegmarkierung", true),
                proposal("o1", "s4", "Radwegbeleuchtung", true), proposal("o2", "s5", "Radwegreinigung", true),
                proposal("o3", "s6", "Radwegwinterdienst", true), proposal("k1", "s7", "Parkgebühren", true),
                proposal("k2", "s8", "Parkdauer", true), proposal("k3", "s9", "Parkleitsysteme", true));
        final List<String> operation = Arrays.asList("Radwegbeleuchtung", "Radwegreinigung", "Radwegwinterdienst",
                "Betrieb der Radwege");
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isCard(m)) {
                String topic = nameAfter(request, "\n\nThema:\n");
                String card = operation.contains(topic) ? "T-rad" : topic.startsWith("Park") ? "T-park" : "";
                return MainModelChatResult.ok(card.isEmpty()
                        ? "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}"
                        : "{\"grund\":\"Eine Frage der Karte.\",\"zuordnung\":\"teil_von\",\"karte\":\"" + card
                        + "\",\"frage\":\"" + questionLabel(request, card) + "\"}");
            }
            if (isParent(m)) {
                String name = request.contains("Radwegbreite") ? "Ausstattung der Radwege"
                        : request.contains("Radwegbeleuchtung") ? "Betrieb der Radwege" : "Parkraum";
                return MainModelChatResult.ok("{\"grund\":\"Gemeinsam.\",\"oberthema\":\"" + name
                        + "\",\"beschreibung\":\"Was dazugehört.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            String b = nameAfter(request, "Thema B:\n");
            return relation(!nameAfter(request, "Thema A:\n").equals("Ausstattung der Radwege") ? "verschieden"
                    : b.equals("Betrieb der Radwege") ? "gleich" : b.equals("Parkraum") ? "a_teil_von_b"
                    : "verschieden");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, twoCards(), null);

        Map<String, Integer> counts = result.getParentCounts();
        assertEquals("no place holds two parents", 0, (int) counts.get("parentPairs"));
        assertEquals("no pair of one place went stale", 0, (int) counts.get("parentPairsStale"));
        assertEquals(2, (int) counts.get("acrossPairs"));
        assertEquals(1, (int) counts.get("acrossMerged"));
        assertEquals("the verdict on „Parkraum“ came after the equipment gave its parts away", 1,
                (int) counts.get("acrossPairsStale"));
        String operating = topicOf(result, "o1").getParentId();
        assertEquals("Betrieb der Radwege", result.topic(operating).getName());
        assertEquals("T-rad", result.topic(operating).getParentId());
        for (String id : new String[]{"a1", "a2", "a3"}) {
            assertEquals(id, operating, topicOf(result, id).getParentId());
        }
        assertEquals("T-park", result.topic(topicOf(result, "k1").getParentId()).getParentId());
        assertEquals(2, formed(result).size());
    }

    /**
     * The review of 22.11.2026: „Bau der Radwege“ takes „Radwegzählung“ in at the consolidation of the top, and in the
     * last pairs the parts of „Verkehrsregelung am Radweg“ (their mean vectors meet at 0.65). The card step reads it
     * once more on that text and puts it under the card.
     */
    @Test
    public void aFreeParentWhoseTextTheLastPairsChangedGoesToTheCardStepOnceMore() throws Exception {
        double far = 0.68;
        Table vectors = new Table();
        siblings(vectors, new double[]{1, 0}, 2, 9, "Radwegbreite", "Radwegbelag", "Radwegmarkierung");
        siblings(vectors, new double[]{0.742857, 0.669439}, 5, 9, "Radwegampeln", "Radwegkreuzungen",
                "Radwegschilder");
        vectors.put("Radwegzählung", far, 0, 0, 0, 0, 0, 0, 0, Math.sqrt(1 - far * far));
        vectors.put("Radverkehr", away(9)).put("Wie gut sind Radwege ausgebaut?", away(9));
        List<TopicProposal> proposals = Arrays.asList(proposal("p1", "s1", "Radwegbreite", true),
                proposal("p2", "s2", "Radwegbelag", true), proposal("p3", "s3", "Radwegmarkierung", true),
                proposal("q1", "s4", "Radwegampeln", true), proposal("q2", "s5", "Radwegkreuzungen", true),
                proposal("q3", "s6", "Radwegschilder", true), proposal("z1", "s7", "Radwegzählung", true));
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        final List<String> cards = new ArrayList<String>();
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isCard(m)) {
                String topic = request.substring(request.indexOf("\n\nThema:\n") + 9);
                cards.add(topic);
                return MainModelChatResult.ok(topic.startsWith("Bau der Radwege\n") && topic.contains("Radwegampeln")
                        ? "{\"grund\":\"Der Ausbau.\",\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                        : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            if (isParent(m)) {
                return MainModelChatResult.ok(request.contains("Radwegbreite")
                        ? "{\"grund\":\"Der Bau.\",\"oberthema\":\"Bau der Radwege\",\"beschreibung\":\"Wie Radwege "
                        + "gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                        : "{\"grund\":\"Die Regeln.\",\"oberthema\":\"Verkehrsregelung am Radweg\",\"beschreibung\":"
                        + "\"Wie der Verkehr am Radweg geregelt wird.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (nameAfter(request, "Thema A:\n").equals("Radwegzählung")) {
                return relation("a_teil_von_b");
            }
            return relation(request.split("Teilthemen: ", -1).length == 3 && request.contains("Radwegzählung")
                    ? "gleich" : "verschieden");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, memory, null);

        Map<String, Integer> counts = result.getParentCounts();
        assertEquals("the two parents as formed, then once more after the counting joined", 2,
                (int) counts.get("parentPairs"));
        assertEquals(1, (int) counts.get("parentsMerged"));
        assertEquals("after the counting joined, and after the parts of the regulation came", 2,
                (int) counts.get("cardCasesAgain"));
        assertEquals(1, (int) counts.get("cardPlacedAgain"));
        String last = cards.get(cards.size() - 1);
        assertTrue(last, last.startsWith("Bau der Radwege\n") && last.contains("Radwegampeln"));
        String building = topicOf(result, "p1").getParentId();
        assertEquals("Bau der Radwege", result.topic(building).getName());
        assertEquals("T-rad", result.topic(building).getParentId());
        for (String id : new String[]{"p2", "p3", "q1", "q2", "q3", "z1"}) {
            assertEquals(id, building, topicOf(result, id).getParentId());
        }
        assertEquals(1, formed(result).size());
    }

    @Test
    public void aHeadThatStandsAloneJoinsTheMostSimilarParentOfAnyPlace() throws Exception {
        Result result = aloneRun("a_teil_von_b", Options.defaults().withParallel(1));

        Map<String, Integer> counts = result.getParentCounts();
        assertEquals("no parent at the top: the consolidation of its place has none to offer", 0,
                (int) counts.get("looseCases"));
        assertEquals("Radwegpflege was in the parent's case: not asked again", 1, (int) counts.get("singleCases"));
        assertEquals(1, (int) counts.get("singleJoined"));
        assertEquals(0, (int) counts.get("singleFull"));
        String parent = topicOf(result, "a1").getParentId();
        assertEquals("Radwegbau", result.topic(parent).getName());
        assertEquals("T-rad", result.topic(parent).getParentId());
        assertEquals(parent, topicOf(result, "d1").getParentId());
        assertEquals("", topicOf(result, "e1").getParentId());
        List<Comparison> single = noted(result.getAsked(), TopicMerger.NOTE_SINGLE);
        assertEquals(1, single.size());
        assertEquals("d1", single.get(0).getAId());
        assertTrue(single.get(0).getBId().startsWith(TopicMerger.PARENT_ID_PREFIX));

        Result off = aloneRun("a_teil_von_b", Options.defaults().withParallel(1).withAcrossPlaces(false));
        assertEquals(0, (int) off.getParentCounts().get("singleCases"));
        assertEquals("", topicOf(off, "d1").getParentId());
    }

    /**
     * The architect's lever (2) after c15h: „Radwegbau“ has three parts, so with a limit of three it is full and takes
     * „Radwegzählung“ only as the same subject; the limit counts as the batch stands, and 0 sets none. After c15i it
     * holds for a loose head of the parent's own place too, unless switched off.
     */
    @Test
    public void aFullParentTakesAHeadThatStandsAloneOrALooseHeadOnlyAsTheSameSubject() throws Exception {
        assertEquals(30, Options.defaults().getFullParts());
        Result full = aloneRun("a_teil_von_b", Options.defaults().withParallel(1).withFullParts(3));
        assertEquals(1, (int) full.getParentCounts().get("singleCases"));
        assertEquals(0, (int) full.getParentCounts().get("singleJoined"));
        assertEquals(1, (int) full.getParentCounts().get("singleFull"));
        assertEquals("", topicOf(full, "d1").getParentId());

        Result same = aloneRun("gleich", Options.defaults().withParallel(1).withFullParts(3));
        assertEquals(1, (int) same.getParentCounts().get("singleJoined"));
        assertEquals(0, (int) same.getParentCounts().get("singleFull"));
        assertEquals(topicOf(same, "a1").getParentId(), topicOf(same, "d1").getParentId());

        for (int limit : new int[]{4, 0}) {
            Result room = aloneRun("a_teil_von_b", Options.defaults().withParallel(1).withFullParts(limit));
            assertEquals("limit " + limit, 1, (int) room.getParentCounts().get("singleJoined"));
            assertEquals("limit " + limit, topicOf(room, "a1").getParentId(), topicOf(room, "d1").getParentId());
        }

        assertTrue(Options.defaults().isFullLoose());
        Result loose = looseRun(relation("a_teil_von_b"), Options.defaults().withParallel(1).withFullParts(3));
        assertEquals("a full parent takes a loose head of its place only as the same subject too", 0,
                (int) loose.getParentCounts().get("looseJoined"));
        assertEquals(1, (int) loose.getParentCounts().get("looseFull"));
        assertEquals(0, (int) loose.getParentCounts().get("singleFull"));
        assertEquals("", topicOf(loose, "d1").getParentId());
        assertEquals("the parent judged it: not asked again as a head that stands alone", 0,
                (int) loose.getParentCounts().get("singleCases"));

        Result looseSame = looseRun(relation("gleich"), Options.defaults().withParallel(1).withFullParts(3));
        assertEquals(1, (int) looseSame.getParentCounts().get("looseJoined"));
        assertEquals(0, (int) looseSame.getParentCounts().get("looseFull"));

        Result looseOff = looseRun(relation("a_teil_von_b"),
                Options.defaults().withParallel(1).withFullParts(3).withFullLoose(false));
        assertFalse(Options.defaults().withFullLoose(false).isFullLoose());
        assertEquals("switched off, the limit is for the heads that stand alone only", 1,
                (int) looseOff.getParentCounts().get("looseJoined"));
        assertEquals(0, (int) looseOff.getParentCounts().get("looseFull"));
        assertEquals(topicOf(looseOff, "a1").getParentId(), topicOf(looseOff, "d1").getParentId());
        try {
            Options.defaults().withFullParts(-1);
            fail("a negative limit");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("0 (no limit)"));
        }
    }

    /**
     * „Radwegbau“ over „Radwegbreite“, „Radwegbelag“ and „Radwegmarkierung“ goes under the card; „Radwegpflege“ was in
     * its case and stays out; „Radwegzählung“ stands alone at the top and meets the parent at 0.68. A head put to a
     * parent with its parts listed is answered with {@code verdict}.
     */
    private static Result aloneRun(final String verdict, Options options) throws Exception {
        double far = 0.68;
        Table vectors = new Table()
                .put("Radwegbreite", SIBLING_SHARED, SIBLING_OWN, 0, 0, 0, 0)
                .put("Radwegbelag", SIBLING_SHARED, 0, SIBLING_OWN, 0, 0, 0)
                .put("Radwegmarkierung", SIBLING_SHARED, 0, 0, SIBLING_OWN, 0, 0)
                .put("Radwegpflege", SIBLING_SHARED, 0, 0, 0, SIBLING_OWN, 0)
                .put("Radwegzählung", far, 0, 0, 0, 0, Math.sqrt(1 - far * far))
                .put("Radverkehr", -1, 0, 0, 0, 0, 0)
                .put("Wie gut sind Radwege ausgebaut?", -1, 0, 0, 0, 0, 0);
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Radwegbreite", true),
                proposal("b1", "s2", "Radwegbelag", true), proposal("c1", "s3", "Radwegmarkierung", true),
                proposal("e1", "s4", "Radwegpflege", true), proposal("d1", "s5", "Radwegzählung", true));
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Bau von Radwegen.\",\"oberthema\":\"Radwegbau\","
                        + "\"beschreibung\":\"Wie Radwege gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (isCard(m)) {
                return MainModelChatResult.ok(request.contains("Thema:\nRadwegbau\n")
                        ? "{\"grund\":\"Der Ausbau.\",\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                        : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            return relation(request.contains("Teilthemen: ") ? verdict : "verschieden");
        });
        return new TopicMerger(chat, vectors, MISSION, "de", options, null, CLOCK).merge(proposals, memory, null);
    }

    /**
     * The architect's lever after c15h: „Zählstellen“ meets „Radwegbau“ at 0.28 only, but the parent says it is about
     * counting points too. Under a card the parent leaves the head alone at the top; without cards both stand at the
     * top and the head is loose beside it. One of the four distinct names holds the term, so it is rare from a share
     * above a quarter: not at the default of 2 % in so small a run.
     */
    @Test
    public void aHeadThatSharesARareTermWithAParentMeetsItOnceBelowTheSimilarity() throws Exception {
        String note = TopicMerger.NOTE_SINGLE + TopicMerger.NOTE_RARE + "zählstell";
        Result alone = rareRun(true, "a_teil_von_b", Options.defaults().withParallel(1).withRareTerms(0.5), 0.3);
        Map<String, Integer> counts = alone.getParentCounts();
        assertEquals(1, (int) counts.get("singleCases"));
        assertEquals(1, (int) counts.get("singleJoined"));
        assertEquals(1, (int) counts.get("rareSingle"));
        assertEquals(1, (int) counts.get("rareSingleJoined"));
        assertEquals(0, (int) counts.get("rareLoose"));
        List<Comparison> rare = noted(alone.getAsked(), note);
        assertEquals(1, rare.size());
        assertEquals("z1", rare.get(0).getAId());
        assertTrue(rare.get(0).getBId().startsWith(TopicMerger.PARENT_ID_PREFIX));
        assertTrue("below the similarity of the parent step: " + rare.get(0).getSimilarity(),
                rare.get(0).getSimilarity() < 0.30);
        assertTrue(noted(alone.getAsked(), TopicMerger.NOTE_SINGLE).isEmpty());
        String parent = topicOf(alone, "a1").getParentId();
        assertEquals("Radwegbau", alone.topic(parent).getName());
        assertEquals("T-rad", alone.topic(parent).getParentId());
        assertEquals(parent, topicOf(alone, "z1").getParentId());

        Result apart = rareRun(true, "verschieden", Options.defaults().withParallel(1).withRareTerms(0.5), 0.3);
        assertEquals(1, (int) apart.getParentCounts().get("rareSingle"));
        assertEquals(0, (int) apart.getParentCounts().get("rareSingleJoined"));
        assertEquals("", topicOf(apart, "z1").getParentId());

        for (Options without : new Options[]{Options.defaults().withParallel(1),
                Options.defaults().withParallel(1).withRareTerms(0)}) {
            Result none = rareRun(true, "a_teil_von_b", without, 0.3);
            assertEquals("share " + without.getRareTermShare(), 0, (int) none.getParentCounts().get("singleCases"));
            assertEquals(0, (int) none.getParentCounts().get("rareSingle"));
            assertEquals("", topicOf(none, "z1").getParentId());
        }

        Result loose = rareRun(false, "a_teil_von_b", Options.defaults().withParallel(1).withRareTerms(0.5), 0.3);
        assertEquals(1, (int) loose.getParentCounts().get("looseCases"));
        assertEquals(1, (int) loose.getParentCounts().get("rareLoose"));
        assertEquals(1, (int) loose.getParentCounts().get("rareLooseJoined"));
        assertEquals(1, noted(loose.getAsked(), TopicMerger.NOTE_LOOSE + TopicMerger.NOTE_RARE + "zählstell").size());
        assertEquals(topicOf(loose, "a1").getParentId(), topicOf(loose, "z1").getParentId());

        Result looseApart = rareRun(false, "verschieden", Options.defaults().withParallel(1).withRareTerms(0.5), 0.3);
        assertEquals(1, (int) looseApart.getParentCounts().get("rareLoose"));
        assertEquals(0, (int) looseApart.getParentCounts().get("rareLooseJoined"));
        assertEquals("the parent judged the head at its place: no second case as a head that stands alone", 0,
                (int) looseApart.getParentCounts().get("singleCases"));
        assertEquals("", topicOf(looseApart, "z1").getParentId());
    }

    /**
     * At 0.68 „Zählstellen“ meets „Radwegbau“ by similarity first: joined, nothing more; not joined, the parent has
     * judged it and the rare term brings no second case.
     */
    @Test
    public void aRareTermBringsNoCaseBesideTheCaseBySimilarityToTheSameParent() throws Exception {
        for (String verdict : new String[]{"a_teil_von_b", "verschieden"}) {
            Result result = rareRun(true, verdict, Options.defaults().withParallel(1).withRareTerms(0.5), 0.68);
            Map<String, Integer> counts = result.getParentCounts();
            assertEquals(verdict, 1, (int) counts.get("singleCases"));
            assertEquals(verdict, 0, (int) counts.get("rareSingle"));
            assertEquals(verdict, 1, noted(result.getAsked(), TopicMerger.NOTE_SINGLE).size());
            assertEquals(verdict, "a_teil_von_b".equals(verdict) ? 1 : 0, (int) counts.get("singleJoined"));
        }
    }

    /**
     * „Radwegsanierung“ joins „Radwegbau“ in the batch in which the parent turns „Zählstellen“ down (both meet it by
     * similarity): the parent's text has changed since, but it has judged the head, and the rare term brings no second
     * case to it.
     */
    @Test
    public void aParentThatJudgedAHeadGetsNoRareCaseForItAfterItsTextChanged() throws Exception {
        Result result = rareRun(true, "verschieden", Options.defaults().withParallel(1).withRareTerms(0.5), 0.68,
                true);
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(2, (int) counts.get("singleCases"));
        assertEquals(1, (int) counts.get("singleJoined"));
        assertEquals(0, (int) counts.get("rareSingle"));
        assertEquals(topicOf(result, "a1").getParentId(), topicOf(result, "w1").getParentId());
        assertEquals("", topicOf(result, "z1").getParentId());
    }

    private static Result rareRun(boolean card, final String verdict, Options options, double near)
            throws Exception {
        return rareRun(card, verdict, options, near, false);
    }

    /**
     * „Radwegbau“ over three heads and „Zählstellen“ (0.3 along their direction, or {@code near}); the parent says it
     * is about counting points too. With {@code card} the parent goes under the card Radverkehr; a head put to it
     * hears {@code verdict}. With {@code second} „Radwegsanierung“ stands alone too, meets the parent like
     * „Zählstellen“ at 0.68 and is always taken in as a part.
     */
    private static Result rareRun(boolean card, final String verdict, Options options, double near, boolean second)
            throws Exception {
        double far = 0.68;
        Table vectors = new Table()
                .put("Radwegbreite", SIBLING_SHARED, SIBLING_OWN, 0, 0, 0, 0)
                .put("Radwegbelag", SIBLING_SHARED, 0, SIBLING_OWN, 0, 0, 0)
                .put("Radwegmarkierung", SIBLING_SHARED, 0, 0, SIBLING_OWN, 0, 0)
                .put("Zählstellen", near, 0, 0, 0, Math.sqrt(1 - near * near), 0)
                .put("Radwegsanierung", far, 0, 0, 0, 0, Math.sqrt(1 - far * far))
                .put("Radverkehr", -1, 0, 0, 0, 0, 0)
                .put("Wie gut sind Radwege ausgebaut?", -1, 0, 0, 0, 0, 0);
        List<TopicProposal> proposals = new ArrayList<TopicProposal>(Arrays.asList(
                proposal("a1", "s1", "Radwegbreite", true), proposal("b1", "s2", "Radwegbelag", true),
                proposal("c1", "s3", "Radwegmarkierung", true), proposal("z1", "s4", "Zählstellen", true)));
        if (second) {
            proposals.add(proposal("w1", "s5", "Radwegsanierung", true));
        }
        ResearchMemory memory = new ResearchMemory();
        if (card) {
            memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
            memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                    ResearchQuestion.Origin.KONZEPT, 1L));
        }
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Bau von Radwegen.\",\"oberthema\":\"Radwegbau\","
                        + "\"beschreibung\":\"Wie Radwege gebaut und Zählstellen gesetzt werden.\","
                        + "\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (isCard(m)) {
                return MainModelChatResult.ok(request.contains("Thema:\nRadwegbau\n")
                        ? "{\"grund\":\"Der Ausbau.\",\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                        : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            return relation(request.contains("Thema A:\nRadwegsanierung") ? "a_teil_von_b"
                    : request.contains("Teilthemen: ") ? verdict : "verschieden");
        });
        return new TopicMerger(chat, vectors, MISSION, "de", options, null, CLOCK).merge(proposals, memory, null);
    }

    /**
     * „Verkehrszählung“ over three heads of counting stays at the top, „Radwegbau“ goes under the card; their vectors
     * are apart (0.0), but both say they are about counting points, and one name holds the term. Across places the
     * rare term brings the pair to the model, and still only the same subject merges.
     */
    @Test
    public void aFreeParentThatSharesARareTermWithAParentOfAnotherPlaceMeetsItAndMergesOnlyAsTheSame()
            throws Exception {
        String note = TopicMerger.NOTE_ACROSS + TopicMerger.NOTE_RARE + "zählstell";
        Result same = rareAcrossRun("gleich", Options.defaults().withParallel(1).withRareTerms(0.5));
        Map<String, Integer> counts = same.getParentCounts();
        assertEquals(1, (int) counts.get("rareAcross"));
        assertEquals(1, (int) counts.get("rareAcrossMerged"));
        assertEquals(1, (int) counts.get("acrossMerged"));
        List<Comparison> across = noted(same.getAsked(), note);
        assertEquals(1, across.size());
        assertTrue(across.get(0).getAText(), across.get(0).getAText().startsWith("Verkehrszählung\n"));
        assertEquals(0.0, across.get(0).getSimilarity(), 1e-6);
        List<MergedTopic> formed = formed(same);
        assertEquals(1, formed.size());
        assertEquals("Radwegbau", formed.get(0).getName());
        for (String id : new String[]{"a1", "b1", "c1", "x1", "y1", "z1"}) {
            assertEquals(id, formed.get(0).getId(), topicOf(same, id).getParentId());
        }

        Result part = rareAcrossRun("a_teil_von_b", Options.defaults().withParallel(1).withRareTerms(0.5));
        assertEquals(1, (int) part.getParentCounts().get("rareAcross"));
        assertEquals(0, (int) part.getParentCounts().get("rareAcrossMerged"));
        assertEquals(1, (int) part.getParentCounts().get("acrossPartOf"));
        assertEquals(2, formed(part).size());

        Result off = rareAcrossRun("gleich", Options.defaults().withParallel(1).withRareTerms(0));
        assertEquals(0, (int) off.getParentCounts().get("acrossPairs"));
        assertEquals(0, (int) off.getParentCounts().get("rareAcross"));
        assertEquals(2, formed(off).size());
    }

    private static Result rareAcrossRun(final String pair, Options options) throws Exception {
        Table vectors = new Table()
                .put("Radverkehr", 0, 0, 0, 0, 0, 0, 0, 1)
                .put("Wie gut sind Radwege ausgebaut?", 0, 0, 0, 0, 0, 0, 0, 1);
        siblings(vectors, new double[]{1, 0}, 2, 8, "Radwegbreite", "Radwegbelag", "Radwegmarkierung");
        siblings(vectors, new double[]{0, 1}, 5, 8, "Zählschleifen", "Zählkameras", "Zählstellenbetrieb");
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Radwegbreite", true),
                proposal("b1", "s2", "Radwegbelag", true), proposal("c1", "s3", "Radwegmarkierung", true),
                proposal("x1", "s4", "Zählschleifen", true), proposal("y1", "s5", "Zählkameras", true),
                proposal("z1", "s6", "Zählstellenbetrieb", true));
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                return MainModelChatResult.ok(request.contains("Radwegbreite")
                        ? "{\"grund\":\"Der Bau.\",\"oberthema\":\"Radwegbau\",\"beschreibung\":\"Wie Radwege "
                        + "gebaut und Zählstellen gesetzt werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                        : "{\"grund\":\"Das Zählen.\",\"oberthema\":\"Verkehrszählung\",\"beschreibung\":"
                        + "\"Wie Zählstellen den Verkehr erfassen.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (isCard(m)) {
                return MainModelChatResult.ok(request.contains("Thema:\nRadwegbau\n")
                        ? "{\"grund\":\"Der Ausbau.\",\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}"
                        : "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            return request.split("Teilthemen: ", -1).length == 3 ? relation(pair) : relation("verschieden");
        });
        return new TopicMerger(chat, vectors, MISSION, "de", options, null, CLOCK).merge(proposals, memory, null);
    }

    @Test
    public void aRareTermIsAWordOfANameWithItsEndingFoldedOrAnAcronymAsWritten() {
        assertEquals(terms("person", "notsignal", "anlag", "#PNA"),
                TopicMerger.RareTerms.termsOf("Personen-Notsignal-Anlagen (PNA)"));
        assertEquals("a word under five letters is no term", terms("#RFID"),
                TopicMerger.RareTerms.termsOf("RFID-Tags"));
        assertEquals(terms("#PNA"), TopicMerger.RareTerms.termsOf("PNAs"));
        assertEquals("words that name no subject are left out", terms("prüfung", "sachverständig", "aufsicht"),
                TopicMerger.RareTerms.termsOf("Prüfung durch Sachverständige unter Aufsicht"));
        String[][] folded = {{"exoskeletten", "exoskelett"}, {"exoskelette", "exoskelett"},
                {"exoskeletts", "exoskelett"}, {"sensoren", "sensor"}, {"sensors", "sensor"}, {"lasern", "laser"},
                {"lasers", "laser"}, {"regeln", "regel"}, {"prozesse", "prozess"}, {"prozessen", "prozess"},
                {"messen", "messen"}, {"person", "person"}, {"stationen", "station"}, {"status", "status"},
                {"praxis", "praxis"}, {"daten", "daten"}, {"zählstellen", "zählstell"},
                {"exoskelettes", "exoskelett"}};
        for (String[] pair : folded) {
            assertEquals(pair[0], pair[1], TopicMerger.RareTerms.fold(pair[0]));
        }
        assertEquals("DGUV", TopicMerger.RareTerms.acronym("DGUV"));
        assertEquals("PNA", TopicMerger.RareTerms.acronym("PNAs"));
        for (String none : new String[]{"IoT", "A", "ABCDEF", "Arm", "As"}) {
            assertNull(none, TopicMerger.RareTerms.acronym(none));
        }
        assertEquals("exoskelett", TopicMerger.RareTerms.match("rückenexoskelett", "exoskelett"));
        assertEquals("exoskelett", TopicMerger.RareTerms.match("exoskelett", "rückenexoskelett"));
        assertNull(TopicMerger.RareTerms.match("laser", "radweg"));
        assertEquals("#RFID", TopicMerger.RareTerms.match("#RFID", "#RFID"));
        assertNull("an acronym matches only itself", TopicMerger.RareTerms.match("#RFI", "#RFID"));
        assertNull("an acronym never matches a word", TopicMerger.RareTerms.match("#LASER", "laser"));
        assertNull(TopicMerger.RareTerms.match("laserschutz", "#LASER"));
        assertEquals("LASER", TopicMerger.RareTerms.shown("#LASER"));
        assertEquals("zählstell", TopicMerger.RareTerms.shown("zählstell"));
    }

    /**
     * Twelve distinct names (one twice in other case), a share of 0.2: rare is what fewer than 2.4 names hold. „LASER“
     * stands in one name as an acronym and in three as the word: not rare. A word no name holds is no term.
     */
    @Test
    public void aRareTermIsHeldByFewNamesAndAnAcronymCountsInEveryCase() {
        List<String> names = Arrays.asList("LASER-Schutzbrillen", "Laser im Bau", "Laser und Lärm", "Laser-Messung",
                "RFID-Etiketten", "RFID-Leser", "Hebehilfen", "Gabelstapler", "Atemschutz", "Fußschutz", "Kopfschutz",
                "Rückenexoskelette", "rfid-etiketten");
        TopicMerger.RareTerms rare = new TopicMerger.RareTerms(names, 0.2);
        assertEquals(4, rare.holding("#LASER"));
        assertFalse(rare.rare("#LASER"));
        assertEquals("the same name in other case counts once", 2, rare.holding("#RFID"));
        assertTrue(rare.rare("#RFID"));
        assertEquals(1, rare.holding("exoskelett"));
        assertTrue(rare.rare("exoskelett"));
        assertEquals(4, rare.holding("schutz"));
        assertFalse(rare.rare("schutz"));
        assertEquals(0, rare.holding("lager"));
        assertFalse("no name holds it", rare.rare("lager"));

        Set<String> leser = TopicMerger.RareTerms.termsOf("RFID-Leser");
        assertEquals("#RFID", rare.shared(leser, TopicMerger.RareTerms.termsOf("RFID-Etiketten im Lager")));
        assertEquals("a common term shared does not count", "exoskelett",
                rare.shared(TopicMerger.RareTerms.termsOf("Exoskelette und Schutz"),
                        TopicMerger.RareTerms.termsOf("Rückenexoskelett mit Kopfschutz")));
        assertNull(rare.shared(TopicMerger.RareTerms.termsOf("Schutz am Kopf"),
                TopicMerger.RareTerms.termsOf("Rückenexoskelett mit Kopfschutz")));
        assertEquals("the fewest names first", "exoskelett",
                rare.shared(TopicMerger.RareTerms.termsOf("RFID und Exoskelette"),
                        TopicMerger.RareTerms.termsOf("RFID-Exoskelett")));
        assertNull("an acronym that is also a common word is not rare",
                rare.shared(TopicMerger.RareTerms.termsOf("LASER-Schutzbrillen"),
                        TopicMerger.RareTerms.termsOf("LASER-Klassen")));
        assertNull(rare.shared(TopicMerger.RareTerms.termsOf("LASER-Schutzbrillen"),
                TopicMerger.RareTerms.termsOf("Laser-Messung")));
        assertEquals("#LASER", new TopicMerger.RareTerms(names, 0.5).shared(
                TopicMerger.RareTerms.termsOf("LASER-Schutzbrillen"), TopicMerger.RareTerms.termsOf("LASER-Klassen")));

        List<String> four = Arrays.asList("RFID-Etiketten", "RFID-Leser", "Hebehilfen", "Gabelstapler");
        assertFalse("fewer than the share, not as many", new TopicMerger.RareTerms(four, 0.5).rare("#RFID"));
        assertTrue(new TopicMerger.RareTerms(four, 0.51).rare("#RFID"));
    }

    @Test
    public void theShareOfARareTermIsAtLeastZeroAndBelowOne() {
        assertEquals("off since c15j", 0.0, Options.defaults().getRareTermShare(), 1e-12);
        assertEquals(0.02, Options.defaults().withRareTerms(0.02).getRareTermShare(), 1e-12);
        assertEquals(0.0, Options.defaults().withRareTerms(0).getRareTermShare(), 1e-12);
        for (double wrong : new double[]{-0.1, 1.0, Double.NaN}) {
            try {
                Options.defaults().withRareTerms(wrong);
                fail("share " + wrong);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("0 <= share < 1"));
            }
        }
    }

    /** The architect's lever after c15j: concentration over the sources, 0.6 of 2 sources, at least 4 proposals. */
    @Test
    public void aConcentratedTermNeedsItsShareFromFewSourcesAndAMinimumOfProposals() {
        Options defaults = Options.defaults();
        assertEquals(0.6, defaults.getConcentration(), 1e-12);
        assertEquals(2, defaults.getConcentrationSources());
        assertEquals(4, defaults.getConcentrationMin());
        assertTrue(defaults.isTermPairs());
        assertTrue(defaults.countsTerms());
        assertFalse(defaults.withConcentration(0, 2, 4).countsTerms());
        assertTrue(defaults.withConcentration(0, 2, 4).withRareTerms(0.02).countsTerms());
        assertFalse(defaults.withTermPairs(false).isTermPairs());
        Options other = defaults.withConcentration(0.5, 3, 6);
        assertEquals(0.5, other.getConcentration(), 1e-12);
        assertEquals(3, other.getConcentrationSources());
        assertEquals(6, other.getConcentrationMin());
        assertEquals("the other options keep it", 6, other.withParallel(1).withRareTerms(0.1).getConcentrationMin());
        for (double[] wrong : new double[][]{{-0.1, 2, 4}, {1.1, 2, 4}, {Double.NaN, 2, 4}, {0.6, 0, 4},
                {0.6, 2, 0}}) {
            try {
                defaults.withConcentration(wrong[0], (int) wrong[1], (int) wrong[2]);
                fail(Arrays.toString(wrong));
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("concentrated term"));
            }
        }

        List<String> names = Arrays.asList("Zählstellenbetrieb", "Zählstellendaten", "Zählstellennetz",
                "Zählstellenkarte", "Datenschutz", "Datenschutzrecht", "Datenschutz", "Datenschutzbeauftragte",
                "RFID-Leser", "rfid-Etiketten", "RFID-Tore", "RFID im Lager", "Lagerlogistik");
        List<String> sources = Arrays.asList("s1", "s1", "s2", "s3", "s1", "s2", "s3", "s4", "s5", "s5", "s5", "s6",
                "s7");
        TopicMerger.RareTerms terms = new TopicMerger.RareTerms(names, sources, 0, 0.6, 2, 4);
        Map<String, Integer> zaehl = new LinkedHashMap<String, Integer>();
        zaehl.put("s1", 2);
        zaehl.put("s2", 1);
        zaehl.put("s3", 1);
        assertEquals("the most first, then by the source", zaehl, terms.sourcesOf("zählstell"));
        assertEquals(0.75, terms.topShare("zählstell"), 1e-12);
        assertTrue(terms.concentrated("zählstell"));
        assertTrue(terms.counts("zählstell"));
        assertEquals("a name proposed twice counts twice", 4, sum(terms.sourcesOf("datenschutz")));
        assertEquals(0.5, terms.topShare("datenschutz"), 1e-12);
        assertFalse("spread over four sources", terms.concentrated("datenschutz"));
        assertEquals("an acronym in every case", 4, sum(terms.sourcesOf("#RFID")));
        assertTrue(terms.concentrated("#RFID"));
        assertFalse("fewer than four proposals", terms.concentrated("lager"));
        assertEquals(2, sum(terms.sourcesOf("lager")));
        assertFalse("no proposal holds it", terms.concentrated("radweg"));
        assertEquals(0.0, terms.topShare("radweg"), 1e-12);
        assertFalse("rarity is off", terms.rare("lager") && terms.counts("lager"));
        assertEquals("zählstell", terms.shared(TopicMerger.RareTerms.termsOf("Wie Zählstellen messen"),
                TopicMerger.RareTerms.termsOf("Wo Zählstellen stehen")));
        assertNull(terms.shared(TopicMerger.RareTerms.termsOf("Datenschutz im Betrieb"),
                TopicMerger.RareTerms.termsOf("Datenschutz am Tor")));
        assertFalse("off at a share of 0", new TopicMerger.RareTerms(names, sources, 0, 0, 2, 4)
                .concentrated("zählstell"));
        assertTrue("one source of three", new TopicMerger.RareTerms(names, sources, 0, 0.75, 1, 4)
                .concentrated("#RFID"));
        assertFalse(new TopicMerger.RareTerms(names, sources, 0, 0.76, 1, 4).concentrated("#RFID"));
        assertTrue("rare or concentrated", new TopicMerger.RareTerms(names, sources, 0.2, 0.6, 2, 4).counts("lager"));
        assertEquals("#RFID", TopicMerger.RareTerms.termOfShown("RFID"));
        assertEquals("zählstell", TopicMerger.RareTerms.termOfShown("zählstell"));
    }

    private static int sum(Map<String, Integer> counts) {
        int sum = 0;
        for (int n : counts.values()) {
            sum += n;
        }
        return sum;
    }

    /**
     * The architect's analysis of c15j: within one place a subject fell into several families. Two parents of one
     * place, far apart, share the term "Zählstellen" that three of four proposals with it bring from two sources;
     * the pairs of the place meet once at any similarity and merge on "gleich".
     */
    @Test
    public void twoParentsOfOnePlaceThatShareAConcentratedTermMeetOnceBelowTheSimilarity() throws Exception {
        String note = TopicMerger.NOTE_PAIR + TopicMerger.NOTE_CONCENTRATED + "zählstell";
        String[] few = {"s1", "s1", "s2", "s3"};
        Result same = termPairRun("gleich", Options.defaults().withParallel(1), few);
        Map<String, Integer> counts = same.getParentCounts();
        assertEquals(1, (int) counts.get("rarePairs"));
        assertEquals(1, (int) counts.get("rarePairsMerged"));
        assertEquals(1, (int) counts.get("parentPairs"));
        assertEquals(1, (int) counts.get("parentsMerged"));
        assertEquals(0, (int) counts.get("rareAcross"));
        List<Comparison> pairs = noted(same.getAsked(), note);
        assertEquals(1, pairs.size());
        assertEquals(0.0, pairs.get(0).getSimilarity(), 1e-6);
        List<MergedTopic> formed = formed(same);
        assertEquals(1, formed.size());
        for (String id : new String[]{"a1", "b1", "c1", "x1", "y1", "z1"}) {
            assertEquals(id, formed.get(0).getId(), topicOf(same, id).getParentId());
        }

        Result apart = termPairRun("verschieden", Options.defaults().withParallel(1), few);
        assertEquals(1, (int) apart.getParentCounts().get("rarePairs"));
        assertEquals(0, (int) apart.getParentCounts().get("rarePairsMerged"));
        assertEquals(2, formed(apart).size());

        for (Options off : new Options[]{Options.defaults().withParallel(1).withTermPairs(false),
                Options.defaults().withParallel(1).withConcentration(0, 2, 4)}) {
            Result none = termPairRun("gleich", off, few);
            assertEquals(0, (int) none.getParentCounts().get("parentPairs"));
            assertEquals(0, (int) none.getParentCounts().get("rarePairs"));
            assertEquals(2, formed(none).size());
        }
        Result spread = termPairRun("gleich", Options.defaults().withParallel(1), new String[]{"s1", "s2", "s3",
                "s4"});
        assertEquals("four sources, half from the largest two", 0, (int) spread.getParentCounts().get("rarePairs"));
        assertEquals(2, formed(spread).size());

        Result rare = termPairRun("gleich", Options.defaults().withParallel(1).withConcentration(0, 2, 4)
                .withRareTerms(0.9), new String[]{"s1", "s2", "s3", "s4"});
        assertEquals(1, noted(rare.getAsked(), TopicMerger.NOTE_PAIR + TopicMerger.NOTE_RARE + "zählstell").size());
        assertEquals(1, (int) rare.getParentCounts().get("rarePairsMerged"));
    }

    /** Two parents at the top, far apart; {@code sources} are those of the four proposals with "Zählstellen". */
    private static Result termPairRun(final String pair, Options options, String[] sources) throws Exception {
        Table vectors = new Table()
                .put("Radverkehr", 0, 0, 0, 0, 0, 0, 0, 0, 1)
                .put("Wie gut sind Radwege ausgebaut?", 0, 0, 0, 0, 0, 0, 0, 0, 1);
        siblings(vectors, new double[]{1, 0}, 2, 9, "Zählschleifen", "Zählkameras", "Zählstellenbetrieb");
        siblings(vectors, new double[]{0, 1}, 5, 9, "Zählstellendaten", "Zählstellennetz", "Zählstellenkarte");
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s8", "Zählschleifen", true),
                proposal("b1", "s9", "Zählkameras", true), proposal("c1", sources[0], "Zählstellenbetrieb", true),
                proposal("x1", sources[1], "Zählstellendaten", true),
                proposal("y1", sources[2], "Zählstellennetz", true),
                proposal("z1", sources[3], "Zählstellenkarte", true));
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                return MainModelChatResult.ok(request.contains("Zählschleifen")
                        ? "{\"grund\":\"Die Technik.\",\"oberthema\":\"Zähltechnik\",\"beschreibung\":\"Wie "
                        + "Zählstellen den Verkehr messen.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                        : "{\"grund\":\"Die Orte.\",\"oberthema\":\"Orte der Zählung\",\"beschreibung\":"
                        + "\"Wo Zählstellen im Netz stehen.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (isCard(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\","
                        + "\"karte\":\"keine\",\"frage\":\"keine\"}");
            }
            return request.split("Teilthemen: ", -1).length == 3 ? relation(pair) : relation("verschieden");
        });
        return new TopicMerger(chat, vectors, MISSION, "de", options, null, CLOCK).merge(proposals, memory, null);
    }

    /**
     * The subject step at the top (the builder's lever after the architect's run c15k of 22.11.2026). Two parents of
     * one subject formed by aspect, far apart, and two lone heads of one subject: the model names each family's
     * subject from its parts, the families of one subject go to the pair question, and on "ja" each pair goes under a
     * new parent named by the subject; the parents stay below it as subfamilies, the depth allows it.
     */
    @Test
    public void familiesOfOneSubjectAtTheTopGoUnderANewParentNamedByIt() throws Exception {
        Options on = Options.defaults().withParallel(1).withSubjects(true, 0.85);
        Result result = subjectRun("ja", on, false, null);
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(4, (int) counts.get("subjectFamilies"));
        assertEquals(0, (int) counts.get("subjectFailed"));
        assertEquals(0, (int) counts.get("subjectCard"));
        assertEquals("the two parents and the two heads, not across", 2, (int) counts.get("subjectPairs"));
        assertEquals(2, (int) counts.get("subjectYes"));
        assertEquals(2, (int) counts.get("subjectRoofs"));
        assertEquals(4, (int) counts.get("subjectMerged"));
        assertEquals(2, (int) counts.get("subjectKept"));
        assertEquals(0, (int) counts.get("subjectFlat"));
        assertEquals(6, (int) counts.get("subjectLargest"));
        assertEquals(2, (int) counts.get("subjectLargestParts"));

        MergedTopic roof = formedNamed(result, "Fahrradstraßen");
        MergedTopic basics = formedNamed(result, "Grundlagen der Fahrradstraßen");
        MergedTopic law = formedNamed(result, "Recht der Fahrradstraßen");
        assertEquals("", roof.getParentId());
        assertEquals(roof.getId(), basics.getParentId());
        assertEquals(roof.getId(), law.getParentId());
        assertEquals(basics.getId(), topicOf(result, "d1").getParentId());
        assertEquals(law.getId(), topicOf(result, "r3").getParentId());
        assertTrue(roof.getDescription(), roof.getDescription().contains("Grundlagen der Fahrradstraßen"));
        MergedTopic noise = formedNamed(result, "Lärm an Bahnstrecken");
        assertEquals(noise.getId(), topicOf(result, "b1").getParentId());
        assertEquals(noise.getId(), topicOf(result, "b2").getParentId());
        assertEquals(4, formed(result).size());

        List<Comparison> named = kind(result.getAsked(), Comparison.Kind.GEGENSTAND);
        assertEquals(4, named.size());
        for (Comparison comparison : named) {
            assertEquals(Comparison.SUBJECT_MARK, comparison.getBText());
            assertEquals(6, comparison.getVersion());
            assertTrue(comparison.getNote(), comparison.getNote().startsWith(TopicMerger.NOTE_SUBJECT));
        }
        Comparison ofBasics = named.get(0);
        assertTrue(ofBasics.getAText(), ofBasics.getAText().startsWith("Oberthema: Grundlagen der Fahrradstraßen\n"
                + "Teilthemen: "));
        assertTrue("the parts, not only the parent's name", ofBasics.getAText().contains("Beschilderung von "
                + "Fahrradstraßen"));
        List<Comparison> pairs = kind(result.getAsked(), Comparison.Kind.GEGENSTANDSPAAR);
        assertEquals(2, pairs.size());
        assertEquals(TopicMerger.NOTE_SUBJECT_PAIR + "Fahrradstraßen / Fahrradstraßen", pairs.get(0).getNote());
        assertEquals(Relation.GLEICH, pairs.get(0).getRelation());
        assertEquals(1.0, pairs.get(0).getSimilarity(), 1e-6);
        List<TopicMerger.SubjectFinding> findings = result.getSubjects();
        assertEquals(2, findings.size());
        assertEquals(TopicMerger.SubjectFinding.ROOF, findings.get(0).getKind());
        assertEquals("Fahrradstraßen", findings.get(0).getName());
        assertEquals("", findings.get(0).getPlace());
        assertEquals(Arrays.asList("Grundlagen der Fahrradstraßen", "Recht der Fahrradstraßen"),
                findings.get(0).getFamilies());
        assertEquals(6, findings.get(0).getProposals());

        Result again = subjectRun("ja", on, false, result.getComparisons());
        assertEquals("every verdict reused", 0, kind(again.getAsked(), Comparison.Kind.GEGENSTAND).size()
                + kind(again.getAsked(), Comparison.Kind.GEGENSTANDSPAAR).size());
        assertEquals(2, (int) again.getParentCounts().get("subjectRoofs"));

        Result no = subjectRun("nein", on, false, null);
        assertEquals(2, (int) no.getParentCounts().get("subjectPairs"));
        assertEquals(0, (int) no.getParentCounts().get("subjectYes"));
        assertEquals(0, (int) no.getParentCounts().get("subjectRoofs"));
        assertEquals(2, formed(no).size());
        assertEquals("", formedNamed(no, "Grundlagen der Fahrradstraßen").getParentId());

        Result off = subjectRun("ja", Options.defaults().withParallel(1), false, null);
        assertEquals(0, kind(off.getComparisons(), Comparison.Kind.GEGENSTAND).size());
        assertEquals(0, (int) off.getParentCounts().get("subjectFamilies"));
        assertEquals(2, formed(off).size());
        assertTrue(off.getSubjects().isEmpty());
    }

    /**
     * Under a card the families' parents are laid flat: their parts go right under the new parent, which hangs under
     * the card; a family whose subject is the card's own stays where it is (the architect's card guard).
     */
    @Test
    public void underACardTheParentsAreLaidFlatAndTheCardsOwnSubjectDrawsNothingTogether() throws Exception {
        Options on = Options.defaults().withParallel(1).withSubjects(true, 0.85);
        Result result = subjectRun("ja", on, true, null);
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(2, (int) counts.get("subjectRoofs"));
        assertEquals("the two parents under the card", 2, (int) counts.get("subjectFlat"));
        assertEquals(0, (int) counts.get("subjectKept"));
        MergedTopic roof = formedNamed(result, "Fahrradstraßen");
        assertEquals("T-rad", roof.getParentId());
        for (String id : new String[]{"d1", "d2", "d3", "r1", "r2", "r3"}) {
            assertEquals(id, roof.getId(), topicOf(result, id).getParentId());
        }
        assertEquals("the aspect parents are formed no more", 2, formed(result).size());
        assertEquals("Radverkehr", result.getSubjects().get(0).getPlace());
        assertEquals(6, result.getSubjects().get(0).getParts());

        Result guarded = subjectRun("ja", on, true, null, "Radverkehr");
        Map<String, Integer> guard = guarded.getParentCounts();
        assertEquals(2, (int) guard.get("subjectCard"));
        assertEquals("the lone heads at the top still meet", 1, (int) guard.get("subjectPairs"));
        assertEquals(1, (int) guard.get("subjectRoofs"));
        assertEquals("T-rad", formedNamed(guarded, "Grundlagen der Fahrradstraßen").getParentId());
        assertEquals("T-rad", formedNamed(guarded, "Recht der Fahrradstraßen").getParentId());
        int cards = 0;
        for (TopicMerger.SubjectFinding finding : guarded.getSubjects()) {
            if (TopicMerger.SubjectFinding.CARD.equals(finding.getKind())) {
                cards++;
                assertEquals("Radverkehr", finding.getName());
                assertEquals(3, finding.getProposals());
            }
        }
        assertEquals(2, cards);
    }

    /** A failed subject holds its family back, so the next run asks again; the others go on. */
    @Test
    public void aFailedSubjectHoldsItsFamilyBack() throws Exception {
        Result result = subjectRun("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85), false, null,
                "Fahrradstraßen", true);
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(1, (int) counts.get("subjectFailed"));
        assertEquals(3, (int) counts.get("subjectFamilies"));
        assertEquals("only the parents meet", 1, (int) counts.get("subjectPairs"));
        assertTrue(result.getHeldBack().containsKey("b1"));
        assertTrue(result.getHeldBack().get("b1"), result.getHeldBack().get("b1").startsWith("subject step failed"));
    }

    /**
     * Subjects worded apart but near in their vectors meet too; the new parent takes the subject most families name;
     * each family meets at most {@link Options#getCandidates()} others, the nearest by their proposals first.
     */
    @Test
    public void nearSubjectsMeetTheMajorityNamesTheParentAndTheCandidatesAreBounded() throws Exception {
        SubjectCase near = new SubjectCase("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85));
        near.basics = "Fahrradstraße";
        Result result = near.merge();
        assertEquals("„Fahrradstraße“ and „Fahrradstraßen“ at 0.995", 2,
                (int) result.getParentCounts().get("subjectPairs"));
        Comparison pair = kind(result.getAsked(), Comparison.Kind.GEGENSTANDSPAAR).get(0);
        assertEquals(TopicMerger.NOTE_SUBJECT_PAIR + "Fahrradstraße / Fahrradstraßen", pair.getNote());
        assertEquals(0.995, pair.getSimilarity(), 1e-3);
        assertEquals("a tie of votes and of proposals: the first family's subject", "",
                formedNamed(result, "Fahrradstraße").getParentId());
        SubjectCase apart = new SubjectCase("ja", Options.defaults().withParallel(1).withSubjects(true, 0.999));
        apart.basics = "Fahrradstraße";
        Result far = apart.merge();
        assertEquals("below the similarity only the noise meets", 1, (int) far.getParentCounts().get("subjectPairs"));

        SubjectCase three = new SubjectCase("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85));
        three.thirdNoise = true;
        Result all = three.merge();
        assertEquals("the cycle parents, then every pair of the three noise heads", 4,
                (int) all.getParentCounts().get("subjectPairs"));
        MergedTopic noise = formedNamed(all, "Lärm an Bahnstrecken");
        for (String id : new String[]{"b1", "b2", "b3"}) {
            assertEquals(id, noise.getId(), topicOf(all, id).getParentId());
        }
        assertEquals(3, (int) all.getParentCounts().get("subjectLargestParts"));
        SubjectCase bounded = new SubjectCase("ja", Options.defaults().withParallel(1).withCandidates(1)
                .withSubjects(true, 0.85));
        bounded.thirdNoise = true;
        Result one = bounded.merge();
        assertEquals("each noise head with its nearest: Bahnlärm and Schienenlärm both to Streckenlärm", 3,
                (int) one.getParentCounts().get("subjectPairs"));
        MergedTopic chained = formedNamed(one, "Lärm an Bahnstrecken");
        assertEquals("joined through Streckenlärm", chained.getId(), topicOf(one, "b1").getParentId());
        assertEquals(chained.getId(), topicOf(one, "b2").getParentId());
    }

    /** The card guard also by the vector: a subject worded apart from the card but at its vector draws nothing. */
    @Test
    public void aSubjectAtTheCardsVectorDrawsNothingTogether() throws Exception {
        Result guarded = subjectRun("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85), true, null,
                "Radverkehr in Städten");
        assertEquals(2, (int) guarded.getParentCounts().get("subjectCard"));
        assertEquals(1, (int) guarded.getParentCounts().get("subjectRoofs"));
        assertEquals("T-rad", formedNamed(guarded, "Recht der Fahrradstraßen").getParentId());
    }

    /**
     * The card guard has its own threshold (default 0.85): a subject at 0.75 to the card's name draws its families
     * together under the card when only the pairs' threshold drops to 0.70, and is held by the guard at 0.70.
     */
    @Test
    public void theCardGuardKeepsItsOwnThresholdWhenThePairsThresholdDrops() throws Exception {
        assertEquals(0.85, Options.defaults().getSubjectCard(), 1e-9);
        assertEquals(0.85, Options.defaults().withSubjects(true, 0.70).getSubjectCard(), 1e-9);
        assertEquals(0.70, Options.defaults().withSubjectCard(0.70).withSubjects(true, 0.85).getSubjectCard(), 1e-9);
        assertEquals(0.70, Options.defaults().withSubjects(true, 0.85).withSubjectCard(0.70).getSubjectCard(), 1e-9);
        assertEquals(0.85, Options.defaults().withSubjectCard(0.70).withSubjects(true, 0.85).getSubjectSimilarity(),
                1e-9);
        Result pairsLower = subjectRun("ja", Options.defaults().withParallel(1).withSubjects(true, 0.70), true, null,
                "Radverkehrsstraßen");
        assertEquals(0, (int) pairsLower.getParentCounts().get("subjectCard"));
        MergedTopic roof = formedNamed(pairsLower, "Radverkehrsstraßen");
        assertEquals(roof.getId(), topicOf(pairsLower, "d1").getParentId());
        assertEquals(roof.getId(), topicOf(pairsLower, "r1").getParentId());
        Result guarded = subjectRun("ja", Options.defaults().withParallel(1).withSubjects(true, 0.70)
                .withSubjectCard(0.70), true, null, "Radverkehrsstraßen");
        assertEquals(2, (int) guarded.getParentCounts().get("subjectCard"));
        assertEquals("T-rad", formedNamed(guarded, "Recht der Fahrradstraßen").getParentId());
        assertEquals(1, (int) guarded.getParentCounts().get("subjectRoofs"));
        assertEquals(2, (int) pairsLower.getParentCounts().get("subjectRoofs"));
        for (double wrong : new double[]{0, -0.1, 1.01, Double.NaN}) {
            try {
                Options.defaults().withSubjectCard(wrong);
                fail("card guard " + wrong);
            } catch (IllegalArgumentException expected) {
                // the guard must lie in (0, 1]
            }
        }
    }

    /** A parent named like the subject is laid flat: the new parent takes its place; the other stays a subfamily. */
    @Test
    public void aParentNamedLikeTheSubjectGivesItsPlaceToTheNewParent() throws Exception {
        Result result = subjectRun("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85), false, null,
                "Grundlagen der Fahrradstraßen");
        assertEquals(1, (int) result.getParentCounts().get("subjectKept"));
        assertEquals(1, (int) result.getParentCounts().get("subjectFlat"));
        List<MergedTopic> named = new ArrayList<MergedTopic>();
        for (MergedTopic topic : formed(result)) {
            if (topic.getName().equals("Grundlagen der Fahrradstraßen")) {
                named.add(topic);
            }
        }
        assertEquals("one parent of that name: the new one", 1, named.size());
        MergedTopic roof = named.get(0);
        assertEquals("", roof.getParentId());
        assertEquals(roof.getId(), topicOf(result, "d1").getParentId());
        assertEquals(roof.getId(), formedNamed(result, "Recht der Fahrradstraßen").getParentId());
        assertEquals(3, formed(result).size());
    }

    /** A pair judged before in the other order is reused: the answer is the same either way. */
    @Test
    public void aPairJudgedInTheOtherOrderIsReused() throws Exception {
        Options on = Options.defaults().withParallel(1).withSubjects(true, 0.85);
        Result first = subjectRun("ja", on, false, null);
        List<Comparison> turned = new ArrayList<Comparison>();
        for (Comparison c : first.getComparisons()) {
            turned.add(c.getKind() != Comparison.Kind.GEGENSTANDSPAAR ? c : new Comparison(c.getKind(), c.getBId(),
                    c.getBText(), c.getAId(), c.getAText(), false, c.getSimilarity(), c.getStatus(), c.getRelation(),
                    c.getReason(), "", c.getAnswers(), c.getModel(), c.getTimeMillis(), c.getMillis(), c.getVersion(),
                    c.getNote()));
        }
        Result again = subjectRun("nein", on, false, turned);
        assertEquals(0, kind(again.getAsked(), Comparison.Kind.GEGENSTANDSPAAR).size());
        assertEquals("the reused „ja“, not the model's „nein“ now", 2,
                (int) again.getParentCounts().get("subjectRoofs"));
    }

    /**
     * The review of fcca35cf: a subfamily kept under a new parent at the top that later gives its parts away is no
     * part of the new parent any more; before, the new parent kept the dead node and the next card step crashed on it.
     */
    @Test
    public void aKeptSubfamilyThatGivesItsPartsAwayLeavesTheNewParent() throws Exception {
        SubjectCase run = new SubjectCase("ja", Options.defaults().withParallel(1).withCandidates(1)
                .withSubjects(true, 0.85));
        run.crowd = true;
        Result result = run.merge();
        assertEquals(2, (int) result.getParentCounts().get("subjectKept"));
        MergedTopic roof = formedNamed(result, "Fahrradstraßen");
        MergedTopic law = formedNamed(result, "Recht der Fahrradstraßen");
        assertEquals(roof.getId(), law.getParentId());
        assertEquals("the basics gave their parts to the law", law.getId(), topicOf(result, "d1").getParentId());
        assertTrue(result.getParentCounts().get("parentsMerged") >= 1);
        for (MergedTopic topic : formed(result)) {
            assertFalse("no empty parent: " + topic.getName(), topic.getName().equals("Grundlagen der Fahrradstraßen"));
        }
        List<String> carded = new ArrayList<String>();
        for (Comparison comparison : result.getComparisons()) {
            if (comparison.getKind() == Comparison.Kind.KARTE
                    && comparison.getAText().startsWith("Fahrradstraßen\n")) {
                carded.add(comparison.getAText());
            }
        }
        assertEquals("the new parent as formed, then once more with one subfamily", 2, carded.size());
        assertTrue(carded.get(1), carded.get(1).endsWith("\nTeilthemen: Recht der Fahrradstraßen"));
    }

    /**
     * A name must be free as for every new parent: a head of the families worded like the subject becomes the parent
     * itself; a subject worded like a known topic forms no parent.
     */
    @Test
    public void aHeadNamedLikeTheSubjectBecomesTheParentAndATakenNameFormsNone() throws Exception {
        SubjectCase head = new SubjectCase("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85));
        head.noiseSubject = "Bahnlärm";
        Result result = head.merge();
        assertEquals(1, (int) result.getParentCounts().get("subjectHeadRoofs"));
        assertEquals(2, (int) result.getParentCounts().get("subjectRoofs"));
        assertEquals(topicOf(result, "b1").getId(), topicOf(result, "b2").getParentId());
        assertEquals("", topicOf(result, "b1").getParentId());
        for (MergedTopic topic : formed(result)) {
            assertFalse(topic.getName().equals("Bahnlärm"));
        }

        SubjectCase card = new SubjectCase("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85));
        card.noiseSubject = "Radverkehr";
        Result taken = card.merge();
        assertEquals(2, (int) taken.getParentCounts().get("subjectNameTaken"));
        assertEquals("only the cycle parents", 1, (int) taken.getParentCounts().get("subjectRoofs"));
        assertEquals("", topicOf(taken, "b2").getParentId());
    }

    /** A family alone at its place is not named; without the step across places a new parent at the top is carded. */
    @Test
    public void aFamilyAloneIsNotNamedAndATopParentIsCardedWithoutTheStepAcrossPlaces() throws Exception {
        SubjectCase alone = new SubjectCase("ja", Options.defaults().withParallel(1).withSubjects(true, 0.85));
        alone.underCard = true;
        alone.oneNoise = true;
        Result result = alone.merge();
        assertEquals("the two parents under the card, not Bahnlärm alone at the top", 2,
                (int) result.getParentCounts().get("subjectFamilies"));

        Result across = subjectRun("ja", Options.defaults().withParallel(1).withAcrossPlaces(false)
                .withSubjects(true, 0.85), false, null);
        int carded = 0;
        for (Comparison comparison : across.getComparisons()) {
            carded += comparison.getKind() == Comparison.Kind.KARTE
                    && comparison.getAText().startsWith("Fahrradstraßen\n") ? 1 : 0;
        }
        assertEquals(1, carded);
    }

    @Test
    public void theSubjectIsOneToFourWordsAndThePairJaOrNein() {
        assertEquals("Fahrradstraßen", TopicMerger.parseSubject("{\"grund\":\"x\",\"gegenstand\":"
                + "\" \u201eFahrradstraßen\u201c. \"}").subject);
        assertEquals("Lärm an Bahnstrecken", TopicMerger.parseSubject("{\"grund\":\"x\",\"gegenstand\":"
                + "\"Lärm an Bahnstrecken\"}").subject);
        assertEquals(Relation.A_TEIL_VON_B, TopicMerger.parseSubject("{\"grund\":\"x\",\"gegenstand\":"
                + "\"Tempo-30-Zonen\"}").relation);
        assertFalse(TopicMerger.parseSubject("{\"grund\":\"x\",\"gegenstand\":\"Recht der Fahrradstraßen in Städten\"}")
                .problem.isEmpty());
        assertFalse(TopicMerger.parseSubject("{\"grund\":\"x\",\"gegenstand\":\"keiner\"}").problem.isEmpty());
        assertFalse(TopicMerger.parseSubject("{\"grund\":\"x\",\"gegenstand\":\"\"}").problem.isEmpty());
        assertFalse(TopicMerger.parseSubject("{\"grund\":\"x\"}").problem.isEmpty());
        assertEquals(Relation.GLEICH, TopicMerger.parseSubjectPair("{\"grund\":\"x\",\"antwort\":\"ja\"}").relation);
        assertEquals(Relation.VERSCHIEDEN, TopicMerger.parseSubjectPair("{\"grund\":\"x\",\"antwort\":\"Nein\"}")
                .relation);
        assertFalse(TopicMerger.parseSubjectPair("{\"grund\":\"x\",\"antwort\":\"vielleicht\"}").problem.isEmpty());
        assertTrue(TopicMerger.subjectPrompt(MISSION, "Thema: Bahnlärm", true).contains("nicht nur aus dem Namen"));
        assertTrue(TopicMerger.subjectPairPrompt(MISSION, "a", "b", true).contains("Im Zweifel \"nein\""));
    }

    private static Result subjectRun(String pair, Options options, boolean underCard, List<Comparison> earlier)
            throws Exception {
        return subjectRun(pair, options, underCard, earlier, "Fahrradstraßen");
    }

    private static Result subjectRun(String pair, Options options, boolean underCard, List<Comparison> earlier,
                                     String cycleSubject) throws Exception {
        SubjectCase run = new SubjectCase(pair, options);
        run.underCard = underCard;
        run.earlier = earlier;
        run.basics = cycleSubject;
        run.law = cycleSubject;
        return run.merge();
    }

    private static Result subjectRun(String pair, Options options, boolean underCard, List<Comparison> earlier,
                                     String cycleSubject, boolean noiseFails) throws Exception {
        SubjectCase run = new SubjectCase(pair, options);
        run.underCard = underCard;
        run.earlier = earlier;
        run.basics = cycleSubject;
        run.law = cycleSubject;
        run.noiseFails = noiseFails;
        return run.merge();
    }

    /**
     * Two parents of one subject by aspect, far apart (at the top, or with {@code underCard} under the card
     * „Radverkehr“), the basics named {@code basics}, the law {@code law}, and two lone heads on railway noise at the
     * top (with {@code thirdNoise} a third between them); the model joins the families of a pair as {@code pair} says;
     * with {@code noiseFails} the subject of „Bahnlärm“ fails.
     */
    private static final class SubjectCase {
        final String pair;
        final Options options;
        boolean underCard;
        List<Comparison> earlier;
        String basics = "Fahrradstraßen";
        String law = "Fahrradstraßen";
        boolean noiseFails;
        boolean thirdNoise;
        /** Only „Bahnlärm“, no second noise head. */
        boolean oneNoise;
        String noiseSubject = "Lärm an Bahnstrecken";
        /**
         * Three parents at the top: the two of one subject 0.66 apart and „Bau der Radwege“ at 0.70 to each; with one
         * candidate the two meet first under their new parent, where the model calls the basics part of the law.
         */
        boolean crowd;

        SubjectCase(String pair, Options options) {
            this.pair = pair;
            this.options = options;
        }

        Result merge() throws Exception {
            Table vectors = crowd ? crowdVectors() : new Table()
                    .put("Radverkehr", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)
                    .put("Radverkehr in Städten", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)
                    .put("Wie gut sind Radwege ausgebaut?", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)
                    .put("Bahnlärm", 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0)
                    .put("Schienenlärm", 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0)
                    .put("Streckenlärm", 0, 0, 0, 0, 0, 0, 0, 0, 0.6, 0.8, 0, 0)
                    .put("Lärm an Bahnstrecken", 0, 0, 0, 0, 0, 0, 0, 0, Math.sqrt(0.5), Math.sqrt(0.5), 0, 0)
                    .put("Fahrradstraßen", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
                    .put("Fahrradstraße", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.1, 0.995)
                    .put("Radverkehrsstraßen", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.75, Math.sqrt(1 - 0.75 * 0.75))
                    .put("Grundlagen der Fahrradstraßen", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
            if (!crowd) {
                siblings(vectors, new double[]{1, 0}, 2, 12, "Definition der Fahrradstraße", "Beschilderung von "
                        + "Fahrradstraßen", "Geschichte der Fahrradstraße");
                siblings(vectors, new double[]{0, 1}, 5, 12, "Fahrradstraßen in der StVO", "Anlieger in "
                        + "Fahrradstraßen", "Bußgelder in Fahrradstraßen");
            }
            List<TopicProposal> proposals = new ArrayList<TopicProposal>(Arrays.asList(
                    proposal("d1", "s1", "Definition der Fahrradstraße", true),
                    proposal("d2", "s2", "Beschilderung von Fahrradstraßen", true),
                    proposal("d3", "s3", "Geschichte der Fahrradstraße", true),
                    proposal("r1", "s4", "Fahrradstraßen in der StVO", true),
                    proposal("r2", "s5", "Anlieger in Fahrradstraßen", true),
                    proposal("r3", "s6", "Bußgelder in Fahrradstraßen", true),
                    proposal("b1", "s7", "Bahnlärm", true)));
            if (!oneNoise) {
                proposals.add(proposal("b2", "s8", "Schienenlärm", true));
            }
            if (crowd) {
                proposals.add(proposal("w1", "s10", "Radwegbreite", true));
                proposals.add(proposal("w2", "s11", "Radwegbelag", true));
                proposals.add(proposal("w3", "s12", "Radwegmarkierung", true));
            }
            if (thirdNoise) {
                proposals.add(proposal("b3", "s9", "Streckenlärm", true));
            }
            ResearchMemory memory = new ResearchMemory();
            memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
            memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                    ResearchQuestion.Origin.KONZEPT, 1L));
            Scripted chat = new Scripted(m -> {
                String request = prompt(m);
                if (TopicMerger.SUBJECT_SYSTEM_DE.equals(m.get(0).getContent())) {
                    if (request.contains("Familie A:")) {
                        return MainModelChatResult.ok("{\"grund\":\"Dieselbe Sache.\",\"antwort\":\"" + pair + "\"}");
                    }
                    if (request.contains("Fahrradstraße")) {
                        return MainModelChatResult.ok("{\"grund\":\"Die Straßen.\",\"gegenstand\":\""
                                + (request.contains("Definition der Fahrradstraße") ? basics : law) + "\"}");
                    }
                    if (noiseFails && request.contains("Thema: Bahnlärm")) {
                        return MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow");
                    }
                    if (thirdNoise && request.contains("Thema: Bahnlärm")) {
                        return MainModelChatResult.ok("{\"grund\":\"Der Lärm.\",\"gegenstand\":\"Streckenlärm\"}");
                    }
                    if (request.contains("Radwegbreite")) {
                        return MainModelChatResult.ok("{\"grund\":\"Die Wege.\",\"gegenstand\":\"Radwege\"}");
                    }
                    return MainModelChatResult.ok("{\"grund\":\"Der Lärm.\",\"gegenstand\":\"" + noiseSubject
                            + "\"}");
                }
                if (isParent(m) && request.contains("Radwegbreite")) {
                    return MainModelChatResult.ok("{\"grund\":\"Der Bau.\",\"oberthema\":\"Bau der Radwege\","
                            + "\"beschreibung\":\"Wie Radwege gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
                }
                if (isParent(m)) {
                    return MainModelChatResult.ok(request.contains("Definition der Fahrradstraße")
                            ? "{\"grund\":\"Die Grundlagen.\",\"oberthema\":\"Grundlagen der Fahrradstraßen\","
                            + "\"beschreibung\":\"Was Fahrradstraßen sind.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                            : "{\"grund\":\"Das Recht.\",\"oberthema\":\"Recht der Fahrradstraßen\","
                            + "\"beschreibung\":\"Welche Regeln gelten.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
                }
                if (isCard(m)) {
                    return underCard && request.contains("Fahrradstraße")
                            ? MainModelChatResult.ok("{\"grund\":\"Radverkehr.\",\"zuordnung\":\"teil_von\","
                            + "\"karte\":\"T-rad\",\"frage\":\"F1\"}")
                            : MainModelChatResult.ok("{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":"
                            + "\"keine\",\"karte\":\"keine\",\"frage\":\"keine\"}");
                }
                if (request.contains("Thema A:\nGrundlagen der Fahrradstraßen\n")
                        && request.contains("Thema B:\nRecht der Fahrradstraßen\n")) {
                    return relation("a_teil_von_b");
                }
                return relation("verschieden");
            });
            return new TopicMerger(chat, vectors, MISSION, "de", options, null, CLOCK).merge(proposals, memory,
                    earlier);
        }

        /**
         * The basics along (1, 0, 0), the law along (0.75, 0.661, 0), the building of cycle lanes along a direction at
         * 0.8 to both: as parents 0.66 apart and 0.70 to the building, as heads below the parent step's 0.60.
         */
        private Table crowdVectors() {
            Table vectors = new Table()
                    .put("Radverkehr", unitAt(14))
                    .put("Wie gut sind Radwege ausgebaut?", unitAt(14))
                    .put("Bahnlärm", unitAt(12))
                    .put("Schienenlärm", unitAt(13))
                    .put("Lärm an Bahnstrecken", unitAt(12))
                    .put("Fahrradstraßen", unitAt(15))
                    .put("Radwege", unitAt(16));
            siblings(vectors, new double[]{1, 0, 0}, 3, 17, "Definition der Fahrradstraße", "Beschilderung von "
                    + "Fahrradstraßen", "Geschichte der Fahrradstraße");
            siblings(vectors, new double[]{0.75, 0.661438, 0}, 6, 17, "Fahrradstraßen in der StVO", "Anlieger in "
                    + "Fahrradstraßen", "Bußgelder in Fahrradstraßen");
            siblings(vectors, new double[]{0.8, 0.302372, 0.518245}, 9, 17, "Radwegbreite", "Radwegbelag",
                    "Radwegmarkierung");
            return vectors;
        }

        private double[] unitAt(int dimension) {
            double[] values = new double[17];
            values[dimension] = 1;
            return values;
        }
    }

    private static MergedTopic formedNamed(Result result, String name) {
        for (MergedTopic topic : formed(result)) {
            if (topic.getName().equals(name)) {
                return topic;
            }
        }
        throw new AssertionError("no parent named " + name + " in " + names(formed(result)));
    }

    private static List<String> names(List<MergedTopic> topics) {
        List<String> names = new ArrayList<String>();
        for (MergedTopic topic : topics) {
            names.add(topic.getName());
        }
        return names;
    }

    private static List<Comparison> kind(List<Comparison> comparisons, Comparison.Kind kind) {
        List<Comparison> out = new ArrayList<Comparison>();
        for (Comparison comparison : comparisons) {
            if (comparison.getKind() == kind) {
                out.add(comparison);
            }
        }
        return out;
    }

    private static Set<String> terms(String... terms) {
        return new HashSet<String>(Arrays.asList(terms));
    }

    /**
     * Depth 3. „Radwegnetz“ (two sources) at the top has the parts „Radwegbreite“, „Radwegbelag“ and
     * „Radwegmarkierung“, which the second pass puts under „Bau der Radwege“, and „Radwegbeleuchtung“ (two sources),
     * which has the part „Lampenwartung“ and meets the three only at 0.56 but their parent at 0.65. Three heads on
     * planning stand at the top apart from the net (0.57) and get „Planung des Verkehrs“, which the net meets at
     * 0.66. The vectors come from the wanted cosines (Cholesky).
     */
    private static Table deepVectors() {
        return new Table()
                .put("Radwegnetz", 1, 0, 0, 0, 0, 0, 0, 0, 0)
                .put("Radwegbreite", 0.7, 0.714143, 0, 0, 0, 0, 0, 0, 0)
                .put("Radwegbelag", 0.7, 0.182036, 0.690552, 0, 0, 0, 0, 0, 0)
                .put("Radwegmarkierung", 0.7, 0.182036, 0.140268, 0.676156, 0, 0, 0, 0, 0)
                .put("Radwegbeleuchtung", 0.7, 0.09802, 0.075529, 0.061469, 0.700649, 0, 0, 0, 0)
                .put("Lampenwartung", 0.5, 0.070014, 0.053949, 0.043906, 0.480074, 0.713995, 0, 0, 0)
                .put("Verkehrsplanung", 0.57, 0.0014, 0.001079, 0.000878, 0.001038, 0.020038, 0.821397, 0, 0)
                .put("Verkehrszählung", 0.57, 0.0014, 0.001079, 0.000878, 0.001038, 0.020038, 0.358771, 0.738902, 0)
                .put("Verkehrsmodelle", 0.57, 0.0014, 0.001079, 0.000878, 0.001038, 0.020038, 0.358771, 0.224626,
                        0.703932);
    }

    private static List<TopicProposal> deepProposals() {
        return Arrays.asList(proposal("n1", "q1", "Radwegnetz", true), proposal("n2", "q2", "Radwegnetz", true),
                proposal("b1", "q3", "Radwegbreite", true), proposal("b2", "q4", "Radwegbelag", true),
                proposal("b3", "q5", "Radwegmarkierung", true), proposal("l1", "q6", "Radwegbeleuchtung", true),
                proposal("l2", "q7", "Radwegbeleuchtung", true), proposal("w1", "q8", "Lampenwartung", true),
                proposal("v1", "q9", "Verkehrsplanung", true), proposal("v2", "q10", "Verkehrszählung", true),
                proposal("v3", "q11", "Verkehrsmodelle", true));
    }

    /** Parts under the net and the lighting; the net to the planning parent answered by {@code net}. */
    private static Scripted deepChat(final MainModelChatResult net) {
        return new Scripted(m -> {
            String request = prompt(m);
            if (isParent(m)) {
                return MainModelChatResult.ok(request.contains("Verkehrsplanung")
                        ? "{\"grund\":\"Planung.\",\"oberthema\":\"Planung des Verkehrs\",\"beschreibung\":\"Wie "
                        + "Verkehr geplant wird.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                        : "{\"grund\":\"Der Bau.\",\"oberthema\":\"Bau der Radwege\",\"beschreibung\":\"Wie Radwege "
                        + "gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            if (request.contains("Thema A:\nRadwegnetz\n")) {
                return net;
            }
            return relation(request.contains("Thema B:\nRadwegnetz\n")
                    || request.contains("Thema B:\nRadwegbeleuchtung\n")
                    || request.contains("Thema B:\nBau der Radwege\n") ? "a_teil_von_b" : "verschieden");
        });
    }

    @Test
    public void twoJoinsOfOneBatchDoNotStackPastTheDepth() throws Exception {
        Result result = new TopicMerger(deepChat(relation("a_teil_von_b")), deepVectors(), MISSION, "de",
                Options.defaults().withParallel(1).withSubtopics(2, 3), null, CLOCK)
                .merge(deepProposals(), new ResearchMemory(), null);

        Map<String, Integer> counts = result.getParentCounts();
        assertEquals("the net to the planning, the lighting to the building", 2, (int) counts.get("looseCases"));
        assertEquals("the net went first; then the lighting had no room left", 1, (int) counts.get("looseJoined"));
        assertEquals("„Bau der Radwege“ stands under the net, in the planning's own subtree: no pair across places "
                + "(the review of 22.11.2026)", 0, (int) counts.get("acrossPairs"));
        assertEquals(0, (int) counts.get("acrossTooDeep"));
        assertTrue(noted(result.getAsked(), TopicMerger.NOTE_ACROSS).isEmpty());
        MergedTopic net = topicOf(result, "n1");
        assertEquals("Planung des Verkehrs", result.topic(net.getParentId()).getName());
        assertEquals("the lighting stays under the net", net.getId(), topicOf(result, "l1").getParentId());
        String building = topicOf(result, "b1").getParentId();
        assertEquals("Bau der Radwege", result.topic(building).getName());
        assertEquals(net.getId(), result.topic(building).getParentId());
        for (MergedTopic topic : result.getTopics()) {
            int levels = 0;
            for (MergedTopic at = topic; !at.getParentId().isEmpty(); at = result.topic(at.getParentId())) {
                levels++;
            }
            assertTrue(topic.getName() + " at level " + levels, levels <= 3);
        }
    }

    @Test
    public void aParentUnderAHeadAFailedCaseHeldBackIsHeldBackToo() throws Exception {
        Result result = new TopicMerger(deepChat(MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT,
                "slow")), deepVectors(), MISSION, "de", Options.defaults().withParallel(1).withSubtopics(2, 3), null,
                CLOCK).merge(deepProposals(), new ResearchMemory(), null);

        for (String id : new String[]{"n1", "n2", "b1", "b2", "b3", "l1", "l2", "w1"}) {
            assertTrue(id, result.getHeldBack().get(id).startsWith("loose head case failed: "));
        }
        List<MergedTopic> formed = formed(result);
        assertEquals("„Bau der Radwege“ comes out neither empty nor at the top", 1, formed.size());
        assertEquals("Planung des Verkehrs", formed.get(0).getName());
    }

    @Test
    public void aStoredPairVerdictIsReusedInTheOtherOrderWithItsNote() throws Exception {
        Table vectors = new Table();
        List<TopicProposal> proposals = radwegProposals(vectors);
        Result first = new TopicMerger(twoParentsChat("a_teil_von_b"), vectors, MISSION, "de",
                Options.defaults().withParallel(1), null, CLOCK).merge(proposals, new ResearchMemory(), null);
        Comparison pair = noted(first.getAsked(), TopicMerger.NOTE_PAIR).get(0);
        List<Comparison> earlier = new ArrayList<Comparison>(first.getAsked());
        earlier.remove(pair);
        earlier.add(new Comparison(Comparison.Kind.THEMA, pair.getBId(), pair.getBText(), pair.getAId(),
                pair.getAText(), false, pair.getSimilarity(), Comparison.Status.OK, Relation.B_TEIL_VON_A,
                pair.getReason(), "", pair.getAnswers(), pair.getModel(), pair.getTimeMillis(), 0L));

        Scripted silent = new Scripted(m -> {
            throw new AssertionError("reused, not asked");
        });
        Result again = new TopicMerger(silent, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, new ResearchMemory(), earlier);

        assertEquals(0, silent.calls.get());
        List<Comparison> reused = noted(again.getComparisons(), TopicMerger.NOTE_PAIR);
        assertEquals(1, reused.size());
        assertEquals(pair.getAText(), reused.get(0).getAText());
        assertEquals("turned round", Relation.A_TEIL_VON_B, reused.get(0).getRelation());
        assertEquals(formed(first).get(0).getName(), formed(again).get(0).getName());
        assertEquals(1, formed(again).size());
    }

    /** The fixture of {@link #aHeadNoCaseTookInJoinsTheMostSimilarParentOfItsPlaceUnlessItsCaseJudgedIt}. */
    private static Result looseRun(final MainModelChatResult loose) throws Exception {
        return looseRun(loose, Options.defaults().withParallel(1));
    }

    private static Result looseRun(final MainModelChatResult loose, Options options) throws Exception {
        double far = 0.68;
        Table vectors = new Table()
                .put("Radwegbreite", SIBLING_SHARED, SIBLING_OWN, 0, 0, 0, 0)
                .put("Radwegbelag", SIBLING_SHARED, 0, SIBLING_OWN, 0, 0, 0)
                .put("Radwegmarkierung", SIBLING_SHARED, 0, 0, SIBLING_OWN, 0, 0)
                .put("Radwegpflege", SIBLING_SHARED, 0, 0, 0, SIBLING_OWN, 0)
                .put("Radwegzählung", far, 0, 0, 0, 0, Math.sqrt(1 - far * far));
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Radwegbreite", true),
                proposal("b1", "s2", "Radwegbelag", true), proposal("c1", "s3", "Radwegmarkierung", true),
                proposal("e1", "s4", "Radwegpflege", true), proposal("d1", "s5", "Radwegzählung", true));
        Scripted chat = new Scripted(m -> {
            if (isParent(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Bau von Radwegen.\",\"oberthema\":\"Radwegbau\","
                        + "\"beschreibung\":\"Wie Radwege gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            return prompt(m).contains("Teilthemen: ") ? loose : relation("verschieden");
        });
        return new TopicMerger(chat, vectors, MISSION, "de", options, null, CLOCK)
                .merge(proposals, new ResearchMemory(), null);
    }

    @Test
    public void aLooseHeadJoinsOnlyAsAPartOrTheSameAndAFailedCaseHoldsItBack() throws Exception {
        Result same = looseRun(relation("gleich"));
        assertEquals(1, (int) same.getParentCounts().get("looseJoined"));
        assertEquals(topicOf(same, "a1").getParentId(), topicOf(same, "d1").getParentId());

        for (String apart : new String[]{"b_teil_von_a", "verschieden"}) {
            Result stays = looseRun(relation(apart));
            assertEquals(apart, 0, (int) stays.getParentCounts().get("looseJoined"));
            assertEquals(apart, "", topicOf(stays, "d1").getParentId());
            assertEquals("the same two texts are not asked again for the heads that stand alone", 0,
                    (int) stays.getParentCounts().get("singleCases"));
        }

        Result failed = looseRun(MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow"));
        String reason = failed.getHeldBack().get("d1");
        assertTrue(reason, reason.startsWith("loose head case failed: "));
        assertEquals(Comparison.Status.FEHLER, noted(failed.getAsked(), TopicMerger.NOTE_LOOSE).get(0).getStatus());
        assertEquals("the parent is placed", 1, formed(failed).size());
    }

    /**
     * „Radwegbau“ over three heads at the top, „Fahrradparken“ over three heads under the card Parken; their mean
     * vectors are apart. „Radabstellanlagen“ meets the first parent at 0.64 and the second at 0.62, „Radwegzählung“
     * only the first (0.64); neither comes into a case. The consolidation at the top puts both to „Radwegbau“ and
     * hears „verschieden“ for „Radabstellanlagen“ and {@code counting} for „Radwegzählung“. „Radparkhäuser“ stands
     * alone right under the card Radverkehr and meets only „Fahrradparken“ (0.62). A head put to „Fahrradparken“
     * hears {@code parking}.
     */
    private static Result secondParentRun(final MainModelChatResult counting, final MainModelChatResult parking)
            throws Exception {
        double toBuilding = 0.68;
        double toParking = 0.66;
        Table vectors = new Table();
        siblings(vectors, new double[]{1, 0}, 2, 11, "Radwegbreite", "Radwegbelag", "Radwegmarkierung");
        siblings(vectors, new double[]{0, 1}, 5, 11, "Fahrradbügel", "Fahrradgaragen", "Fahrradständer");
        vectors.put("Radabstellanlagen", toBuilding, toParking, 0, 0, 0, 0, 0, 0,
                Math.sqrt(1 - toBuilding * toBuilding - toParking * toParking), 0, 0);
        vectors.put("Radwegzählung", toBuilding, 0, 0, 0, 0, 0, 0, 0, 0, Math.sqrt(1 - toBuilding * toBuilding), 0);
        vectors.put("Radparkhäuser", 0, toParking, 0, 0, 0, 0, 0, 0, 0, 0, Math.sqrt(1 - toParking * toParking));
        for (String card : new String[]{"Radverkehr", "Parken", "Wie gut sind Radwege ausgebaut?",
                "Was kostet Anwohnerparken?"}) {
            vectors.put(card, away(11));
        }
        List<TopicProposal> proposals = Arrays.asList(proposal("a1", "s1", "Radwegbreite", true),
                proposal("b1", "s2", "Radwegbelag", true), proposal("c1", "s3", "Radwegmarkierung", true),
                proposal("f1", "s4", "Fahrradbügel", true), proposal("f2", "s5", "Fahrradgaragen", true),
                proposal("f3", "s6", "Fahrradständer", true), proposal("d1", "s7", "Radabstellanlagen", true),
                proposal("z1", "s8", "Radwegzählung", true), proposal("u1", "s9", "Radparkhäuser", true));
        Scripted chat = new Scripted(m -> {
            String request = prompt(m);
            if (isCard(m)) {
                String topic = nameAfter(request, "\n\nThema:\n");
                String card = topic.startsWith("Fahrrad") ? "T-park" : topic.equals("Radparkhäuser") ? "T-rad" : "";
                return MainModelChatResult.ok(card.isEmpty()
                        ? "{\"grund\":\"Keine Frage der Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}"
                        : "{\"grund\":\"Eine Frage der Karte.\",\"zuordnung\":\"teil_von\",\"karte\":\"" + card
                        + "\",\"frage\":\"" + questionLabel(request, card) + "\"}");
            }
            if (isParent(m)) {
                return MainModelChatResult.ok(request.contains("Fahrradbügel")
                        ? "{\"grund\":\"Abstellen.\",\"oberthema\":\"Fahrradparken\",\"beschreibung\":\"Wo Räder "
                        + "abgestellt werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}"
                        : "{\"grund\":\"Bau von Radwegen.\",\"oberthema\":\"Radwegbau\",\"beschreibung\":\"Wie "
                        + "Radwege gebaut werden.\",\"themen\":[\"T1\",\"T2\",\"T3\"]}");
            }
            String b = nameAfter(request, "Thema B:\n");
            if (b.equals("Fahrradparken")) {
                return parking;
            }
            return b.equals("Radwegbau") && nameAfter(request, "Thema A:\n").equals("Radwegzählung") ? counting
                    : relation("verschieden");
        });
        return new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null, CLOCK)
                .merge(proposals, twoCards(), null);
    }

    /**
     * The review of 22.11.2026: a head the consolidation of its place put to a parent is passed over by that parent at
     * the end, as a head its case judged, whether the parent's text changed since or not, and goes to the next most
     * similar parent of any place (lever (b)). A single child right under a card may leave the card for it too.
     */
    @Test
    public void aHeadItsPlaceDeclinedGoesToTheNextMostSimilarParentOfAnyPlace() throws Exception {
        for (String counting : new String[]{"a_teil_von_b", "verschieden"}) {
            Result result = secondParentRun(relation(counting), relation("a_teil_von_b"));

            Map<String, Integer> counts = result.getParentCounts();
            assertEquals(counting, 2, (int) counts.get("looseCases"));
            assertEquals(counting, "verschieden".equals(counting) ? 0 : 1, (int) counts.get("looseJoined"));
            List<Comparison> single = noted(result.getAsked(), TopicMerger.NOTE_SINGLE);
            assertEquals(counting + ": „Radwegbau“ judged both heads at the top", 2, single.size());
            assertEquals(counting, "Radabstellanlagen", single.get(0).getAText());
            assertEquals(counting, "Radparkhäuser", single.get(1).getAText());
            for (Comparison comparison : single) {
                assertTrue(counting, comparison.getBText().startsWith("Fahrradparken\nWorum es geht: "));
            }
            assertEquals(counting, 2, (int) counts.get("singleCases"));
            assertEquals(counting, 2, (int) counts.get("singleJoined"));
            String parking = topicOf(result, "f1").getParentId();
            assertEquals(counting, "Fahrradparken", result.topic(parking).getName());
            assertEquals(counting, "T-park", result.topic(parking).getParentId());
            assertEquals(counting, parking, topicOf(result, "d1").getParentId());
            assertEquals(counting + ": it left the card Radverkehr", parking, topicOf(result, "u1").getParentId());
            assertEquals(counting, "verschieden".equals(counting) ? "" : topicOf(result, "a1").getParentId(),
                    topicOf(result, "z1").getParentId());
        }
    }

    @Test
    public void aFailedCaseOfAHeadThatStandsAloneHoldsBackTheHeadAndNotTheParent() throws Exception {
        Result failed = secondParentRun(relation("verschieden"),
                MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow"));

        for (String id : new String[]{"d1", "u1"}) {
            String reason = failed.getHeldBack().get(id);
            assertTrue(id + ": " + reason, reason != null && reason.startsWith("single head case failed: "));
        }
        List<Comparison> single = noted(failed.getAsked(), TopicMerger.NOTE_SINGLE);
        assertEquals(2, single.size());
        for (Comparison comparison : single) {
            assertEquals(Comparison.Status.FEHLER, comparison.getStatus());
        }
        assertEquals(0, (int) failed.getParentCounts().get("singleJoined"));
        String parking = topicOf(failed, "f1").getParentId();
        assertEquals("Fahrradparken", failed.topic(parking).getName());
        assertEquals("the parent stays where it is", "T-park", failed.topic(parking).getParentId());
        assertNull(failed.getHeldBack().get("f1"));
        assertEquals(2, formed(failed).size());
    }

    /** The cards Radverkehr and Parken with a question each. */
    private static ResearchMemory twoCards() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addTopic(ResearchTopic.ofCard("T-park", "Parken", "anchor-concept-parken", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie gut sind Radwege ausgebaut?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("K2", "Was kostet Anwohnerparken?", "T-park",
                ResearchQuestion.Origin.KONZEPT, 1L));
        return memory;
    }

    @Test
    public void aGroupTooLargeForOneCaseFallsApartWithoutLosingAHeadAndOneNameGathersItsPieces() throws Exception {
        String[] names = {"Radwegbreite", "Radwegbelag", "Radwegmarkierung", "Radwegbeleuchtung", "Radwegreinigung",
                "Radwegwinterdienst", "Radwegampeln", "Radwegkreuzungen", "Radwegschilder", "Radwegbegrünung",
                "Radwegentwässerung", "Radwegbordsteine", "Radwegpoller", "Radwegrampen", "Radwegtunnel",
                "Radwegbrücken", "Radwegrastplätze", "Radwegzählstellen", "Radwegbaustellen", "Radwegsanierung"};
        Table vectors = new Table();
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (int i = 0; i < names.length; i++) {
            double[] values = new double[names.length + 1];
            values[0] = SIBLING_SHARED;
            values[i + 1] = SIBLING_OWN;
            vectors.put(names[i], values);
            proposals.add(proposal("h" + (i + 1), "s" + (i + 1), names[i], true));
        }
        Scripted chat = new Scripted(m -> {
            if (!isParent(m)) {
                return MainModelChatResult.ok("{\"grund\":\"Verschieden.\",\"beziehung\":\"verschieden\"}");
            }
            List<String> labels = new ArrayList<String>();
            for (String line : prompt(m).split("\n")) {
                if (line.matches("T\\d+: .*")) {
                    labels.add("\"" + line.substring(0, line.indexOf(':')) + "\"");
                }
            }
            return MainModelChatResult.ok("{\"grund\":\"Alles Radwege.\",\"oberthema\":\"Radwege\",\"beschreibung\":"
                    + "\"Wie Radwege gebaut und betrieben werden.\",\"themen\":[" + String.join(",", labels) + "]}");
        });

        Result result = new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1), null,
                CLOCK).merge(proposals, new ResearchMemory(), null);

        int heads = 0;
        int cases = 0;
        for (Comparison comparison : result.getAsked()) {
            if (comparison.getKind() == Comparison.Kind.OBERTHEMA) {
                int size = comparison.getAText().split("\n").length;
                assertTrue(comparison.getAText(), size >= TopicMerger.PARENT_MIN
                        && size <= TopicMerger.PARENT_MAX + TopicMerger.PARENT_MIN - 1);
                heads += size;
                cases++;
            }
        }
        assertTrue("too many heads for one case", cases >= 2);
        assertEquals("every head in exactly one case", names.length, heads);
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(20, (int) counts.get("freeHeads"));
        assertEquals(20, (int) counts.get("freeInCases"));
        assertEquals(0, (int) counts.get("freeLost"));
        assertEquals(0, (int) counts.get("freeAlone"));
        assertEquals("one parent, the later cases named it again", 1, (int) counts.get("formedTop"));
        String parent = topicOf(result, "h1").getParentId();
        assertTrue(result.topic(parent).isFormed());
        for (int i = 1; i <= names.length; i++) {
            assertEquals("h" + i, parent, topicOf(result, "h" + i).getParentId());
        }
        assertEquals(names.length - (int) counts.get("joined"), result.getAsked().stream()
                .filter(c -> c.getKind() == Comparison.Kind.OBERTHEMA).findFirst().get().getAText().split("\n").length);

        List<Integer> seventeen = new ArrayList<Integer>();
        for (int i = 0; i < 17; i++) {
            seventeen.add(i);
        }
        assertEquals(Arrays.asList(seventeen.subList(0, 8), seventeen.subList(8, 17)),
                TopicMerger.chunks(seventeen, 16));
        assertTrue(TopicMerger.chunks(Collections.<Integer>emptyList(), 16).isEmpty());
    }

    @Test
    public void theParentAnswerNamesAParentOverThreeOrMoreHeadsOrKeins() {
        List<String> labels = Arrays.asList("T1", "T2", "T3", "T4");
        List<Set<String>> keys = Arrays.<Set<String>>asList(Collections.singleton("radwegbreite"),
                new HashSet<String>(Arrays.asList("radwegbelag", "asphaltradwege")),
                Collections.singleton("radwegmarkierung"), Collections.singleton("radwegbeleuchtung"));
        Set<String> taken = new HashSet<String>(Arrays.asList("radwegbreite", "radwegbelag", "asphaltradwege",
                "radwegmarkierung", "radwegbeleuchtung", "bahnlärm"));
        TopicMerger.ParentAnswer fresh = TopicMerger.parseParent("{\"grund\":\"Bau  eines Radwegs.\","
                + "\"oberthema\":\" Radwege \",\"beschreibung\":\"Wie Radwege  gebaut sind.\","
                + "\"themen\":[\"t1\",\"T2\",\"T2\",\"T3\",\"T9\"]}", labels, keys, taken);
        assertEquals("", fresh.problem);
        assertTrue(fresh.formed());
        assertEquals("", fresh.head);
        assertEquals("Radwege", fresh.name);
        assertEquals("Wie Radwege gebaut sind.", fresh.description);
        assertEquals(Arrays.asList("T1", "T2", "T3"), fresh.parts);
        assertEquals("Bau eines Radwegs.", fresh.reason);
        assertEquals("new parent \u201eRadwege\u201c of T1, T2, T3; not in the list: T9", fresh.note);

        TopicMerger.ParentAnswer head = TopicMerger.parseParent("{\"grund\":\"x\",\"oberthema\":\"Asphaltradwege\","
                + "\"beschreibung\":\"\",\"themen\":[\"T1\",\"T2\",\"T3\",\"T4\"]}", labels, keys, taken);
        assertEquals("named like a proposal of T2", "T2", head.head);
        assertEquals(Arrays.asList("T1", "T3", "T4"), head.parts);
        assertEquals("named like T2, so T2 is the parent; T2 is the parent of T1, T3, T4", head.note);

        TopicMerger.ParentAnswer other = TopicMerger.parseParent("{\"grund\":\"x\",\"oberthema\":\"Bahnlärm\","
                + "\"beschreibung\":\"\",\"themen\":[\"T1\",\"T2\",\"T3\"]}", labels, keys, taken);
        assertFalse("a new parent named like another topic", other.formed());
        assertEquals("", other.problem);
        assertEquals("\u201eBahnlärm\u201c is named like another topic: counts as keins", other.note);

        TopicMerger.ParentAnswer two = TopicMerger.parseParent("{\"grund\":\"x\",\"oberthema\":\"Radwege\","
                + "\"beschreibung\":\"\",\"themen\":[\"T1\",\"T2\"]}", labels, keys, taken);
        assertFalse(two.formed());
        assertEquals("a parent over 2 of the topics (it needs 3): counts as keins", two.note);

        TopicMerger.ParentAnswer none = TopicMerger.parseParent("{\"grund\":\"Nichts Gemeinsames.\","
                + "\"oberthema\":\"keins\",\"beschreibung\":\"\",\"themen\":[\"T1\",\"T2\",\"T3\"]}", labels,
                keys, taken);
        assertFalse(none.formed());
        assertEquals("", none.problem);
        assertEquals("", none.note);
        assertEquals("Nichts Gemeinsames.", none.reason);

        assertEquals("the answer is no JSON", TopicMerger.parseParent("kein json", labels, keys, taken).problem);
        assertTrue(TopicMerger.parseParent("{\"grund\":\"x\",\"themen\":[]}", labels, keys, taken).problem
                .startsWith("\"oberthema\" must be"));
        assertTrue(TopicMerger.parseParent("{\"grund\":\"x\",\"oberthema\":\"Radwege\"}", labels, keys, taken)
                .problem.startsWith("\"themen\" must be"));
        String english = TopicMerger.parentPrompt("A book on city traffic.", "T1: Cycle lanes\nT2: Lane width",
                false);
        assertTrue(english, english.contains("Topics from the research (each stands on its own so far, under no other "
                + "topic):\nT1: Cycle lanes\nT2: Lane width\n\nDo at least three of these topics belong to a common "
                + "parent topic"));
        assertTrue(english, english.contains("When in doubt, \"keins\"."));
        assertEquals("the prompt for heads at the top stays as it was", english,
                TopicMerger.parentPrompt("A book on city traffic.", "T1: Cycle lanes\nT2: Lane width", "", false));
        String under = TopicMerger.parentPrompt("A book on city traffic.", "T1: Cycle lanes\nT2: Lane width",
                "Cycling", false);
        assertTrue(under, under.contains("Topics from the research, all already under the topic \u201cCycling\u201d "
                + "(each stands on its own there so far):\nT1: Cycle lanes\nT2: Lane width\n\nDo at least three of "
                + "these topics belong to a common parent topic within \u201cCycling\u201d that could carry"));
        assertTrue(under, under.contains("- \u201cCycling\u201d itself is no parent topic here: the parent topic is "
                + "narrower than \u201cCycling\u201d and gathers only part of its topics.\n"));
    }

    /** The label of a card's first question in a request of the card step. */
    private static String questionLabel(String request, String cardId) {
        java.util.regex.Matcher label = java.util.regex.Pattern.compile("\n  (F\\d+): ").matcher(request);
        return label.find(request.indexOf("\n" + cardId + ": ")) ? label.group(1) : "?";
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

    /**
     * A chain Radverkehr (two sources) - Radwege (two) - Radwegbreite (two) - Markierung, each in the band of the
     * next, and the pair Abstellanlagen - Fahrradparken of one source each, far from the chain.
     */
    private static Table chainVectors() {
        return new Table()
                .put("Radverkehr", 1, 0, 0, 0, 0, 0)
                .put("Radwege", 0.8, 0.6, 0, 0, 0, 0)
                .put("Radwegbreite", 0.48, 0.6, 0.64, 0, 0, 0)
                .put("Markierung der Radwegbreite", 0.2, 0.4, 0.55, 0.7071, 0, 0)
                .put("Fahrradparken", 0, 0, 0, 0, 0.8, 0.6)
                .put("Abstellanlagen", 0, 0, 0, 0, 1, 0);
    }

    private static List<TopicProposal> chainProposals() {
        return Arrays.asList(proposal("p1", "s1", "Radverkehr", true), proposal("p2", "s2", "Radverkehr", true),
                proposal("q1", "s3", "Radwege", true), proposal("q2", "s4", "Radwege", true),
                proposal("r1", "s5", "Radwegbreite", true), proposal("r2", "s6", "Radwegbreite", true),
                proposal("m1", "s7", "Markierung der Radwegbreite", true),
                proposal("t1", "s8", "Fahrradparken", true), proposal("u1", "s9", "Abstellanlagen", true));
    }

    /** Each link of the chain is part of the one above; bike parking is part of the parking scheme. */
    private static Scripted chainChat() {
        return new Scripted(m -> MainModelChatResult.ok(prompt(m).contains("Thema A:\nAbstellanlagen\n\n")
                ? "{\"grund\":\"Fahrradparken braucht Abstellanlagen.\",\"beziehung\":\"b_teil_von_a\"}"
                : "{\"grund\":\"Ein Teil davon.\",\"beziehung\":\"a_teil_von_b\"}"));
    }

    /** The merged topic that holds a proposal. */
    private static MergedTopic topicOf(Result result, String proposalId) {
        for (MergedTopic topic : result.getTopics()) {
            if (topic.getProposalIds().contains(proposalId)) {
                return topic;
            }
        }
        throw new AssertionError("no topic holds " + proposalId);
    }


    /**
     * The merge without the parent step, which has tests of its own: here it would put the heads the pairs left apart
     * to the model a second time.
     */
    private static TopicMerger merger(Scripted chat, Table vectors) {
        return new TopicMerger(chat, vectors, MISSION, "de", Options.defaults().withParallel(1)
                .withParentStep(false, 0.65), null, CLOCK);
    }

    /**
     * The card step on {@link #cardVectors()}, apart from the rule on established parents: here a topic of one source
     * takes subtopics, so the families of two show how the card step treats a family.
     */
    private static TopicMerger cardMerger(Scripted chat) {
        return new TopicMerger(chat, cardVectors(), MISSION, "de", Options.defaults().withParallel(1)
                .withSubtopics(1, 2), null, CLOCK);
    }

    /** A request of the parent step. */
    private static boolean isParent(List<ChatMessage> messages) {
        return TopicMerger.PARENT_SYSTEM_DE.equals(messages.get(0).getContent());
    }

    /** Siblings share this part of their vectors: two of them meet at a cosine of 0.7, in the band. */
    private static final double SIBLING_SHARED = Math.sqrt(0.7);
    private static final double SIBLING_OWN = Math.sqrt(0.3);

    /**
     * Heads along one direction of the first dimensions: each meets the direction at {@link #SIBLING_SHARED} and has a
     * dimension of its own from {@code own} on, so two of them meet at 0.7 times the cosine of their directions.
     */
    private static void siblings(Table vectors, double[] direction, int own, int dimensions, String... names) {
        for (int i = 0; i < names.length; i++) {
            double[] values = new double[dimensions];
            for (int d = 0; d < direction.length; d++) {
                values[d] = SIBLING_SHARED * direction[d];
            }
            values[own + i] = SIBLING_OWN;
            vectors.put(names[i], values);
        }
    }

    /** A vector far from every head that leans along the first dimension. */
    private static double[] away(int dimensions) {
        double[] values = new double[dimensions];
        values[0] = -1;
        return values;
    }

    /** Four sides of a cycle lane, each pair at 0.7, and Bahnlärm far from all. */
    private static Table parentVectors() {
        return new Table()
                .put("Radwegbreite", SIBLING_SHARED, SIBLING_OWN, 0, 0, 0, 0)
                .put("Radwegbelag", SIBLING_SHARED, 0, SIBLING_OWN, 0, 0, 0)
                .put("Radwegmarkierung", SIBLING_SHARED, 0, 0, SIBLING_OWN, 0, 0)
                .put("Radwegbeleuchtung", SIBLING_SHARED, 0, 0, 0, SIBLING_OWN, 0)
                .put("Bahnlärm", 0, 0, 0, 0, 0, 1);
    }

    private static List<TopicProposal> parentProposals() {
        return Arrays.asList(proposal("r1", "s1", "Radwegbreite", true), proposal("r2", "s2", "Radwegbelag", true),
                proposal("r3", "s3", "Radwegmarkierung", true), proposal("r4", "s4", "Radwegbeleuchtung", true),
                proposal("l1", "s5", "Bahnlärm", true));
    }

    /** The merge with the parent step, on {@link #parentVectors()}, without cards. */
    private static TopicMerger parentMerger(Scripted chat) {
        return new TopicMerger(chat, parentVectors(), MISSION, "de", Options.defaults().withParallel(1), null, CLOCK);
    }

    /** A request of the card step. */
    private static boolean isCard(List<ChatMessage> messages) {
        return TopicMerger.CARD_SYSTEM_DE.equals(messages.get(0).getContent());
    }

    /** Two cards with a question each, far from every proposal of {@link #cardProposals()}. */
    private static ResearchMemory cardMemory() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-rad", "Radverkehr", "anchor-concept-radverkehr", 0, 1L));
        memory.addTopic(ResearchTopic.ofCard("T-park", "Parken", "anchor-concept-parken", 0, 1L));
        memory.addQuestion(ResearchQuestion.open("K1", "Wie sicher sind Fahrradstraßen?", "T-rad",
                ResearchQuestion.Origin.KONZEPT, 1L));
        memory.addQuestion(ResearchQuestion.open("K2", "Was kostet Anwohnerparken?", "T-park",
                ResearchQuestion.Origin.KONZEPT, 1L));
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
                .put("Bahnlärm", 0, 0, 0, 0, 0, 0, 1)
                .put("Wie sicher sind Fahrradstraßen?", 0.6, 0, 0, 0, 0.8, 0, 0)
                .put("Was kostet Anwohnerparken?", 0, 0, 0, 0.6, 0, 0.8, 0);
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
                        + "\"zuordnung\":\"teil_von\",\"karte\":\"T-rad\",\"frage\":\"F1\"}");
            }
            if (request.contains("Thema:\nParkraumbewirtschaftung\n")) {
                return MainModelChatResult.ok("{\"grund\":\"Das ist die Karte Parken.\","
                        + "\"zuordnung\":\"gleich\",\"karte\":\"T-park\",\"frage\":\"F2\"}");
            }
            return MainModelChatResult.ok("{\"grund\":\"Keine Karte.\","
                    + "\"zuordnung\":\"keine\",\"karte\":\"keine\",\"frage\":\"keine\"}");
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
