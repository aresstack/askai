package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The productive SourceCaptureReader maps a stored capture to the canonical SourceCapture (lossless paragraph
 * blocks), resolves the owning source id by canonical URL, and returns null for an unknown capture.
 */
public class CaptureStoreSourceCaptureReaderTest {

    @Test
    public void readsAStoredCaptureAsACanonicalSourceCaptureWithResolvedSourceId() {
        CaptureStore captures = new CaptureStore(10, 123L);
        VisitedCapture visited = captures.record("https://Example.com/a?utm_source=x#frag", "Title",
                "First paragraph.\n\nSecond paragraph.");

        InMemoryResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        repo.put(ResearchSourceRecord.builder("source-7").url("https://example.com/a").build());
        CaptureStoreSourceCaptureReader reader =
                new CaptureStoreSourceCaptureReader(captures, new CanonicalUrlSourceIdResolver(repo));

        SourceCapture capture = reader.read(visited.getCaptureId());
        assertEquals("src provenance resolved via canonical URL", "source-7", capture.getSourceId());
        assertEquals(visited.getCaptureId(), capture.getCaptureId());
        assertEquals(2, capture.getBlocks().size());
        assertEquals("First paragraph.", capture.getBlocks().get(0).getText());
        assertEquals("Second paragraph.", capture.getBlocks().get(1).getText());
    }

    @Test
    public void theJobsSourceIdWinsOverTheFirstSourceOfTheSameAddress() {
        // Two sources share one address (an older store wrote a second source for a re-read page): the canonical
        // URL lookup would attribute every capture of that page to the FIRST of them. The job names its source.
        CaptureStore captures = new CaptureStore(10, 123L);
        VisitedCapture visited = captures.record("https://example.com/a", "Title", "Body.");
        InMemoryResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        repo.put(ResearchSourceRecord.builder("source-7").url("https://example.com/a").build());
        repo.put(ResearchSourceRecord.builder("source-70").url("https://example.com/a").build());
        CaptureStoreSourceCaptureReader reader =
                new CaptureStoreSourceCaptureReader(captures, new CanonicalUrlSourceIdResolver(repo), repo);

        assertEquals("source-70", reader.read(visited.getCaptureId(), "source-70").getSourceId());
        assertEquals("without a job source the lookup decides", "source-7",
                reader.read(visited.getCaptureId(), "").getSourceId());
    }

    @Test
    public void captureIdsContinueAfterTheHighestNumberTheProjectAlreadyUsed() {
        // A restarted session must never reissue cap-8: processing jobs and knowledge captures are keyed by it.
        assertEquals(34L, CaptureStore.highestCaptureNumber(java.util.Arrays.asList(
                "cap-8", "cap-34", "import-reconciled-source-3", "cap-x")));
        assertEquals(0L, CaptureStore.highestCaptureNumber(java.util.Collections.<String>emptyList()));
        CaptureStore resumed = new CaptureStore(10, 123L, 34L);
        assertEquals("cap-35", resumed.record("https://example.com/a", "T", "Body.").getCaptureId());
        assertEquals("cap-1", new CaptureStore(10, 123L).record("https://example.com/a", "T", "B.")
                .getCaptureId());
    }

    @Test
    public void anUnknownCaptureIdIsNull() {
        CaptureStore captures = new CaptureStore(10, 123L);
        CaptureStoreSourceCaptureReader reader =
                new CaptureStoreSourceCaptureReader(captures, CaptureStoreSourceCaptureReader.SourceIdResolver.NONE);
        assertNull(reader.read("cap-does-not-exist"));
    }

    @Test
    public void aGoneCaptureFallsBackToTheDurableSourceRecord() {
        // Issue #29: the in-memory capture store is bounded working material. After a restart/eviction the
        // job's canonical input comes from the PERSISTED source record (full cleaned text), so a delayed,
        // user-triggered segmentation still works.
        CaptureStore captures = new CaptureStore(10, 123L);
        InMemoryResearchSourceRepository repo = new InMemoryResearchSourceRepository();
        repo.put(ResearchSourceRecord.builder("source-9")
                .url("https://example.com/gone")
                .title("Durable title")
                .capturedAt(4242L)
                .checksum("hash-9")
                .fullText("First paragraph.\n\nSecond paragraph.")
                .build());
        CaptureStoreSourceCaptureReader reader = new CaptureStoreSourceCaptureReader(captures,
                CaptureStoreSourceCaptureReader.SourceIdResolver.NONE, repo);

        SourceCapture capture = reader.read("cap-gone", "source-9");
        assertEquals("source-9", capture.getSourceId());
        assertEquals("cap-gone", capture.getCaptureId());
        assertEquals("Durable title", capture.getTitle());
        assertEquals("hash-9", capture.getChecksum());
        assertEquals(2, capture.getBlocks().size());
        assertEquals("First paragraph.", capture.getBlocks().get(0).getText());

        // Without a source id (or without persisted text) the capture is honestly gone.
        assertNull(reader.read("cap-gone", ""));
        assertNull(reader.read("cap-gone", "source-unknown"));
    }

    @Test
    public void anUnlinkedCaptureGetsAnEmptySourceId() {
        CaptureStore captures = new CaptureStore(10, 123L);
        VisitedCapture visited = captures.record("https://nowhere.test/x", "T", "Body text.");
        CaptureStoreSourceCaptureReader reader = new CaptureStoreSourceCaptureReader(captures,
                new CanonicalUrlSourceIdResolver(new InMemoryResearchSourceRepository()));

        SourceCapture capture = reader.read(visited.getCaptureId());
        assertEquals("", capture.getSourceId());
    }
}
