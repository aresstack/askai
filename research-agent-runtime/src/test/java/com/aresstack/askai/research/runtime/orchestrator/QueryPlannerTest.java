package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plan PR 13: the concept query planner with a fake model. */
public class QueryPlannerTest {

    /** Answers from a script, one per call; records the prompts and budgets it was given. */
    static final class ScriptedChat implements MainModelChat {
        final LinkedList<MainModelChatResult> answers = new LinkedList<MainModelChatResult>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();
        final List<Integer> budgets = new ArrayList<Integer>();

        ScriptedChat answer(String json) {
            answers.add(MainModelChatResult.ok(json));
            return this;
        }

        ScriptedChat answer(MainModelChatResult result) {
            answers.add(result);
            return this;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            calls.add(new ArrayList<ChatMessage>(messages));
            budgets.add(maxOutputTokens);
            if (answers.isEmpty()) {
                throw new IllegalStateException("no scripted answer left");
            }
            return answers.removeFirst();
        }

        @Override
        public String modelName() {
            return "fake";
        }
    }

    private static ConceptQueryPlan.Node node(String id, String label, String base) {
        return new ConceptQueryPlan.Node(id, label, Arrays.asList("Java-Plugins", label), "root", base);
    }

    private static ConceptQueryPlan.Request request(String language, int max, ConceptQueryPlan.Node... nodes) {
        return new ConceptQueryPlan.Request(language, "Java-Plugins isolieren", Arrays.asList(nodes),
                Collections.singletonList("java classloader"), Collections.singletonList("Spring"), max);
    }

    @Test
    public void theRoleRunsOnTheSourcesPlannerSlotWithThinkingOff() {
        assertEquals(ResearchModelSlots.PHASE_RESEARCH_PLANNER, QueryPlanner.CONCEPT_ROLE.getSlot());
        assertEquals(ThinkingMode.OFF, QueryPlanner.CONCEPT_ROLE.getThinking());
        assertEquals(256, QueryPlanner.CONCEPT_ANSWER_TOKENS);
    }

    @Test
    public void everyNodeGetsItsBaseQueryPlusAtMostTheMaximumOfValidVariants() {
        ScriptedChat chat = new ScriptedChat()
                .answer("{\"queries\":[\"Classloader Hierarchie Plugins\",\"ab\","
                        + "\"Spring Boot Plugin Classloader\",\"classloader hierarchie   plugins\","
                        + "\"Java Classloader\",\"Plugin Isolation Classloader Leck\",\"OSGi Bundle Classloader\"]}")
                .answer("{\"queries\":[\"JPMS Module Layer Plugins\"]}");
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("de", 2,
                node("n1", "Classloader", "Java Classloader Plugins"),
                node("n2", "Modulsystem", "Java Modulsystem")));

        assertEquals(2, result.getNodes().size());
        ConceptQueryPlan.NodePlan first = result.getNodes().get(0);
        assertEquals("n1", first.getNodeId());
        assertEquals(Arrays.asList("Java Classloader Plugins", "Classloader Hierarchie Plugins",
                "Plugin Isolation Classloader Leck"), first.queries());
        assertEquals("", first.getNote());
        assertEquals(Arrays.asList("Java Modulsystem", "JPMS Module Layer Plugins"),
                result.getNodes().get(1).queries());
        assertEquals(Arrays.asList(256, 256), chat.budgets);
    }

    @Test
    public void anInvalidAnswerIsRepairedOnceThenTheBaseQueryStaysAlone() {
        ScriptedChat chat = new ScriptedChat().answer("not json").answer("{\"queries\":[\"Spring Plugins\"]}");
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("de", 3,
                node("n1", "Classloader", "Java Classloader Plugins")));

        ConceptQueryPlan.NodePlan plan = result.getNodes().get(0);
        assertEquals(Collections.singletonList("Java Classloader Plugins"), plan.queries());
        assertTrue(plan.getNote(), plan.getNote().contains("blacklisted"));
        assertEquals(2, chat.calls.size());
        assertTrue(chat.calls.get(1).get(3).getContent().startsWith("Your answer was rejected"));
    }

    @Test
    public void aFailedOrThrowingModelKeepsTheBaseQueryAndNeverThrows() {
        ScriptedChat chat = new ScriptedChat()
                .answer(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "offline"));
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(chat, request("en", 2,
                node("n1", "Class loading", "java class loading"),
                node("n2", "Modules", "java module system")));

        assertEquals(Collections.singletonList("java class loading"), result.getNodes().get(0).queries());
        assertTrue(result.getNodes().get(0).getNote().contains("offline"));
        assertEquals(Collections.singletonList("java module system"), result.getNodes().get(1).queries());
        assertTrue(result.getNodes().get(1).getNote().contains("planner failed"));
    }

    @Test
    public void theNodeLabelStandsInForAnUnusableBaseQuery() {
        ConceptQueryPlan.Result result = QueryPlanner.planConcept(null, request("de", 2,
                node("n1", "Classloader", "")));
        assertEquals(Collections.singletonList("Classloader"), result.getNodes().get(0).queries());
    }

    @Test
    public void thePromptAsksForTheRequestedLanguageAndCarriesTheContext() {
        ScriptedChat chat = new ScriptedChat().answer("{\"queries\":[\"Classloader Hierarchie Plugins\"]}");
        QueryPlanner.planConcept(chat, request("de", 2, node("n1", "Classloader", "Java Classloader Plugins")));

        assertTrue(chat.calls.get(0).get(0).getContent().contains("LANGUAGE"));
        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue(prompt, prompt.startsWith("LANGUAGE\nGerman"));
        assertTrue(prompt, prompt.contains("in German."));
        assertTrue(prompt, prompt.contains("Java-Plugins isolieren"));
        assertTrue(prompt, prompt.contains("Java-Plugins > Classloader"));
        assertTrue(prompt, prompt.contains("- java classloader"));
        assertTrue(prompt, prompt.contains("BLACKLIST\n- Spring"));
        assertTrue(QueryPlanner.conceptPrompt(request("en", 1), node("n", "x", "abc"), "abc",
                Collections.<String>emptyList()).startsWith("LANGUAGE\nEnglish"));
    }
}
