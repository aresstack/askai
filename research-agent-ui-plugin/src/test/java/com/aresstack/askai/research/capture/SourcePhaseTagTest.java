package com.aresstack.askai.research.capture;

import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.store.FileResearchSourceRepository;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;

/**
 * Plan PR 14: every accepted source carries the query, the phase and the plan entry of the search that found it,
 * resolved from its {@code search_request_id}, and the tags survive a reload.
 */
public class SourcePhaseTagTest {

    private static final SourceAcceptanceService.SourceTagger TAGGER = new SourceAcceptanceService.SourceTagger() {
        public String phaseFor(String searchRequestId) {
            return "req-plan".equals(searchRequestId) ? "research" : "outline";
        }

        public String planEntryFor(String searchRequestId) {
            return "req-plan".equals(searchRequestId) ? "plan-0001" : "";
        }
    };

    @Test
    public void aPlannedQueryTagsItsSourcesWithPhaseAndEntry() {
        CaptureStore captures = new CaptureStore(10, 1000L);
        final InMemoryResearchSourceRepository repo = InMemoryResearchSourceRepository.empty();
        SourceAcceptanceService service = new SourceAcceptanceService(captures, repo,
                new SourceAcceptanceService.SourceCreator() {
                    public void create(ResearchSourceRecord record) {
                        repo.put(record);
                    }
                }, new ResearchSearchIndex.InMemory());
        service.setSourceTagger(TAGGER);
        VisitedCapture planned = captures.record("https://example.com/a", "A", "Alpha body text.");
        VisitedCapture typed = captures.record("https://example.org/b", "B", "Beta body text.");
        ResearchSourceRecord a = repo.get(service.accept(planned.getCaptureId(), "Classloader Java", false, "de",
                "req-plan").sourceId);
        ResearchSourceRecord b = repo.get(service.accept(typed.getCaptureId(), "jpms", false, "de",
                "req-typed").sourceId);
        assertEquals("Classloader Java", a.getSearchQuery());
        assertEquals("research", a.getPhaseTag());
        assertEquals("plan-0001", a.getPlanEntryId());
        assertEquals("outline", b.getPhaseTag());
        assertEquals("", b.getPlanEntryId());
    }

    @Test
    public void theTagsSurviveAReload() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("askai-tags").toFile();
        new FileResearchSourceRepository(dir).put(ResearchSourceRecord.builder("source-1").url("https://a.example")
                .phaseTag("research").planEntryId("plan-0007").revision(1L).build());
        ResearchSourceRecord reloaded = new FileResearchSourceRepository(dir).get("source-1");
        assertEquals("research", reloaded.getPhaseTag());
        assertEquals("plan-0007", reloaded.getPlanEntryId());
    }
}
