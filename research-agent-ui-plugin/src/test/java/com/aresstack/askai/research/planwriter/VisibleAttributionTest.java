package com.aresstack.askai.research.planwriter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The rule that a sentence naming the speaker of a claim does not state it as a fact; self-written sentences. */
public class VisibleAttributionTest {

    private static final List<String> NONE = Collections.emptyList();
    private static final String MAKER = "HERSTELLER_HANDEL";
    private static final String ASSOCIATION = "VERBAND";

    private static boolean named(String sentence, int number) {
        return VisibleAttribution.attributes(sentence, number, NONE);
    }

    private static boolean named(String sentence, int number, String sourceKind, String... names) {
        return VisibleAttribution.attributes(sentence, number, Arrays.asList(names), sourceKind);
    }

    private static boolean named(String sentence, int number, String sourceKind, Collection<String> names) {
        return VisibleAttribution.attributes(sentence, number, names, sourceKind);
    }

    private static String withSpeaker(String sentence, int number, String sourceKind, String phrase) {
        return VisibleAttribution.withSpeaker(sentence, number, NONE, sourceKind, phrase);
    }

    @Test
    public void everyFrameThatHandsAClaimToItsSpeakerCounts() {
        assertTrue(named("Nach Angaben des Herstellers erkennt die Kamera Kennzeichen auch bei Nacht [2].", 2));
        assertTrue(named("Laut einem Anbieter senkt das System den Verbrauch um zehn Prozent [1].", 1));
        assertTrue(named("Laut einem großen deutschen Anbieter senkt das System den Verbrauch [1].", 1));
        assertTrue(named("Dem Verband zufolge fehlen in vielen Städten sichere Radwege [3].", 3));
        assertTrue(named("Die Ampel schaltet nach 40 Sekunden um, so der Hersteller [1].", 1));
        assertTrue(named("Ein großer Anbieter beschreibt, dass die Sensoren auch Regen erkennen [2].", 2));
        assertTrue(named("Wie der Verband betont, sinken die Unfallzahlen seit Jahren [4].", 4));
        assertTrue(named("Nach Herstellerangaben hält der Akku acht Stunden [1].", 1));
        assertTrue(named("Der Hersteller gibt eine Reichweite von 300 Kilometern an [2].", 2));
        assertTrue("a compound holds the speaker", named("Laut Softwareanbietern lässt sich die Anlage per App "
                + "steuern [1].", 1));
        assertTrue(named("Laut Branchenportalen steigt die Nachfrage nach Kameras [1].", 1));
        assertTrue(named("Betont wird vom Verband nichts, doch empfiehlt ein Verband mehr Zonen [1].", 1));
        assertTrue("its own account, and the speaker is in the sentence", named("Nach eigenen Angaben liefert der "
                + "Hersteller die Anlage in zwei Wochen [1].", 1));
        assertTrue(named("According to the manufacturer, the camera reads plates at night [1].", 1));
        assertTrue(named("The camera reads every plate, as a vendor states [1].", 1));

        assertTrue(named("Laut einem Sprecher des Herstellers erkennt die Kamera jedes Kennzeichen [1].", 1, MAKER));
        assertTrue(named("Laut der Website des Herstellers erkennt die Kamera jedes Kennzeichen [1].", 1, MAKER));
        assertTrue(named("Laut dem Datenblatt des Herstellers erreicht die Kamera 120 Bilder pro Sekunde [1].", 1,
                MAKER));
        assertTrue(named("Nach Angaben eines Sprechers des Verbands sinken die Unfallzahlen [1].", 1, ASSOCIATION));
        assertTrue(named("Laut einer Studie des Verbands sinken die Unfallzahlen [1].", 1, ASSOCIATION));
        assertTrue(named("Aus Sicht des Verbands fehlen sichere Radwege [1].", 1, ASSOCIATION));
        assertTrue(named("Die Kamera erkennt jedes Kennzeichen, heißt es beim Hersteller [1].", 1, MAKER));
        assertTrue(named("Die Kamera erkennt jedes Kennzeichen, so ein Sprecher des Herstellers [1].", 1, MAKER));
        assertTrue(named("In seinem Jahresbericht betont der Verband, dass die Unfallzahlen sinken [1].", 1,
                ASSOCIATION));
        assertTrue(named("Der Verband empfiehlt mehr Tempo-30-Zonen in Wohngebieten [1].", 1, ASSOCIATION));
        assertTrue(named("Der Verband weist darauf hin, dass viele Radwege zu schmal sind [1].", 1, ASSOCIATION));
        assertTrue(named("Dabei gibt der Hersteller an, dass die Kamera jedes Kennzeichen erkennt [1].", 1, MAKER));
        assertTrue(named("Der Hersteller liefert nach eigenen Angaben die Anlage in zwei Wochen [1].", 1, MAKER));
        assertTrue(named("Die Kamera ist laut Hersteller wetterfest [1].", 1, MAKER));
        assertTrue(named("The vendor says that the camera reads every plate [1].", 1, MAKER));
        assertTrue(named("According to the company's website, the camera reads every plate [1].", 1, MAKER));
        assertTrue(named("The camera reads every plate, says the company [1].", 1, MAKER));
    }

