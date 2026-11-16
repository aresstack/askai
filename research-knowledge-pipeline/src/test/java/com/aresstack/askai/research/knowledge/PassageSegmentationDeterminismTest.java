package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** IDs are deterministic from fachliche identity (capture + position + pipeline + embedding), not a counter. */
public class PassageSegmentationDeterminismTest {

    /** A fixed embedder: constant fingerprint so passage ids are stable and version-aware. */
    private static final class FixedEmbedder implements EmbeddingPort {
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new EmbeddingVector("m", "fp-1", new float[]{1f, 0f}));
            }
            return out;
        }
    }

    private static SourceCapture capture() {
        SourceCapture.StructuralBlock block = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root",
                "Display optics matter. Battery power matters. Privacy matters too.");
        return new SourceCapture("cap-1", "src-1", "https://x/y", 0L, "h", "T", "",
                Collections.singletonList(block));
    }

    private static PassageSegmentation segmentation() {
        return new PassageSegmentation(new RegexSentenceSegmenter(), new FixedEmbedder(), "seg-v1");
    }

    private static List<String> sentenceIds(PassageSegmentation.Result r) {
        List<String> ids = new ArrayList<String>();
        for (Sentence s : r.getSentences()) {
            ids.add(s.getSentenceId());
        }
        return ids;
    }

    private static List<String> passageIds(PassageSegmentation.Result r) {
        List<String> ids = new ArrayList<String>();
        for (Passage p : r.getPassages()) {
            ids.add(p.getPassageId());
        }
        return ids;
    }

    @Test
    public void sameCaptureAndVersionReproduceIdenticalIds() {
        PassageSegmentation seg = segmentation();
        PassageSegmentation.Result first = seg.segment(capture());
        PassageSegmentation.Result second = seg.segment(capture());
        assertEquals(sentenceIds(first), sentenceIds(second));
        assertEquals(passageIds(first), passageIds(second));
        assertTrue("at least one passage", first.getPassages().size() >= 1);
    }

    @Test
    public void idsCarryTheirFachlicheIdentity() {
        PassageSegmentation.Result r = segmentation().segment(capture());
        assertEquals("cap-1#s0", r.getSentences().get(0).getSentenceId());
        // passage id: capture + position + segmentation version + embedding fingerprint
        String passageId = r.getPassages().get(0).getPassageId();
        assertTrue(passageId, passageId.startsWith("cap-1#p0@seg-v1-fp-1"));
    }

    @Test
    public void aDifferentSegmentationVersionYieldsDifferentPassageIds() {
        String v1 = new PassageSegmentation(new RegexSentenceSegmenter(), new FixedEmbedder(), "seg-v1")
                .segment(capture()).getPassages().get(0).getPassageId();
        String v2 = new PassageSegmentation(new RegexSentenceSegmenter(), new FixedEmbedder(), "seg-v2")
                .segment(capture()).getPassages().get(0).getPassageId();
        assertTrue("version-aware ids differ", !v1.equals(v2));
    }
}
