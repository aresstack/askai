package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/** PR 20: 6,000 vectors of 768 dimensions cluster in under 60 s. Runs with {@code :slowTest}, not {@code test}. */
@Category(SlowTests.class)
public class TopicClustererSlowTest {

    @Test(timeout = 120000L)
    public void sixThousandVectorsOf768DimensionsClusterUnderSixtySeconds() {
        List<Passage> passages = TopicClustererParityTest.passages(6000);
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                TopicClustererParityTest.seededVectors(passages, 768, 60, 1.0, 42L);
        long start = System.nanoTime();
        int topics = new TopicClusterer(IdSequence.counting()).cluster(passages, vectors).size();
        long millis = (System.nanoTime() - start) / 1000000L;
        System.out.println("[pr20-slow] passages=6000 dimension=768 topics=" + topics + " millis=" + millis);
        assertTrue("took " + millis + " ms", millis < 60000L);
    }
}