    @Test
    public void aSpeakerOutsideAFrameOrAnotherKindOfSourceNamesNoSpeakerOfTheClaim() {
        assertFalse(named("Die Kamera erkennt jedes Kennzeichen zuverlässig [2].", 2));
        assertFalse("a study speaks, the companies only save", named("Laut einer Studie sparen "
                + "Transportunternehmen Kraftstoff [1].", 1));
        assertFalse(named("Der Hersteller hat seinen Sitz in Hamburg, und die Kamera erkennt Fahrzeuge [1].", 1));
        assertFalse("the rule knows makers, sellers and associations only", named("Laut Polizei sind die Unfälle "
                + "gesunken [1].", 1));
        assertFalse(named("Laut Best-Practice-Beispielen sinken die Kosten deutlich [1].", 1));
        assertFalse(named("Laut internationalen Marktanalysen wächst der Markt [1].", 1));
        assertFalse("its own account without a speaker", named("Nach eigenen Angaben sinken die Kosten [1].", 1));
        assertFalse(named("Nach eigenen Angaben will die Stadt mehr gegen Raser tun, denn die Kameras erkennen jedes "
                + "Kennzeichen [1].", 1, MAKER));
        assertFalse(named("", 1));
        assertFalse(named(null, 1));
    }

    /** The review of 22.11.2026: a maker, a seller or an association as object, owner or customer says nothing. */
    @Test
    public void aSpeakerWordThatIsNoSpeakerOfTheClaimClearsNothing() {
        assertFalse("an object", named("Die Software empfiehlt dem Händler automatisch den besten Preis [1].", 1,
                MAKER));
        assertFalse(named("Die Plattform empfiehlt den Händlern passende Produkte [1].", 1, MAKER));
        assertFalse(named("Das System empfiehlt dem Unternehmen, Routen zu optimieren [1].", 1, MAKER));
        assertFalse(named("Die Kamera warnt den Händler, sobald ein Fahrzeug die Schranke passiert [1].", 1, MAKER));
        assertFalse(named("Die Plattform präsentiert den Unternehmen passende Bewerber [1].", 1, MAKER));
        assertFalse(named("Das System warnt den Hersteller frühzeitig vor Ausfällen [1].", 1, MAKER));
        assertFalse("customers who like it", named("Besonders schätzen die Unternehmen die einfache Bedienung der "
                + "Kamera [1].", 1, MAKER));
        assertFalse(named("Viele Händler schätzen die Kamera, weil sie jedes Kennzeichen erkennt [1].", 1, MAKER));
        assertFalse(named("Die Kamera, die viele Unternehmen schätzen, erkennt jedes Kennzeichen [1].", 1, MAKER));
        assertFalse("an owner", named("Die Kamera des Herstellers warnt den Fahrer bei Müdigkeit [1].", 1, MAKER));
        assertFalse(named("Die App des Anbieters rät dem Fahrer nach zwei Stunden zu einer Pause [1].", 1, MAKER));
        assertFalse(named("Die Software des Unternehmens schätzt den Verkehrsfluss in Echtzeit [1].", 1, MAKER));
        assertFalse(named("In monatlichen Raten der Anbieter kostet die Kamera 30 Euro [1].", 1, MAKER));
        assertFalse("a maker that shows, not says", named("Der Hersteller präsentiert eine Kamera, die jedes "
                + "Kennzeichen auch bei Nacht erkennt [1].", 1, MAKER));
        assertFalse(named("Für Logistikunternehmen empfiehlt sich ein System, das den Verbrauch senkt [1].", 1,
                MAKER));
        assertFalse("the adjective laut", named("Die Wärmepumpe ist kaum laut und spart Unternehmen viel Strom [1].",
                1, MAKER));
        assertFalse("a contrast turns the speaker against the claim", named("Anders als die Hersteller behaupten, "
                + "erkennt die Kamera nur jedes zweite Kennzeichen [1].", 1));
        assertFalse("a noun is no verb of saying", named("Entgegen den Versprechen der Hersteller erkennt die Kamera "
                + "nur jedes zweite Kennzeichen [1].", 1));
        assertFalse(named("The system warns the retailer when stock runs low [1].", 1, MAKER));
        assertFalse(named("The tool recommends the best supplier for each order [1].", 1, MAKER));
        assertFalse(named("The system estimates the company's savings at 20 percent [1].", 1, MAKER));
        assertFalse(named("The camera reads every plate at night, so the company needs no extra lighting [1].", 1,
                MAKER));
        assertFalse(named("The sensor needs no maintenance, so its developers offer a ten-year warranty [1].", 1,
                MAKER));
        assertFalse("a speaker word inside another word", named("According to lawmakers, the camera halves the "
                + "number of accidents [1].", 1, MAKER));
        assertFalse(named("According to the accompanying study, the camera halves the number of accidents [1].", 1,
                MAKER));
        assertFalse(named("According to experts many companies save fuel [1].", 1, MAKER));
        assertFalse(named("Laut der Energieberatung der Stadt spart eine Wärmepumpe viel Heizkosten [1].", 1, MAKER));
    }

