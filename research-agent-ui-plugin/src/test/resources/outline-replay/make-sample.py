#!/usr/bin/env python3
"""Writes sample-corpus.json, the small self-written sample of the outline replay harness.

The texts are written for this sample; sources and domains are made up. The vectors are synthetic, not embeddings:
a shared subject axis, one axis per theme and a little deterministic noise. They only give the topic analysis
something to cluster. Run:  python3 make-sample.py  (writes next to this file).
"""
import json
import math
import os

DIM = 12
TITLE = "Hochbeete im Stadtgarten"
MISSION = ("Praxisbuch für Hobbygärtner in der Stadt: wie man Hochbeete baut, bepflanzt und über das Jahr pflegt. "
           "Fahrradpflege gehört nicht dazu.")
THEMES = [
    ("bau", "Bau und Material", ["selbstbau.example", "garten.example", "stadtgruen.example"], [
        "Ein Hochbeet aus Lärchenholz hält ohne Anstrich etwa zehn Jahre. Die Bretter sollten mindestens drei "
        "Zentimeter stark sein, damit sie dem Druck der feuchten Erde standhalten.",
        "Als Standort eignet sich ein ebener Platz mit sechs Stunden Sonne am Tag. Eine Breite von höchstens "
        "1,20 Metern erlaubt es, die Mitte des Beets von beiden Seiten zu erreichen.",
        "Ein engmaschiges Drahtgitter am Boden hält Wühlmäuse fern. Es wird vor dem Befüllen ausgelegt und an den "
        "Seitenwänden einige Zentimeter hochgezogen.",
        "Die Innenwände werden mit Noppenfolie ausgekleidet, damit das Holz nicht dauernd nass ist. Die Folie endet "
        "knapp unter der Oberkante und bleibt unten offen, damit Wasser ablaufen kann.",
        "Befüllt wird in Schichten: unten grober Strauchschnitt, darüber Laub und Rasenschnitt, dann halbreifer "
        "Kompost und oben Pflanzerde. Jede Schicht ist etwa zwanzig Zentimeter hoch.",
        "Hochbeete aus verzinktem Stahl erwärmen sich im Frühjahr schneller als Holzbeete. Im Hochsommer kann die "
        "Erde am Rand jedoch stärker austrocknen.",
        "Für ein Beet von zwei Metern Länge braucht man vier Eckpfosten und zwei Mittelpfosten. Die Pfosten stehen "
        "innen, damit die Bretter den Erddruck auf sie übertragen.",
        "Die Arbeitshöhe richtet sich nach der Körpergröße. Für die meisten Erwachsenen sind 80 bis 90 Zentimeter "
        "angenehm, für Kinderbeete genügen 40 Zentimeter.",
    ]),
    ("pflanzen", "Bepflanzung und Fruchtfolge", ["garten.example", "stadtgruen.example", "gemuese.example"], [
        "Im ersten Jahr setzt die Füllung viele Nährstoffe frei. Dann gedeihen Starkzehrer wie Tomaten, Zucchini "
        "und Kohl besonders gut.",
        "Im zweiten Jahr folgen Mittelzehrer wie Möhren, Mangold und Salat. Im dritten Jahr kommen Schwachzehrer "
        "wie Erbsen, Radieschen und Kräuter an die Reihe.",
        "In der Mischkultur stehen Möhren neben Zwiebeln, weil der Duft der einen die Schädlinge der anderen stört. "
        "Tomaten und Basilikum gelten ebenfalls als gute Nachbarn.",
        "Schon im März lassen sich Radieschen, Spinat und Pflücksalat säen. Ein Vlies über dem Beet schützt die "
        "Keimlinge in kalten Nächten.",
        "Hohe Pflanzen wie Stabtomaten kommen an die Nordseite, damit sie die niedrigen nicht beschatten. Rankende "
        "Kapuzinerkresse darf am Rand über die Kante wachsen.",
        "Nach der Ernte im Herbst bedeckt Feldsalat oder eine Gründüngung den Boden. So bleibt die Erde locker und "
        "wird über den Winter nicht ausgewaschen.",
        "Kartoffeln brauchen viel Tiefe und eignen sich deshalb gut für das Hochbeet. Man legt die Knollen im April "
        "und häufelt zweimal an.",
        "Erdbeeren tragen im Hochbeet zwei bis drei Jahre gut. Danach werden die Pflanzen ersetzt und an einen "
        "anderen Platz gesetzt.",
    ]),
    ("pflege", "Bewässerung und Pflanzenschutz", ["stadtgruen.example", "gemuese.example", "selbstbau.example"], [
        "Hochbeete trocknen schneller aus als Bodenbeete. Im Sommer muss an heißen Tagen morgens gründlich gegossen "
        "werden.",
        "Eine Mulchschicht aus Rasenschnitt hält die Feuchtigkeit in der Erde. Sie sollte dünn aufgetragen werden, "
        "damit sie nicht fault.",
        "Ein Tropfschlauch mit Zeitschaltuhr gibt das Wasser direkt an die Wurzeln ab. Das spart Wasser und hält "
        "die Blätter trocken.",
        "Schnecken erreichen auch hohe Beete. Ein nach außen abgewinkeltes Blech an der Oberkante hält sie "
        "zuverlässig ab.",
        "Gegen die Raupen des Kohlweißlings hilft ein feinmaschiges Netz. Es wird sofort nach dem Pflanzen über "
        "den Kohl gespannt.",
        "Blattläuse lassen sich mit einem scharfen Wasserstrahl abspritzen. Marienkäfer und Florfliegen fressen "
        "den Rest.",
        "Regenwasser aus der Tonne ist weicher als Leitungswasser und für die meisten Gemüse besser. Eine Tonne "
        "mit 200 Litern reicht für ein Beet etwa zwei Wochen.",
        "Im Frühjahr sackt die Füllung um zehn bis zwanzig Zentimeter. Dann wird mit reifem Kompost aufgefüllt, "
        "bevor neu gepflanzt wird.",
    ]),
]
FOREIGN = ("fahrrad", "Fahrradpflege", ["rad.example", "werkstatt.example"], [
    "Ein platter Reifen lässt sich in zehn Minuten flicken. Zuerst wird das Rad ausgebaut und der Mantel mit zwei "
    "Reifenhebern von der Felge gehoben.",
    "Die Kette sollte alle 300 Kilometer gereinigt und geölt werden. Überschüssiges Öl wird mit einem Lappen "
    "abgewischt.",
    "Schleifende Bremsbeläge erkennt man am Geräusch. Die Beläge werden so eingestellt, dass sie die Felge "
    "gleichzeitig und vollflächig berühren.",
    "Der richtige Reifendruck steht auf der Reifenflanke. Zu wenig Druck erhöht den Rollwiderstand und die "
    "Pannengefahr.",
    "Springt die Schaltung über die Gänge, ist meist die Zugspannung zu gering. Eine Vierteldrehung an der "
    "Stellschraube genügt oft.",
    "Vor dem Winter lohnt sich ein Blick auf die Beleuchtung. Kontakte am Dynamo werden gereinigt und lockere "
    "Kabel befestigt.",
])


