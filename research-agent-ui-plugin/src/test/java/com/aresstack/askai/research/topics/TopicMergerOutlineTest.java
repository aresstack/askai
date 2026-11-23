package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.topics.TopicFinderTest.Scripted;
import com.aresstack.askai.research.topics.TopicMerger.Comparison;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The outline from above ({@link Options#withOutline}): sections per place, the leaves put into them in batches, the
 * rest, the fallback by similarity, the split of a large section, sections of an earlier run, reuse. Texts on road
 * traffic, written for the test.
 */
public class TopicMergerOutlineTest {

    private static final String MISSION = "Ein Praxisbuch über Verkehr in Städten: Tempo, Radwege, Parken.";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
    private static final int DIMENSIONS = 80;
    private static final String TEMPO = "Tempolimits: Wie schnell in der Stadt gefahren werden darf.";
    private static final String PARKING = "Parken: Wo und zu welchem Preis Autos stehen.";

    /** Six leaves at the free level: three on speed, two on parking, one on railway noise. */
    private static final List<String> SIX = Arrays.asList("Tempo 30 in Wohngebieten", "Bußgelder bei Tempoverstößen",
            "Blitzer in der Stadt", "Parkgebühren", "Anwohnerparken", "Bahnlärm");

    @Test
    public void theOutlineSetsSectionsAndPutsTheLeavesIntoThem() {
        Case run = new Case(SIX);
        String noise = "Wie laut Züge an Wohnhäusern vorbeifahren, " + repeat('x', 150);
        run.descriptions.put("Bahnlärm", noise);
        run.vectors.put("Bahnlärm: " + noise, run.vectors.get("Bahnlärm"));
        run.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell in der Stadt gefahren werden "
                + "darf.", "Parken", "Wo und zu welchem Preis Autos stehen.")) : ok(assign(prompt(m), leaf ->
                leaf.startsWith("Tempo") || leaf.startsWith("Bußgeld") || leaf.startsWith("Blitzer") ? "Tempolimits"
                        : leaf.contains("ark") ? "Parken" : null));
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        MergedTopic tempo = formedNamed(result, "Tempolimits");
        MergedTopic parking = formedNamed(result, "Parken");
        assertEquals("", tempo.getParentId());
        assertEquals("Wie schnell in der Stadt gefahren werden darf.", tempo.getDescription());
        for (String id : new String[]{"p1", "p2", "p3"}) {
            assertEquals(tempo.getId(), topicOf(result, id).getParentId());
        }
        assertEquals(parking.getId(), topicOf(result, "p4").getParentId());
        assertEquals(parking.getId(), topicOf(result, "p5").getParentId());
        assertEquals("railway noise stands alone", "", topicOf(result, "p6").getParentId());
        assertEquals(2, formed(result).size());
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(1, (int) counts.get("outlinePlaces"));
        assertEquals(6, (int) counts.get("outlineLeaves"));
        assertEquals(2, (int) counts.get("outlineSections"));
        assertEquals(5, (int) counts.get("outlineAssigned"));
        assertEquals(1, (int) counts.get("outlineRest"));
        assertEquals(1, (int) counts.get("outlineAlone"));
        assertEquals(0, (int) counts.get("outlineFailed"));
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.ABSCHNITTE).size());
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.ZUORDNUNG).size());
        assertEquals("one leaf without a section calls no rest", 0, kind(result.getAsked(), Comparison.Kind.REST)
                .size());
        assertEquals("the parent step is left out", 0, kind(result.getAsked(), Comparison.Kind.OBERTHEMA).size());
        String sectionPrompt = prompt(run.chat.requests.get(0));
        for (String leaf : SIX) {
            assertTrue(leaf, sectionPrompt.contains("- " + leaf + "\n"));
        }
        assertTrue(sectionPrompt.contains("Die Themen stehen unter keiner Karte. Die Karten des Buchs sind: (keine)."));
        assertTrue(sectionPrompt.contains("Alle Aspekte derselben Sache (Definition, Funktionsweise, Risiken, "
                + "Maßnahmen, Recht, Praxis) gehören in denselben Abschnitt."));
        assertTrue(sectionPrompt.contains(MISSION));
        String assignPrompt = prompt(run.chat.requests.get(1));
        assertTrue(assignPrompt.contains("1. Tempolimits: Wie schnell in der Stadt gefahren werden darf.\n2. Parken"));
        assertTrue(assignPrompt.contains("T6. Bahnlärm: " + noise.substring(0, 140).trim() + " …\n\n"));
        assertFalse("no description in the section call", sectionPrompt.contains("Wie laut Züge"));
        assertTrue(assignPrompt.contains("Ordne nicht nach Aspekt (Recht, Risiko, Maßnahme), sondern nach der Sache."));
        assertTrue(run.chat.schemas.get(1).contains("\"T6\""));
        assertTrue(run.chat.schemas.get(0).contains("abschnitte"));
        Comparison sections = kind(result.getAsked(), Comparison.Kind.ABSCHNITTE).get(0);
        assertEquals(TopicMerger.OUTLINE_MARK, sections.getBText());
        assertEquals(9, sections.getVersion());
        assertEquals("p1", sections.getAId());
        assertEquals(TopicMerger.OUTLINE_ASSIGN_MARK, kind(result.getAsked(), Comparison.Kind.ZUORDNUNG).get(0)
                .getBText());

        Case off = new Case(SIX);
        off.answers = m -> ok("{\"grund\":\"Nein.\",\"oberthema\":\"keins\",\"beschreibung\":\"\",\"themen\":[]}");
        Result before = off.merge(Options.defaults().withParallel(1));
        assertFalse(Options.defaults().isOutline());
        assertEquals(0, kind(before.getAsked(), Comparison.Kind.ABSCHNITTE).size());
        assertEquals(0, (int) before.getParentCounts().get("outlinePlaces"));
    }

    @Test
    public void theRestGetsSectionsOfItsOwnAndIsPutIntoTheNewOnesOnly() {
        Case run = new Case(Arrays.asList("Tempo 30 in Wohngebieten", "Blitzer in der Stadt", "Lärm an Bahnstrecken",
                "Schienenlärm", "Fluglärm", "Lärmschutzwände", "Carsharing"));
        run.answers = m -> {
            String request = prompt(m);
            if (request.contains("Keiner der vorhandenen Abschnitte")) {
                return ok(sections("Lärm", "Wie laut der Verkehr ist.", "Tempolimits", "Doppelt."));
            }
            if (isSections(m)) {
                return ok(sections("Tempolimits", "Wie schnell in der Stadt gefahren werden darf."));
            }
            return ok(assign(request, leaf -> leaf.startsWith("Tempo") || leaf.startsWith("Blitzer") ? "Tempolimits"
                    : leaf.contains("ärm") ? "Lärm" : null));
        };
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.REST).size());
        String rest = prompt(run.chat.requests.get(2));
        assertTrue(rest, rest.contains("Vorhandene Abschnitte (nicht noch einmal anlegen):\n- Tempolimits\n"));
        assertTrue(rest.contains("- Carsharing\n"));
        assertFalse("a leaf of a section is no rest", rest.contains("- Blitzer in der Stadt"));
        String restAssign = prompt(run.chat.requests.get(3));
        assertTrue(restAssign, restAssign.contains("Abschnitte:\n1. Lärm: Wie laut der Verkehr ist.\n\nThemen:"));
        assertEquals("a rest section worded like one there is falls away", 2, formed(result).size());
        MergedTopic noise = formedNamed(result, "Lärm");
        for (String id : new String[]{"p3", "p4", "p5", "p6"}) {
            assertEquals(noise.getId(), topicOf(result, id).getParentId());
        }
        assertEquals("", topicOf(result, "p7").getParentId());
        assertEquals(TopicMerger.OUTLINE_REST_MARK, kind(result.getAsked(), Comparison.Kind.REST).get(0).getBText());
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(5, (int) counts.get("outlineRest"));
        assertEquals(2, (int) counts.get("outlineSections"));
        assertEquals(6, (int) counts.get("outlineAssigned"));
        assertEquals(1, (int) counts.get("outlineAlone"));

        Case two = new Case(Arrays.asList("Tempo 30 in Wohngebieten", "Blitzer in der Stadt", "Fluglärm",
                "Carsharing"));
        two.answers = run.answers;
        Result fewer = two.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals("two leaves without a section call no rest", 0, kind(fewer.getAsked(), Comparison.Kind.REST)
                .size());
        assertEquals(2, (int) fewer.getParentCounts().get("outlineAlone"));
    }

    /** The architect, 23.11.2026: a rest section named like a card falls away too, before leaves go into it. */
    @Test
    public void aRestSectionNamedLikeACardFallsAway() throws Exception {
        Case run = new Case(Arrays.asList("Tempo 30 in Wohngebieten", "Blitzer in der Stadt", "Lärm an Bahnstrecken",
                "Schienenlärm", "Fluglärm", "Lärmschutzwände", "Carsharing"));
        run.memory.addTopic(ResearchTopic.ofCard("K-laerm", "Lärm", "anchor-laerm", 0, 1L));
        run.memory.addQuestion(ResearchQuestion.open("F-laerm", "Wie laut ist die Stadt?", "K-laerm",
                ResearchQuestion.Origin.KONZEPT, 1L));
        run.answers = m -> {
            String request = prompt(m);
            if (TopicMerger.CARD_SYSTEM_DE.equals(m.get(0).getContent())) {
                return ok("{\"grund\":\"Keine Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            if (request.contains("Keiner der vorhandenen Abschnitte")) {
                return ok(sections("Lärm", "Wie laut der Verkehr ist.", "Lärmquellen", "Woher der Lärm kommt."));
            }
            if (isSections(m)) {
                return ok(sections("Tempolimits", "Wie schnell in der Stadt gefahren werden darf."));
            }
            return ok(assign(request, leaf -> leaf.startsWith("Tempo") || leaf.startsWith("Blitzer") ? "Tempolimits"
                    : leaf.contains("ärm") ? "Lärm" : null));
        };
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.REST).size());
        assertEquals(1, (int) result.getParentCounts().get("outlineCardName"));
        for (List<ChatMessage> request : run.chat.requests) {
            assertFalse(prompt(request).contains(". Lärm: Wie laut"));
        }
        for (MergedTopic topic : formed(result)) {
            assertFalse(topic.getName(), topic.getName().equals("Lärm"));
        }
    }

    @Test
    public void aLeafThatWouldReachTooDeepStaysWhereItIs() {
        Case run = new Case(SIX);
        run.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell in der Stadt gefahren werden "
                + "darf.", "Parken", "Wo und zu welchem Preis Autos stehen.")) : ok(assign(prompt(m), leaf ->
                leaf.contains("ark") ? "Parken" : leaf.startsWith("Bahn") ? null : "Tempolimits"));
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true).withSubtopics(2, 0));
        assertEquals(0, formed(result).size());
        assertEquals(5, (int) result.getParentCounts().get("outlineTooDeep"));
        assertEquals(6, (int) result.getParentCounts().get("outlineAlone"));
    }

    @Test
    public void aLeafLeftOutGoesToTheNearestSectionFromHalfOn() {
        Case run = new Case(SIX);
        // „Blitzer“ at 0.8 to the speed section, „Parkgebühren“ at 0.4 to the parking section
        run.vectors.put(TEMPO, mix(2, 0.8, DIMENSIONS - 2, 0.6));
        run.vectors.put(PARKING, mix(3, 0.4, DIMENSIONS - 3, Math.sqrt(1 - 0.16)));
        run.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell in der Stadt gefahren werden "
                + "darf.", "Parken", "Wo und zu welchem Preis Autos stehen."))
                : ok("{\"T1\":1,\"T2\":1,\"T4\":9,\"T5\":2,\"T6\":0}");
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        MergedTopic tempo = formedNamed(result, "Tempolimits");
        assertEquals("left out, near the speed section", tempo.getId(), topicOf(result, "p3").getParentId());
        assertEquals("out of range, too far from parking: alone", "", topicOf(result, "p4").getParentId());
        assertEquals("one leaf left for parking: no section", 1, formed(result).size());
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(2, (int) counts.get("outlineInvalid"));
        assertEquals(1, (int) counts.get("outlineEmbedded"));
        assertEquals(0, (int) counts.get("outlineFailed"));
        assertTrue(kind(result.getAsked(), Comparison.Kind.ZUORDNUNG).get(0).getNote().startsWith("2 leaves"));

        Case failing = new Case(SIX);
        failing.vectors.put(TEMPO, mix(2, 0.8, DIMENSIONS - 2, 0.6));
        failing.vectors.put(PARKING, mix(3, 0.4, DIMENSIONS - 3, Math.sqrt(1 - 0.16)));
        failing.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell in der Stadt gefahren werden "
                + "darf.", "Parken", "Wo und zu welchem Preis Autos stehen.")) : ok("[1, 2]");
        Result failed = failing.merge(Options.defaults().withParallel(1).withOutline(true));
        assertFalse(kind(failed.getAsked(), Comparison.Kind.ZUORDNUNG).get(0).isOk());
        assertEquals("asked again once on an answer that does not read", 3, failing.chat.calls.get());
        assertEquals(1, (int) failed.getParentCounts().get("outlineFailed"));
        assertEquals(1, (int) failed.getParentCounts().get("outlineEmbedded"));
        assertEquals("one leaf by similarity makes no section", 0, formed(failed).size());
        assertEquals(6, (int) failed.getParentCounts().get("outlineAlone"));
        assertTrue(failed.getHeldBack().isEmpty());
    }

    @Test
    public void aFailedSectionCallHoldsTheLeavesOfItsPlaceBack() {
        Case run = new Case(SIX);
        run.answers = m -> MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow");
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(6, result.getHeldBack().size());
        assertTrue(result.getHeldBack().get("p1").startsWith("outline failed"));
        assertEquals(0, result.getTopics().size());
        assertEquals(1, (int) result.getParentCounts().get("outlineFailed"));
        assertEquals(0, (int) result.getParentCounts().get("outlineAlone"));
        assertEquals(0, kind(result.getAsked(), Comparison.Kind.ZUORDNUNG).size());
    }

    /** The review: a failed section call holds back each leaf with its subtopics, none comes out at the top. */
    @Test
    public void aFailedSectionCallHoldsBackTheSubtopicsOfALeafToo() {
        Case run = new Case(Arrays.asList("Parken", "Parken", "Parkgebühren", "Bahnlärm"));
        run.vectors.put("Parken", unit(0));
        run.vectors.put("Parkgebühren", mix(0, 0.7, 5, Math.sqrt(0.51)));
        run.answers = m -> TopicMerger.OUTLINE_SYSTEM_DE.equals(m.get(0).getContent())
                ? MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "slow")
                : ok("{\"grund\":\"Gebühren gehören zum Parken.\",\"beziehung\":\""
                + (prompt(m).contains("Thema A:\nParkgebühren") ? "a_teil_von_b" : "b_teil_von_a") + "\"}");
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.THEMA).size());
        assertEquals(Relation.A_TEIL_VON_B, kind(result.getAsked(), Comparison.Kind.THEMA).get(0).getRelation());
        assertEquals("parking twice, its subtopic on fees and railway noise", 4, result.getHeldBack().size());
        assertTrue(result.getHeldBack().get("p3").startsWith("outline failed"));
        assertEquals(0, result.getTopics().size());
    }

    @Test
    public void underACardTheSectionsStandUnderItAndNoneIsNamedLikeTheCard() throws Exception {
        Case run = new Case(Arrays.asList("Tempo 30 in Wohngebieten", "Blitzer in der Stadt", "Parkgebühren",
                "Anwohnerparken", "Parkhäuser", "Falschparken", "Bußgelder bei Tempoverstößen"));
        run.memory.addTopic(ResearchTopic.ofCard("K-park", "Parken", "anchor-parken", 0, 1L));
        run.memory.addQuestion(ResearchQuestion.open("F-park", "Wo kann man in der Stadt parken?", "K-park",
                ResearchQuestion.Origin.KONZEPT, 1L));
        run.answers = m -> {
            String request = prompt(m);
            if (TopicMerger.CARD_SYSTEM_DE.equals(m.get(0).getContent())) {
                String topic = request.substring(request.indexOf("Thema:\n") + 7);
                topic = topic.substring(0, topic.indexOf('\n'));
                return ok(topic.contains("ark")
                        ? "{\"grund\":\"Parken.\",\"zuordnung\":\"teil_von\",\"karte\":\"K-park\",\"frage\":\"F1\"}"
                        : "{\"grund\":\"Keine Karte.\",\"zuordnung\":\"keine\",\"karte\":\"keine\","
                        + "\"frage\":\"keine\"}");
            }
            if (isSections(m)) {
                return ok(request.contains("Karte „Parken“") ? sections("Parkraum", "Was Parken kostet.",
                        "Parken", "Wie man parkt.") : sections("Tempolimits", "Wie schnell gefahren werden darf.",
                        "parken", "Auf der freien Ebene wie die Karte benannt."));
            }
            return ok(assign(request, leaf -> leaf.contains("gebühr") || leaf.startsWith("Anwohner") ? "Parkraum"
                    : leaf.contains("ark") ? "Parken" : "Tempolimits"));
        };
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        MergedTopic room = formedNamed(result, "Parkraum");
        assertEquals("K-park", room.getParentId());
        assertEquals(room.getId(), topicOf(result, "p3").getParentId());
        assertEquals("the section named like its card falls away", "K-park", topicOf(result, "p5").getParentId());
        assertEquals("K-park", topicOf(result, "p6").getParentId());
        MergedTopic tempo = formedNamed(result, "Tempolimits");
        assertEquals("", tempo.getParentId());
        assertEquals(tempo.getId(), topicOf(result, "p7").getParentId());
        assertEquals(2, formed(result).size());
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(2, (int) counts.get("outlinePlaces"));
        assertEquals("the architect, 23.11.2026: on every level", 2, (int) counts.get("outlineCardName"));
        assertEquals(2, (int) counts.get("outlineAlone"));
        boolean free = false;
        for (List<ChatMessage> request : run.chat.requests) {
            free |= prompt(request).contains("Die Themen stehen unter keiner Karte. Die Karten des Buchs sind: "
                    + "Parken.");
        }
        assertTrue("the free level names the cards", free);
        for (List<ChatMessage> request : run.chat.requests) {
            assertFalse("dropped before any leaf is put into it", prompt(request).contains("parken: Auf der freien"));
        }
        boolean logged = false;
        for (Comparison sections : kind(result.getAsked(), Comparison.Kind.ABSCHNITTE)) {
            logged |= sections.getAText().startsWith("Freie Ebene; Karten: Parken\n");
        }
        assertTrue("the case as logged and reused names the cards", logged);
    }

    /** The architect after c15t and c15u, 23.11.2026: the book's title in the prompt, not the mission. */
    @Test
    public void theCallsCarryTheTitleOfTheBookNotTheMission() {
        Case run = new Case(SIX);
        run.title = "Verkehr in Städten";
        run.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell.", "Parken", "Wo Autos stehen."))
                : ok(assign(prompt(m), leaf -> leaf.contains("ark") ? "Parken" : leaf.startsWith("Bahn") ? null
                : "Tempolimits"));
        run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(3, run.chat.requests.size());
        for (List<ChatMessage> request : run.chat.requests) {
            assertTrue(prompt(request), prompt(request).startsWith("Buch: Verkehr in Städten\n"));
            assertFalse(prompt(request).contains("Praxisbuch"));
        }
        assertTrue("the leaves under keys", prompt(run.chat.requests.get(1)).contains("\nT1. Tempo 30 in "
                + "Wohngebieten"));
    }

    /** The architect, 23.11.2026: a model that copies the numbers of the topics gets the call again. */
    @Test
    public void anAnswerThatCopiesTheTopicNumbersCountsAsNone() {
        assertTrue(TopicMerger.copied("{\"T1\":1,\"T2\":2,\"T3\":3,\"T4\":4,\"T5\":5,\"T6\":1}", 6, 4));
        assertFalse("within the sections no proof", TopicMerger.copied("{\"T1\":1,\"T2\":2,\"T3\":3,\"T4\":4,"
                + "\"T5\":5,\"T6\":1}", 6, 5));
        assertFalse(TopicMerger.copied("{\"T1\":1,\"T2\":2,\"T3\":3,\"T4\":4,\"T5\":1,\"T6\":6}", 6, 2));
        assertFalse(TopicMerger.copied("{\"T1\":0,\"T2\":2,\"T3\":\"3\",\"T4\":4,\"T5\":5}", 5, 2));
        assertTrue("strings count too", TopicMerger.copied("{\"T2\":\"2\",\"T3\":3,\"T4\":4,\"T5\":5,"
                + "\"T6\":6}", 6, 2));
        assertFalse(TopicMerger.copied("nicht json", 6, 2));
        Case run = new Case(SIX);
        run.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell.", "Parken", "Wo Autos stehen."))
                : ok("{\"T1\":1,\"T2\":2,\"T3\":3,\"T4\":4,\"T5\":5,\"T6\":6}");
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        Comparison assigned = kind(result.getAsked(), Comparison.Kind.ZUORDNUNG).get(0);
        assertFalse(assigned.isOk());
        assertTrue(run.chat.requests.size() > 2);
        assertEquals(1, (int) result.getParentCounts().get("outlineFailed"));
    }

    /**
     * The architect after c15t and c15u, 23.11.2026: the rest set a section on a thing that had one. One call per
     * place names groups; the largest keeps its name and takes the leaves of each member at 0.6 or more to it.
     */
    @Test
    public void sectionsOnTheSameThingAreMergedIntoTheLargest() {
        List<String> leaves = Arrays.asList("Tempo 30 in Wohngebieten", "Bußgelder bei Tempoverstößen",
                "Blitzer in der Stadt", "Geschwindigkeit an Schulen", "Schulwege", "Parkgebühren", "Anwohnerparken");
        Function<List<ChatMessage>, MainModelChatResult> answers = m -> isSections(m) ? ok(sections(
                "Geschwindigkeit", "Wie schnell gefahren wird.", "Tempolimits", "Wie schnell.", "Parken", "Wo Autos "
                + "stehen."))
                : ok(assign(prompt(m), leaf -> leaf.startsWith("Tempo") || leaf.startsWith("Bußgeld")
                || leaf.startsWith("Blitzer") ? "Tempolimits" : leaf.startsWith("Geschw") || leaf.startsWith("Schul")
                ? "Geschwindigkeit" : "Parken"));
        Case run = new Case(leaves);
        run.answers = answers;
        run.merges = m -> ok("{\"zusammenlegen\":[[2,1],[3],[9,1]]}");
        // the speed sections at 0.8 to each other, parking far from both
        run.vectors.put("Tempolimits: Wie schnell.", unit(70));
        run.vectors.put("Geschwindigkeit: Wie schnell gefahren wird.", mix(70, 0.8, 71, 0.6));
        run.vectors.put("Parken: Wo Autos stehen.", unit(72));
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        MergedTopic tempo = formedNamed(result, "Tempolimits");
        for (String id : new String[]{"p1", "p2", "p3", "p4", "p5"}) {
            assertEquals(id, tempo.getId(), topicOf(result, id).getParentId());
        }
        assertEquals(2, formed(result).size());
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(1, (int) counts.get("outlineMergeGroups"));
        assertEquals(1, (int) counts.get("outlineMerged"));
        assertEquals(0, (int) counts.get("outlineMergeGuard"));
        Comparison merge = kind(result.getAsked(), Comparison.Kind.ZUSAMMENLEGEN).get(0);
        assertEquals(TopicMerger.OUTLINE_MERGE_MARK, merge.getBText());
        assertEquals("the largest first", "Freie Ebene\nAbschnitte:\n1. Tempolimits: Wie schnell.\n"
                + "2. Geschwindigkeit: Wie schnell gefahren wird.\n3. Parken: Wo Autos stehen.", merge.getAText());
        String request = prompt(run.chat.requests.get(2));
        assertTrue(request.contains("Lege nichts zusammen, was nur verwandt ist."));
        assertTrue(request.endsWith("Antworte nur mit JSON: {\"zusammenlegen\":[[1,7],[3,12,15]]}"));

        // the guard: a member below 0.6 to the largest stays beside it
        Case guarded = new Case(leaves);
        guarded.answers = answers;
        guarded.merges = m -> ok("{\"zusammenlegen\":[[1,2]]}");
        guarded.vectors.put("Tempolimits: Wie schnell.", unit(70));
        guarded.vectors.put("Geschwindigkeit: Wie schnell gefahren wird.", mix(70, 0.5, 71, Math.sqrt(0.75)));
        guarded.vectors.put("Parken: Wo Autos stehen.", unit(72));
        Result beside = guarded.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(3, formed(beside).size());
        assertEquals(1, (int) beside.getParentCounts().get("outlineMergeGuard"));
        assertEquals(0, (int) beside.getParentCounts().get("outlineMerged"));

        // a failed call merges nothing and holds nothing back
        Case failing = new Case(leaves);
        failing.answers = answers;
        failing.merges = m -> ok("{\"gruppen\":[[1,2]]}");
        Result kept = failing.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(3, formed(kept).size());
        assertEquals(1, (int) kept.getParentCounts().get("outlineFailed"));
        assertTrue(kept.getHeldBack().isEmpty());
        assertFalse(kind(kept.getAsked(), Comparison.Kind.ZUSAMMENLEGEN).get(0).isOk());
    }

    @Test
    public void aPlaceWithFewerThanTwoNewSectionsOfTwoLeavesAsksNoMerge() {
        Case run = new Case(SIX);
        run.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell.", "Parken", "Wo Autos stehen."))
                : ok(assign(prompt(m), leaf -> leaf.startsWith("Tempo") ? "Parken" : leaf.startsWith("Bahn") ? null
                : "Tempolimits"));
        run.merges = m -> {
            throw new AssertionError("no merge call");
        };
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(0, kind(result.getAsked(), Comparison.Kind.ZUSAMMENLEGEN).size());
    }

    @Test
    public void mergeAnswersAreReadStrictly() {
        List<List<Integer>> groups = TopicMerger.groupsOf("{\"zusammenlegen\":[[1,7],[3,\"4\",3],[2],[0,9],5,"
                + "[6,\"x\",1.5,6]]}", 7);
        assertEquals("[[1, 7], [3, 4]]", groups.toString());
        assertNull(TopicMerger.groupsOf("{\"gruppen\":[]}", 3));
        assertNull(TopicMerger.groupsOf("{\"zusammenlegen\":{}}", 3));
        assertTrue(TopicMerger.groupsOf("{\"zusammenlegen\":[]}", 3).isEmpty());
        JsonObject schema = JsonParser.parseString(TopicMerger.mergeSchema()).getAsJsonObject();
        assertEquals("integer", schema.getAsJsonObject("properties").getAsJsonObject("zusammenlegen")
                .getAsJsonObject("items").getAsJsonObject("items").get("type").getAsString());
        assertTrue(TopicMerger.mergePrompt("A book", "1. Fees", false).startsWith("Book: A book\n"));
        assertTrue(TopicMerger.mergePrompt("", "1. Fees", true).startsWith("Buch: (ohne Titel)\n"));
    }

    /**
     * The architect after c15v and c15w, 23.11.2026: a small section of at most three leaves beside a larger one of
     * the same thing goes into it as a whole; one that fits none stays.
     */
    @Test
    public void aSmallSectionGoesAsAWholeIntoALargerOneOfTheSameThing() {
        List<String> leaves = Arrays.asList("Tempo 30 in Wohngebieten", "Bußgelder bei Tempoverstößen",
                "Blitzer in der Stadt", "Tempo an Schulen", "Tempo auf Landstraßen", "Radarfallen", "Mobile Blitzer",
                "Carsharing", "Leihräder");
        Case run = new Case(leaves);
        run.answers = m -> {
            String request = prompt(m);
            if (isSections(m)) {
                return ok(sections("Tempolimits", "Wie schnell.", "Kontrollen", "Wer das Tempo prüft.",
                        "Teilen", "Fahrzeuge teilen."));
            }
            if (request.contains("\nT1. Kontrollen")) {
                return ok("{\"T1\":1,\"T2\":0}");
            }
            return ok(assign(request, leaf -> leaf.startsWith("Tempo") || leaf.startsWith("Bußgeld")
                    ? "Tempolimits" : leaf.contains("litzer") || leaf.startsWith("Radar") ? "Kontrollen" : "Teilen"));
        };
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        MergedTopic tempo = formedNamed(result, "Tempolimits");
        for (String id : new String[]{"p1", "p2", "p3", "p4", "p5", "p6", "p7"}) {
            assertEquals(id, tempo.getId(), topicOf(result, id).getParentId());
        }
        assertEquals("two leaves of their own stay a section", formedNamed(result, "Teilen").getId(),
                topicOf(result, "p8").getParentId());
        assertEquals(2, formed(result).size());
        assertEquals(1, (int) result.getParentCounts().get("outlineSmallMoved"));
        Comparison small = null;
        for (Comparison asked : kind(result.getAsked(), Comparison.Kind.ZUORDNUNG)) {
            small = TopicMerger.OUTLINE_SMALL_MARK.equals(asked.getBText()) ? asked : small;
        }
        assertEquals("Freie Ebene\nAbschnitte:\n1. Tempolimits: Wie schnell.\nKleine Abschnitte:\n"
                + "T1. Kontrollen: Wer das Tempo prüft.\nT2. Teilen: Fahrzeuge teilen.", small.getAText());

        Case failing = new Case(leaves);
        Function<List<ChatMessage>, MainModelChatResult> before = run.answers;
        failing.answers = m -> prompt(m).contains("\nT1. Kontrollen") ? ok("nicht json") : before.apply(m);
        Result kept = failing.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals("a failed call moves nothing", 3, formed(kept).size());
        assertEquals(0, (int) kept.getParentCounts().get("outlineSmallMoved"));
    }

    /** The architect after c15v and c15w, 23.11.2026: a section named after an aspect is split by things. */
    @Test
    public void aSectionNamedAfterAnAspectIsSplitByThings() {
        List<String> leaves = Arrays.asList("Helmpflicht Radfahrer", "Gurtpflicht", "Kindersitze", "Helmgrößen",
                "Gurtstraffer", "Sitzerhöhungen", "Carsharing");
        Case run = new Case(leaves);
        run.answers = m -> {
            String request = prompt(m);
            if (request.contains("nach einem Aspekt benannt, seine Themen")) {
                return ok(sections("Helme", "Helme beim Radfahren.", "Gurte", "Gurte im Auto."));
            }
            if (isSections(m)) {
                return ok(sections("Rechtliche Grundlagen", "Die Regeln.", "Carsharing", "Autos teilen."));
            }
            return ok(assign(request, leaf -> leaf.startsWith("Car") ? null : request.contains("1. Helme")
                    ? (leaf.startsWith("Helm") ? "Helme" : leaf.contains("Gurt") ? "Gurte" : null)
                    : "Rechtliche Grundlagen"));
        };
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.TEILUNG).size());
        assertEquals(TopicMerger.OUTLINE_ASPECT_MARK, kind(result.getAsked(), Comparison.Kind.TEILUNG).get(0)
                .getBText());
        assertEquals(1, (int) result.getParentCounts().get("outlineAspect"));
        MergedTopic law = formedNamed(result, "Rechtliche Grundlagen");
        MergedTopic helmets = formedNamed(result, "Helme");
        assertEquals(law.getId(), helmets.getParentId());
        assertEquals(helmets.getId(), topicOf(result, "p1").getParentId());
        assertEquals(formedNamed(result, "Gurte").getId(), topicOf(result, "p2").getParentId());
        boolean said = false;
        for (List<ChatMessage> request : run.chat.requests) {
            said |= isSections(request) && prompt(request).contains("Ein Abschnitt darf nicht nach einem Aspekt "
                    + "benannt sein (Recht, Pflichten, Grundlagen), wenn seine Themen verschiedene Sachen betreffen.");
        }
        assertTrue(said);
        assertTrue(TopicMerger.aspectNamed("Pflichten des Arbeitgebers"));
        assertTrue(TopicMerger.aspectNamed("Legal basics"));
        assertFalse(TopicMerger.aspectNamed("Helme"));
        assertTrue(TopicMerger.aspectNamed("Datenschutzrecht"));
        assertTrue(TopicMerger.aspectNamed("Rechtliche Fragen"));
        assertTrue(TopicMerger.aspectNamed("Meldepflichten"));
        assertTrue(TopicMerger.aspectNamed("Arbeitsschutzvorschriften"));
        for (String thing : new String[]{"Zugriffsberechtigungen", "Rechteck", "Lawinenschutz", "Dutch ovens",
                "Lineale (rulers)", "Eingesetzte Werkzeuge", "Barrierefrei und gerecht", "Senkrecht parken"}) {
            assertFalse(thing, TopicMerger.aspectNamed(thing));
        }
        assertTrue(TopicMerger.outlinePrompt("A book.", Comparison.Kind.TEILUNG, "", Collections.<String>emptyList(),
                "Rules", "", "", "- Helmets", true, false).contains("It is named after an aspect"));

        // the review: one subsection alone is no split
        Case one = new Case(leaves);
        Function<List<ChatMessage>, MainModelChatResult> before = run.answers;
        one.answers = m -> prompt(m).contains("nach einem Aspekt benannt, seine Themen")
                ? ok(sections("Schutzausrüstung", "Helme und Gurte.")) : prompt(m).contains("1. Schutzausrüstung")
                ? ok(assign(prompt(m), leaf -> leaf.startsWith("Car") ? null : "Schutzausrüstung")) : before.apply(m);
        Result single = one.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(1, (int) single.getParentCounts().get("outlineSplitOne"));
        assertEquals(0, (int) single.getParentCounts().get("outlineSubsections"));
        assertEquals(formedNamed(single, "Rechtliche Grundlagen").getId(), topicOf(single, "p1").getParentId());

        // five leaves are too few to split by aspect
        Case few = new Case(leaves.subList(1, 7));
        few.answers = run.answers;
        Result unsplit = few.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(0, kind(unsplit.getAsked(), Comparison.Kind.TEILUNG).size());
    }

    /**
     * The architect's switch stimmigkeit, 23.11.2026 (Angelo's idea of a cross-encoder): the cross-encoder ranks the
     * leaves of a section of six or more, the small model judges the lower half against the upper; a "nein" goes to
     * the nearest other section by embedding, else alone.
     */
    @Test
    public void theCoherenceCheckTakesALeafThatDoesNotFitOut() {
        List<String> leaves = Arrays.asList("Tempo 30 in Wohngebieten", "Bußgelder bei Tempoverstößen",
                "Blitzer in der Stadt", "Tempo an Schulen", "Tempo auf Landstraßen", "Radarfallen", "Parkgebühren",
                "Anwohnerparken", "Parkhäuser");
        Function<List<ChatMessage>, MainModelChatResult> answers = m -> isSections(m) ? ok(sections("Tempolimits",
                "Wie schnell.", "Parken", "Wo Autos stehen.")) : ok(assign(prompt(m), leaf -> leaf.startsWith("Anw")
                || leaf.startsWith("Parkh") || leaf.equals("Parken") ? "Parken" : "Tempolimits"));
        Map<String, Double> score = new HashMap<String, Double>();
        for (int i = 0; i < leaves.size(); i++) {
            score.put(leaves.get(i), 9.0 - i);
        }
        List<String> queries = new ArrayList<String>();
        TopicMerger.CoherenceScorer scorer = (query, documents) -> {
            queries.add(query);
            double[] values = new double[documents.size()];
            for (int j = 0; j < documents.size(); j++) {
                values[j] = score.get(documents.get(j).substring(0, documents.get(j).indexOf(" (")));
            }
            return values;
        };
        Case run = new Case(leaves);
        run.answers = answers;
        run.scorer = scorer;
        run.small = m -> ok(prompt0(m).contains("K4: Parkgebühren") ? "{\"K1\":\"ja\",\"K2\":\"ja\",\"K3\":"
                + "\"ja\",\"K4\":\"nein\"}" : "{}");
        run.vectors.put("Parken: Wo Autos stehen.", unit(6));
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals("Parkgebühren to parking by embedding", formedNamed(result, "Parken").getId(),
                topicOf(result, "p7").getParentId());
        assertEquals(formedNamed(result, "Tempolimits").getId(), topicOf(result, "p6").getParentId());
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals("parking has two leaves: not checked", 1, (int) counts.get("outlineCoherenceChecked"));
        assertEquals(4, (int) counts.get("outlineCoherenceCandidates"));
        assertEquals(1, (int) counts.get("outlineCoherenceMarked"));
        assertEquals(1, (int) counts.get("outlineCoherenceMoved"));
        assertEquals(Arrays.asList("Tempolimits: Wie schnell."), queries);
        assertEquals(1, run.smallChat.requests.size());
        String request = prompt0(run.smallChat.requests.get(0));
        assertTrue(request, request.startsWith("Buchabschnitt: Tempolimits\nInhalt: Wie schnell.\n\nDiese "
                + "Unterthemen gehoeren sicher in den Abschnitt (Referenz):\n- Tempo 30 in Wohngebieten - \n"
                + "- Bußgelder bei Tempoverstößen - \n- Blitzer in der Stadt - \n\n"));
        assertTrue(request.contains("\nK1: Tempo an Schulen - \n"));
        assertTrue(request.endsWith("Schluessel K1 bis K4, Wert \"ja\" oder \"nein\"."));
        Comparison check = kind(result.getAsked(), Comparison.Kind.STIMMIGKEIT).get(0);
        assertTrue(check.getAText(), check.getAText().contains("K4 Parkgebühren -  (3.000, Rang 7/7)"));
        assertEquals("1 nein", check.getNote());
        for (List<ChatMessage> asked : run.chat.requests) {
            assertFalse("the large model is not asked", prompt(asked).contains("Diese Unterthemen"));
        }

        // an invalid answer is "ja" for all, a failed score skips the section, no candidate with "nein" stays alone
        Case invalid = new Case(leaves);
        invalid.answers = answers;
        invalid.scorer = scorer;
        invalid.small = m -> ok("kein json");
        Result kept = invalid.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(0, (int) kept.getParentCounts().get("outlineCoherenceMarked"));
        assertEquals(formedNamed(kept, "Tempolimits").getId(), topicOf(kept, "p7").getParentId());
        Case broken = new Case(leaves);
        broken.answers = answers;
        broken.scorer = (query, documents) -> {
            throw new IOException("no cross-encoder");
        };
        broken.small = run.small;
        Result skipped = broken.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(1, (int) skipped.getParentCounts().get("outlineCoherenceFailed"));
        assertEquals(0, kind(skipped.getAsked(), Comparison.Kind.STIMMIGKEIT).size());
        Case alone = new Case(leaves);
        alone.answers = answers;
        alone.scorer = scorer;
        alone.small = run.small;
        alone.vectors.put("Tempolimits: Wie schnell.", unit(6));
        Result single = alone.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals("never back into its own section", 1, (int) single.getParentCounts()
                .get("outlineCoherenceAlone"));
        assertEquals("", topicOf(single, "p7").getParentId());
        assertArrayEquals(new boolean[]{false, true, false, false}, TopicMerger.verdictsOf("{\"K1\":\"ja\","
                + "\"K2\":\" Nein \",\"K3\":\"vielleicht\",\"K5\":\"nein\"}", 4));
        assertArrayEquals(new boolean[2], TopicMerger.verdictsOf("[\"nein\"]", 2));
    }

    @Test
    public void aLargeSectionIsSplitAndTheLeavesGoInBatchesOfFifty() {
        List<String> names = new ArrayList<String>();
        for (int i = 1; i <= 52; i++) {
            names.add(String.format("Kreuzung %02d", i));
        }
        Case run = new Case(names);
        run.answers = m -> {
            String request = prompt(m);
            if (request.contains("Teile ihn in Unterabschnitte")) {
                return ok(sections("Ampeln", "Wie Ampeln schalten.", "Kreisverkehre", "Wie Kreisel laufen.",
                        "Kreuzungen", "Noch einmal der Abschnitt."));
            }
            if (isSections(m)) {
                return ok(sections("Kreuzungen", "Wie sich Wege kreuzen."));
            }
            return ok(assign(request, leaf -> request.contains("1. Kreuzungen") ? "Kreuzungen"
                    : number(leaf) <= 30 ? "Ampeln" : number(leaf) <= 50 ? "Kreisverkehre" : "Kreuzungen"));
        };
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals("50 and 2, then 50 and 2 for the split", 4, kind(result.getAsked(), Comparison.Kind.ZUORDNUNG)
                .size());
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.TEILUNG).size());
        assertEquals(TopicMerger.OUTLINE_SPLIT_MARK, kind(result.getAsked(), Comparison.Kind.TEILUNG).get(0)
                .getBText());
        MergedTopic crossings = formedNamed(result, "Kreuzungen");
        MergedTopic lights = formedNamed(result, "Ampeln");
        MergedTopic rounds = formedNamed(result, "Kreisverkehre");
        assertEquals("", crossings.getParentId());
        assertEquals(crossings.getId(), lights.getParentId());
        assertEquals(crossings.getId(), rounds.getParentId());
        assertEquals(lights.getId(), topicOf(result, "p1").getParentId());
        assertEquals(rounds.getId(), topicOf(result, "p40").getParentId());
        assertEquals("a subsection named like the section falls away: right under the section", crossings.getId(),
                topicOf(result, "p52").getParentId());
        assertEquals(3, formed(result).size());
        String split = "";
        for (List<ChatMessage> request : run.chat.requests) {
            if (prompt(request).contains("Teile ihn")) {
                split = prompt(request);
            }
        }
        assertTrue(split.contains("Die Themen gehören alle zum Abschnitt „Kreuzungen“ (Wie sich Wege "
                + "kreuzen.). Er ist zu groß für einen Buchabschnitt."));
        Map<String, Integer> counts = result.getParentCounts();
        assertEquals(1, (int) counts.get("outlineSections"));
        assertEquals(1, (int) counts.get("outlineSplit"));
        assertEquals(2, (int) counts.get("outlineSubsections"));
        assertEquals(52, (int) counts.get("outlineAssigned"));
        assertEquals(0, (int) counts.get("outlineAlone"));

        for (List<ChatMessage> request : run.chat.requests) {
            assertFalse("at most 50 leaves a call", prompt(request).contains("\n51. "));
        }

        Case shallow = new Case(names);
        shallow.answers = run.answers;
        Result flat = shallow.merge(Options.defaults().withParallel(1).withOutline(true).withSubtopics(2, 1));
        assertEquals("one level allows the section, not the subsections", 1, formed(flat).size());
        assertEquals("no room: no split asked", 0, kind(flat.getAsked(), Comparison.Kind.TEILUNG).size());
        assertEquals(1, (int) flat.getParentCounts().get("outlineSplitNoRoom"));
        assertEquals(0, (int) flat.getParentCounts().get("outlineTooDeep"));
        assertEquals(formedNamed(flat, "Kreuzungen").getId(), topicOf(flat, "p1").getParentId());

        Case small = new Case(names.subList(0, 40));
        small.answers = run.answers;
        Result fits = small.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals("40 leaves are no large section", 0, kind(fits.getAsked(), Comparison.Kind.TEILUNG).size());
    }

    @Test
    public void aPlaceWithSectionsOfAnEarlierRunKeepsThem() throws Exception {
        Case run = new Case(Arrays.asList("Blitzer in der Stadt", "Bußgelder bei Tempoverstößen", "Bahnlärm"));
        run.memory.addTopic(ResearchTopic.found("thema-0001", "Tempolimits", "Wie schnell gefahren werden darf.",
                Collections.<ResearchTopic.Finding>emptyList(), "test", 1L));
        run.memory.addTopic(ResearchTopic.found("thema-0002", "Tempo 30", "", Collections.<ResearchTopic.Finding>
                emptyList(), "test", 1L).withParent("thema-0001", "test", 1L));
        run.answers = m -> ok(assign(prompt(m), leaf -> leaf.startsWith("Bahn") ? null : "Tempolimits"));
        Result result = run.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(0, kind(result.getAsked(), Comparison.Kind.ABSCHNITTE).size());
        assertEquals(1, kind(result.getAsked(), Comparison.Kind.ZUORDNUNG).size());
        assertTrue(prompt(run.chat.requests.get(0)).contains("1. Tempolimits: Wie schnell gefahren werden darf."));
        assertFalse("a known topic without topics below is no section",
                prompt(run.chat.requests.get(0)).contains("Tempo 30"));
        assertEquals("thema-0001", topicOf(result, "p1").getParentId());
        assertEquals("thema-0001", topicOf(result, "p2").getParentId());
        assertEquals("", topicOf(result, "p3").getParentId());
        assertEquals(0, formed(result).size());
        assertEquals(1, (int) result.getParentCounts().get("outlineKnown"));
        assertEquals(2, (int) result.getParentCounts().get("outlineAssigned"));
    }

    @Test
    public void callsOnTheSameTextAreReused() {
        Case first = new Case(SIX);
        first.answers = m -> isSections(m) ? ok(sections("Tempolimits", "Wie schnell in der Stadt gefahren werden "
                + "darf.", "Parken", "Wo und zu welchem Preis Autos stehen.")) : ok(assign(prompt(m), leaf ->
                leaf.contains("ark") ? "Parken" : leaf.startsWith("Bahn") ? null : "Tempolimits"));
        Result once = first.merge(Options.defaults().withParallel(1).withOutline(true));
        Case again = new Case(SIX);
        again.answers = m -> MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "not asked");
        again.earlier = once.getComparisons();
        Result twice = again.merge(Options.defaults().withParallel(1).withOutline(true));
        assertEquals(0, again.chat.calls.get());
        assertEquals(0, twice.getAsked().size());
        assertEquals(formedNamed(twice, "Tempolimits").getId(), topicOf(twice, "p1").getParentId());
        assertEquals(formedNamed(twice, "Parken").getId(), topicOf(twice, "p5").getParentId());
    }

    @Test
    public void answersAreReadStrictly() {
        List<String[]> sections = TopicMerger.sectionsOf("{\"abschnitte\":[{\"name\":\"Parken\",\"inhalt\":\"A.\"},"
                + "{\"name\":\" parken \",\"inhalt\":\"B.\"},{\"name\":\"\",\"inhalt\":\"C.\"},\"Lärm\","
                + "{\"name\":\"Lärm\"}]}");
        assertEquals(2, sections.size());
        assertEquals("Parken", sections.get(0)[0]);
        assertEquals("A.", sections.get(0)[1]);
        assertEquals("", sections.get(1)[1]);
        assertTrue(TopicMerger.sectionsOf("nicht json").isEmpty());
        assertTrue(TopicMerger.sectionsOf("{\"themen\":[]}").isEmpty());
        assertEquals(120, TopicMerger.sectionsOf("{\"abschnitte\":[{\"name\":\"" + repeat('x', 200) + "\"}]}")
                .get(0)[0].length());
        int[] chosen = TopicMerger.assignmentOf("{\"T1\":2,\"T2\":\"0\",\"T3\":3,\"T4\":-1,\"5\":1,\"T6\":1.5}", 7,
                2);
        assertEquals(Arrays.toString(new int[]{2, 0, -1, -1, -1, -1, -1}), Arrays.toString(chosen));
        assertNull(TopicMerger.assignmentOf("[1,2]", 2, 2));
        assertNull("no valid entry", TopicMerger.assignmentOf("{\"T1\":5}", 1, 2));
        JsonObject schema = JsonParser.parseString(TopicMerger.assignSchema(3)).getAsJsonObject();
        assertEquals(3, schema.getAsJsonArray("required").size());
        assertEquals("integer", schema.getAsJsonObject("properties").getAsJsonObject("T3").get("type").getAsString());
        assertTrue(TopicMerger.outlinePrompt("A book.", Comparison.Kind.REST, "Parking", Collections.<String>
                emptyList(), "", "", "- Fees", "- Garages", false).contains("Sections there are (do not set them "
                + "again):\n- Fees"));
        assertTrue(TopicMerger.assignPrompt("A book.", "1. Fees", "1. Garages", false).contains("not sort by aspect"));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** Leaves p1, p2, … from sources s1, s2, …, each along a dimension of its own; any other text far from all. */
    private static final class Case {
        final List<String> leaves;
        final Map<String, float[]> vectors = new HashMap<String, float[]>();
        final Map<String, String> descriptions = new HashMap<String, String>();
        final ResearchMemory memory = new ResearchMemory();
        Function<List<ChatMessage>, MainModelChatResult> answers;
        /** The answers to the merge calls; none by default: nothing to merge. */
        Function<List<ChatMessage>, MainModelChatResult> merges = m -> ok("{\"zusammenlegen\":[]}");
        String title;
        /** The coherence check: off while null. */
        TopicMerger.CoherenceScorer scorer;
        Function<List<ChatMessage>, MainModelChatResult> small;
        Scripted smallChat;
        List<Comparison> earlier;
        Scripted chat;

        Case(List<String> leaves) {
            this.leaves = leaves;
            for (int i = 0; i < leaves.size(); i++) {
                vectors.put(leaves.get(i), unit(i % (DIMENSIONS - 4)));
            }
        }

        Result merge(Options options) {
            List<TopicProposal> proposals = new ArrayList<TopicProposal>();
            for (int i = 0; i < leaves.size(); i++) {
                proposals.add(new TopicProposal("p" + (i + 1), "s" + (i + 1), "https://s" + (i + 1)
                        + ".example/verkehr", "s" + (i + 1) + "-p1", "", FindingCheck.NICHT_GEFUNDEN, leaves.get(i),
                        descriptions.containsKey(leaves.get(i)) ? descriptions.get(leaves.get(i)) : "",
                        Collections.<String>emptyList(), "gemma4:e2b", 1, 5L));
            }
            chat = new Scripted(m -> isMerge(m) ? merges.apply(m) : answers.apply(m));
            EmbeddingPort embeddings = (role, texts) -> {
                List<EmbeddingPort.EmbeddingVector> out = new ArrayList<EmbeddingPort.EmbeddingVector>();
                for (String text : texts) {
                    float[] vector = vectors.containsKey(text) ? vectors.get(text) : unit(DIMENSIONS - 1);
                    out.add(new EmbeddingPort.EmbeddingVector("test-embed", "test-embed", vector));
                }
                return out;
            };
            TopicMerger merger = title == null ? new TopicMerger(chat, embeddings, MISSION, "de", options, null, CLOCK)
                    : new TopicMerger(chat, embeddings, MISSION, title, "de", options, null, CLOCK);
            if (scorer != null) {
                smallChat = new Scripted(small);
                merger.withCoherence(scorer, smallChat);
            }
            return merger.merge(proposals, memory, earlier);
        }
    }

    private static float[] unit(int dimension) {
        float[] values = new float[DIMENSIONS];
        values[dimension] = 1;
        return values;
    }

    private static float[] mix(int one, double a, int two, double b) {
        float[] values = new float[DIMENSIONS];
        values[one] = (float) a;
        values[two] = (float) b;
        return values;
    }

    private static boolean isSections(List<ChatMessage> messages) {
        return TopicMerger.OUTLINE_SYSTEM_DE.equals(messages.get(0).getContent())
                && !prompt(messages).contains("Ordne jedes Thema") && !isMerge(messages);
    }

    private static boolean isMerge(List<ChatMessage> messages) {
        return prompt(messages).contains("zusammengelegt werden sollen");
    }

    private static String prompt(List<ChatMessage> messages) {
        return messages.get(1).getContent();
    }

    /** The prompt of a call without a system message: the coherence check. */
    private static String prompt0(List<ChatMessage> messages) {
        return messages.get(0).getContent();
    }

    private static MainModelChatResult ok(String text) {
        return MainModelChatResult.ok(text);
    }

    /** An answer that sets sections: names and contents in turn. */
    private static String sections(String... nameAndContent) {
        StringBuilder json = new StringBuilder("{\"abschnitte\":[");
        for (int i = 0; i < nameAndContent.length; i += 2) {
            json.append(i == 0 ? "" : ",").append("{\"name\":\"").append(nameAndContent[i]).append("\",\"inhalt\":\"")
                    .append(nameAndContent[i + 1]).append("\"}");
        }
        return json.append("]}").toString();
    }

    /** An assignment answer to the prompt: each topic to the numbered section the function names, null for 0. */
    private static String assign(String prompt, Function<String, String> sectionOf) {
        Map<String, Integer> numbers = new LinkedHashMap<String, Integer>();
        String sections = prompt.substring(prompt.indexOf("Abschnitte:\n") + 12, prompt.indexOf("\n\nThemen:\n"));
        for (String line : sections.split("\n")) {
            String rest = line.substring(line.indexOf(". ") + 2);
            numbers.put(rest.contains(": ") ? rest.substring(0, rest.indexOf(": ")) : rest,
                    Integer.valueOf(line.substring(0, line.indexOf('.'))));
        }
        String topics = prompt.substring(prompt.indexOf("\n\nThemen:\n") + 10, prompt.indexOf("\n\nOrdne jedes"));
        StringBuilder json = new StringBuilder("{");
        for (String line : topics.split("\n")) {
            String number = line.substring(0, line.indexOf('.'));
            String rest = line.substring(line.indexOf(". ") + 2);
            String leaf = rest.contains(": ") ? rest.substring(0, rest.indexOf(": ")) : rest;
            String section = sectionOf.apply(leaf);
            Integer pick = section == null ? null : numbers.get(section);
            json.append(json.length() == 1 ? "" : ",").append('"').append(number).append("\":")
                    .append(pick == null ? 0 : pick);
        }
        return json.append('}').toString();
    }

    private static int number(String leaf) {
        return Integer.parseInt(leaf.substring(leaf.length() - 2));
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
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

    private static List<MergedTopic> formed(Result result) {
        List<MergedTopic> formed = new ArrayList<MergedTopic>();
        for (MergedTopic topic : result.getTopics()) {
            if (topic.isFormed()) {
                formed.add(topic);
            }
        }
        return formed;
    }

    private static MergedTopic formedNamed(Result result, String name) {
        List<String> names = new ArrayList<String>();
        for (MergedTopic topic : formed(result)) {
            if (topic.getName().equals(name)) {
                return topic;
            }
            names.add(topic.getName());
        }
        throw new AssertionError("no section named " + name + " in " + names);
    }

    private static MergedTopic topicOf(Result result, String proposalId) {
        for (MergedTopic topic : result.getTopics()) {
            if (topic.getProposalIds().contains(proposalId)) {
                return topic;
            }
        }
        throw new AssertionError("no topic holds " + proposalId);
    }
}
