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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
        assertTrue(assignPrompt.contains("6. Bahnlärm: " + noise.substring(0, 140).trim() + " …\n\n"));
        assertFalse("no description in the section call", sectionPrompt.contains("Wie laut Züge"));
        assertTrue(assignPrompt.contains("Ordne nicht nach Aspekt (Recht, Risiko, Maßnahme), sondern nach der Sache."));
        assertTrue(run.chat.schemas.get(1).contains("\"6\""));
        assertTrue(run.chat.schemas.get(0).contains("abschnitte"));
        Comparison sections = kind(result.getAsked(), Comparison.Kind.ABSCHNITTE).get(0);
        assertEquals(TopicMerger.OUTLINE_MARK, sections.getBText());
        assertEquals(7, sections.getVersion());
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
                : ok("{\"1\":1,\"2\":1,\"4\":9,\"5\":2,\"6\":0}");
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
                        "Parken", "Wie man parkt.") : sections("Tempolimits", "Wie schnell gefahren werden darf."));
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
        assertEquals(1, (int) counts.get("outlineCardName"));
        assertEquals(2, (int) counts.get("outlineAlone"));
        boolean free = false;
        for (List<ChatMessage> request : run.chat.requests) {
            free |= prompt(request).contains("Die Themen stehen unter keiner Karte. Die Karten des Buchs sind: "
                    + "Parken.");
        }
        assertTrue("the free level names the cards", free);
        boolean logged = false;
        for (Comparison sections : kind(result.getAsked(), Comparison.Kind.ABSCHNITTE)) {
            logged |= sections.getAText().startsWith("Freie Ebene; Karten: Parken\n");
        }
        assertTrue("the case as logged and reused names the cards", logged);
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
        assertEquals(52, (int) flat.getParentCounts().get("outlineTooDeep"));
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
        int[] chosen = TopicMerger.assignmentOf("{\"1\":2,\"2\":\"0\",\"3\":3,\"4\":-1,\"5\":\"zwei\",\"6\":1.5}", 7,
                2);
        assertEquals(Arrays.toString(new int[]{2, 0, -1, -1, -1, -1, -1}), Arrays.toString(chosen));
        assertNull(TopicMerger.assignmentOf("[1,2]", 2, 2));
        assertNull("no valid entry", TopicMerger.assignmentOf("{\"1\":5}", 1, 2));
        JsonObject schema = JsonParser.parseString(TopicMerger.assignSchema(3)).getAsJsonObject();
        assertEquals(3, schema.getAsJsonArray("required").size());
        assertEquals("integer", schema.getAsJsonObject("properties").getAsJsonObject("3").get("type").getAsString());
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
            chat = new Scripted(answers);
            EmbeddingPort embeddings = (role, texts) -> {
                List<EmbeddingPort.EmbeddingVector> out = new ArrayList<EmbeddingPort.EmbeddingVector>();
                for (String text : texts) {
                    float[] vector = vectors.containsKey(text) ? vectors.get(text) : unit(DIMENSIONS - 1);
                    out.add(new EmbeddingPort.EmbeddingVector("test-embed", "test-embed", vector));
                }
                return out;
            };
            return new TopicMerger(chat, embeddings, MISSION, "de", options, null, CLOCK).merge(proposals, memory,
                    earlier);
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
                && !prompt(messages).contains("Ordne jedes Thema");
    }

    private static String prompt(List<ChatMessage> messages) {
        return messages.get(1).getContent();
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
