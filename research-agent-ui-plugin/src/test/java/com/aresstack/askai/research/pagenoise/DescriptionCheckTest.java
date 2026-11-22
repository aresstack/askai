package com.aresstack.askai.research.pagenoise;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The first sentence of the page-noise model held against its kind (JUnit, no model): plain cases name a kind of
 * leftover, sentences about such things do not. Self-written sentences.
 */
public class DescriptionCheckTest {

    private static void names(NoiseMark.Kind kind, String sentence) {
        assertEquals(sentence, kind, DescriptionCheck.kindNamed(sentence));
    }

    private static void namesNothing(String sentence) {
        assertNull(sentence, DescriptionCheck.kindNamed(sentence));
    }

    @Test
    public void aProviderSpeakingAboutItsOwnOfferIsAdvertising() {
        names(NoiseMark.Kind.WERBUNG, "Ein Anbieter empfiehlt sein Produkt für die Baustelle.");
        names(NoiseMark.Kind.WERBUNG, "Ein Softwarehersteller beschreibt die Vorteile seiner Lösung.");
        names(NoiseMark.Kind.WERBUNG, "Ein Kursanbieter bewirbt seine Kurse für Fahranfänger.");
        names(NoiseMark.Kind.WERBUNG, "Ein Unternehmen stellt seine Partnerschaft mit einer Versicherung vor.");
        names(NoiseMark.Kind.WERBUNG, "Ein Händler beschreibt Funktionen, um das Gerät zu verkaufen.");
        names(NoiseMark.Kind.WERBUNG, "Der Online-Shop nennt Preise, um Kunden zu gewinnen.");
        names(NoiseMark.Kind.WERBUNG, "A vendor promotes its own software.");
        names(NoiseMark.Kind.WERBUNG, "Eine Werbeanzeige für Fahrradhelme.");
    }

    @Test
    public void theSiteSpeakingAboutItselfIsASiteDescriptionImprintOrNotice() {
        names(NoiseMark.Kind.SEITENBESCHREIBUNG, "Die Seite stellt sich und ihr Team vor.");
        names(NoiseMark.Kind.SEITENBESCHREIBUNG, "Ein Verlag beschreibt seine Geschichte.");
        names(NoiseMark.Kind.IMPRESSUM, "Die Firma nennt ihre Anschrift und ihre Öffnungszeiten.");
        names(NoiseMark.Kind.IMPRESSUM, "Das Impressum nennt den Betreiber der Seite.");
        names(NoiseMark.Kind.SEITENHINWEIS, "Die Website bittet um Zustimmung zu Cookies.");
        names(NoiseMark.Kind.SEITENHINWEIS, "Ein Cookie-Hinweis der Seite.");
    }

    @Test
    public void thePieceSpeakingAboutItselfIsASiteDescription() {
        names(NoiseMark.Kind.SEITENBESCHREIBUNG, "Die Einleitung kündigt an, was der Beitrag behandelt.");
        names(NoiseMark.Kind.SEITENBESCHREIBUNG, "Ein Absatz beschreibt den Aufbau des Dokuments.");
        names(NoiseMark.Kind.SEITENBESCHREIBUNG, "The introduction outlines what the article covers.");
    }

    @Test
    public void thePieceNamedAsAKindOfLeftover() {
        names(NoiseMark.Kind.VERZEICHNIS, "Ein Inhaltsverzeichnis verweist auf die Kapitel eines Leitfadens.");
        names(NoiseMark.Kind.VERZEICHNIS, "The piece is a table of contents.");
        names(NoiseMark.Kind.LITERATUR, "Das Stück ist eine Literaturliste zu Fahrradhelmen.");
        names(NoiseMark.Kind.LITERATUR, "Eine Liste von Normen ohne Erklärung.");
        names(NoiseMark.Kind.NAVIGATION, "Ein Navigationsmenü der Seite.");
        names(NoiseMark.Kind.BILDUNTERSCHRIFT, "Eine Bildunterschrift nennt den Fotografen.");
        names(NoiseMark.Kind.UEBERSCHRIFT, "Ein Anriss verweist auf einen Forumsbeitrag.");
        names(NoiseMark.Kind.UEBERSCHRIFT, "Das ist nur eine Überschrift.");
    }

    @Test
    public void aSentenceAboutSuchThingsNamesNothing() {
        namesNothing("Ein Text erklärt, welche Regeln für Werbung mit Gesundheitsdaten gelten.");
        namesNothing("Ein Portal erklärt, wie Arbeitgeber ihre Geräte sicher einsetzen.");
        namesNothing("Ein Hersteller erklärt allgemein, wie Sturzsensoren arbeiten.");
        namesNothing("Ein Gesetzestext regelt, welche Kontaktdaten der Arbeitgeber speichern darf.");
        namesNothing("Eine Behörde erklärt, wann Cookies eine Einwilligung brauchen.");
        namesNothing("Ein Fachartikel stellt Studienergebnisse zu Fahrradhelmen vor.");
        namesNothing("Ein Text nennt die Pflichten des Arbeitgebers bei der Prüfung seiner Geräte.");
        namesNothing("Eine Studie über Werbung an Schulen zeigt Ergebnisse.");
        namesNothing("Ein Gesetz kündigt neue Pflichten für Halter an.");
        namesNothing("Der Arbeitgeber muss ein Verzeichnis der Verarbeitungstätigkeiten führen.");
        namesNothing("A guide explains how employers use their devices.");
        namesNothing("");
        namesNothing(null);
    }
}
