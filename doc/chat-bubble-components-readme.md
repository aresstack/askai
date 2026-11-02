# AskAI Chat-Bubble-Komponenten

Dieses Paket enthält eigenständige Swing-Komponenten für eine spätere Integration in die AskAI-Chat-Ausgabe. Die vorhandene `ChatTranscript`-Implementierung wird bewusst noch nicht ersetzt.

## Enthaltene Komponenten

| Klasse | Aufgabe |
|---|---|
| `BubbleTranscriptPanel` | Scrollbarer Verlauf und Integrations-Fassade |
| `SpeechBubblePanel` | Selektierbare und streambare Benutzer-/Assistentenblase |
| `AgentActivityBubblePanel` | Animierte Agenten-Aktivitätsblase mit Abschlussanimation |
| `BubbleSide` | Richtungsregel für links/rechts angeordnete Blasen |
| `BubblePalette` | Austauschbare Farbpalette |
| `BubbleComponentsShowcase` | Manuell startbare Vorschau unter `src/test/java` |

## Verbindliche Richtungsregel

**Alle Sprechblasen-Zipfel und alle Gedankenblasen-Kreise zeigen zur Mitte des Chatfensters.**

- Benutzerblase rechts: Zipfel zeigt nach links.
- Assistentenblase links: Zipfel zeigt nach rechts.
- Gedankenblase links (Assistenten-Thinking wie Agenten-Aktivität): Die aufsteigenden Kreise wandern
  nach rechts, zur Mitte, und werden dabei größer.
- Eine rechts angeordnete Gedankenblase spiegelt das (Kreise nach links, zur Mitte).

Die Seiten-Regel ist zentral in `BubbleSide` abgebildet; die gemeinsame Zeichen-/Animationslogik in
`AnimatedThoughtBubblePanel` (Farben über `ThoughtBubbleTheme`).

## Was die Agentenblase ausdrückt

Die Agentenblase zeigt eine benutzerverständliche Begründung für eine sichtbare Agentenaktion, beispielsweise:

> Herstellerseite öffnen  
> Die offizielle Produktseite soll die technischen Angaben verifizieren.

Sie ist nicht dafür vorgesehen, verborgenes internes Modell-Reasoning anzuzeigen.

## Animationsablauf

`AgentActivityBubblePanel` besitzt den visuellen Ablauf:

1. `RUNNING`: Die Begründung ist sichtbar. Vier Kreise wandern zur Mitte, steigen auf und werden größer.
2. `BURSTING`: Die Gedankenblase bläht sich kurz auf, verblasst und zerfällt in Partikel.
3. `FLOATING_RESULT`: Eine kompakte Zusammenfassung steigt auf. Innerhalb von `BubbleTranscriptPanel`
   wird sie auf einer transparenten Overlay-Ebene gezeichnet, überlagert damit alle darunterliegenden
   Zeilen und steigt bis zur oberen Kante des Verlaufs auf, bevor sie ausblendet. (Ohne Transcript-Overlay
   fällt die Blase auf den zeileninternen Aufstieg zurück.)
4. `FINISHED`: Die Animation endet. `BubbleTranscriptPanel` entfernt die temporäre Zeile.

Die Animation verwendet ausschließlich `javax.swing.Timer` und läuft damit auf dem Swing Event Dispatch Thread.

## Minimale Integration

`BubbleTranscriptPanel` kann später anstelle der bisherigen `ChatTranscript`-Komponente in `OllamaChatPanel` eingesetzt werden.

```java
private final BubbleTranscriptPanel transcript;

private BubbleTranscriptPanel createTranscript() {
    return new BubbleTranscriptPanel(BubblePalette.windowsPhoneInspired());
}
```

Die Komponente selbst ist bereits ein `JPanel` und kann direkt in das Layout eingefügt werden:

```java
add(transcript, BorderLayout.CENTER);
```

## Normale Nachrichten

```java
transcript.appendUserMessage(userText);

transcript.startAssistantMessage(modelName);
transcript.appendAssistantDelta(firstDelta);
transcript.appendAssistantDelta(secondDelta);
transcript.finishAssistantMessage();

transcript.appendInfo("Verbindung hergestellt");
```

`SpeechBubblePanel` enthält intern ein transparentes `JTextArea`. Dadurch bleibt der Text trotz `Graphics2D`-Blasenrahmen selektierbar und kopierbar.

