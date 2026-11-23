package com.aresstack.askai.research.benchmark.writer;

import static org.junit.Assert.assertEquals;

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
    public void readsTheChapterOfSeventeenNovember() {
        WriterAbInput input = WriterAbInput.parse("{\"language\":\"de\",\"synopsis\":\"S\","
                + "\"glossary\":[\"EEG: Erneuerbare-Energien-Gesetz\"],\"sections\":[" + section("a") + ","
                + section("b") + "," + section("c") + "]}");
        assertEquals(1, input.format);
        assertEquals(3, input.sections.size());
        assertEquals("S", input.synopsis);
        assertEquals(2, input.sections.get(0).passages.get(1).getNumber());
        assertEquals("p2", input.sections.get(0).passages.get(1).getPassageId());
        assertEquals(Arrays.asList("EEG: Erneuerbare-Energien-Gesetz"), input.sections.get(2).glossary);
        assertEquals("S", input.outlineChapter().getBrief().getPurpose());
        assertEquals(900, input.outlineChapter().getSections().get(1).getBrief().getWordTarget());
    }

    @Test
    public void theExportFormatSurvivesTheFile() {
        WriterAbInput first = WriterAbInput.parse("{\"language\":\"de\",\"synopsis\":\"S\",\"sections\":["
                + section("a") + "," + section("b") + "," + section("c") + "]}");
        WriterAbInput again = WriterAbInput.parse(first.toJson());
        assertEquals(WriterAbInput.FORMAT, again.format);
        assertEquals("de", again.language);
        assertEquals(first.synopsis, again.synopsis);
        assertEquals(first.sections.get(1).passages, again.sections.get(1).passages);
        assertEquals(first.sections.get(1).keyPoints, again.sections.get(1).keyPoints);
        assertEquals(2, again.sourceChecksums().size());
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
}