    @Test
    public void aSpeakerOfAnotherClauseNamesNoSpeakerOfTheClaim() {
        assertFalse(named("Der Hersteller nennt keinen Preis, doch die Kamera erkennt jedes Kennzeichen [1].", 1,
                MAKER));
        assertFalse(named("Kein Hersteller gibt an, wie oft die Kamera ausfällt; sie erkennt jedes Kennzeichen [1].",
                1, MAKER));
        assertFalse(named("Der Hersteller präsentiert die Kamera auf einer Messe; sie erkennt jedes Kennzeichen [1].",
                1, MAKER));
        assertFalse(named("Während der Verband strengere Regeln empfiehlt, erkennt die Kamera bereits jedes "
                + "Fahrzeug [1].", 1, ASSOCIATION));
        assertFalse(named("The manufacturer rejected all warranty claims, and the camera reads every plate [1].", 1,
                MAKER));
        assertFalse("after the last marker only a tag counts", named("Die Kamera erkennt jedes Kennzeichen [1], und "
                + "der Hersteller nennt einen Preis von 5.000 Euro.", 1, MAKER));
        String tagged = "Die Kamera erkennt jedes Kennzeichen [1], so der Hersteller, und sie misst die "
                + "Geschwindigkeit genau [2].";
        assertTrue("a tag belongs to the marker before it", named(tagged, 1, MAKER));
        assertFalse(named(tagged, 2, MAKER));
        assertFalse("a semicolon ends a frame's reach", named("Laut einem Verband fehlen in vielen Städten Radwege "
                + "[1]; die Kamera erkennt jedes Fahrrad [2].", 2, ASSOCIATION));
        assertFalse("a frame before a semicolon speaks for its own clause only", named("Der Verband betont: Radwege "
                + "fehlen; die Kamera erkennt jedes Fahrrad [1].", 1, ASSOCIATION));
        assertFalse(named("Laut dem Hersteller ist die Kamera wetterfest [1], tatsächlich erkennt sie aber nur jedes "
                + "zweite Kennzeichen [2].", 2, MAKER));
    }