## Agentenaktivität starten und aktualisieren

Den Rückgabewert als Handle für genau diese Aktivität aufbewahren:

```java
AgentActivityBubblePanel activity = transcript.startAgentActivity(
        "Herstellerseite öffnen",
        "Die offizielle Produktseite soll die technischen Angaben verifizieren.");
```

Eine laufende Aktivität kann aktualisiert werden:

```java
transcript.updateAgentActivity(
        activity,
        "Datenblatt lesen",
        "Die dokumentierten Fähigkeiten werden mit den Modellangaben verglichen.");
```

## Agentenaktivität abschließen

Erfolg:

```java
transcript.completeAgentActivity(activity, "Produktdaten verifiziert");
```

Fehler:

```java
transcript.failAgentActivity(activity, "Herstellerseite nicht erreichbar");
```

Abbruch:

```java
transcript.cancelAgentActivity(activity, "Recherche abgebrochen");
```

Nach dem Aufruf läuft die Abschlussanimation selbstständig. Erst nach dem aufsteigenden Ergebnistext entfernt der Verlauf die Aktivitätszeile.

## ACP-/MCP-Zuordnung für die spätere Agentenintegration

Die UI sollte fachliche Agentenereignisse erhalten und keine Infrastrukturtexte selbst zusammensetzen. Eine spätere Adapterklasse kann beispielsweise folgende Ereignisse abbilden:

| Agentenereignis | UI-Aufruf |
|---|---|
| Tool-Aktion begonnen | `startAgentActivity(...)` |
| Begründung oder Phase geändert | `updateAgentActivity(...)` |
| Tool-Aktion erfolgreich | `completeAgentActivity(...)` |
| Tool-Aktion fehlgeschlagen | `failAgentActivity(...)` |
| Tool-Aktion abgebrochen | `cancelAgentActivity(...)` |

Für parallele Aktionen jeweils das zurückgegebene `AgentActivityBubblePanel` getrennt speichern. Dadurch wird immer die richtige Blase aktualisiert oder beendet.

## Threading

Alle verändernden Methoden von `BubbleTranscriptPanel` müssen auf dem Swing Event Dispatch Thread aufgerufen werden. Netzwerk-, Streaming- oder Agenten-Callbacks deshalb über `SwingUtilities.invokeLater(...)` weiterreichen:

```java
SwingUtilities.invokeLater(new Runnable() {
    public void run() {
        transcript.appendAssistantDelta(delta);
    }
});
```

## Vorschau starten

In IntelliJ die `main`-Methode dieser Klasse starten:

```text
com.aresstack.askai.ui.bubble.BubbleComponentsShowcase
```

Die Vorschau zeigt:

- eine rechts angeordnete Benutzerblase,
- eine links angeordnete Assistentenblase,
- die laufende Agentenblase,
- das Platzen,
- die nach oben steigende Ergebniszusammenfassung.

## Vorgesehene spätere Erweiterungen

Die Komponenten sind absichtlich noch unabhängig von Markdown und ACP. Sinnvolle spätere Adapter sind:

- `MessageContentRenderer` für Markdown und Codeblöcke,
- `AgentActivityPresenter` als Adapter von ACP-/MCP-Ereignissen,
- Freigabekomponenten für Agentenaktionen, die Benutzerzustimmung benötigen,
- persistierbarer Aktivitätsverlauf, falls abgeschlossene Agentenschritte nicht vollständig verschwinden sollen.

## Dateien für die Integration

```text
askai-app/src/main/java/com/aresstack/askai/ui/bubble/BubbleSide.java
askai-app/src/main/java/com/aresstack/askai/ui/bubble/BubblePalette.java
askai-app/src/main/java/com/aresstack/askai/ui/bubble/SpeechBubblePanel.java
askai-app/src/main/java/com/aresstack/askai/ui/bubble/BubbleMessageRow.java
askai-app/src/main/java/com/aresstack/askai/ui/bubble/AgentActivityBubblePanel.java
askai-app/src/main/java/com/aresstack/askai/ui/bubble/BubbleTranscriptPanel.java
askai-app/src/test/java/com/aresstack/askai/ui/bubble/BubbleComponentsTest.java
askai-app/src/test/java/com/aresstack/askai/ui/bubble/BubbleComponentsShowcase.java
doc/chat-bubble-components-readme.md
```
