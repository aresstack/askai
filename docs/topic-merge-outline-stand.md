# Komponente 15: Gliederung von oben – Stand und Ansatzpunkte

Stand 23.11.2026, Branch `claude/project-thread-9yjmw1-zusammenfuehrung` (PR #269), Kopf `c14069eb`.
Angelo hat 15 am 23.11.2026 mit Schalter `stimmigkeit` an als **grob gebaut** abgehakt. Diese Datei hält fest,
was gebaut ist, was gemessen wurde und wo man zur Optimierung ansetzen kann.

## Was gebaut ist

`TopicMerger` (`research-agent-ui-plugin/.../research/topics/TopicMerger.java`), innere Klasse `OutlinePass`,
eingeschaltet über `Options.withOutline(true)` (Bibliothek: aus; Bestandstest: Option `gliederung`, Standard an).
Sie ersetzt Oberthema-Schritt, Abgleich und Gegenstandsschritt. Schritt 1, Kartenschritt, Zwillinge und Fragen
bleiben. Je Stelle (jede Karte und die freie Ebene) laufen nacheinander:

| Schritt | Fall im Log | Was passiert |
|---|---|---|
| Abschnitte | `ABSCHNITTE` | 12b liest alle Blattnamen der Stelle mit Buchtitel und Karte und legt Abschnitte an (eine Sache je Abschnitt, kein Abschnitt nach einem Aspekt benannt). Ein Abschnitt, der wie eine Karte heißt, fällt sofort weg. |
| Zuordnung | `ZUORDNUNG` | 12b ordnet die Blätter in Paketen zu 50 zu (Schlüssel T1, T2, …; 0 = keiner). Ungültige Einträge gehen per Einbettung (≥ 0,5) zum nächsten Abschnitt. Fünf kopierte Nummern in Folge über den letzten Abschnitt hinaus machen die Antwort ungültig. |
| Rest | `REST` | Ab 3 Blättern ohne Abschnitt ein eigener Abschnittsaufruf, dann Zuordnung nur gegen die neuen Abschnitte. |
| Stimmigkeit | `STIMMIGKEIT`, `ZUORDNUNG` mit Marke `stimmigkeit umhaengen` | Nur mit Schalter, siehe unten. |
| Zusammenlegen | `ZUSAMMENLEGEN` | Ein Aufruf je Stelle nennt Gruppen von Abschnitten derselben Sache. Der größte behält den Namen; ein Glied kommt nur bei Einbettung ≥ 0,6 zum größten dazu. |
| Kleine Abschnitte | `ZUORDNUNG` mit Marke `kleine abschnitte` | Neue Abschnitte mit höchstens 3 Blättern werden als Ganzes den übrigen Abschnitten der Stelle zugeordnet. |
| Teilung | `TEILUNG` | Abschnitte über 40 Blätter, und Abschnitte ab 6 Blättern mit einem Aspektwort im Namen (Recht, Pflicht, Grundlage, …), werden in Unterabschnitte geteilt (nur mit Platz, `tiefe=3`). Weniger als zwei Unterabschnitte heißt: keine Teilung. |

### Stimmigkeitsprüfung (Schalter `stimmigkeit`, Standard aus)

Angelos Idee, vom Architekten offline geprüft und bestellt. Eingeschaltet über
`TopicMerger.withCoherence(scorer, kleinesModell)`; im Bestandstest `-D…stimmigkeit=true` mit `rerankerUrl`
(Cross-Encoder über `POST /api/rerank`), `rerankerModell`, `stimmigkeitModell` (gemma4:e2b),
`stimmigkeitNumCtx` (8192).

1. Nach Zuordnung und Rest: jeder Abschnitt mit mindestens 6 Blättern. Der Cross-Encoder
   (ms-marco-MiniLM-L-6-v2) bewertet jedes Blatt, Anfrage „Abschnitt: Inhalt“, Dokument „Blatt (Beschreibung bis
   300 Zeichen)“, absteigend sortiert.
2. Obere Hälfte (abgerundet) = Referenz, untere Hälfte = Kandidaten.
3. Ein Aufruf des kleinen Modells je Abschnitt: würde ein Buchabschnitt über diese Sache den Kandidaten sinnvoll
   behandeln? Nur „nein“ nimmt heraus; ungültig oder fehlend heißt „ja“.
4. Die herausgenommenen Blätter eines Abschnitts gehen gesammelt durch die 12b-Zuordnung gegen die übrigen
   Abschnitte der Stelle, ohne den Herkunftsabschnitt, 0 erlaubt. Bei 0, ungültiger Antwort oder Fehler bleibt das
   Blatt allein.

Zähler in `result.json` unter `gliederung.zaehler`: `geprueft`, `kandidaten`, `markiert`, `umgehaengt`,
`markiertAllein`, `stimmigkeitFehler`. Jede Kandidatenzeile im Log trägt Score, Rang, Urteil und Ziel.

## Messverlauf

Alle Läufe vom Architekten auf dem Bestand vom 17.11., je zwei Läufe mit frischen Seeds, Vorabfestlegung vor jedem
Lauf. Z1 = erwartete Themen beisammen (über 14, nötig 10). S2 = Stimmigkeit von 15 blind gelesenen Abschnitten (nötig
11 stimmig, höchstens 1 gemischt). Z9 und Z10 = blinde Lesungen nach Vorabfestlegung. Z11 = 20 umgehängte Blätter,
das Ziel behandelt die Sache des Blatts (nötig 15).

| Läufe | Kopf | Änderung | Z1 (von 14) | S2 | Ergebnis |
|---|---|---|---|---|---|
| c15t, c15u | 5274ca1a | erste Fassung, Buchauftrag im Prompt | 9, 9 | – | nicht bestanden (Z1) |
| c15v, c15w | 7c8d53d1 | Buchtitel statt Auftrag, Kartennamen weg, T-Schlüssel, Zusammenlegen | 12, 13 | 9/15 | nicht bestanden (S2) |
| c15x, c15y | ff7c9a2c | kleine Abschnitte, Aspektsatz, Aspektteilung | 13, 13 | 8/15, 2 gemischt | nicht bestanden (S2) |
| c15z, c15zb | 35ce73ba | Stimmigkeit an, Umhängen per Einbettung | 13, 12 | 11/15, 2 gemischt | knapp nicht bestanden (S2) |
| c15zc, c15zd | c14069eb | Umhängen über die 12b-Zuordnung | 12, 13 | **13/15, 0 gemischt** | Hauptfrage erfüllt, Z11 14/20 knapp verfehlt |

Letzter Stand im Einzelnen (c15zc, c15zd): Z2 bis Z6 erfüllt, Z9 19/20, Z10 18/20, markiert 160 und 148, umgehängt
je 104, allein 56 und 44, `stimmigkeitFehler` 0.

## Wo man ansetzen kann

### 1. Umhängen (Z11 14/20, Schwelle 15)

Die 6 Fehlumhängungen in der Probe:

- **Nur ein Wort oder eine Rolle gemeinsam**: das Ziel teilt mit dem Blatt nur ein Wort oder eine Rolle, nicht die
  Sache (Beispiele: „Verwaltungsrat“ landete bei Betriebsrat, „Robustheit von RFID-Hardware“ bei Sensorik).
- **Markierung schon falsch**: in 3 der 6 Fälle passte der Herkunftsabschnitt besser. Das kleine Modell hat ein
  passendes Blatt herausgenommen, das Umhängen konnte es nicht mehr richtig machen.

Mögliche Hebel (ungemessen):

- Den Herkunftsabschnitt bei der Neuzuordnung wieder zur Wahl stellen, damit eine falsche Markierung zurückgeht.
- Ein zweites Urteil vor dem Herausnehmen, etwa 12b statt e2b oder zwei Stimmen, nur für die markierten Blätter.
- Im Zuordnungsprompt für herausgenommene Blätter ausdrücklich sagen, dass ein gemeinsames Wort nicht reicht.
- Beim Umhängen ein Ziel nur annehmen, wenn das Blatt auch in dessen Cross-Encoder-Rangfolge nicht ganz unten
  landet.

### 2. Markierung

Markiert wird rund ein Drittel der Kandidaten (c15z: 146 von 446, c15zb: 133 von 435). Der Offline-Test des Architekten (100 Blätter,
11 unpassend) ergab mit e2b 9 Treffer bei 5 falschen Markierungen. Die Schwelle von 6 Blättern und die Halbierung
sind nicht weiter optimiert. Ein mehrsprachiger Cross-Encoder wurde nicht getestet (Download war offline gesperrt);
das Modell ist englisch trainiert, der Bestand deutsch.

### 3. Weitere bekannte Schwächen

- **Sammelabschnitte**: ein generischer Abschnitt („Arbeitsschutzmaßnahmen und PSA“, „Compliance und Einhaltung“)
  zieht Reste an. Der Aspektsatz und die Aspektteilung mildern das, beheben es nicht.
- **Seitenreste von Anbietern** (Rabatte, Cookies) bilden gelegentlich einen gemischten Abschnitt. Das gehört zum
  Seitenmüll-Check vor 13 (PR #274), nicht zu 15.
- **Zusammenlegen und kleine Abschnitte laufen nach der Stimmigkeit**: ein herausgenommenes Blatt kann mit seinem
  neuen kleinen Abschnitt wieder in den alten wandern. Im Log sichtbar, nicht gemessen.
- **Aspektwörter** sind eine feste Liste von Wortstämmen; Schwelle 6 und Liste sind ungemessen.
- **Zusammenlegen-Wächter 0,6** ist der Vorschlag des Architekten, nicht gemessen.
- Der **Cross-Encoder** ist im Bestandstest nur über HTTP erreichbar; die lokale Engine der App (oder ein Skript mit
  denselben Gewichten) muss laufen.

## Wie man nachmisst

Bestandstest `TopicMergerStockTest` (Gradle-Aufgabe `componentStockTest`), Optionen im Javadoc der Klasse. Für den
Stand oben: `gliederung=true`, `tiefe=3`, `numCtx=32768`, `stimmigkeit=true` mit `rerankerUrl`. Die Vorabfestlegungen,
Auswertungen und Logs der Läufe liegen beim Architekten auf dem Testrechner, nicht im Repo (fremde Quellentexte).
