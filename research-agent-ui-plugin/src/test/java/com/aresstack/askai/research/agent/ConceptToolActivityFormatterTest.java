package com.aresstack.askai.research.agent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #20 pins for the concept tool bubble: readable, localized status lines instead of raw log
 * lines; success only on a host receipt; duplicates neither repeat nor count twice; unknown and
 * internal lines (NONE, nudges, repairs, revisions) never surface.
 */
public class ConceptToolActivityFormatterTest {

    private static final String ADD_CARDS = "concept round 1: add_cards parent=[\"hasenhaltung\"] "
            + "names=[\"Rasse und Genetik\",\"Ernährung\",\"Unterbringung und Gehege\","
            + "\"Gesundheit und Tierarzt\",\"Sozialverhalten\"]";

    private static ConceptToolActivityFormatter fed(String... lines) {
        ConceptToolActivityFormatter formatter = new ConceptToolActivityFormatter();
        for (String line : lines) {
            formatter.accept(line);
        }
        return formatter;
    }

    private static String[] hasenhaltungTurn() {
        return new String[] {
                ADD_CARDS,
                "concept_add_cards -> APPLIED revision=1",
                "concept_add_cards -> ADDED: Rasse und Genetik",
                "concept_add_cards -> ADDED: Ernährung",
                "concept_add_cards -> ADDED: Unterbringung und Gehege",
                "concept_add_cards -> ADDED: Gesundheit und Tierarzt",
                "concept_add_cards -> ADDED: Sozialverhalten",
                "concept round 1 -> APPLIED revision=1",
                "concept action: NONE",
                "concept search tags missing — offer nudge turn",
                "concept round 2: offer suggestions=4",
                "offer_searches -> OFFERED 4 tags",
                "concept round 2 -> OFFERED",
                "concept action: NONE",
        };
    }

    @Test
    public void addCardsIsInProgressUntilTheHostConfirms() {
        ConceptToolActivityFormatter formatter = fed(ADD_CARDS);
        assertEquals("• Themenbereiche werden vorbereitet …", formatter.body(true));
        assertEquals("• Preparing topic areas …", formatter.body(false));
        assertEquals("Keine Änderung am Konzept", formatter.summary(true));
    }

    @Test
    public void confirmedTurnReadsAsResultsWithoutTechnicalTokens() {
        ConceptToolActivityFormatter formatter = fed(hasenhaltungTurn());
        String body = formatter.body(true);
        assertEquals("• Konzept erweitert – 5 Themenbereiche hinzugefügt: Rasse und Genetik, "
                + "Ernährung, Unterbringung und Gehege …\n"
                + "• 4 Suchvorschläge vorbereitet", body);
        for (String raw : new String[] {"add_cards", "NONE", "revision", "parent", "round", "nudge", "["}) {
            assertFalse("raw token leaked: " + raw, body.contains(raw));
        }
        assertEquals("5 Themenbereiche hinzugefügt · 4 Suchvorschläge", formatter.summary(true));
        assertEquals("5 topic areas added · 4 search suggestions", formatter.summary(false));
        assertEquals("• Concept extended – 5 topic areas added: Rasse und Genetik, Ernährung, "
                + "Unterbringung und Gehege …\n• 4 search suggestions prepared", formatter.body(false));
    }

    @Test
    public void repeatedLinesNeitherRepeatNorCountTwice() {
        ConceptToolActivityFormatter once = fed(hasenhaltungTurn());
        ConceptToolActivityFormatter twice = new ConceptToolActivityFormatter();
        for (String line : hasenhaltungTurn()) {
            twice.accept(line);
            assertFalse("a repeated line must not change the view", twice.accept(line));
        }
        for (String line : hasenhaltungTurn()) {
            twice.accept(line);
        }
        assertEquals(once.body(true), twice.body(true));
        assertEquals(once.summary(true), twice.summary(true));
    }

