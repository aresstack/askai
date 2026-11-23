package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.SourceCapture;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The source processing path embeds passages and the sentence windows of the passage boundaries as documents. */
public class PassageSegmentationEmbeddingRoleTest {

    private static SourceCapture capture() {
        SourceCapture.StructuralBlock block = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root",
                "Kupfer leitet Strom. Kupfer leitet Waerme. Der Preis stieg 2024. Minen liegen in Chile. "
                        + "Recycling spart Energie. Die Nachfrage waechst.");
        return new SourceCapture("cap-1", "src-1", "https://example.org/a", 0L, "h", "T", "",
                Collections.singletonList(block));
    }

    @Test
    public void everyTextOfTheSegmentationIsADocument() {
        RecordingEmbeddingPort port = new RecordingEmbeddingPort();
        PassageSegmentation.Result result = new PassageSegmentation(new RegexSentenceSegmenter(), port, "seg-v1",
                "de", 2, 0.35, 2, 8).segment(capture());

        assertFalse(port.calls().isEmpty());
        assertTrue("no query role in source processing", port.texts(EmbeddingPort.Role.QUERY).isEmpty());
        assertTrue("no raw role in source processing", port.texts(EmbeddingPort.Role.RAW).isEmpty());
        // the sentence texts used to place the boundaries
        assertTrue(port.texts(EmbeddingPort.Role.DOCUMENT).contains("Kupfer leitet Strom."));
        // and every finished passage
        for (Passage passage : result.getPassages()) {
            assertTrue(passage.getText(), port.texts(EmbeddingPort.Role.DOCUMENT).contains(passage.getText()));
        }
        assertEquals(port.calls().size(), port.texts(EmbeddingPort.Role.DOCUMENT).size());
    }
}
