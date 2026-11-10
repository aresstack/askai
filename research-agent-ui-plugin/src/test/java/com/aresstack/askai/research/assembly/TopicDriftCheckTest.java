package com.aresstack.askai.research.assembly;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Topic drift against live run 3: mission, approved outline and section 1.4 as stored (verbatim). */
public class TopicDriftCheckTest {

    static final String MISSION = "Erstelle das Konzept für ein kompaktes, vollständig belegtes deutsches Praxisbuch "
            + "„Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz“ für Sicherheitsfachkräfte, Umfang "
            + "20–30 Seiten. Vier Hauptthemen: (1) Technologien und Sensortypen, (2) reale Arbeitsschutz-Gefährdungen "
            + "und praktische Wirksamkeit, (3) Recht, Datenschutz und Betriebsrat in Deutschland, (4) Implementierung "
            + "mit Kosten, Grenzen und Praxisbeispielen. Bevorzuge frei zugängliche Primärquellen (DGUV, BAuA, "
            + "EU-OSHA, Gesetze, wissenschaftliche Studien); keine Buchhändler, Shopkataloge oder Verkaufsseiten als "
            + "Nachweise.";

    private static OutlineSection section(String id, int order, String title, String purpose, String... points) {
        return new OutlineSection(id, "c1", order, title, new OutlineBrief(purpose, Arrays.asList(points), 1100),
                OutlineProvenance.NONE, false, "");
    }

    static Outline outline() {
        return new Outline(Collections.singletonList(new OutlineChapter("c1", 1, "Wearables auf Baustellen",
                OutlineBrief.EMPTY, OutlineProvenance.NONE, false, "", Arrays.asList(
                section("s1", 1, "Technologien und Sensortypen", "Dieser Abschnitt führt die grundlegenden "
                                + "Technologien und Sensortypen ein, die in Wearables auf Baustellen zum Einsatz kommen.",
                        "Smartwatches und Sensorarmbänder sind im Betrieb relevant.",
                        "Datenübertragung von Sensoren zu Smartphone/Server.",
                        "Relevante Sensorarten für Baustellenumgebungen (z.B. Bewegung, Temperatur, falls relevant).",
                        "Schnittstelle zwischen Hardware und Datenmanagement."),
                section("s2", 2, "Arbeitsschutz-Gefährdungen und Wirksamkeit", "Dieser Abschnitt analysiert die "
                                + "tatsächlichen Arbeitsschutz-Gefährdungen und die praktische Wirksamkeit von Wearables "
                                + "im Baustellenkontext.",
                        "Reduktion gesundheitsgefährdender Faktoren durch Wearables.",
                        "Abgleich von Sensorinformationen mit Arbeitsschutzstandards (DGUV).",
                        "Bewertung der tatsächlichen Wirksamkeit im Vergleich zu traditionellen Methoden.",
                        "Identifikation spezifischer Gefährdungen (z.B. Absturz, Ermüdung)."),
                section("s3", 3, "Recht, Datenschutz und Betriebsrat in Deutschland", "Dieser Abschnitt behandelt die "
                                + "rechtlichen Rahmenbedingungen, insbesondere Datenschutz, Arbeitsschutzrecht und die "
                                + "Rolle des Betriebsrats in Deutschland.",
                        "Rechtliche Rahmenbedingungen für die Erfassung von Mitarbeiterdaten.",
                        "Datenschutzkonformität (DSGVO) bei der Nutzung von Wearable-Daten.",
                        "Betriebsrat und Mitbestimmungsrechte bei der Einführung von Überwachungssystemen.",
                        "Rechtliche Anforderungen an die Datensicherheit und -verarbeitung."),
                section("s4", 4, "Implementierung mit Kosten und Grenzen", "Dieser Abschnitt fokussiert auf die "
                                + "praktische Implementierung von Wearables, einschließlich der Kosten, Grenzen und "
                                + "konkreter Praxisbeispiele.",
                        "Kostenanalyse: Hardware, Software, Installation.",
                        "Technische Grenzen: Zuverlässigkeit der Daten (z.B. Fitness-Tracker vs. Sicherheitsdaten).",
                        "Praxisbeispiele: Konkrete Anwendungsfälle auf Baustellen.",
                        "Risikobewertung und Limitationen der Technologie im realen Einsatz.")))));
    }

