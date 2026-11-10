package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.tabs.RowSignal;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The Outline tab's rows: book order, numbers, one state per entry, a filter that keeps a hit's chapter. */
public class OutlineRowTest {

    private static final OutlineBrief BRIEF =
            new OutlineBrief("What the chapter is for.", Collections.singletonList("One point"), 0);

    private static Outline outline() {
        OutlineSection thin = new OutlineSection("s-1", "ch-1", 1, "Heat stress", BRIEF, OutlineProvenance.NONE,
                false, "", false, true);
        OutlineSection stale = new OutlineSection("s-2", "ch-1", 2, "Fall detection", BRIEF,
                OutlineProvenance.NONE, false, "its sources were excluded");
        OutlineChapter first = new OutlineChapter("ch-1", 1, "Wearables", BRIEF, OutlineProvenance.NONE, true, "",
                Arrays.asList(thin, stale));
        OutlineChapter second = new OutlineChapter("ch-2", 2, "Outlook", OutlineBrief.EMPTY,
                OutlineProvenance.NONE, false, "", Collections.<OutlineSection>emptyList());
        return new Outline(Arrays.asList(first, second));
    }

    @Test
    public void rowsKeepTheBookOrderAndNumberTheEntries() {
        List<OutlineRow> rows = OutlineRow.rows(outline(), Collections.<String>emptySet(), "");
        assertEquals(4, rows.size());
        assertEquals("1", rows.get(0).number());
        assertTrue(rows.get(0).isChapter());
        assertEquals("1.1", rows.get(1).number());
        assertEquals("1.2", rows.get(2).number());
        assertEquals("2", rows.get(3).number());
    }

    @Test
    public void everyEntryHasOneStateAndOneWordForIt() {
        List<OutlineRow> rows = OutlineRow.rows(outline(), Collections.<String>emptySet(), null);
        assertEquals("a chapter the user edited is still ready", RowSignal.DONE, rows.get(0).signal());
        assertEquals("Edited", rows.get(0).status());
        assertEquals(RowSignal.CAUTION, rows.get(1).signal());
        assertEquals("Thin", rows.get(1).status());
        assertTrue(rows.get(1).note().startsWith("Thin: fewer than two sources"));
        assertEquals(RowSignal.CAUTION, rows.get(2).signal());
        assertEquals("Stale", rows.get(2).status());
        assertEquals("Stale: its sources were excluded\nWhat the chapter is for.", rows.get(2).note());
        assertEquals(RowSignal.PENDING, rows.get(3).signal());
        assertEquals("No brief", rows.get(3).status());
    }

    @Test
    public void aFailedBriefOutranksEveryOtherState() {
        List<OutlineRow> rows = OutlineRow.rows(outline(), Collections.singleton("ch-2"), "");
        assertEquals(RowSignal.FAILED, rows.get(3).signal());
        assertEquals("Brief failed", rows.get(3).status());
    }

    @Test
    public void theFilterKeepsTheChapterOfAHit() {
        List<OutlineRow> rows = OutlineRow.rows(outline(), Collections.<String>emptySet(), " FALL ");
        assertEquals(2, rows.size());
        assertEquals("Wearables", rows.get(0).title());
        assertEquals("the section keeps its number in the book", "1.2", rows.get(1).number());
        assertEquals(1, OutlineRow.rows(outline(), Collections.<String>emptySet(), "outlook").size());
        assertEquals(0, OutlineRow.rows(outline(), Collections.<String>emptySet(), "nothing").size());
    }
}
