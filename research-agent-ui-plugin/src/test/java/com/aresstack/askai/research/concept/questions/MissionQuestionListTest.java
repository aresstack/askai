package com.aresstack.askai.research.concept.questions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Repair plan step 2a, part A: the question list as the user edits and the app stores it. */
public class MissionQuestionListTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static MissionQuestionList drafted() {
        Map<String, List<String>> perCard = new LinkedHashMap<String, List<String>>();
        perCard.put("Technik", Arrays.asList("Welche Sensoren gibt es?", "Wie genau messen sie?"));
        perCard.put("Recht", Arrays.asList("Was regelt die DSGVO?"));
        return MissionQuestionList.of(perCard, MissionQuestion.Origin.APP);
    }

    @Test
    public void theTextFormRoundTripsAndKeepsTheOrigin() {
        MissionQuestionList list = drafted();
        String text = list.toText();
        assertEquals("## Technik\n- Welche Sensoren gibt es?\n- Wie genau messen sie?\n\n## Recht\n"
                + "- Was regelt die DSGVO?\n", text);

        MissionQuestionList edited = MissionQuestionList.parse(text.replace("- Wie genau messen sie?\n", "")
                + "* Muss der Betriebsrat zustimmen?\n", list);

        assertEquals(3, edited.size());
        assertEquals("F03", edited.getQuestions().get(2).getId());
        assertEquals("Recht", edited.getQuestions().get(2).getCard());
        assertEquals(MissionQuestion.Origin.APP, edited.getQuestions().get(0).getOrigin());
        assertEquals("added by the user", MissionQuestion.Origin.USER, edited.getQuestions().get(2).getOrigin());
    }

    @Test
    public void emptyLinesNumbersAndDoublesAreIgnored() {
        MissionQuestionList list = MissionQuestionList.parse("# Karte\n\n1. Eins?\n2) Zwei?\n- Eins?\n", null);
        assertEquals(2, list.size());
        assertEquals("Zwei?", list.getQuestions().get(1).getText());
        assertTrue(MissionQuestionList.parse("", null).isEmpty());
    }

    @Test
    public void jsonAndTheStoreRoundTrip() throws Exception {
        FileMissionQuestionStore store = new FileMissionQuestionStore(folder.getRoot());
        assertTrue(store.load().isEmpty());
        store.save(drafted());
        MissionQuestionList loaded = store.load();
        assertEquals(drafted().toText(), loaded.toText());
        assertEquals(MissionQuestion.Origin.APP, loaded.getQuestions().get(1).getOrigin());
        assertEquals("Technik", loaded.getQuestions().get(1).getCard());
    }

    /** Architect review of PR #257: a question points at its card by the card's anchor id, not by its name alone. */
    @Test
    public void questionsAreBoundToTheAnchorIdOfTheirCard() {
        MissionQuestionList list = MissionQuestionList.parse("## Recht und Datenschutz\n- Wann stimmt der "
                + "Betriebsrat zu?\n## Alte Karte\n- Was kostet es?\n", MissionQuestionList.empty());
        java.util.List<String> cards = java.util.Arrays.asList("Technologien und Sensortypen",
                " recht und datenschutz ");

        MissionQuestionList bound = list.withCardAnchors(cards);

        org.junit.Assert.assertEquals("anchor-concept-recht-und-datenschutz",
                bound.getQuestions().get(0).getCardAnchorId());
        org.junit.Assert.assertEquals("", bound.getQuestions().get(1).getCardAnchorId());
        org.junit.Assert.assertEquals(java.util.Collections.singletonList("Alte Karte"),
                list.cardsWithoutCard(cards));
        org.junit.Assert.assertEquals("the binding survives JSON", "anchor-concept-recht-und-datenschutz",
                MissionQuestionList.fromJson(bound.toJson()).getQuestions().get(0).getCardAnchorId());
        org.junit.Assert.assertEquals("the same id the Weidezaun gives the card",
                com.aresstack.askai.research.scope.ConceptAnchorProjection.anchorsOf(
                        "{\"title\":\"\",\"concept\":[{\"Recht und Datenschutz\":[]}]}",
                        com.aresstack.askai.research.domain.scope.ResearchScopeDraft.empty()).get(0).getAnchorId(),
                bound.getQuestions().get(0).getCardAnchorId());
    }
}
