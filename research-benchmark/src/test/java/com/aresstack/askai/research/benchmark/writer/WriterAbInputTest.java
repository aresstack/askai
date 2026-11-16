package com.aresstack.askai.research.benchmark.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.askai.research.runtime.writer.SectionAssignment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class WriterAbInputTest {

    private static String section(String id) {
        return "{\"id\":\"" + id + "\",\"title\":\"T " + id + "\",\"brief\":\"B\",\"keyPoints\":[\"k1\",\"k2\"],"
                + "\"words\":900,\"passages\":[{\"sourceId\":\"s1\",\"checksum\":\"c\",\"passageId\":\"p1\","
                + "\"text\":\"Text eins.\"},{\"sourceId\":\"s2\",\"checksum\":\"d\",\"passageId\":\"p2\","
                + "\"text\":\"Text zwei.\"}]}";
    }

    @Test
    public void parsesTheChapterAndBuildsTheArmsAssignment() {
        WriterAbInput input = WriterAbInput.parse("{\"language\":\"de\",\"synopsis\":\"S\","
                + "\"glossary\":[\"EEG: Erneuerbare-Energien-Gesetz\"],\"sections\":[" + section("a") + ","
                + section("b") + "," + section("c") + "]}");
        assertEquals(3, input.sections.size());
        assertEquals(2, input.sections.get(0).passages.get(1).getNumber());
        SectionAssignment assignment = input.assignment(input.sections.get(1), WriterAbArm.planArms().get(1), 3072,
                "digest");
        assertEquals("German", assignment.getBookLanguage());
        assertEquals(1100, assignment.getWordsPerCall());
        assertEquals("digest", assignment.getPreviousDigest());
        assertEquals(Arrays.asList("EEG: Erneuerbare-Energien-Gesetz"), assignment.getGlossary());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aChapterNeedsThreeSections() {
        WriterAbInput.parse("{\"sections\":[" + section("a") + "]}");
    }

    @Test
    public void ownerRankingResolvesThroughTheKey() {
        String key = "section 1: A=gemma4:e2b, B=llama3.1:8b, C=gpt-oss:20b\n"
                + "chapter: A=llama3.1:8b, B=gpt-oss:20b, C=gemma4:e2b\n";
        String sheet = "# Writer A/B\n\n## section 1\n\nRanking: B, A, C\n\n### Text A\n\nx\n\n"
                + "## chapter\n\nRanking: a > c > b\n";
        Map<String, List<String>> resolved = OwnerRanking.resolve(sheet, key);
        assertEquals(Arrays.asList("llama3.1:8b", "gemma4:e2b", "gpt-oss:20b"), resolved.get("section 1"));
        assertEquals(Arrays.asList("llama3.1:8b", "gemma4:e2b", "gpt-oss:20b"), resolved.get("chapter"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnratedItemIsRejected() {
        OwnerRanking.resolve("## section 1\n\nRanking: \n", "section 1: A=gemma4:e2b, B=llama3.1:8b\n");
    }

    @Test
    public void digestIsTheCutLastParagraph() {
        assertEquals("Zweiter Absatz [1].", WriterAbLiveTest.digest("Erster.\n\nZweiter Absatz [1]."));
        assertTrue(WriterAbLiveTest.digest("").isEmpty());
    }
}