    @Test
    public void hostReceiptBeforeTheRuntimeLineGivesTheSameResult() {
        ConceptToolActivityFormatter formatter = fed(
                "concept_add_cards -> APPLIED revision=1",
                "concept_add_cards -> ADDED: Ernährung",
                "concept round 1: add_cards parent=[] names=[\"Ernährung\"]",
                "concept round 1 -> APPLIED revision=1");
        assertEquals("• Konzept erweitert – 1 Themenbereich hinzugefügt: Ernährung", formatter.body(true));
    }

    @Test
    public void rejectedAndRefusedRoundsNeverReadAsStored() {
        ConceptToolActivityFormatter rejected = fed(ADD_CARDS,
                "concept round 1 -> REJECTED Error: PARENT_NOT_FOUND");
        assertEquals("• Themenbereiche wurden nicht übernommen", rejected.body(true));
        assertEquals("Keine Änderung übernommen", rejected.summary(true));
        assertEquals("No change applied", rejected.summary(false));

        ConceptToolActivityFormatter refused = fed(ADD_CARDS,
                "concept round 1 -> REFUSED (placeholder copied from the instructions)");
        assertFalse("an internal repair round stays invisible", refused.hasVisibleLines());
        assertEquals("Keine Änderung am Konzept", refused.summary(true));
    }

    @Test
    public void relayedOfferOrExclusionWithoutHostReceiptIsNoSuccess() {
        ConceptToolActivityFormatter offer = fed("concept round 2: offer suggestions=4",
                "concept round 2 -> OFFERED");
        assertEquals("• Suchvorschläge werden vorbereitet …", offer.body(true));
        assertEquals("Keine Änderung am Konzept", offer.summary(true));

        ConceptToolActivityFormatter exclude = fed("concept round 1: exclude topic=\"Zucht\"",
                "exclude_topic -> REJECTED: the scope is locked",
                "concept round 1 -> EXCLUDED (terminal)");
        assertEquals("• Ausschluss wurde nicht übernommen", exclude.body(true));
        assertEquals("Keine Änderung übernommen", exclude.summary(true));
    }

    @Test
    public void confirmedExclusionNamesTheUsersTopic() {
        ConceptToolActivityFormatter formatter = fed("concept round 1: exclude topic=\"Zucht\"");
        assertEquals("• Thema „Zucht“ wird ausgeschlossen …", formatter.body(true));
        formatter.accept("exclude_topic -> EXCLUDED id=zucht label=\"Zucht\"");
        formatter.accept("concept round 1 -> EXCLUDED (terminal)");
        assertEquals("• Thema „Zucht“ ausgeschlossen", formatter.body(true));
        assertEquals("• Topic “Zucht” excluded", formatter.body(false));
        assertEquals("„Zucht“ ausgeschlossen", formatter.summary(true));
    }

    @Test
    public void noChangeAndScopeSuppressionAreReportedHonestly() {
        ConceptToolActivityFormatter formatter = fed(ADD_CARDS,
                "concept_add_cards -> NO_CHANGE revision=3",
                "concept_add_cards -> ALREADY_PRESENT: Ernährung",
                "concept_add_cards -> SUPPRESSED_BY_SCOPE: Zucht",
                "concept round 1 -> NO_CHANGE");
        assertEquals("• Keine neuen Themenbereiche (1 bereits vorhanden) (1 außerhalb des Themenraums)",
                formatter.body(true));
        assertEquals("Keine Änderung am Konzept", formatter.summary(true));
    }

    @Test
    public void readAndProbeAreShortWorkingSteps() {
        ConceptToolActivityFormatter formatter = fed(
                "concept round 1: read path=[]", "concept round 1 -> RESULT",
                "concept round 2: probe terms=[\"Zucht\"]", "concept round 2 -> PROBED",
                "concept post-probe sensor lock armed (observation-only for the rest of the turn)");
        assertEquals("• Konzept gelesen\n• Themenraum geprüft", formatter.body(true));
        assertEquals("Themenraum geprüft", formatter.summary(true));
    }

