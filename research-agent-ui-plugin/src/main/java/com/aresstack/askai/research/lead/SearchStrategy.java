package com.aresstack.askai.research.lead;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How a search goes at a question (component 8 of the build plan of 21.11.2026): the first search has no particular
 * strategy, a change of strategy takes other words, another kind of source, named publishers or another language.
 * The wire name is what the lead's answer, the stored decision and the search record ({@code SearchRecord.strategy})
 * carry.
 */
public enum SearchStrategy {

    /** The usual search, no particular strategy (every first search). */
    STANDARD("standard", "die übliche Suche ohne besondere Strategie", "the usual search, no particular strategy"),
    /** Other terms: technical or everyday language, synonyms, abbreviations, the words of the sources found. */
    ANDERE_WORTWAHL("andere_wortwahl", "andere Begriffe: Fachsprache oder Alltagssprache, Synonyme, Abkürzungen, die "
            + "Wörter der gefundenen Quellen", "other terms: technical or everyday language, synonyms, abbreviations, "
            + "the words of the sources found"),
    /** Another kind of source: authorities, standards, studies, trade press, associations, court rulings. */
    ANDERE_QUELLENART("andere_quellenart", "eine andere Art von Quelle: Behörden, Normen, Studien, Fachpresse, "
            + "Verbände, Urteile", "another kind of source: authorities, standards, studies, trade press, "
            + "associations, court rulings"),
    /** Named publishers or institutions that publish on the question. */
    GEZIELTE_HERAUSGEBER("gezielte_herausgeber", "bestimmte Herausgeber oder Institutionen, die dazu veröffentlichen",
            "named publishers or institutions that publish on it"),
    /** Sources in another language. */
    ANDERE_SPRACHE("andere_sprache", "Quellen in einer anderen Sprache", "sources in another language");

    /** The order in which a guardrail picks a strategy not tried yet. */
    static final List<SearchStrategy> CHANGE_ORDER = Collections.unmodifiableList(Arrays.asList(ANDERE_WORTWAHL,
            ANDERE_QUELLENART, GEZIELTE_HERAUSGEBER, ANDERE_SPRACHE));

    private final String wire;
    private final String german;
    private final String english;

    SearchStrategy(String wire, String german, String english) {
        this.wire = wire;
        this.german = german;
        this.english = english;
    }

    /** The name in answers, decisions and search records. */
    public String wire() {
        return wire;
    }

    /** What the strategy means, in German or English, as the research lead and the search planner show it. */
    public String meaning(boolean inGerman) {
        return inGerman ? german : english;
    }

    /**
     * The strategy of a wire name, an enum name or a search record (case and blanks ignored); an empty name, "keine"
     * and "none" are {@link #STANDARD}; {@code null} for anything else.
     */
    public static SearchStrategy of(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (n.isEmpty() || n.equals("keine") || n.equals("none")) {
            return STANDARD;
        }
        for (SearchStrategy strategy : values()) {
            if (strategy.wire.equals(n) || strategy.name().toLowerCase(Locale.ROOT).equals(n)) {
                return strategy;
            }
        }
        return null;
    }
}
