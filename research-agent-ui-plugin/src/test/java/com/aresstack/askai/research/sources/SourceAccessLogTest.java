package com.aresstack.askai.research.sources;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SourceAccessLogTest {

    @Test
    public void blockedThenResolvedKeepsTheTriggeringPage() {
        SourceAccessLog log = SourceAccessLog.EMPTY
                .blocked("researchgate.net", "https://www.researchgate.net/publication/1");
        assertEquals(SourceAccessState.BLOCKED,
                log.stateOf("https://www.researchgate.net/publication/2"));
        log = log.resolved("researchgate.net", "");
        assertEquals(SourceAccessState.RESOLVED, log.stateOf("https://researchgate.net/x"));
        assertEquals("https://www.researchgate.net/publication/1",
                log.lookup("https://researchgate.net/x").getUrl());
    }

    @Test
    public void aNewCheckOnASolvedWebsiteIsBlockedAgain() {
        SourceAccessLog log = SourceAccessLog.EMPTY.resolved("almas-industries.de", "https://almas-industries.de/a")
                .blocked("almas-industries.de", "https://almas-industries.de/b");
        assertEquals(SourceAccessState.BLOCKED, log.stateOf("https://almas-industries.de/c"));
    }

    @Test
    public void onlyTheFamilyAndItsSubdomainsMatch() {
        SourceAccessLog log = SourceAccessLog.EMPTY.blocked("example.org", "");
        assertEquals(SourceAccessState.BLOCKED, log.stateOf("https://blog.example.org:8443/p?q=1"));
        assertEquals(SourceAccessState.NONE, log.stateOf("https://notexample.org/"));
        assertEquals(SourceAccessState.NONE, log.stateOf(""));
        assertNull(log.lookup("https://other.com/"));
    }

    @Test
    public void emptyFamiliesAreIgnoredAndLeadingWwwIsNormalised() {
        assertTrue(SourceAccessLog.EMPTY.blocked(" ", "u").isEmpty());
        assertEquals("example.org", SourceAccessLog.EMPTY.blocked("www.Example.org", "").entries()
                .iterator().next().getFamily());
    }

    @Test
    public void tooltipNoteNamesTheStateAndTheWebsite() {
        SourceAccessLog log = SourceAccessLog.EMPTY.blocked("researchgate.net", "");
        assertTrue(ResearchSourcesView.accessNote(log.lookup("https://researchgate.net/"))
                .contains("not solved"));
        assertTrue(ResearchSourcesView.accessNote(log.resolved("researchgate.net", "")
                .lookup("https://researchgate.net/")).contains("was solved"));
        assertEquals("", ResearchSourcesView.accessNote(null));
    }
}