    @Test
    public void unknownAndInternalLinesAreIgnored() {
        ConceptToolActivityFormatter formatter = new ConceptToolActivityFormatter();
        for (String line : new String[] {null, "", "garbage", "concept action: NONE",
                "concept identity -> NEW EPOCH 2", "concept round x: add_cards",
                "concept round 4: invalid conceptAction (missing names)",
                "concept round 9 -> APPLIED revision=2", // no announced action for round 9
                "concept tool budget exhausted — dropping the further conceptAction",
                "scope update -> APPLIED: mission recorded", "concept_add_cards -> "}) {
            assertFalse(String.valueOf(line), formatter.accept(line));
        }
        assertFalse(formatter.hasVisibleLines());
        assertEquals("", formatter.body(true));
    }

    @Test
    public void viewIsCappedAndLongNamesAreShortened() {
        ConceptToolActivityFormatter formatter = fed(
                "concept round 1: read path=[]", "concept round 1 -> RESULT",
                "concept round 2: probe terms=[]", "concept round 2 -> PROBED",
                "concept round 3: rename path=[\"a\"] name=\"b\"", "concept round 3 -> APPLIED revision=2",
                "concept round 4: move source=[\"a\"] parent=[\"c\"]",
                "concept_move_leaf -> APPLIED revision=3", "concept_move_leaf -> MOVED: b",
                "concept round 4 -> APPLIED revision=3",
                "concept round 5: add_cards parent=[] names=[]",
                "concept_add_cards -> ADDED: Eine sehr lange Bezeichnung für einen Themenbereich");
        String body = formatter.body(true);
        assertEquals(ConceptToolActivityFormatter.MAX_VISIBLE_LINES, body.split("\n").length);
        assertFalse("the oldest kind scrolls out", body.contains("Konzept gelesen"));
        assertTrue(body.endsWith("hinzugefügt: Eine sehr lange Bezeichnung für…"));
        assertEquals("Themenraum geprüft · Konzept angepasst · „b“ verschoben · 1 Themenbereich hinzugefügt",
                formatter.summary(true));
    }

    @Test
    public void equalNamesUnderDifferentParentsCountApart() {
        String[] lines = {
                "concept round 1: add_cards parent=[\"A\"] names=[\"Overview\"]",
                "concept_add_cards -> APPLIED revision=1", "concept_add_cards -> ADDED: Overview",
                "concept round 1 -> APPLIED revision=1",
                "concept round 2: add_cards parent=[\"B\"] names=[\"Overview\"]",
                "concept_add_cards -> APPLIED revision=2", "concept_add_cards -> ADDED: Overview",
                "concept round 2 -> APPLIED revision=2"};
        ConceptToolActivityFormatter formatter = fed(lines);
        assertEquals("• Concept extended – 2 topic areas added: Overview", formatter.body(false));
        for (String line : lines) {
            formatter.accept(line); // a replay stays idempotent
        }
        assertEquals("2 topic areas added", formatter.summary(false));
    }

    @Test
    public void aLaterFailureOfTheSameKindStaysVisible() {
        ConceptToolActivityFormatter formatter = fed(
                "concept round 1: offer suggestions=4", "offer_searches -> OFFERED 4 tags",
                "concept round 1 -> OFFERED",
                "concept round 2: offer suggestions=2");
        assertEquals("• 4 search suggestions prepared (another step running …)", formatter.body(false));
        formatter.accept("concept round 2 -> REJECTED Error: empty query");
        assertEquals("• 4 search suggestions prepared (a further step was not applied)", formatter.body(false));
        assertEquals("4 search suggestions", formatter.summary(false));
    }

    @Test
    public void endpointLossEndsEveryPendingStep() {
        ConceptToolActivityFormatter formatter = fed(ADD_CARDS,
                "concept concept endpoint unavailable — keeping the last answer (timeout)");
        assertEquals("• Konzept-Werkzeug nicht erreichbar", formatter.body(true));
        assertEquals("Keine Änderung übernommen", formatter.summary(true));
    }

    @Test
    public void resetStartsTheNextTurnEmpty() {
        ConceptToolActivityFormatter formatter = fed(hasenhaltungTurn());
        formatter.reset();
        assertFalse(formatter.hasVisibleLines());
        assertEquals("Keine Änderung am Konzept", formatter.summary(true));
    }
}
