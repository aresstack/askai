package com.aresstack.askai.research.book;

import com.aresstack.askai.research.assembly.BookAssemblyInputs;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * One book title for every phase after Concept: the concept's own title, else the title the mission quotes. Live run
 * 4 (2026-11-12) had an approved concept without a title, so the planner, the outline and the writer saw none and
 * the assembled book was headed with the whole mission.
 */
public class FrozenConceptBookTitleTest {

    private static final String RUN_4_MISSION = "Erstelle das Konzept für ein kompaktes, vollständig belegtes "
            + "deutsches Praxisbuch „Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz“ für "
            + "Sicherheitsfachkräfte, Umfang 20–30 Seiten.";
    private static final String UNTITLED_CONCEPT =
            "{\"title\":\"\",\"subtitle\":\"\",\"concept\":[{\"Wearables auf Baustellen\":[]}]}";

    private static FrozenConcept frozen(String mission, String deliverable, String document) {
        return FrozenConcept.of(new ConceptPin("t-concept", 1, true, "hash", 7L, 1000L, mission, deliverable, "de",
                Collections.<String>emptyList()), document);
    }

    @Test
    public void theConceptsOwnTitleIsTheBookTitle() {
        FrozenConcept concept = frozen(RUN_4_MISSION, "", "{\"title\":\"  Sicher bauen mit Wearables \","
                + "\"subtitle\":\"\",\"concept\":[]}");

        assertEquals("Sicher bauen mit Wearables", concept.getBookTitle());
    }

    @Test
    public void withoutAConceptTitleTheMissionsQuotedTitleIsTheBookTitle() {
        FrozenConcept concept = frozen(RUN_4_MISSION, "", UNTITLED_CONCEPT);

        assertEquals("Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz", concept.getBookTitle());
    }

    @Test
    public void withoutAnyTitleThereIsNoneRatherThanAnInventedOne() {
        FrozenConcept concept = frozen("Write a practical book about wearables on sites.", "", UNTITLED_CONCEPT);

        assertEquals("", concept.getBookTitle());
    }

    @Test
    public void anUnreadableConceptStillYieldsTheMissionsTitle() {
        FrozenConcept concept = frozen(RUN_4_MISSION, "", "not json");

        assertEquals("Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz", concept.getBookTitle());
    }

    @Test
    public void theAssembledBookIsHeadedWithTheBookTitleNeverTheLength() {
        BookAssemblyInputs.Concept concept = BookAssemblyInputs.Concept.of(
                frozen(RUN_4_MISSION, "20-30 pages", UNTITLED_CONCEPT));

        assertEquals("Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz", concept.getTitle());
    }

    @Test
    public void onlyABookWithoutAnyTitleIsHeadedWithItsMission() {
        String mission = "Write a practical book about wearables on sites.";

        assertEquals(mission, BookAssemblyInputs.Concept.of(frozen(mission, "", UNTITLED_CONCEPT)).getTitle());
    }
}
