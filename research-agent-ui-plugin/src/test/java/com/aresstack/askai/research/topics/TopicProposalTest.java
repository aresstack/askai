package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.topics.TopicProposal.FindingCheck;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 13: a proposal keeps at most two questions, needs id, source and name, and gives its finding. */
public class TopicProposalTest {

    @Test
    public void questionsAreCleanedAndLimitedToTwo() {
        TopicProposal proposal = new TopicProposal(" vs-1 ", "s1", " https://a.example/x ", "p1", " Zitat ",
                null, " Lastenräder ", "Satz", Arrays.asList(" Wie viele? ", "", null, "Wie viele?", "Wo?"), "m",
                0, 5L);
        assertEquals("vs-1", proposal.getId());
        assertEquals("https://a.example/x", proposal.getSourceUrl());
        assertEquals("Zitat", proposal.getQuote());
        assertEquals("Lastenräder", proposal.getName());
        assertEquals(Arrays.asList("Wie viele?", "Wo?"), proposal.getQuestions());
        assertEquals(FindingCheck.NICHT_GEFUNDEN, proposal.getFindingCheck());
        assertFalse(proposal.hasFinding());
        assertEquals(1, proposal.getRound());
        try {
            new TopicProposal("vs-2", "s1", "", "p1", "", FindingCheck.GEPRUEFT, "Name", "", Arrays.asList("A?",
                    "B?", "C?"), "m", 1, 1L);
            fail("three questions");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void aProposalNeedsIdSourceAndName() {
        for (String[] missing : new String[][]{{"", "s1", "Name"}, {"vs-1", " ", "Name"}, {"vs-1", "s1", null}}) {
            try {
                new TopicProposal(missing[0], missing[1], "", "", "", FindingCheck.GEPRUEFT, missing[2], "",
                        Collections.<String>emptyList(), "m", 1, 1L);
                fail("missing part");
            } catch (IllegalArgumentException expected) {
                // fine
            }
        }
    }

    @Test
    public void theFindingHasTheFormOfTheMemory() {
        TopicProposal proposal = new TopicProposal("vs-1", "s1", "", "p4", "die letzte Meile", FindingCheck.KORRIGIERT,
                "Lastenräder", "", Collections.<String>emptyList(), "m", 1, 1L);
        assertTrue(proposal.hasFinding());
        ResearchTopic.Finding finding = proposal.toFinding();
        assertEquals("s1", finding.getSourceId());
        assertEquals("p4", finding.getPassageId());
        assertEquals("die letzte Meile", finding.getQuote());
        assertEquals("vs-1 Lastenräder (s1 / p4, KORRIGIERT)", proposal.toString());
    }
}
