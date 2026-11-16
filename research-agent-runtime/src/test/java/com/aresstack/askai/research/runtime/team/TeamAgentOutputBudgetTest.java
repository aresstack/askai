package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * The live regression behind "Die neuen Quellen wurden übernommen; die Auswertung konnte diesmal nicht
 * erstellt werden": the post-search review is the LONGEST contracted answer (summary of up to 12 sources
 * plus brief/suggestions/scope patch in ONE JSON), and a 1024-token output budget truncated it
 * mid-string — unparseable, the repair truncated identically, every review ended UNUSABLE_ANSWER. This
 * pins the granted output budget and the truncation-diagnosable trace excerpt.
 */
public class TeamAgentOutputBudgetTest {

    /** Captures the output budget the agent actually grants the model. */
    private static final class BudgetCapturingModel implements MainModelChat {
        final List<Integer> grantedBudgets = new ArrayList<Integer>();

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            grantedBudgets.add(maxOutputTokens);
            return MainModelChatResult.ok("{\"assistantMessage\":\"Zusammenfassung der Quellen.\"}");
        }

        public String modelName() {
            return "gemma4:e2b";
        }
    }

    @Test
    public void theDefaultOutputBudgetFitsTheReviewContract() {
        BudgetCapturingModel model = new BudgetCapturingModel();
        ResearchTeamAgent agent = new ResearchTeamAgent(model);
        TeamAgentResult result = agent.internalTurn(TeamAgentPlaybook.sourceReviewInstruction(),
                ResearchStatusView.empty());
        assertTrue(result.isOk());
        assertTrue("one model call", model.grantedBudgets.size() == 1);
        assertTrue("the review's single JSON (12-source summary + brief + patch) does not fit 1024 "
                + "tokens; granted: " + model.grantedBudgets.get(0),
                model.grantedBudgets.get(0) >= 8192);
        assertTrue("the default is the documented constant, not a scattered literal",
                model.grantedBudgets.get(0) == ResearchTeamAgent.DEFAULT_MAX_OUTPUT_TOKENS);
    }

    @Test
    public void theConfiguredBudgetIsGrantedVerbatimAndNonsenseIsIgnored() {
        BudgetCapturingModel model = new BudgetCapturingModel();
        ResearchTeamAgent agent = new ResearchTeamAgent(model);
        agent.setMaxOutputTokens(8192); // the user's setting, handed to the process at launch
        agent.setMaxOutputTokens(0);    // a non-positive value must never shrink the budget to nothing
        agent.internalTurn(TeamAgentPlaybook.sourceReviewInstruction(), ResearchStatusView.empty());
        assertTrue("the configured budget reaches the model verbatim: " + model.grantedBudgets.get(0),
                model.grantedBudgets.get(0) == 8192);
        assertTrue(agent.getMaxOutputTokens() == 8192);
    }

    /** Answers every call with the same text, cut at the output budget (finish reason "length"). */
    private static final class TruncatingModel implements MainModelChat {
        int calls;
        final String text;

        TruncatingModel(String text) {
            this.text = text;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            calls++;
            return MainModelChatResult.okTruncated(text);
        }

        public String modelName() {
            return "gemma4:e2b";
        }
    }

    @Test
    public void anAnswerCutAtTheOutputBudgetIsNamedAsSuchAndNotRepaired() {
        TruncatingModel model = new TruncatingModel("{\"assistantMessage\":\"Zusammenfassung der Que");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);
        agent.setMaxOutputTokens(256);

        TeamAgentResult result = agent.internalTurn(TeamAgentPlaybook.sourceReviewInstruction(),
                ResearchStatusView.empty());

        assertTrue(result.getStatus() == TeamAgentResult.Status.UNUSABLE_ANSWER);
        assertTrue("the cause is known: the output budget", result.isTruncated());
        assertTrue(result.getTruncatedAtTokens() == 256);
        assertTrue("a repair would be cut at the same place — exactly one model call: " + model.calls,
                model.calls == 1);
        String german = TeamAgentReply.visible(result, "de");
        assertTrue(german, german.contains("Token-Limit (256 Tokens)") && german.contains("Answer budget"));
        assertTrue("the cut-off text never reaches the user", !german.contains("Zusammenfassung der Que"));
        String english = TeamAgentReply.visible(result, "en");
        assertTrue(english, english.contains("token limit (256 tokens)"));
    }

    @Test
    public void aTruncatedButReadableAnswerIsStillAnAnswer() {
        TruncatingModel model = new TruncatingModel("{\"assistantMessage\":\"Zusammenfassung der Quellen.\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);
        TeamAgentResult result = agent.internalTurn(TeamAgentPlaybook.sourceReviewInstruction(),
                ResearchStatusView.empty());
        assertTrue(result.isOk());
        assertTrue(!result.isTruncated());
    }

    @Test
    public void anUnreadableAnswerThatWasNotCutKeepsTheGenericLine() {
        TeamAgentResult result = TeamAgentResult.unusableAnswer("no JSON");
        assertTrue(!result.isTruncated());
        assertTrue(TeamAgentReply.visible(result, "de").startsWith("Ich konnte gerade keine klare Antwort"));
    }

    @Test
    public void theParseFailureExcerptShowsHeadAndTailSoTruncationIsVisible() {
        StringBuilder longAnswer = new StringBuilder("{\"assistantMessage\":\"");
        for (int i = 0; i < 200; i++) {
            longAnswer.append("wort").append(i).append(' ');
        }
        // No closing brace/quote — the shape of an output-budget truncation.
        String excerpt = ResearchTeamAgent.excerptOf(longAnswer.toString());
        assertTrue("the head is visible", excerpt.startsWith("{\"assistantMessage\":"));
        assertTrue("the TAIL is visible — that is where a truncation shows",
                excerpt.endsWith(longAnswer.substring(longAnswer.length() - 300).replace('\n', '⏎')));
        assertTrue("the total length is named", excerpt.contains("chars]"));
    }
}
