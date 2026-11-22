package com.aresstack.askai.research.pagenoise;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Holds the model's first sentence (who speaks, to what end) against the kind it named. When the model names
 * {@code AUSSAGE} but its own sentence says the piece is a page leftover, the sentence wins. The architect's
 * acceptance of version 2 showed the model writing that a provider recommends its own product and naming AUSSAGE all
 * the same.
 *
 * <p>Only plain cases count, so that a statement about such things (cookie rules, the duties of a manufacturer) is not
 * taken for a leftover. Mostly the first clause of the sentence (up to the first comma) is read:</p>
 * <ul>
 *     <li>the sentence names the piece as a kind of leftover, at its start ("Ein Inhaltsverzeichnis ...") or after
 *     "ist" ("Das Stück ist eine Literaturliste");</li>
 *     <li>a provider or the site is the subject and speaks about itself: it advertises, names its own product, offer
 *     or partners, presents itself, gives its own contact data or asks for cookies; or it writes to sell or to win
 *     customers;</li>
 *     <li>the piece itself is the subject and speaks about itself: what the article announces or covers, how the
 *     document is built.</li>
 * </ul>
 * German and English sentences are read alike, since the sentence comes in the language of the prompt.
 */
final class DescriptionCheck {

    /** At most one word with an adjective's ending between article and noun; no preposition. */
    private static final String ADJECTIVE = "(?:(?!(?:über|unter|für|mit|von|aus|zu|zur|zum|about|with|for|from|of"
            + "|on)\\b)[\\p{L}-]+(?:e|er|es|en|em|al|ive|ed|ing)\\s+)?";
    private static final String ARTICLE = "(?:ein|eine|einer|der|die|das|den|dieser|diese|dieses|a|an|the|this)\\s+";
    private static final String ONLY = "(?:nur\\s+|lediglich\\s+|bloß\\s+|only\\s+|just\\s+)?";
    private static final String OWN = "\\b(?:sein|seine|seinen|seinem|seiner|seines|ihr|ihre|ihren|ihrem|ihrer|ihres"
            + "|eigene|eigenen|eigenem|eigener|eigenes|its|their)\\s+(?:eigene[nmrs]?\\s+|neue[nmrs]?\\s+|own\\s+"
            + "|new\\s+)?[\\p{L}-]*";

    /** The piece named as a kind of leftover, at the start of the sentence. */
    private static final Map<NoiseMark.Kind, Pattern> TYPE_AT_START = new EnumMap<NoiseMark.Kind, Pattern>(
            NoiseMark.Kind.class);
    /** The piece named as a kind of leftover after "ist", in the first clause. */
    private static final Map<NoiseMark.Kind, Pattern> TYPE_AFTER_IS = new EnumMap<NoiseMark.Kind, Pattern>(
            NoiseMark.Kind.class);
    /** What a provider or the site says about itself, in the first clause after a self subject. */
    private static final Map<NoiseMark.Kind, Pattern> SELF_ACTS = new EnumMap<NoiseMark.Kind, Pattern>(
            NoiseMark.Kind.class);

