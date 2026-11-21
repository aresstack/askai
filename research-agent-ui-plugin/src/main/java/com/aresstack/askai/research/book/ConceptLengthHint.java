package com.aresstack.askai.research.book;

import com.aresstack.askai.research.domain.scope.DeliverableLengthText;
import com.aresstack.askai.research.domain.scope.ResearchDeliverable;

/**
 * Repair plan step 1: Phase 1 says visibly which book length the later phases will get, and says it when there is
 * none. Before, a concept frozen without a length left Phase 3 silently without a word budget. Stateless.
 */
public final class ConceptLengthHint {

    private ConceptLengthHint() {
    }

    /**
     * @param stated      the deliverable of the scope
     * @param mission     the mission text
     * @param fromMission whether the mission's length fills an empty deliverable
     */
    public static String of(ResearchDeliverable stated, String mission, boolean fromMission) {
        String described = DeliverableLengthText.describe(stated);
        if (!described.isEmpty()) {
            return "Book length: " + described + ".";
        }
        if (!fromMission) {
            return MISSING;
        }
        String read = DeliverableLengthText.describe(DeliverableLengthText.read(mission));
        java.util.List<String> named = DeliverableLengthText.candidates(mission);
        if (!read.isEmpty()) {
            if (named.size() > 1) {
                return "Book length: " + read + ", read from the mission (it also names " + others(named, read)
                        + ").";
            }
            return "Book length: " + read + ", read from the mission.";
        }
        if (named.size() > 1) {
            return "No book length: the mission names " + String.join(", ", named) + "; write \"Umfang\" before the "
                    + "one the book has, otherwise the outline has no word budget.";
        }
        return MISSING;
    }

    private static final String MISSING = "No book length: name it in the mission (for example \"20 bis 30 "
            + "Seiten\"), otherwise the outline has no word budget.";

    private static String others(java.util.List<String> named, String taken) {
        java.util.List<String> rest = new java.util.ArrayList<>(named);
        rest.remove(taken);
        return String.join(", ", rest);
    }

    /** {@code true} when the hint says that no length is known. */
    public static boolean isMissing(String hint) {
        return hint != null && hint.startsWith("No book length");
    }
}
