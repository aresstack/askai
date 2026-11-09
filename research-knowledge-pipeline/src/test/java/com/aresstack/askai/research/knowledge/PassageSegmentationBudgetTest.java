package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.SourceCapture;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** PR 20: no embedding input exceeds the budget; passages longer than it are split before embedding. */
public class PassageSegmentationBudgetTest {

    private static String words(String word, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(word).append(i);
        }
        return sb.toString();
    }

    private static SourceCapture capture(SourceCapture.StructuralBlock... blocks) {
        return new SourceCapture("cap-1", "src-1", "https://example.org/a", 0L, "h", "T", "", Arrays.asList(blocks));
    }

    private static PassageSegmentation segmentation(RecordingEmbeddingPort port, int budget) {
        return new PassageSegmentation(new RegexSentenceSegmenter(), port, "seg-v2", "de", 2, 0.35, 2, 8,
                PassageSegmentation.BoundaryMode.STRUCTURE_ONLY, budget);
    }

    @Test
    public void aLongParagraphIsGroupedIntoWholeSentencesWithinTheBudget() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            text.append(words("Satz" + i + "wort", 8)).append(". ");
        }
        RecordingEmbeddingPort port = new RecordingEmbeddingPort();
        PassageSegmentation.Result result = segmentation(port, 200).segment(capture(
                new SourceCapture.StructuralBlock("b1", SourceCapture.BlockKind.PARAGRAPH, "Root", text.toString())));

        assertTrue(result.getPassages().size() > 1);
        int sentences = 0;
        for (Passage passage : result.getPassages()) {
            assertTrue(passage.getText().length() <= 200);
            sentences += passage.getSentenceIds().size();
        }
        assertEquals("every sentence lands in exactly one passage", 12, sentences);
        for (String input : port.texts(EmbeddingPort.Role.DOCUMENT)) {
            assertTrue(input.length() <= 200);
        }
    }

    @Test
    public void aCodeBlockAboveTheBudgetIsCutIntoNumberedPiecesOfTheSameSentence() {
        RecordingEmbeddingPort port = new RecordingEmbeddingPort();
        String code = words("token", 120);
        PassageSegmentation.Result result = segmentation(port, 150).segment(capture(
                new SourceCapture.StructuralBlock("b1", SourceCapture.BlockKind.CODE, "Root", code)));

        List<Passage> passages = result.getPassages();
        assertTrue(passages.size() > 1);
        HashSet<String> ids = new HashSet<String>();
        StringBuilder joined = new StringBuilder();
        for (Passage passage : passages) {
            assertTrue(passage.getText().length() <= 150);
            assertEquals(Collections.singletonList(result.getSentences().get(0).getSentenceId()),
                    passage.getSentenceIds());
            ids.add(passage.getPassageId());
            joined.append(joined.length() == 0 ? "" : " ").append(passage.getText());
        }
        assertEquals("ids stay unique", passages.size(), ids.size());
        assertEquals("nothing is lost", code, joined.toString());
        assertTrue(passages.get(1).getPassageId().startsWith("cap-1#p0.1@"));
    }

    @Test
    public void shortPassagesKeepTheirFormerIds() {
        RecordingEmbeddingPort port = new RecordingEmbeddingPort();
        PassageSegmentation.Result result = segmentation(port, PassageSegmentation.DEFAULT_MAX_EMBEDDING_CHARS)
                .segment(capture(new SourceCapture.StructuralBlock("b1", SourceCapture.BlockKind.PARAGRAPH, "Root",
                        "Kupfer leitet Strom. Kupfer leitet Waerme.")));
        assertEquals(1, result.getPassages().size());
        assertTrue(result.getPassages().get(0).getPassageId().startsWith("cap-1#p0@seg-v2-"));
    }
}
