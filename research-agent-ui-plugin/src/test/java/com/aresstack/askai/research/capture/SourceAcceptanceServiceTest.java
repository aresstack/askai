package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.sources.SourceStatus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** VISITED → CANDIDATE → ACCEPTED: strict separation, atomic + idempotent acceptance, dedup, index boundary. */
public class SourceAcceptanceServiceTest {

    private static final class Fx {
        final CaptureStore captures = new CaptureStore(10, 1000L);
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        final ResearchSearchIndex.InMemory index = new ResearchSearchIndex.InMemory();
        final SourceAcceptanceService service = new SourceAcceptanceService(captures, repo,
                new SourceAcceptanceService.SourceCreator() {
                    public void create(ResearchSourceRecord record) {
                        repo.put(record);
                    }
                }, index);
    }

    @Test
    public void visitCreatesCaptureButNeverASourceOrIndexEntry() {
        Fx fx = new Fx();
        VisitedCapture cap = fx.captures.record("https://Example.com/a?utm_source=x#top", "A", "Alpha text.");
        assertEquals(VisitedCapture.Stage.VISITED, cap.getStage());
        assertEquals("https://example.com/a", cap.getCanonicalUrl()); // canonicalized
        assertTrue("no source from a visit", fx.repo.find(SourceQuery.all()).isEmpty());
        assertEquals("no index entry from a visit", 0, fx.index.size());

        // CANDIDATE (assessment metadata) still creates neither source nor index entry.
        fx.captures.assess(cap.getCaptureId(), "HIGH", "docs", "looks relevant");
        assertEquals(VisitedCapture.Stage.CANDIDATE, fx.captures.get(cap.getCaptureId()).getStage());
        assertTrue(fx.repo.find(SourceQuery.all()).isEmpty());
        assertEquals(0, fx.index.size());
    }

    @Test
    public void acceptRecordsTheUserRelevantStarWhenSet() {
        Fx fx = new Fx();
        VisitedCapture cap = fx.captures.record("https://example.com/star", "Star", "Body about wearables.");
        SourceAcceptanceService.Result r = fx.service.accept(cap.getCaptureId(), "wearables", true);
        assertEquals(SourceAcceptanceService.Status.ACCEPTED, r.status);
        assertTrue("the ⭐ is recorded on the source", fx.repo.get(r.sourceId).isUserRelevant());

        // A plain accept (no star) leaves it false.
        VisitedCapture plain = fx.captures.record("https://example.com/plain", "Plain", "Other body text.");
        SourceAcceptanceService.Result r2 = fx.service.accept(plain.getCaptureId());
        assertFalse(fx.repo.get(r2.sourceId).isUserRelevant());
    }

    @Test
    public void acceptCreatesExactlyOneSourceAndOneIndexEntryAndIsIdempotent() {
        Fx fx = new Fx();
        VisitedCapture cap = fx.captures.record("https://example.com/a", "Alpha", "Alpha body.\n\nMore.");
        fx.captures.assess(cap.getCaptureId(), "HIGH", "article", "good");
        SourceAcceptanceService.Result r = fx.service.accept(cap.getCaptureId());
        assertEquals(SourceAcceptanceService.Status.ACCEPTED, r.status);
        assertEquals(1, fx.repo.find(SourceQuery.all()).size());
        assertEquals(1, fx.index.size());
        assertEquals(2, r.passageCount);
        assertFalse(r.duplicate);
        // Assessment metadata travelled onto the record.
        ResearchSourceRecord rec = fx.repo.get(r.sourceId);
        assertEquals("good", rec.getComment());
        assertEquals("article", rec.getSourceType());

        // Idempotent: same capture again → ALREADY_ACCEPTED, same id, still 1 source + 1 index entry.
        SourceAcceptanceService.Result again = fx.service.accept(cap.getCaptureId());
        assertEquals(SourceAcceptanceService.Status.ALREADY_ACCEPTED, again.status);
        assertEquals(r.sourceId, again.sourceId);
        assertEquals(1, fx.repo.find(SourceQuery.all()).size());
        assertEquals(1, fx.index.size());

        // Compact tool result: no raw text leaked.
        assertFalse(r.render().contains("Alpha body"));
    }

