package com.aresstack.askai.research.benchmark;

import org.junit.Test;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BenchmarkQuestionSetTest {

    @Test
    public void theShippedSetIsValidAndExercisesEveryMetric() {
        BenchmarkQuestionSet set = BenchmarkQuestionSet.load();
        assertEquals(1, set.getVersion());
        assertTrue("at least four questions", set.getQuestions().size() >= 4);
        boolean contradiction = false;
        boolean gap = false;
        boolean distractor = false;
        Set<String> ids = new HashSet<String>();
        for (BenchmarkQuestion question : set.getQuestions()) {
            assertTrue(ids.add(question.getId()));
            assertFalse(question.getExpected().getFacts().isEmpty());
            contradiction |= !question.getExpected().getContradictions().isEmpty();
            gap |= !question.getExpected().getGaps().isEmpty();
            distractor |= !question.getExpected().getDistractorPages().isEmpty();
        }
        assertTrue("a question with a planted contradiction", contradiction);
        assertTrue("a question with a planted gap", gap);
        assertTrue("a question with distractor pages", distractor);
    }

    @Test
    public void selectionKeepsSetOrderAndRejectsUnknownIds() {
        BenchmarkQuestionSet set = BenchmarkQuestionSet.load();
        assertEquals(set.getQuestions(), set.select(java.util.Collections.<String>emptyList()));
        assertEquals("q2-contradiction", set.select(Arrays.asList("q3-gap", "q2-contradiction")).get(0).getId());
        try {
            set.select(Arrays.asList("q9-nothing"));
            fail("unknown id must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("q9-nothing"));
        }
    }

    @Test
    public void aFactWhoseKeywordsAreNotOnItsPageIsRejected() {
        assertInvalid(question("\"facts\":[{\"id\":\"f\",\"keywords\":[\"absent words\"],\"supportedBy\":[\"a\"]}]",
                "\"gaps\":[]"), "does not contain all keywords");
    }

    @Test
    public void aGapThatAPageCoversIsRejected() {
        assertInvalid(question("\"facts\":[{\"id\":\"f\",\"keywords\":[\"alpha\"],\"supportedBy\":[\"a\"]}]",
                "\"gaps\":[{\"id\":\"g\",\"section\":\"S\",\"keywords\":[\"beta\"]}]"), "is covered by page");
    }

    @Test
    public void unknownPageReferencesAreRejected() {
        assertInvalid(question("\"facts\":[{\"id\":\"f\",\"keywords\":[\"alpha\"],\"supportedBy\":[\"zz\"]}]",
                "\"gaps\":[]"), "unknown page zz");
    }

    private static String question(String facts, String gaps) {
        return "{\"version\":1,\"questions\":[{\"id\":\"q\",\"purpose\":\"p\",\"question\":\"q?\",\"query\":\"alpha\","
                + "\"outline\":[\"S\"],\"pages\":["
                + "{\"id\":\"a\",\"host\":\"h1\",\"path\":\"/a\",\"title\":\"A\",\"paragraphs\":[\"alpha text\"]},"
                + "{\"id\":\"b\",\"host\":\"h2\",\"path\":\"/b\",\"title\":\"B\",\"paragraphs\":[\"beta text\"]}],"
                + "\"expected\":{\"relevantPages\":[\"a\",\"b\"],\"minimumSources\":2,\"minimumDistinctHosts\":2,"
                + facts + "," + gaps + "}}]}";
    }

    private static void assertInvalid(String json, String reason) {
        try {
            BenchmarkQuestionSet.parse(new StringReader(json));
            fail("must be rejected: " + reason);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(reason));
        }
    }
}
