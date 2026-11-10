package com.aresstack.askai.research.sources;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** The Results rows: one state per source, ordered to do → read → check solved → blocked → set aside. */
public class SourceTableRowTest {

    private static ResearchSourceRecord read(String id, String url, double score) {
        return ResearchSourceRecord.builder(id).title(id).url(url).revision(1L).status(SourceStatus.NEW)
                .fullText("text").rerankScore(score).build();
    }

    @Test
    public void rowsAreOrderedByStateThenByScore() {
        SourceAccessLog log = SourceAccessLog.EMPTY.blocked("blocked.example", "https://blocked.example/a")
                .blocked("solved.example", "https://solved.example/a")
                .resolved("solved.example", "https://solved.example/a");
        List<ResearchSourceRecord> sources = Arrays.asList(
                read("blocked", "https://blocked.example/a", 0.9),
                read("solved", "https://www.solved.example/a", 0.8),
                read("low", "https://plain.example/low", 0.2),
                read("high", "https://plain.example/high", 0.7),
                ResearchSourceRecord.builder("parked").title("parked").url("https://plain.example/p")
                        .revision(1L).status(SourceStatus.PARKED).rerankScore(0.95).build(),
                read("excluded", "https://plain.example/x", 0.99).toBuilder().status(SourceStatus.EXCLUDED)
                        .build());

        List<String> order = new ArrayList<String>();
        List<SourceRowState> states = new ArrayList<SourceRowState>();
        for (SourceTableRow row : SourceTableRow.rows(sources, log)) {
            order.add(row.source().getSourceId());
            states.add(row.state());
        }

        assertEquals(Arrays.asList("parked", "high", "low", "solved", "blocked", "excluded"), order);
        assertEquals(Arrays.asList(SourceRowState.PENDING, SourceRowState.DONE, SourceRowState.DONE,
                SourceRowState.CHECK_SOLVED, SourceRowState.FAILED, SourceRowState.SET_ASIDE), states);
    }

    @Test
    public void theStatusTextSaysWhatHappenedToTheSource() {
        SourceAccessLog log = SourceAccessLog.EMPTY.blocked("blocked.example", "");
        List<SourceTableRow> rows = SourceTableRow.rows(Arrays.asList(
                ResearchSourceRecord.builder("p").url("https://plain.example/p").revision(1L)
                        .status(SourceStatus.PARKED).build(),
                read("r", "https://plain.example/r", 0.5),
                read("b", "https://blocked.example/b", 0.5),
                read("x", "https://plain.example/x", 0.5).toBuilder().status(SourceStatus.DUPLICATE).build()),
                log);
        assertEquals("Unread", rows.get(0).statusText());
        assertEquals("an untitled source shows its address", "https://plain.example/p", rows.get(0).title());
        assertEquals("plain.example", rows.get(0).site());
        assertEquals("Read", rows.get(1).statusText());
        assertEquals("Blocked", rows.get(2).statusText());
        assertEquals("Duplicate", rows.get(3).statusText());
    }

    @Test
    public void everyBoundedValueHasAReadableLabel() {
        assertEquals("Not read yet", SourceLabels.of(SourceStatus.PARKED));
        assertEquals("Excluded", SourceLabels.of(SourceStatus.EXCLUDED));
        assertEquals("Not rated", SourceLabels.of(SourceRelevance.UNKNOWN));
        assertEquals("Primary source", SourceLabels.of(SourceReliability.PRIMARY_SOURCE));
    }
}
