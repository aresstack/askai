package com.aresstack.askai.research.candidates;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;

import java.util.Locale;

/**
 * Component 5: how the candidate search builds its list. The default is the measured best of 21.11.2026: the
 * {@value #DEFAULT_PER_LIST} passages most similar to the question united with the {@value #DEFAULT_PER_LIST} best by
 * key words, at most {@value #DEFAULT_PER_SOURCE} passages of a source in each list. Today's fused order stays
 * selectable as {@link Mode#FUSION}.
 */
public final class CandidateSearchSettings {

    /** How the two rankings of the stock become one list. */
    public enum Mode {
        /** The similarity list and the key-word list side by side, united (default). */
        UNION,
        /** Today's mix: one order fused by rank ({@code ReciprocalRankFusion}, rank constant 60). */
        FUSION
    }

    public static final String KEY_MODE = "research.candidates.mode";
    public static final String KEY_PER_LIST = "research.candidates.perList";
    public static final String KEY_PER_SOURCE = "research.candidates.perSource";
    public static final String KEY_DEPTH = "research.candidates.depth";

    public static final int DEFAULT_PER_LIST = 20;
    public static final int DEFAULT_PER_SOURCE = 2;
    /**
     * How many hits each ranking is read to. The list rules thin the top out (a source, a text), and every candidate
     * shows its rank in both rankings, so both are read well below the list size; a rank beyond the depth shows as 0.
     */
    public static final int DEFAULT_DEPTH = 1000;
    /** The widest window, as the writer's section search ({@code SectionContextBuilder.MAX_SEARCH_WINDOW}). */
    public static final int MAX_DEPTH = 4096;

    private final Mode mode;
    private final int perList;
    private final int perSource;
    private final int depth;

    /**
     * @param perList   passages per list (the union holds up to twice as many)
     * @param perSource passages of one source in one list
     * @param depth     hits read from each ranking; raised to {@code perList}, cut at {@value #MAX_DEPTH}
     */
    public CandidateSearchSettings(Mode mode, int perList, int perSource, int depth) {
        if (mode == null) {
            throw new IllegalArgumentException("the candidate search needs a mode");
        }
        if (perList < 1 || perSource < 1) {
            throw new IllegalArgumentException("a list needs at least one passage, and one of each source");
        }
        this.mode = mode;
        this.perList = Math.min(perList, MAX_DEPTH);
        this.perSource = perSource;
        this.depth = Math.min(MAX_DEPTH, Math.max(depth, this.perList));
    }

    public static CandidateSearchSettings defaults() {
        return new CandidateSearchSettings(Mode.UNION, DEFAULT_PER_LIST, DEFAULT_PER_SOURCE, DEFAULT_DEPTH);
    }

    /** The persisted settings; an unset or unreadable value keeps its default. */
    public static CandidateSearchSettings load(WorkspaceStateStore store) {
        Mode mode = Mode.UNION;
        String stored = store == null ? "" : store.get(KEY_MODE, Mode.UNION.name());
        if (stored != null && Mode.FUSION.name().equals(stored.trim().toUpperCase(Locale.ROOT))) {
            mode = Mode.FUSION;
        }
        return new CandidateSearchSettings(mode,
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_PER_LIST, DEFAULT_PER_LIST),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_PER_SOURCE, DEFAULT_PER_SOURCE),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_DEPTH, DEFAULT_DEPTH));
    }

    public Mode getMode() {
        return mode;
    }

    public int getPerList() {
        return perList;
    }

    public int getPerSource() {
        return perSource;
    }

    public int getDepth() {
        return depth;
    }

    public CandidateSearchSettings withMode(Mode other) {
        return new CandidateSearchSettings(other, perList, perSource, depth);
    }

    public CandidateSearchSettings withDepth(int other) {
        return new CandidateSearchSettings(mode, perList, perSource, other);
    }

    @Override
    public String toString() {
        return mode + ", " + perList + " per list, " + perSource + " per source, depth " + depth;
    }
}