    @Test
    public void theSpeakerMustBeOfTheSourcesKind() {
        String association = "Laut einem Verband erkennt die Kamera jedes Kennzeichen [2].";
        assertFalse("an association speaks for a maker's passage", named(association, 2, MAKER));
        assertTrue(named(association, 2, ASSOCIATION));
        assertTrue("any speaker for a source of no such kind", named(association, 2, ""));
        String vendor = "Die Kamera erkennt jedes Kennzeichen, wie ein Anbieter angibt [2].";
        assertTrue(named(vendor, 2, MAKER));
        assertFalse(named(vendor, 2, ASSOCIATION));
        assertTrue("the industry speaks for both", named("Laut Branchenkennern steigt die Nachfrage [1].", 1, MAKER));
        assertTrue(named("Laut Branchenkennern steigt die Nachfrage [1].", 1, ASSOCIATION));
        String oneGroup = "Mehr Kontrollen machen Tempo-30-Zonen sicherer, wie ein Anbieter angibt [2, 3].";
        assertTrue(named(oneGroup, 2, MAKER));
        assertFalse("one group, two kinds of source", named(oneGroup, 3, ASSOCIATION));
    }

    @Test
    public void eachClaimNeedsItsOwnPartUnlessOneFrameOpensTheSentence() {
        String opened = "Nach Angaben des Herstellers erkennt das System Fußgänger [1] und Radfahrer [2].";
        assertTrue(named(opened, 1));
        assertTrue("the opening frame carries every claim", named(opened, 2));
        assertTrue(named("Laut Hersteller erkennt das System Fußgänger [1], Radfahrer [2] und Tiere [3].", 3, MAKER));
        assertTrue(named("Laut Hersteller gilt: Die Kamera erkennt jedes Kennzeichen [1].", 1, MAKER));
        String mixed = "Die Stadt misst weniger Lärm [1], während ein Anbieter beschreibt, dass die Kamera jedes "
                + "Fahrzeug erkennt [2].";
        assertFalse(named(mixed, 1));
        assertTrue(named(mixed, 2));
        String late = "Die Stadt misst weniger Lärm [1], und die Kamera erkennt jedes Fahrzeug [2].";
        assertFalse(named(late, 2));
        assertTrue("the last part takes a tag after its marker", named("Die Kamera erkennt jedes Fahrzeug [3], so der "
                + "Hersteller.", 3));
        assertTrue("one group, two passages", named("Laut einem Anbieter erkennt die Kamera Fahrzeuge [2, 3].", 3));
        assertTrue("no marker cites it: the whole sentence", named("Laut einem Anbieter erkennt die Kamera "
                + "Fahrzeuge [2].", 5));
        assertFalse(named("Die Kamera erkennt Fahrzeuge [2].", 5));
        assertFalse("two frames: none opens the sentence alone", named("Nach Angaben des Herstellers erkennt das "
                + "System Fußgänger [1] und Radfahrer [2], wie ein Verband betont [3].", 2));
        assertFalse("a new clause with its own subject", named("Laut Hersteller erkennt die Kamera Fußgänger [1] und "
                + "die Ampel schaltet um [2].", 2, MAKER));
        assertFalse("a frame of another clause opens nothing", named("Der Hersteller nennt keinen Preis, doch das "
                + "System erkennt Fußgänger [1] und Radfahrer [2].", 2, MAKER));
        assertFalse("more than three words before the frame", named("Im vergangenen Jahr auf der Messe in Köln laut "
                + "Hersteller erkannte die Kamera Fußgänger [1] und Radfahrer [2].", 2, MAKER));
    }