    /** Section 1.4 of live run 3, sentence by sentence, markers removed. */
    static final List<String> SECTION_1_4 = Arrays.asList(
            "Die Implementierung von Wearables auf Baustellen wirft verschiedene Fragen hinsichtlich Kosten, "
                    + "technischer Grenzen und geeigneter Strategien auf.",
            "Die Kostenanalyse umfasst die Anschaffung von Hardware, Software und die Installation der Systeme.",
            "Die Installation und Bedienung von Funkalarmanlagen, die eine drahtlose Installation ermöglichen, sind "
                    + "einfach und unkompliziert, da sie keine festen Verkabelungen erfordern.",
            "Die Konfiguration der Funkalarmanlagen erfolgt einmalig durch den Elektroinstallateur, und die Bedienung "
                    + "ist über eine benutzerfreundliche Oberfläche intuitiv, was dem Baustellenpersonal eine "
                    + "effektive Verwaltung und Überwachung des Sicherheitssystems ermöglicht.",
            "Technische Grenzen und die Zuverlässigkeit der erfassten Daten sind ein wichtiger Aspekt bei der "
                    + "Implementierung.",
            "Simpson merkt an, dass die Daten der meisten Fitness Armbänder eher unzuverlässig seien und letztendlich "
                    + "nur grobe Richtlinien liefern, sodass Arbeitgeber keine sinnvollen Entscheidungen treffen "
                    + "könnten.",
            "Durch die Implementierung von Trackingsystemen können Unternehmen proaktives Risikomanagement "
                    + "nachweisen, was zu sinkenden Versicherungsprämien führen kann, da Versicherungsanbieter Rabatte "
                    + "für Flotten gewähren, die mit Überwachungstechnologie ausgestattet sind.",
            "Die Möglichkeit, gestohlene Vermögenswerte schnell wiederzufinden und detaillierte "
                    + "Vorfallsdokumentationen bereitzustellen, verbessert das Risikoprofil überwachter Flotten.");

    @Test
    public void theRadioAlarmParagraphOfSection14IsAForeignSubject() {
        TopicDriftCheck check = new TopicDriftCheck(TopicDriftCheck.Criteria.defaults(), MISSION, outline(),
                Collections.<String>emptyList());
        assertEquals(Arrays.asList("Funkalarmanlagen (2 sentences)"), check.foreignSubjects(SECTION_1_4));
    }

    @Test
    public void aSubjectOfTheOutlineIsNeverForeign() {
        TopicDriftCheck check = new TopicDriftCheck(TopicDriftCheck.Criteria.defaults(), MISSION, outline(),
                Collections.<String>emptyList());
        assertEquals(Collections.<String>emptyList(), check.foreignSubjects(Arrays.asList(
                "Die Datenschutzkonformität ist zu prüfen.", "Ohne Datenschutzkonformität geht es nicht.")));
    }

    @Test
    public void theThresholdsAreSettings() {
        TopicDriftCheck strict = new TopicDriftCheck(new TopicDriftCheck.Criteria(10, 3, 5, true), MISSION,
                outline(), Collections.<String>emptyList());
        assertEquals("three sentences required", Collections.<String>emptyList(),
                strict.foreignSubjects(SECTION_1_4));
        TopicDriftCheck glossary = new TopicDriftCheck(TopicDriftCheck.Criteria.defaults(), MISSION, outline(),
                Arrays.asList("Funkalarmanlage"));
        assertEquals("a glossary term belongs to the frame", Collections.<String>emptyList(),
                glossary.foreignSubjects(SECTION_1_4));
    }
}