    static {
        type(NoiseMark.Kind.WERBUNG, "werbung|werbetext|werbeanzeige|produktwerbung|eigenwerbung|verkaufstext"
                + "|advertisement|advert|ad|sales text|promotion");
        type(NoiseMark.Kind.SEITENBESCHREIBUNG, "selbstdarstellung|unternehmensdarstellung|self-presentation");
        type(NoiseMark.Kind.VERZEICHNIS, "inhaltsverzeichnis|stichwortverzeichnis|linkliste|linksammlung|sitemap"
                + "|liste (?:von|mit|der) (?:links|titeln|artikeln|beiträgen|dokumenten|downloads|produkten|terminen)"
                + "|table of contents|list of (?:links|titles|articles|documents|downloads|products|dates)");
        type(NoiseMark.Kind.LITERATUR, "literaturverzeichnis|literaturliste|literaturangaben?|literaturhinweise?"
                + "|quellenangaben?|quellenliste|quellenverzeichnis|normenliste|liste (?:von|mit|der) normen"
                + "|bibliografie|bibliographie|bibliography|reference list|list of references|list of sources"
                + "|list of standards");
        type(NoiseMark.Kind.NAVIGATION, "menü|navigationsmenü|navigationsleiste|navigation|brotkrumen\\p{L}*"
                + "|breadcrumbs?|menu");
        type(NoiseMark.Kind.BILDUNTERSCHRIFT, "bildunterschrift|bildnachweis|bildquelle|bildbeschriftung|beschriftung"
                + "|caption|picture credit|image credit");
        type(NoiseMark.Kind.UEBERSCHRIFT, "überschrift|zwischenüberschrift|titel|anriss|teaser|heading|title");
        type(NoiseMark.Kind.IMPRESSUM, "impressum|kontaktangaben|kontaktdaten|kontaktbereich|imprint"
                + "|contact details");
        type(NoiseMark.Kind.SEITENHINWEIS, "cookie-hinweis|cookiehinweis|cookie-banner|cookiebanner"
                + "|datenschutzhinweis|newsletter-anmeldung|newsletteranmeldung|cookie notice|cookie banner");
        SELF_ACTS.put(NoiseMark.Kind.WERBUNG, unicode("\\b(?:wirbt|bewirbt|vermarktet|advertises|promotes"
                + "|markets)\\b|" + OWN + "(?:produkt|produkte|produkts|produktes|produkten|angebot|angebote|angebots"
                + "|angeboten|lösung|lösungen|dienstleistung|dienstleistungen|leistung|leistungen|software|app|apps"
                + "|gerät|geräte|geräts|geräten|kurs|kurse|kurses|kursen|seminar|seminare|seminaren|schulung"
                + "|schulungen|tarif|tarife|partner|partnern|partnerschaft|partnerschaften|kooperation|kooperationen"
                + "|product|products|offer|offers|service|services|solution|solutions|device|devices|course|courses"
                + "|partners|partnership|partnerships)\\b"));
        SELF_ACTS.put(NoiseMark.Kind.SEITENBESCHREIBUNG, unicode("\\bstellt sich\\b|\\bsich selbst\\b"
                + "|\\büber sich\\b|\\b(?:introduces|presents|describes) itself\\b|\\babout itself\\b|" + OWN
                + "(?:unternehmen|team|geschichte|ziele|mission|referenzen|kunden|auszeichnungen|standorte|company"
                + "|history|aims|customers|references|awards)\\b"));
        SELF_ACTS.put(NoiseMark.Kind.IMPRESSUM, unicode(OWN + "(?:kontaktdaten|kontaktangaben|anschrift"
                + "|adresse|telefonnummer|öffnungszeiten|impressum|contact details|address|phone number|opening hours"
                + "|imprint)\\b"));
        SELF_ACTS.put(NoiseMark.Kind.SEITENHINWEIS, unicode("\\b(?:verwendet|nutzt|setzt|speichert)\\s+cookies"
                + "\\b|\\bweist\\b.{0,40}\\bcookies\\b|\\b(?:bittet|fragt)\\b.{0,40}\\b(?:zustimmung|einwilligung)\\b"
                + "|\\bnewsletter\\b|\\b(?:uses|sets|stores) cookies\\b|\\basks for (?:consent|permission)\\b"));
    }

    /** A provider or the site as the subject at the start of the sentence. */
    private static final Pattern SELF_SUBJECT = unicode("^" + ARTICLE + ADJECTIVE + "[\\p{L}-]*(?:anbieter"
            + "|anbieterin|hersteller|herstellerin|händler|händlerin|dienstleister|dienstleisterin|unternehmen|firma"
            + "|shop|kanzlei|agentur|verlag|betreiber|betreiberin|schule|akademie|institut|verein|verband|seite"
            + "|website|webseite|homepage|plattform|portal|blog|start-?up|provider|vendor|company|manufacturer|seller"
            + "|retailer|firm|agency|publisher|school|academy|institute|association|site|page|platform)\\b");