    @Test
    public void theSourcesSiteIsANameOfItsSpeaker() {
        Collection<String> names = VisibleAttribution.namesOf("https://www.verkehrstechnik-nord.example/kameras");
        assertEquals(Arrays.asList("verkehrstechnik-nord.example", "verkehrstechnik-nord"), names);
        assertTrue(VisibleAttribution.attributes("Laut verkehrstechnik-nord.example blockiert die Schranke bei Stau "
                + "[1].", 1, names));
        assertTrue(VisibleAttribution.attributes("Verkehrstechnik-Nord beschreibt, dass die Schranke bei Stau "
                + "öffnet [1].", 1, names));
        assertFalse("without the name it is no speaker", named("Laut verkehrstechnik-nord.example blockiert die "
                + "Schranke bei Stau [1].", 1));
        assertTrue(named("Laut der Radwegeunion sinken die Unfallzahlen [1].", 1, ASSOCIATION,
                VisibleAttribution.namesOf("radwegeunion.example")));

        Collection<String> blitzer = VisibleAttribution.namesOf("blitzer.example");
        assertEquals(Arrays.asList("blitzer.example", "blitzer"), blitzer);
        assertFalse("a name outside a frame", named("Die Blitzer warnen Autofahrer rechtzeitig vor Gefahren [2].", 2,
                MAKER, blitzer));
        assertFalse(named("Die Blitzer schätzen die Geschwindigkeit auf einen km/h genau [2].", 2, MAKER, blitzer));
        assertFalse("a name in a compound", named("Blitzer-Apps warnen Autofahrer vor jeder Kontrolle [1].", 1, MAKER,
                blitzer));
        assertFalse(named("Die Optika-Kameras melden Stau und warnen die Leitstelle [1].", 1, MAKER,
                VisibleAttribution.namesOf("optika.example")));
        Collection<String> sub = VisibleAttribution.namesOf("https://source.sturzwacht.example/app");
        assertEquals("the name before the public suffix, not a subdomain",
                Arrays.asList("source.sturzwacht.example", "sturzwacht"), sub);
        assertFalse(named("According to one source, the app detects every fall [1].", 1, MAKER, sub));
        assertFalse("a name inside a word", named("Die App schont Ressourcen und warnt den Träger bei einem Sturz "
                + "[1].", 1, MAKER, sub));
        assertFalse(named("Laut Fachblogs öffnet das Schloss in unter einer Sekunde [1].", 1, MAKER,
                VisibleAttribution.namesOf("blog.schlosswerk.example")));
        assertEquals(Arrays.asList("radwege-nord.co.uk", "radwege-nord"),
                VisibleAttribution.namesOf("https://www.radwege-nord.co.uk/news"));

        assertEquals("example.com", VisibleAttribution.hostOf("https://www.Example.com/a/b?x=1"));
        assertEquals("example.com", VisibleAttribution.hostOf("example.com/x"));
        assertEquals("shop.example.de", VisibleAttribution.hostOf("http://nutzer@shop.example.de:8080/p"));
        assertEquals("a password before the host", "example.com",
                VisibleAttribution.hostOf("https://nutzer:kennwort@example.com/a"));
        assertEquals("an @ in the path", "example.com",
                VisibleAttribution.hostOf("https://example.com/weiter@andere.example"));
        assertEquals("no scheme", "cdn.vendor.example", VisibleAttribution.hostOf("//cdn.vendor.example/x"));
        assertEquals("münchen.example", VisibleAttribution.hostOf("münchen.example"));
        assertEquals("an id is no site", "", VisibleAttribution.hostOf("blitzer"));
        assertEquals("", VisibleAttribution.hostOf("src-1234"));
        assertEquals("", VisibleAttribution.hostOf(""));
        assertEquals("", VisibleAttribution.hostOf(null));
        assertEquals("a short name is no name of its own", Collections.singletonList("abc.example"),
                VisibleAttribution.namesOf("abc.example"));
        assertEquals(Collections.singletonList("portal.vdb.example"),
                VisibleAttribution.namesOf("portal.vdb.example"));
        assertTrue(VisibleAttribution.namesOf("blitzer").isEmpty());
    }

    @Test
    public void theSpeakerIsPutInBeforeTheMarkerOfTheClaim() {
        String phrase = ", wie ein Anbieter angibt";
        assertEquals("Die Kamera erkennt jedes Kennzeichen, wie ein Anbieter angibt [2].",
                withSpeaker("Die Kamera erkennt jedes Kennzeichen [2].", 2, MAKER, phrase));
        String middle = withSpeaker("Die Kamera erkennt Fußgänger [3] und warnt den Fahrer [4].", 3, MAKER, phrase);
        assertEquals("a comma closes it when the sentence goes on", "Die Kamera erkennt Fußgänger, wie ein Anbieter "
                + "angibt [3], und warnt den Fahrer [4].", middle);
        assertTrue(named(middle, 3));
        assertFalse(named(middle, 4));
        assertEquals("Die Kamera erkennt Fußgänger, wie ein Anbieter angibt [3], und sie warnt [4].",
                withSpeaker("Die Kamera erkennt Fußgänger [3], und sie warnt [4].", 3, MAKER, phrase));
        String named = "Laut einem Anbieter erkennt die Kamera Fußgänger [3].";
        assertEquals(named, withSpeaker(named, 3, MAKER, phrase));
        assertEquals("not cited", "Die Kamera erkennt Fußgänger [3].",
                withSpeaker("Die Kamera erkennt Fußgänger [3].", 4, MAKER, phrase));
        assertEquals("no words before the marker", "[3] Die Kamera erkennt Fußgänger.",
                withSpeaker("[3] Die Kamera erkennt Fußgänger.", 3, MAKER, phrase));
        String english = withSpeaker("The camera reads every plate [1].", 1, MAKER, ", as a vendor states");
        assertEquals("The camera reads every plate, as a vendor states [1].", english);
        assertTrue(named(english, 1));
        assertTrue(named(withSpeaker("Die Anlage spart Strom [5].", 5, ASSOCIATION, ", wie ein Verband angibt"), 5,
                ASSOCIATION));
    }

