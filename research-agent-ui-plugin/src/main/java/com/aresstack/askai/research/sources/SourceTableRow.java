package com.aresstack.askai.research.sources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One row of the Sources table (the "Results"): a found source with where it stands — not read yet, read, read
 * after a solved security check, blocked by one, or set aside. Immutable.
 */
public final class SourceTableRow {

    private final ResearchSourceRecord source;
    private final SourceAccessLog.Entry access;
    private final SourceRowState state;

    private SourceTableRow(ResearchSourceRecord source, SourceAccessLog.Entry access, SourceRowState state) {
        this.source = source;
        this.access = access;
        this.state = state;
    }

    /**
     * The rows in display order: not read yet, read, check solved, blocked, set aside; within one state by
     * score (best first), then by title.
     */
    public static List<SourceTableRow> rows(List<ResearchSourceRecord> sources, SourceAccessLog accessLog) {
        SourceAccessLog log = accessLog == null ? SourceAccessLog.EMPTY : accessLog;
        List<SourceTableRow> rows = new ArrayList<SourceTableRow>();
        if (sources != null) {
            for (ResearchSourceRecord record : sources) {
                SourceAccessLog.Entry access = log.lookup(addressOf(record));
                rows.add(new SourceTableRow(record, access, stateOf(record, access)));
            }
        }
        Collections.sort(rows, DISPLAY_ORDER);
        return rows;
    }

    static SourceRowState stateOf(ResearchSourceRecord record, SourceAccessLog.Entry access) {
        SourceStatus status = record.getStatus();
        if (status == SourceStatus.EXCLUDED || status == SourceStatus.DUPLICATE
                || status == SourceStatus.SUPERSEDED) {
            return SourceRowState.SET_ASIDE;
        }
        SourceAccessState accessState = access == null ? SourceAccessState.NONE : access.getState();
        if (accessState == SourceAccessState.BLOCKED) {
            return SourceRowState.FAILED;
        }
        if (record.isParked()) {
            return SourceRowState.PENDING;
        }
        return accessState == SourceAccessState.RESOLVED ? SourceRowState.CHECK_SOLVED : SourceRowState.DONE;
    }

    private static final Comparator<SourceTableRow> DISPLAY_ORDER = new Comparator<SourceTableRow>() {
        public int compare(SourceTableRow a, SourceTableRow b) {
            int byState = a.state.compareTo(b.state);
            if (byState != 0) {
                return byState;
            }
            int byScore = Double.compare(scoreOf(b.source), scoreOf(a.source));
            return byScore != 0 ? byScore : a.title().compareToIgnoreCase(b.title());
        }
    };

    private static double scoreOf(ResearchSourceRecord record) {
        return record.hasRerankScore() ? record.getRerankScore() : Double.NEGATIVE_INFINITY;
    }

    static String addressOf(ResearchSourceRecord record) {
        return record.getUrl().isEmpty() ? record.getOrigin() : record.getUrl();
    }

    public ResearchSourceRecord source() {
        return source;
    }

    /** The security-check record of the source's website, or {@code null}. */
    public SourceAccessLog.Entry access() {
        return access;
    }

    public SourceRowState state() {
        return state;
    }

    /** What the main column shows: the title, or the address of an untitled source. */
    public String title() {
        return source.getTitle().trim().isEmpty() ? addressOf(source) : source.getTitle();
    }

    /** The website: the bare host of the address. */
    public String site() {
        String url = addressOf(source);
        int schemeEnd = url.indexOf("://");
        String rest = schemeEnd >= 0 ? url.substring(schemeEnd + 3) : url;
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    /** What happened to the source, in one or two words. */
    public String statusText() {
        switch (state) {
            case PENDING:
                return "Unread";
            case CHECK_SOLVED:
                return "Check solved";
            case FAILED:
                return "Blocked";
            case SET_ASIDE:
                return SourceLabels.of(source.getStatus());
            default:
                SourceStatus status = source.getStatus();
                return status == SourceStatus.NEW ? "Read" : SourceLabels.of(status);
        }
    }
}
