package com.aresstack.askai.research.runtime.writer;

/**
 * Real text from live run 3 ("Wearables auf Baustellen", 10.11.2026, writer llama3.1:8b): the cited passages and the
 * written sentences as the manuscript stored them ({@code manuscript/sections/<id>/0002.json}, field
 * {@code citedText}). Copied verbatim, soft hyphens removed; nothing is invented.
 */
final class LiveRun3Fixtures {

    private LiveRun3Fixtures() {
    }

    // ------------------------------------------------------------------ boilerplate the writer received as evidence

    /** hexagon.com footer navigation (source-12, cap-12#p101): every sentence of section 1.3 cited it. */
    static final String HEXAGON_NAVIGATION = "Steigern Sie Ihr Geschäft mit Überwachung Bauwerksüberwachung in "
            + "Echtzeit Referenzkundenberichte Fangen Sie an Vertriebsteam kontaktieren Alle Produkte durchsuchen "
            + "Globale Standorte Support erhalten Starten Sie Ihre Karriere Entdecken Sie Hexagon Wer wir sind "
            + "Führungsteam Nachhaltigkeit Investor Relations Erfahren Sie mehr Im Einsatz bei Kunden Nächste "
            + "Veranstaltungen Newsroom Ethik und Compliance Rechtliches Datenschutz Nutzungsbedingungen Impressum "
            + "Problem mit der Website melden Cookie-Einstellungen © 2026 Hexagon AB und/oder seine "
            + "Tochtergesellschaften. Alle Rechte vorbehalten. Deutsch";

    /** dormakaba breadcrumb and page title (source-10, cap-10#p0), cited in section 1.1. */
    static final String DORMAKABA_BREADCRUMB = "Architektur Sicherheit Gesellschaft Technologie Urbanisierung "
            + "dormakaba.com DE Sicherheit Intelligente Sicherheit zuerst (Teil 2): IoT und Wearables auf "
            + "vernetzten Baustellen";

    /** A heading (source-15, cap-15#p33), cited in section 1.4. */
    static final String HEADING_HURDLES = "HERAUSFORDERUNGEN BEI DER IMPLEMENTIERUNG: WO LIEGEN DIE HÜRDEN?";

    /** A heading pair (source-53, cap-58#p49), cited in section 1.4 for the cost analysis. */
    static final String HEADING_COSTS = "Analyse der Implementierungskosten Anfängliche Investitionsanforderungen";

    // ------------------------------------------------------------------ prose passages

    /** source-10, cap-10#p16. */
    static final String DORMAKABA_RFID = "Bauunternehmen können diese Wearables beispielsweise einsetzen, um die "
            + "Standorte von Mitarbeitern und Maschinen in Echtzeit zu überwachen. In Verbindung mit "
            + "RFID-Tagging-Systemen können die Geräte gefährliche Situationen erkennen, z. B. wenn Arbeiter "
            + "unbefugte Bereiche betreten oder schweren Maschinen zu nahe kommen.";

    /** source-14, cap-14#p110. */
    static final String BIBLUS_BENEFITS = "Die Hauptvorteile umfassen: höhere operative Effizienz und Verkürzung "
            + "der Produktionszeiten; Reduzierung kostspieliger Fehler durch präzise Planung; Erhöhung der Sicherheit "
            + "der Arbeiter durch Echtzeitüberwachung; Verbesserung der Transparenz und Zusammenarbeit zwischen den "
            + "Projektbeteiligten; höhere Umweltverträglichkeit durch Optimierung des Verbrauchs und der Ressourcen.";

    /** source-16, cap-16#p165. */
    static final String WLW_ARBSCHG = "Professioneller Hinweis: Baustellenüberwachungssysteme müssen gemäß "
            + "Arbeitsschutzgesetz (ArbSchG) und DGUV Vorschrift 1 so platziert werden, dass sie sowohl "
            + "Gefahrenzonen als auch Zugänge überwachen, ohne Mitarbeiter zu filmen, wenn dies nicht erforderlich "
            + "ist. Bei Montagearbeiten in Höhen sollte die Kameraüberwachung mit Sensoren zur Erkennung von "
            + "Sturzrisiken kombiniert werden.";

    /** source-12, cap-12#p15: the shortest prose passage of the run (12 words, one sentence). */
    static final String HEXAGON_GNSS = "GNSS-Systeme verbinden Hardware und Software, um präzise und genaue "
            + "Positionierungsaufgaben zu ermöglichen.";