    @Test
    public void dedupRulesCanonicalUrlAndContentHash() {
        Fx fx = new Fx();
        // Accept the original.
        VisitedCapture a = fx.captures.record("https://example.com/a", "Alpha", "Same content.");
        fx.service.accept(a.getCaptureId());

        // Same canonical URL (tracking params differ) + same hash → identical → ALREADY_ACCEPTED.
        VisitedCapture same = fx.captures.record("https://example.com/a?utm_medium=m", "Alpha", "Same content.");
        SourceAcceptanceService.Result identical = fx.service.accept(same.getCaptureId());
        assertEquals(SourceAcceptanceService.Status.ALREADY_ACCEPTED, identical.status);
        assertEquals(1, fx.repo.find(SourceQuery.all()).size());

        // Different URL, same hash → content duplicate: accepted but flagged DUPLICATE.
        VisitedCapture mirror = fx.captures.record("https://mirror.net/copy", "Copy", "Same content.");
        SourceAcceptanceService.Result dup = fx.service.accept(mirror.getCaptureId());
        assertEquals(SourceAcceptanceService.Status.ACCEPTED, dup.status);
        assertTrue(dup.duplicate);
        assertEquals(SourceStatus.DUPLICATE, fx.repo.get(dup.sourceId).getStatus());

        // Same URL, different hash → changed page → the SAME source, refreshed as its next revision.
        String original = fx.service.accept(a.getCaptureId()).sourceId;
        VisitedCapture changed = fx.captures.record("https://example.com/a", "Alpha v2", "New content.");
        SourceAcceptanceService.Result fresh = fx.service.accept(changed.getCaptureId());
        assertEquals(SourceAcceptanceService.Status.ACCEPTED, fresh.status);
        assertFalse(fresh.duplicate);
        assertEquals("one address stays one source", original, fresh.sourceId);
        assertEquals(2, fx.repo.find(SourceQuery.all()).size());
        ResearchSourceRecord revised = fx.repo.get(original);
        assertEquals("New content.", revised.getFullText());
        assertEquals(changed.getContentHash(), revised.getChecksum());
        assertEquals(2L, revised.getRevision());
        assertEquals("Alpha v2", revised.getTitle());
    }

