package com.aresstack.askai.research.book;

import com.aresstack.askai.research.concept.questions.MissionQuestion;
import com.aresstack.askai.research.concept.questions.MissionQuestionList;
import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.plan.ConceptPlanNodes;
import com.aresstack.askai.research.scope.ConceptAnchorProjection;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Repair plan step 2a, acceptance A2: a frozen question list changes neither the search plan nor the Weidezaun. The
 * plan nodes and the IN anchors of a frozen concept are the same with and without questions.
 */
public class MissionQuestionsLeaveThePlanAloneTest {

    private static final String CONCEPT = "{\"title\":\"Wearables auf Baustellen\",\"subtitle\":\"\",\"concept\":["
            + "{\"Technologien und Sensortypen\":[{\"Inertialsensoren\":[]},{\"Ortung\":[]}]},"
            + "{\"Recht und Datenschutz\":[{\"Betriebsrat\":[]}]}]}";

    private static FrozenConcept frozen(MissionQuestionList questions) {
        return FrozenConcept.of(new ConceptPin("t-concept", 1, true, "hash", 7L, 1000L, "Praxisbuch Wearables",
                "20-30 pages", "de", Collections.<String>emptyList(), questions), CONCEPT);
    }

    @Test
    public void planNodesAndAnchorsAreTheSameWithAndWithoutQuestions() {
        Map<String, List<String>> perCard = new LinkedHashMap<String, List<String>>();
        perCard.put("Technologien und Sensortypen", Arrays.asList("Welche Sensoren messen Sturz und Lage?",
                "Wie genau ist die Ortung in Gebäuden?"));
        perCard.put("Recht und Datenschutz", Arrays.asList("Wann muss der Betriebsrat zustimmen?"));
        MissionQuestionList questions = MissionQuestionList.of(perCard, MissionQuestion.Origin.APP)
                .withCardAnchors(FrozenConcept.firstLevelCards(CONCEPT));
        FrozenConcept without = frozen(MissionQuestionList.empty());
        FrozenConcept with = frozen(questions);
        assertFalse(with.getQuestions().isEmpty());

        assertEquals(nodes(without), nodes(with));
        assertEquals(anchors(without), anchors(with));
        assertEquals(without.getEffectiveDocument(), with.getEffectiveDocument());
        assertEquals(without.summary(), with.summary());
    }

    private static List<String> nodes(FrozenConcept frozen) {
        List<String> out = new ArrayList<String>();
        for (ConceptQueryPlan.Node node : ConceptPlanNodes.of(frozen.getApprovedDocument(),
                frozen.getEffectiveDocument(), null, frozen.getMission(), frozen.getPin().getBlacklist())) {
            out.add(node.getId() + "|" + node.getLabel() + "|" + node.getPath() + "|" + node.getParentId() + "|"
                    + node.getBaseQuery());
        }
        assertFalse(out.isEmpty());
        return out;
    }

    private static List<String> anchors(FrozenConcept frozen) {
        List<String> out = new ArrayList<String>();
        for (ScopeAnchor anchor : ConceptAnchorProjection.anchorsOf(frozen.getEffectiveDocument(),
                ResearchScopeDraft.empty())) {
            out.add(anchor.getAnchorId() + "|" + anchor.getSemanticText() + "|" + anchor.getMembership());
        }
        return out;
    }
}