    /** source-1, cap-1#p10. */
    static final String LATTICEWORK_SENSORS = "Die visuelle KI von VAISense basiert auf einer Kombination aus "
            + "IoT-Sensoren, Nachtsicht-, Infrarot- und Wärmebildkameras. Diese Systeme können Baustellen rund um die "
            + "Uhr automatisch überwachen und bieten einen umfassenden Überblick über alle Aktivitäten und "
            + "potenziellen Sicherheitsrisiken. Dadurch, dass die Abhängigkeit von Sicherheitsteams vor Ort entfällt, "
            + "können Bauunternehmen einen kontinuierlichen Überwachungsüberblick gewinnen und die Sicherheit auf der "
            + "Baustelle insgesamt verbessern.";

    /** source-38, cap-39#p16. */
    static final String SMARTWATCH_SIMPSON = "Simpson ergänzt, dass die Daten der meisten Fitness Armbänder zudem "
            + "eher unzuverlässig und letzten Endes nur grobe Richtlinien seien. Auf ihrer Basis könnten Arbeitgeber "
            + "keine sinnvollen Entscheidungen treffen. Man könnte den Mitarbeitern Wearables natürlich auf "
            + "freiwilliger Basis anbieten. Das sei jedoch ein sensibles Thema, denn schnell könnte "
            + "Konformitätsdruck entstehen.";

    /** source-53, cap-58#p36. */
    static final String SINOTRACK_INSURANCE = "Die Versicherungsprämien sinken häufig, wenn Unternehmen durch eine "
            + "umfassende Implementierung von Trackingsystemen ein proaktives Risikomanagement nachweisen. Viele "
            + "Versicherungsanbieter gewähren Rabatte für Flotten, die mit Überwachungstechnologie ausgestattet "
            + "sind, da sie den Zusammenhang zwischen Trackingsystemen und einer geringeren Schadenshäufigkeit "
            + "erkennen. Die Möglichkeit, gestohlene Vermögenswerte schnell wiederzufinden und detaillierte "
            + "Vorfallsdokumentationen bereitzustellen, verbessert zudem das Risikoprofil überwachter Flotten.";

    /** source-6, cap-6#p4 (BayLfD). */
    static final String BAYLFD_DSK = "Die Datenschutzkonferenz tritt für einen effektiven Schutz der "
            + "Persönlichkeitsrechte der Nutzerinnen und Nutzer von Wearables und Gesundheits-Apps ein. Einer "
            + "repräsentativen Umfrage zufolge soll bereits knapp ein Drittel der Bevölkerung ab 14 Jahren "
            + "sogenannte Fitness-Tracker zur Aufzeichnung von Gesundheitswerten und persönlichen Verhaltensweisen "
            + "nutzen. Am Körper getragene Kleincomputer (sog. Wearables) und auf mobilen Endgeräten installierte "
            + "Anwendungsprogramme (sog. Gesundheits-Apps) sammeln und dokumentieren auswertungsfähige Körperdaten. "
            + "In der Regel werden diese Daten über das Internet an Hersteller, Internetanbieter und sonstige Dritte "
            + "weitergeleitet.";

    /** source-18, cap-18#p40 (Urmet radio alarms; prose, but off-topic for section 1.4). */
    static final String URMET_ALARMS = "Die Installation und Bedienung dieser Funkalarmanlagen gestalten sich "
            + "einfach und unkompliziert. Dank ihres drahtlosen Designs können sie innerhalb kürzester Zeit "
            + "einsatzbereit gemacht werden, ohne dass aufwändige Verkabelungen erforderlich sind. Ist die Anlage "
            + "einmal vom Elektroinstallateur konfiguriert, erfolgt die Bedienung intuitiv über eine "
            + "benutzerfreundliche Oberfläche, die es dem Baustellenpersonal ermöglicht, das Sicherheitssystem "
            + "effektiv und einfach zu verwalten und zu überwachen.";

    static final String[] BOILERPLATE = {HEXAGON_NAVIGATION, DORMAKABA_BREADCRUMB, HEADING_HURDLES, HEADING_COSTS};

    static final String[] PROSE = {DORMAKABA_RFID, BIBLUS_BENEFITS, WLW_ARBSCHG, HEXAGON_GNSS, LATTICEWORK_SENSORS,
            SMARTWATCH_SIMPSON, SINOTRACK_INSURANCE, BAYLFD_DSK, URMET_ALARMS};
}