    @Test
    public void aReReadPageRefreshesItsSourceAndKeepsTheUsersDecisions() {
        Fx fx = new Fx();
        final java.util.List<String> jobs = new java.util.ArrayList<String>();
        fx.service.setKnowledgeProcessingScheduler(
                new com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        jobs.add(captureId + "->" + sourceId);
                    }
                });
        VisitedCapture first = fx.captures.record("https://example.com/news", "News", "Monday edition.");
        SourceAcceptanceService.Result accepted = fx.service.accept(first.getCaptureId(), "q", true);
        fx.repo.put(fx.repo.get(accepted.sourceId).toBuilder().status(SourceStatus.REVIEWED).build());

        VisitedCapture reread = fx.captures.record("https://example.com/news", "News", "Tuesday edition.");
        SourceAcceptanceService.Result refreshed = fx.service.accept(reread.getCaptureId());
        assertEquals(accepted.sourceId, refreshed.sourceId);
        ResearchSourceRecord record = fx.repo.get(accepted.sourceId);
        assertEquals("the review decision stays", SourceStatus.REVIEWED, record.getStatus());
        assertTrue("the ⭐ stays", record.isUserRelevant());
        assertEquals("Tuesday edition.", record.getFullText());
        // The knowledge store gets the NEW capture under the SAME source: the newest replaces the older one there.
        assertEquals(java.util.Arrays.asList(first.getCaptureId() + "->" + accepted.sourceId,
                reread.getCaptureId() + "->" + accepted.sourceId), jobs);
    }

    @Test
    public void parkingAnAddressWhosePageIsAlreadyCapturedSaysSo() {
        Fx fx = new Fx();
        VisitedCapture read = fx.captures.record("https://example.com/read", "Read", "Already read body.");
        String sourceId = fx.service.accept(read.getCaptureId()).sourceId;
        SourceAcceptanceService.ParkResult again = fx.service.park("https://example.com/read?utm_source=x",
                "Read", "snippet", 1.0, "q");
        assertFalse(again.created);
        assertTrue(again.captured);
        assertEquals("status=ALREADY_CAPTURED source_id=" + sourceId, again.render());

        // A parked (not yet read) address is only ALREADY_PRESENT: the search still has to read it.
        fx.service.park("https://example.com/unread", "Unread", "snippet", 1.0, "q");
        SourceAcceptanceService.ParkResult parked = fx.service.park("https://example.com/unread", "Unread",
                "snippet", 1.0, "q");
        assertFalse(parked.captured);
        assertTrue(parked.render().startsWith("status=ALREADY_PRESENT"));
    }

    @Test
    public void acceptStoresTheFullPageTextOnTheRecord() {
        Fx fx = new Fx();
        VisitedCapture cap = fx.captures.record("https://example.com/a", "Alpha", "Alpha body.\n\nMore.");
        SourceAcceptanceService.Result r = fx.service.accept(cap.getCaptureId());
        ResearchSourceRecord rec = fx.repo.get(r.sourceId);
        assertEquals("Alpha body.\n\nMore.", rec.getFullText());
        assertFalse("a visited+read source is not parked", rec.isParked());
    }

    @Test
    public void parkWritesAScoredCandidateWithEmptyFullTextThenAVisitEnrichesItInPlace() {
        Fx fx = new Fx();
        // Park a reranked candidate before visiting: score present, full text empty, status PARKED.
        SourceAcceptanceService.ParkResult parked = fx.service.park(
                "https://example.com/a?utm_source=x", "Alpha", "A short snippet.", 1.5, "smart glasses");
        assertTrue(parked.created);
        assertEquals(1, fx.repo.find(SourceQuery.all()).size());
        ResearchSourceRecord parkedRec = fx.repo.get(parked.sourceId);
        assertEquals(SourceStatus.PARKED, parkedRec.getStatus());
        assertTrue(parkedRec.isParked());
        assertEquals("A short snippet.", parkedRec.getExcerpt());
        assertEquals(1.5, parkedRec.getRerankScore(), 1e-9);
        assertEquals("smart glasses", parkedRec.getSearchQuery());
        assertEquals(0, fx.index.size()); // nothing indexed while parked (no full text)

        // Parking the same canonical URL again is a no-op (idempotent), not a duplicate.
        SourceAcceptanceService.ParkResult again = fx.service.park(
                "https://example.com/a", "Alpha", "other", 9.9, "x");
        assertFalse(again.created);
        assertEquals(parked.sourceId, again.sourceId);
        assertEquals(1, fx.repo.find(SourceQuery.all()).size());

        // Visiting the parked URL enriches the SAME record: full text filled, promoted PARKED→NEW,
        // score kept, no second source created.
        VisitedCapture cap = fx.captures.record("https://example.com/a", "Alpha", "The full page body.");
        SourceAcceptanceService.Result accepted = fx.service.accept(cap.getCaptureId(), "smart glasses");
        assertEquals(SourceAcceptanceService.Status.ACCEPTED, accepted.status);
        assertEquals("enrich in place, no new source", parked.sourceId, accepted.sourceId);
        assertEquals(1, fx.repo.find(SourceQuery.all()).size());
        ResearchSourceRecord enriched = fx.repo.get(parked.sourceId);
        assertEquals("The full page body.", enriched.getFullText());
        assertFalse(enriched.isParked());
        assertEquals(SourceStatus.NEW, enriched.getStatus());
        assertEquals("parked score is kept through enrich", 1.5, enriched.getRerankScore(), 1e-9);
        assertEquals(1, fx.index.size()); // indexed now that it has full text
    }

    @Test
    public void acceptanceFiresTheCaptureHookExactlyOncePerAcceptedCapture() {
        Fx fx = new Fx();
        final java.util.List<String> accepted = new java.util.ArrayList<String>();
        fx.service.setKnowledgeProcessingScheduler(
                new com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        accepted.add(captureId + "->" + sourceId);
                    }
                });
        VisitedCapture cap = fx.captures.record("https://example.com/a", "A", "Alpha body.");
        SourceAcceptanceService.Result r = fx.service.accept(cap.getCaptureId());
        assertEquals(1, accepted.size());
        assertTrue(accepted.get(0).endsWith("->" + r.sourceId));

        // Re-accepting the SAME capture is ALREADY_ACCEPTED: the hook must NOT fire again (no double enqueue).
        fx.service.accept(cap.getCaptureId());
        assertEquals("hook fires once per accepted capture", 1, accepted.size());
    }

    @Test
    public void indexFailureKeepsTheSourceAndIndexIsRebuildable() {
        Fx fx = new Fx();
        VisitedCapture cap = fx.captures.record("https://example.com/x", "X", "X body.");
        fx.index.failNextIndex();
        SourceAcceptanceService.Result r = fx.service.accept(cap.getCaptureId());
        assertEquals(SourceAcceptanceService.Status.ACCEPTED, r.status);
        assertTrue("index marked stale", r.indexStale);
        assertEquals("source survives the index failure", 1, fx.repo.find(SourceQuery.all()).size());
        assertEquals(0, fx.index.size());

        // The index is a derived view: rebuild from the source records restores it.
        fx.index.rebuild(fx.repo.find(SourceQuery.all()));
        assertEquals(1, fx.index.size());
    }

    @Test
    public void unknownCaptureAndBoundedLifecycle() {
        Fx fx = new Fx();
        assertEquals(SourceAcceptanceService.Status.UNKNOWN_CAPTURE, fx.service.accept("nope").status);

        CaptureStore tiny = new CaptureStore(2, 1000L);
        tiny.record("https://e.com/1", "1", "one");
        tiny.record("https://e.com/2", "2", "two");
        tiny.record("https://e.com/3", "3", "three");
        assertEquals("bounded capture lifecycle (oldest evicted)", 2, tiny.size());
    }

    @Test
    public void extractorsHandleHtmlRemnantsMarkdownAndText() {
        DocumentExtractor.ExtractedDocument html = DocumentExtractor.Chain.extract(
                new DocumentExtractor.DocumentInput("text/html", "",
                        "<html><head><title>T</title><script>bad()</script></head>"
                                + "<body><h1>Head</h1><p>One.</p><p>Two.</p></body></html>"));
        assertEquals("T", html.getTitle());
        assertTrue(html.getText().contains("Head"));       // headings preserved as text
        assertFalse(html.getText().contains("bad()"));     // scripts stripped
        assertEquals(3, html.getPassageCount());

        DocumentExtractor.ExtractedDocument md = DocumentExtractor.Chain.extract(
                new DocumentExtractor.DocumentInput("text/markdown", "notes.md",
                        "# Title\n\nBlock one.\n\nBlock two."));
        assertEquals("Title", md.getTitle());
        assertEquals(3, md.getPassageCount());

        DocumentExtractor.ExtractedDocument txt = DocumentExtractor.Chain.extract(
                new DocumentExtractor.DocumentInput("text/plain", "", "First line\n\nSecond block"));
        assertEquals("First line", txt.getTitle());
        assertEquals(2, txt.getPassageCount());
    }
}
