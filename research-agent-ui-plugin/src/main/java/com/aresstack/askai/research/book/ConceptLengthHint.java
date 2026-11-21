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
        String read = fromMission ? DeliverableLengthText.describe(DeliverableLengthText.read(mission)) : "";
        if (!read.isEmpty()) {
            return "Book length: " + read + ", read from the mission.";
        }
        return "No book length: name it in the mission (for example \"20 bis 30 Seiten\"), otherwise the outline "
                + "has no word budget.";
    }

    /** {@code true} when the hint says that no length is known. */
    public static boolean isMissing(String hint) {
        return hint != null && hint.startsWith("No book length");
    }
}