    /** The review of 22.11.2026: "… zuverlässig., wie ein Anbieter angibt [2]" fused two sentences. */
    @Test
    public void theSpeakerGoesWhereItReadsAsPartOfTheSentenceOrNowhere() {
        String phrase = ", wie ein Anbieter angibt";
        assertEquals("before the full stop when the marker follows it", "Die Kamera erkennt jedes Kennzeichen, wie "
                + "ein Anbieter angibt. [2]", withSpeaker("Die Kamera erkennt jedes Kennzeichen. [2]", 2, MAKER,
                phrase));
        assertEquals("Erkennt die Kamera jedes Kennzeichen, wie ein Anbieter angibt? [2]",
                withSpeaker("Erkennt die Kamera jedes Kennzeichen? [2]", 2, MAKER, phrase));
        assertEquals("after a number and its unit", "Die Anlage senkt den Verbrauch um 10 %, wie ein Anbieter angibt "
                + "[1].", withSpeaker("Die Anlage senkt den Verbrauch um 10 % [1].", 1, MAKER, phrase));
        String comma = "Die Kamera erkennt jedes Kennzeichen, [2] auch bei Nacht.";
        assertEquals("no place after a comma", comma, withSpeaker(comma, 2, MAKER, phrase));
        String quote = "Sie nennt die Kamera „sicher.“ [2]";
        assertEquals("no place after a quote", quote, withSpeaker(quote, 2, MAKER, phrase));
        String semicolon = "Die Kamera erkennt jedes Kennzeichen; [2] die Stadt misst.";
        assertEquals(semicolon, withSpeaker(semicolon, 2, MAKER, phrase));
    }

    @Test
    public void noSecondSpeakerIsPutInWhereOneAlreadySpeaks() {
        String phrase = ", wie ein Anbieter angibt";
        String opened = "Nach Angaben des Herstellers erkennt das System Fußgänger [1] und Radfahrer [2].";
        assertEquals("the opening frame carries the claim", opened, withSpeaker(opened, 2, MAKER, phrase));
        String other = "Laut einem Verband erkennt die Kamera jedes Kennzeichen [2].";
        assertEquals("a speaker of another kind stays for the check", other, withSpeaker(other, 2, MAKER, phrase));
        String otherOpens = "Laut einem Verband erkennt die Kamera Fußgänger [1] und Radfahrer [2].";
        assertEquals(otherOpens, withSpeaker(otherOpens, 2, MAKER, phrase));
        assertEquals(other, withSpeaker(other, 2, ASSOCIATION, ", wie ein Verband angibt"));
        assertEquals("only the claim no frame carries gets it", "Laut Hersteller erkennt das System Fußgänger [1] und "
                + "Radfahrer [2], und die Ampel schaltet um, wie ein Anbieter angibt [2].", withSpeaker("Laut "
                + "Hersteller erkennt das System Fußgänger [1] und Radfahrer [2], und die Ampel schaltet um [2].", 2,
                MAKER, phrase));
    }

    @Test
    public void twoPassagesShareAGroupOnlyInOneBracket() {
        assertTrue(VisibleAttribution.shareAGroup("Die Kamera erkennt Fahrzeuge [2, 3].", 2, 3));
        assertTrue(VisibleAttribution.shareAGroup("Die Kamera erkennt Fahrzeuge [2; 3].", 3, 2));
        assertFalse(VisibleAttribution.shareAGroup("Die Kamera erkennt Fahrzeuge [2] und Radfahrer [3].", 2, 3));
        assertFalse(VisibleAttribution.shareAGroup(null, 2, 3));
    }
}