class Noise(object):
    """A small linear congruential generator, so that the file is the same on every machine."""

    def __init__(self, seed):
        self.state = seed

    def next(self):
        self.state = (self.state * 1103515245 + 12345) % 2147483648
        return self.state / 1073741824.0 - 1.0


def unit(values):
    norm = math.sqrt(sum(v * v for v in values))
    return [round(v / norm, 4) for v in values]


def vector(axes, noise, strength):
    values = [0.0] * DIM
    for axis, weight in axes:
        values[axis] = weight
    if noise is not None:
        for d in range(DIM):
            values[d] += strength * noise.next()
    return unit(values)


def main():
    noise = Noise(20261121)
    passages = []
    anchors = []
    for index, (key, label, domains, texts) in enumerate(THEMES + [FOREIGN]):
        foreign = key == FOREIGN[0]
        axes = [(4, 0.9)] if foreign else [(0, 0.55), (1 + index, 0.75)]
        if not foreign:
            anchors.append({"id": "node-" + key, "label": label, "path": TITLE + " > " + label,
                            "vector": vector([(0, 0.55), (1 + index, 0.83)], None, 0.0)})
        for number, text in enumerate(texts):
            source = number // 2
            passages.append({"id": "%s-p%d" % (key, number + 1), "captureId": "cap-%s-%d" % (key, source + 1),
                             "sourceId": "src-%s-%d" % (key, source + 1), "domain": domains[source % len(domains)],
                             "theme": key, "heading": label, "text": text,
                             "vector": vector(axes, noise, 0.27)})
    corpus = {
        "about": "Self-written sample for ReplayOutlineSampleTest; synthetic vectors, see make-sample.py.",
        "title": TITLE, "mission": MISSION, "language": "de", "dim": DIM,
        "frameVector": vector([(0, 1.0), (1, 0.35), (2, 0.35), (3, 0.35)], None, 0.0),
        "anchors": anchors, "passages": passages,
    }
    target = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sample-corpus.json")
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(corpus, handle, ensure_ascii=False, indent=1)
        handle.write("\n")
    print("wrote %s: %d passages, %d anchors, dim %d" % (target, len(passages), len(anchors), DIM))


if __name__ == "__main__":
    main()