    /** A provider or the site writes to sell or to win customers, anywhere in the sentence. */
    private static final Pattern SELF_PURPOSE = unicode("\\bum\\b[^.;]{0,60}\\bzu (?:verkaufen|werben"
            + "|vermarkten)\\b|\\bkunden\\s+(?:zu\\s+)?(?:gewinnen|werben)\\b|\\bto (?:sell|advertise|promote)\\b"
            + "|\\bwin customers\\b");

    /** The piece itself as the subject at the start of the sentence. */
    private static final Pattern PIECE_SUBJECT = unicode("^" + ARTICLE + ADJECTIVE + "(?:einleitung|einführung"
            + "|vorwort|absatz|abschnitt|beitrag|blogbeitrag|artikel|text|textstück|stück|dokument|leitfaden|ratgeber"
            + "|bericht|kapitel|autor|autorin|redaktion|introduction|paragraph|section|article|post|text|document"
            + "|guide|author)\\b");

    /** The piece speaks about itself: what it announces or covers, how it is built. */
    private static final Pattern ABOUT_THE_PIECE = unicode("\\bkündigt\\b[^.;]{0,60}\\ban\\b"
            + "|\\b(?:beschreibt|erklärt|nennt|zeigt|skizziert)\\s+(?:den\\s+|die\\s+)?(?:aufbau|gliederung|inhalt"
            + "|inhalte|themen)\\s+(?:des|dieses|eines|der|dieser)\\s+(?:beitrags|artikels|dokuments|dokumentes"
            + "|leitfadens|textes|berichts|kapitels|buchs|buches|blogbeitrags|ratgebers|folgenden)\\b"
            + "|\\bwas (?:der|dieser|das|dieses|die|diese|im) (?:beitrag|artikel|text|leitfaden|bericht|blogbeitrag"
            + "|ratgeber|dokument|kapitel|folgenden) (?:behandelt|zeigt|enthält|bietet|erklärt|folgt)\\b"
            + "|\\b(?:announces|outlines|says) what (?:the|this) (?:article|post|document|guide|text|chapter) "
            + "(?:covers|is about|contains|explains)\\b|\\bstructure of (?:the|this) (?:document|article|post|guide"
            + "|report)\\b");

    private DescriptionCheck() {
    }

    /** Word boundaries over all letters: without the flag (Java 19 and later) no boundary follows an umlaut. */
    private static Pattern unicode(String regex) {
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    /**
     * The kind of leftover the sentence names in one of the plain ways, or {@code null}; with several, the first in the
     * order of {@link PageNoiseCheck#MODEL_KINDS}.
     */
    static NoiseMark.Kind kindNamed(String description) {
        String text = description == null ? "" : description.toLowerCase(Locale.ROOT)
                .replaceAll("[„“”\"«»‚‘’']", "").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        String firstClause = text.split("[,;:.!?(]", 2)[0].trim();
        boolean self = SELF_SUBJECT.matcher(firstClause).find();
        boolean piece = PIECE_SUBJECT.matcher(firstClause).find();
        for (NoiseMark.Kind kind : PageNoiseCheck.MODEL_KINDS) {
            Pattern atStart = TYPE_AT_START.get(kind);
            if (atStart != null && (atStart.matcher(text).find() || TYPE_AFTER_IS.get(kind).matcher(firstClause)
                    .find())) {
                return kind;
            }
            Pattern act = SELF_ACTS.get(kind);
            if (self && act != null && act.matcher(firstClause).find()) {
                return kind;
            }
            if (self && kind == NoiseMark.Kind.WERBUNG && SELF_PURPOSE.matcher(text).find()) {
                return kind;
            }
            if (piece && kind == NoiseMark.Kind.SEITENBESCHREIBUNG && ABOUT_THE_PIECE.matcher(text).find()) {
                return kind;
            }
        }
        return null;
    }

    private static void type(NoiseMark.Kind kind, String words) {
        TYPE_AT_START.put(kind, unicode("^" + ONLY + ARTICLE + ADJECTIVE + "(?:" + words + ")\\b"));
        TYPE_AFTER_IS.put(kind, unicode("\\b(?:ist|sind|is|are)\\s+" + ONLY + "(?:" + ARTICLE + ")?"
                + ADJECTIVE + "(?:" + words + ")\\b"));
    }
}
