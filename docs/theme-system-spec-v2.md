# Theme-System v2 „Arcane Knowledge“ — Analyse & verbindliche Implementierungs-Spezifikation

Stand: 2026-10-29, Branch `arch` @ `8eef9ba6` (enthält v1: `AskAiTheme`, `ComicTheme.install`,
`ComicButtonUI`). Ersetzt `docs/theme-system-spec.md` (v1), das als Historie bleibt.
Zielgruppe: eine Implementierungs-Session OHNE Vorkontext. Dieses Dokument ist verbindlich.
Abweichungen nur unter „Implementation notes“ am Ende dokumentieren, nicht selbst entscheiden.

## 0. Warum v2 — die Mängel von v1 (User-Abnahme gescheitert)

1. **Controls nicht umgestellt.** Der User hat ausdrücklich verlangt, dass ALLE Controls zuerst auf
   die zentralen Comic-Controls umgestellt werden. v1 hat das durch globale UIManager-Defaults
   ersetzt. Das ist zu wenig: Checkboxen, Combos, Spinner, Tabs, Textfelder sehen weiter nach Metal aus.
2. **Hintergründe weiterhin Swing-Grau.** Die Arcane-Fläche `0xF7F5F0` ist vom Metal-Grau kaum zu
   unterscheiden.
3. **Chat-Bubbles ohne passende Akzente.** Sie waren ausgeschlossen.
4. **Brauner Wolkenhimmel.** `SKY_BAR_SURFACE`/`SKY_TOP` wurden mechanisch „blau → gold“ übersetzt.
   Ein Himmel ist nie braun. Gewünscht: Sonnenuntergang, Orange-Rot.
5. **Keine Theme-Auswahl in den Settings.** Nur eine JVM-Property.
6. **Eigenmächtige Kürzungen der Anforderungen.** Der User verlangt: Theme-Wechsel ZUR LAUFZEIT
   und das Theme EINHEITLICH FÜR ALLES (auch die ~145 verstreuten Farben). Beides ist Pflicht.
   Diese Spec kürzt nichts davon; die Implementierung auch nicht.

## 1. Ist-Analyse (verifiziert)

### 1.1 Roh-Swing in App + Plugin (`askai-app/src/main`, `research-agent-ui-plugin/src/main`)

| Typ | `new …(` | Comic-Pendant heute | Quelle für Pendant |
|---|---|---|---|
| JButton | 101 | ComicButton (9×) | vorhanden |
| JTextField | 104 | — | NEU zu bauen |
| JPasswordField | 4 | — | NEU (Variante von ComicTextField) |
| JCheckBox | 44 | — | Port: enterprise `ComicFormControls.install(JCheckBox)` |
| JComboBox | 45 | — | Port: enterprise `ComicComboBoxUI` |
| JSpinner | 24 | — | Port: enterprise `ComicFormControls.install(JSpinner)` |
| JTextArea | 29 | — | Port: enterprise `PlaceholderTextArea` + NEU `ComicTextArea` |
| JScrollPane | 59 | ComicScrollPane (2×) | vorhanden |
| TitledBorder | 27 | — | NEU `ComicBorder.titled(…)` |
| JTabbedPane | 9 | — | NEU `ComicTabbedPaneUI` |
| JList | 9 | — | NEU `ComicListSupport.style` (Muster `ComicTableSupport`) |
| JTable | 4 | ComicTableSupport | vorhanden |
| JProgressBar | 5 | — | NEU `ComicProgressBarUI` |
| JSplitPane | 4 | ComicSplitPane | vorhanden |
| JSlider | 2 | — | Port: enterprise `ComicFormControls.install(JSlider)` |
| JToggleButton | 1 | — | Port: enterprise `ComicToggleButton` |
| JSeparator | 2 | — | via UIManager reicht |
| JRadioButton | 0 | — | (nicht benötigt) |

44 Dateien enthalten diese Controls. Die größten: `ResearchRuntimeSettingsPanel` (54),
`OllamaChatPanel` (54), `VideoSettingsDialog` (42), `OllamaInstallPanel` (31),
`DataForSeoSettingsPanel` (23), `ResearchSourcesView` (19), `AudioProcessingTestPanel` (18),
`ProxyPanel` (17), `OllamaConfigPanel` (15), `FilterDialog` (12), `AudioProcessingPanel` (10).

### 1.2 Portierbare Quelle: `C:\Projects\enterprise-ai-client\comic-controls`

Package `com.aresstack.enterpriseai.ui.comic.*`. Java-8-sauber (geprüft), nur JDK-Abhängigkeiten.
Porten heißt: Package nach `com.aresstack.comiccontrols.*` umbenennen und JEDE hartkodierte Farbe,
jedes `ResearchUiPalette.X` und jedes `ComicPalette.defaultPalette()` auf die Token aus
`AskAiTheme.active()` umstellen (Abschnitt 3). Zu portieren (NEU):

- `control/ComicFormControls` (install für JCheckBox, JSlider, JSpinner, JToggleButton)
- `control/ComicComboBoxUI` (braucht `paint/StrokeIcon`, `paint/ComposerIcons`)
- `control/ComicToggleButton`, `control/PlaceholderTextArea`
- `paint/StrokeIcon`, `paint/ComposerIcons`, `paint/CountBadgeIcon`, `paint/SkyClouds`
- `theme/ColorContrast` (WCAG-Kontrast, wird für Tests genutzt, Abschnitt 6)

Zusammenführen in bestehende Klassen (SOURCE ist dort weiter):
`ComicButton` (gestrichelter Fokusring), `ComicScrollBarUI` (eigene Thumb-Farben),
`ComicOverlayPanel` (`setPlateLimit`), `ResearchPillDropdown` (Tastatur + Accessibility).

NICHT portieren: `ComicWindow*` (rahmenlose Fenster), `Composer*Button`, `ReadAloudOrb`,
`SidebarIcons`, das Bubble-Package (AskAIs Bubbles sind in Teilen WEITER, siehe 1.4).

### 1.3 Theme-Wahl: Vorbild `C:\Projects\MainframeMate` (`app/src/main/java/de/bund/zrb/ui/theme/`)

MainframeMate: Combo in den Settings, Wert in JSON persistiert, Settings-Change-Listener ruft
`ThemeManager.applyTheme`, dieser setzt UIManager-Keys und ruft `updateComponentTreeUI` für alle
`Window.getWindows()`. Übernommen wird **das Muster** (Combo, Persistenz, ein Apply-Punkt), nicht
der Code (gekoppelt an LockerStyle, JNA, RSyntaxTextArea).

**Anforderung: Theme-Wechsel wirkt SOFORT zur Laufzeit, ohne Neustart.** Hindernis:
`ResearchUiPalette` besteht aus `public static final Color`-Konstanten, die das Plugin überall
direkt liest und teils per `setBackground(...)` an Komponenten hängt. Lösung ohne Umbau der
Verbraucher: E8 (lebende Farben). Nachgewiesen am 2026-10-29: Ein `JPanel` mit einer
`Color`-Subklasse, deren `getRGB()` den aktiven Token liest, malt nach dem Umschalten beim nächsten
`paint` die neue Farbe. (Innerhalb EINES `Graphics`-Objekts cacht `SunGraphics2D.setColor` dieselbe
Referenz — irrelevant, weil nach dem Umschalten neu gezeichnet wird.)

### 1.4 Bubbles

`askai-app/src/main/java/com/aresstack/askai/ui/bubble/BubblePalette.java`, 10 Rollen,
Default `windowsPhoneInspired()`. Nutzerfarben: `config/ChatColorSettings` (5 Farben, persistiert
unter `chat.color.*` in `AppConfigurationRepository`, Zeilen 77–81 und 148–158), Settings-Zeile
„Colors“ mit Reset. `ChatTranscript.paletteFrom(ChatColorSettings)` baut die Palette.

### 1.5 Himmel

`research-agent-ui-plugin/.../agent/ResearchOutOfScopeSky.java` ~Z. 379–393 malt EINE Farbe
(`SKY_TOP`) mit Alpha-Rampe 242 → 205 → 105 → 0. Wolken-Chips: `CLOUD_*` (~Z. 552, 617).

### 1.6 Startpfad und Settings-Dialog

- `askai-app/.../AskAiApp.java:24` lädt `AppConfigurationRepository`, Z. 29–32 `invokeLater`
  → `new AskAiFrame(...)`. `AskAiFrame.java:267` ruft `ComicTheme.install(AskAiTheme.active())`.
- Settings-Dialog: `OllamaChatPanel` (ab ~Z. 925): `SETTINGS_CATEGORIES`-Array + `CardLayout`,
  Karten über `cards.add(settingsCard(...), SETTINGS_CATEGORIES[i])`.

## 2. Verbindliche Entscheidungen

- **E1 — Theme-Quelle und Umschalten.** `AskAiTheme` bekommt
  `public static void setActive(AskAiTheme theme)` (EDT, beliebig oft) und
  `addChangeListener(Runnable)` / `removeChangeListener(Runnable)`. `active()` liefert das zuletzt
  gesetzte Theme. Startwert: JVM-Property `askai.theme`, sonst Konfigurationswert `ui.theme`,
  sonst `arcane`. Der Host setzt den Startwert in `AskAiApp.main` vor `invokeLater`.
  Danach ist das einzige Umschalt-Tor der Host-Dienst **`ThemeSwitcher.apply(AskAiTheme)`**
  (askai-app, Muster MainframeMate `ThemeManager.applyTheme` + `refreshWindow`):
  1. `AskAiTheme.setActive(theme)`,
  2. `ComicTheme.install(theme)` (UIManager-Defaults neu),
  3. für jedes `Window.getWindows()`: `SwingUtilities.updateComponentTreeUI(window)`, die
     JMenuBar extra, `invalidate/validate/repaint`,
  4. Change-Listener feuern (der bestehende `AskAiThemeService` leitet an Plugin-Listener weiter;
     `ThemeService.isDark()`/`color(...)` liefern danach die neuen Werte).
- **E2 — Settings.** Neue Kategorie **„Appearance“** im Settings-Dialog (Englisch, UI-Regel),
  direkt nach „General“. Inhalt: Label „Theme“ + Combo (selbst Comic-Combo) mit
  „Arcane Knowledge“ (`arcane`) und „Classic Comic“ (`classic`). Auswahl wirkt SOFORT über
  `ThemeSwitcher.apply` (Live-Vorschau beim Auswählen) und wird über den bestehenden
  Settings-Speicherweg als `ui.theme` in `AppConfiguration`/`AppConfigurationRepository`
  persistiert (Muster `chat.color.*`). KEIN Neustart-Hinweis.
- **E3 — Controls einheitlich auf Comic-Controls.** JEDE Instanziierung aus Tabelle 1.1 in App und
  Plugin wird ersetzt durch das Comic-Pendant. Stil: Subklasse, wo eine existiert
  (`ComicButton`, `ComicScrollPane`, `ComicSplitPane`, `ComicToggleButton`), sonst eine **Fabrik in
  der neuen Fassade `ComicControls`** (`comic-controls/.../control/ComicControls.java`), die den
  Swing-Typ zurückgibt, damit Feld- und Parametertypen unverändert bleiben:
  `checkBox(String)`, `comboBox()` / `comboBox(E[])` / `comboBox(ComboBoxModel)`, `spinner(SpinnerModel)`,
  `textField()` / `textField(int columns)` / `textField(String)` / `textField(String,int)`,
  `passwordField(...)`, `textArea(...)` (gleiche Überladungen wie JTextArea), `slider(...)`,
  `progressBar(...)`, `tabbedPane()`, `list(...)`, `titledBorder(String)`.
  Muster: `ComicControls.checkBox("x")` statt `new JCheckBox("x")`.
- **E4 — Aufrufstellen-Garantie per Test.** Neuer Test `RawSwingGuardTest` in `askai-app/src/test`:
  scannt die Quelltexte von `askai-app/src/main` und `research-agent-ui-plugin/src/main` (relativer
  Pfad über `user.dir`/Projektwurzel) per Regex nach `new (javax\.swing\.)?(JButton|JCheckBox|JComboBox|
  JSpinner|JTextField|JPasswordField|JTextArea|JTabbedPane|JList|JProgressBar|JSlider|JToggleButton)
  (<[^>]*>)?\(` und `createTitledBorder\(|new TitledBorder\(`. Treffer außerhalb einer expliziten
  Allowlist (Datei:Grund, in der Testklasse als Konstante) lassen den Test fehlschlagen. Die
  Allowlist ist am Ende leer oder hat höchstens begründete Einzelfälle (z. B. Renderer-Komponenten).
  Anonyme Subklassen (`new JButton("x") { ... }`) zählen ebenfalls und werden auf
  `new ComicButton("x") { ... }` umgestellt, sofern sie `paintComponent` nicht selbst übernehmen.
  Eigene Button-Klassen mit `setContentAreaFilled(false)` (ResearchPillButton, MarkdownActionButton
  usw.) sind keine `new JButton(`-Treffer und bleiben unverändert.
- **E5 — v1 bleibt die Basis.** `ComicTheme.install` (globale UIManager-Defaults) bleibt als
  Sicherheitsnetz für Dialoge von Swing selbst (JOptionPane, JFileChooser) und für Labels/Panels.
  `ComicButtonUI` bleibt global. Comic-Controls, die selbst malen, setzen die Opt-out-Property
  (`ComicButtonUI.PLAIN_BUTTON_PROPERTY`), wie `ComicButton` schon heute.
- **E6 — Bubbles folgen dem Theme.** Neue Token-Gruppe „bubble“ in `AskAiTheme` (10 Rollen wie
  `BubblePalette`). `BubblePalette.windowsPhoneInspired()` bleibt als Classic-Wert;
  `ChatColorSettings.defaults()` liefert künftig die Bubble-Token des AKTIVEN Themes (askai-app darf
  comic-controls benutzen). **Migrationsregel für gespeicherte Nutzerfarben:** Ein gespeicherter
  `chat.color.*`-Wert, der GENAU dem alten Classic-Default entspricht, gilt als „nicht angepasst“
  und wird beim Laden durch den Theme-Default ersetzt. Abweichende Werte sind echte
  Nutzerentscheidungen und bleiben. Der Reset-Knopf in den Settings setzt auf die Theme-Defaults.
- **E8 — Lebende Farben (Pflicht-Mechanik für Live-Wechsel).** Neue Klasse
  `comic-controls/.../theme/ThemeColor extends java.awt.Color`, erzeugt über
  `ThemeColor.of(String tokenKey)`. `getRGB()` liefert den Wert des Tokens im AKTUELL aktiven
  Theme (alle anderen Color-Getter leiten sich in `Color` von `getRGB()` ab; zusätzlich
  `getColorComponents/getRGBComponents/getComponents/getRGBColorComponents` überschreiben, damit
  sie ebenfalls live sind). `equals/hashCode` bleiben die von `Color` (wertbasiert). Verwendung:
  - Jede `ResearchUiPalette`-Konstante ist ein `ThemeColor` (Name/Typ/Sichtbarkeit unverändert).
  - `ComicPalette.defaultPalette()` liefert eine Palette aus `ThemeColor`s.
  - Bubble-Defaults (E6) und alle in T7 migrierten Farben sind `ThemeColor`s.
  - UIManager-Werte bleiben feste `ColorUIResource` und werden in `ThemeSwitcher` neu installiert.
  **Regel:** Keine Farbe, die aus einem Token abgeleitet ist (`brighter()`, `new Color(t.getRed(),…,
  alpha)`, Mischfarben), darf in einem Feld/Konstruktor GECACHT werden — Ableitung erst beim Malen.
  Gefundene Caches werden in T7 auf Ableitung-beim-Malen umgestellt.
- **E7 — Himmel als Sonnenuntergang.** Neuer Token `SKY_MID` in der Research-Gruppe (Konstante
  `ResearchUiPalette.SKY_MID`). `ResearchOutOfScopeSky` malt einen Zwei-Farben-Verlauf: oben
  `SKY_TOP` (Alpha 242), bei `contentStop*0.55` `SKY_MID` (Alpha 205), bei `contentStop` `SKY_MID`
  (Alpha 105), unten `SKY_MID` (Alpha 0). Classic: `SKY_MID == SKY_TOP` → Classic sieht
  pixelgleich aus wie heute.

## 3. Token-Werte (Arcane; Classic = heutige Werte unverändert)

Die Arcane-Werte aus v1 werden ersetzt. Leitidee: Pergament statt Grau, Marine als Tinte,
Gold als Akzent, Rot bleibt Funktionsfarbe.

### 3.1 Comic-Gruppe (helle Seite)

| Token | Classic | Arcane v2 |
|---|---|---|
| surface | `0xF4F4F4` | `0xEFE4CF` (Pergament, deutlich kein Grau) |
| ink | `0x252525` | `0x24304A` |
| navigationBlue | `0x1676D2` | `0x2D4F86` |
| agentPetrol | `0x15827A` | `0x2A6F6B` |
| accentYellow | `0xF2C94C` | `0xE9B949` |
| accentOrange | `0xE39A18` | `0xD08A2C` |
| accentRed | `0xC94C4C` | `0xC94C4C` |
| buttonPlate | `0xFFFFFF` | `0xFBF6EA` |
| warmFocus | `0xFFF8E1` | `0xFFF3D6` |
| fieldBg | `0xFFFFFF` | `0xFFFCF4` |
| fieldBgQuiet | `0xFAFAFA` | `0xF8F1E2` |
| fieldBorder | `0xC6C6C6` | `0xCDBE9E` |
| hintText | `0x888888` | `0x857A63` |
| hintTextSoft | `0xAAAAAA` | `0xA89C82` |
| overlayDim | `rgba(0,0,0,110)` | `rgba(11,18,32,120)` |

### 3.2 Research-Gruppe (dunkle Seite + Himmel)

Dunkle Flächen, Gold-Akzente, `DANGER_RED`, `TEXT_*`: Werte aus v1 (`AskAiTheme.arcane()` heute)
BEHALTEN. Ersetzt werden nur die hellen Licht- und Himmel-Token:

| Konstante | Classic | Arcane v2 |
|---|---|---|
| SKY_TOP | `0xF5FAFE` | `0xE4572E` (Orange-Rot) |
| SKY_MID (neu) | = SKY_TOP | `0xF4A259` (Abendgold) |
| SKY_BAR_SURFACE | `0xBFDCF2` | `0xF3B07A` |
| SKY_CAPTION | `0x6E87A2` | `0x7A3B22` |
| CLOUD_SURFACE | `0xF7FBFF` | `0xFFF6EC` |
| CLOUD_BORDER | `0xAFCDE8` | `0xEDB48A` |
| CLOUD_TEXT | `0x40556B` | `0x5A2E1C` |
| CLOUD_HOVER_SURFACE | `0xEAF4FC` | `0xFFEBD6` |
| CLOUD_HOVER_BORDER | `0x8FB8DE` | `0xE08F5C` |
| CLOUD_CLOSE | `0x72879A` | `0x9A5A3C` |
| LIGHT_CONTROL_BG | `0xFFFFFF` | `0xFFFCF4` |
| LIGHT_CONTROL_BORDER | `0xC9CED6` | `0xCDBE9E` |

### 3.3 Bubble-Gruppe (neu)

| Rolle | Classic (= `windowsPhoneInspired()`) | Arcane v2 |
|---|---|---|
| transcriptBackground | `0xF4F4F4` | `0xF6EEDD` |
| userBackground | `0x1676D2` | `0x24406E` (Marine) |
| userForeground | `0xFFFFFF` | `0xFFF8EA` |
| assistantBackground | `0x15827A` | `0x2A6F6B` |
| assistantForeground | `0xFFFFFF` | `0xFFF8EA` |
| activityBackground | `0xF2C94C` | `0xE9B949` (Gold) |
| activityForeground | `0x252525` | `0x24304A` |
| activityAccent | `0xE39A18` | `0xB9771F` |
| failureAccent | `0xC94C4C` | `0xC94C4C` |
| infoForeground | `0x6B6F76` | `0x7A7262` |

(Classic-Werte vor dem Pinnen mit `BubblePalette.windowsPhoneInspired()` und
`ChatColorSettings.defaults()` im Code abgleichen. Bei Abweichung gilt der Code-Wert.)

## 4. Arbeitspakete (je ein Commit, Reihenfolge verbindlich)

**T1 — Port der enterprise-Controls** in `comic-controls` (1.2), Farben auf Token. Tests: je Control
ein Headless-Test (Install setzt UI/Icons, Farben = Token des aktiven Themes).

**T2 — Neue Controls + Fassade:** `ComicControls` (E3), `ComicTextFieldUI` bzw. Border+Insets für
JTextField/JPasswordField (abgerundete Kontur `fieldBorder`, Fokus-Kontur `navigationBlue`, Hintergrund
`fieldBg`), `ComicTabbedPaneUI` (Reiter als Plates wie ComicButton, aktiver Reiter `accentYellow`,
Inhalt `surface`), `ComicProgressBarUI` (abgerundet, Füllung `accentOrange`), `ComicListSupport`,
`ComicBorder.titled(String, palette)` (Tintenlinie gerundet, Titel in `ink` bold). Tests je Control.

**T3 — Token v2 + lebende Farben + Umschalten:** Werte aus Abschnitt 3, `SKY_MID`, Bubble-Gruppe,
`ThemeColor` (E8), `setActive`/Listener (E1), Default `arcane`. Tests: Classic-Pins erweitern
(Bubble-Gruppe, `SKY_MID == SKY_TOP`); `ThemeColor` liefert nach `setActive` den neuen Wert;
Headless-Live-Test: `JPanel` mit `ResearchUiPalette.DARK_BASE` als Hintergrund in ein
`BufferedImage` malen, umschalten, erneut malen → Pixel = neuer Token.

**T4 — `ThemeSwitcher` + Settings „Appearance“ + Persistenz + Startpfad** (E1, E2). Tests:
Roundtrip `ui.theme` in `AppConfigurationRepository`; `ThemeSwitcher.apply` setzt aktives Theme,
installiert UIManager neu und feuert Listener genau einmal; Startwert-Vorrang Property > Config >
Default.

**T5 — Bubbles + Himmel** (E6, E7). Tests: Migrationsregel (Classic-Default gespeichert → Theme-Wert;
abweichend → bleibt); Himmel-Verlauf: Farbstopps aus Token (Painter-Logik in pure Methode
extrahieren und testen).

**T6a–T6e — Aufrufstellen-Migration (DER TEURE TEIL)**, je Gruppe ein Commit, nach jedem Commit
`:askai-app:compileJava :research-agent-ui-plugin:compileJava`:
- T6a: `research-agent-ui-plugin` komplett (ResearchRuntimeSettingsPanel, DataForSeoSettingsPanel,
  ResearchSourcesView, LegacyBrowserSearchSettingsPanel, SearchEngineOrderEditor,
  ResearchSettingsContribution, ResearchDocumentViewContribution, Outline/Concept-Views, Rest)
- T6b: `OllamaChatPanel`
- T6c: `VideoSettingsDialog`, `RecordVideoDialog`, `Audio*Panel`, `BatchTranscriptionPanel`
- T6d: `OllamaInstallPanel`, `OllamaConfigPanel`, `OllamaModelsPanel`, `OllamaLibraryPanel`,
  `OllamaActionsPanel`, `OllamaModelCard`, `AiModelsPanel`, `ProxyPanel`, `FilterDialog`
- T6e: Rest + `RawSwingGuardTest` (E4) scharf schalten (leere bzw. begründete Allowlist)

**T7a–T7d — Alle verstreuten Farben auf Token (Pflicht, „einheitlich für alles“).** Jede
`new Color(…)`, `Color.WHITE/BLACK/GRAY/…`-Verwendung und jede Hex-Farbe in `askai-app/src/main`
und `research-agent-ui-plugin/src/main` (~145 Stellen) wird ein `ThemeColor` aus `AskAiTheme`.
Gruppen: T7a `research-agent-ui-plugin` (alle Pakete), T7b `askai-app/ui` (Chat, Composer,
Model-Browser, Settings-Panels, Rest von `ui`), T7c `askai-app/ui/markdown` (Markdown/Mermaid/Code),
T7d Rest (`ui/bubble`, `notify`, `config`, `video`, `batch`). Zuordnungsregeln (verbindlich):
1. Entspricht der Literalwert einem vorhandenen Classic-Token → diesen Token verwenden.
2. Sonst neuen Token in einer Gruppe pro Bereich anlegen (`chat.*`, `models.*`, `markdown.*`,
   `research.*`, …), Name = Rolle („statusOkText“, „codeBlockBackground“), NICHT die Farbe.
   Classic-Wert = der bisherige Literalwert (Classic bleibt pixelgleich).
3. Arcane-Wert nach Familie: Grau-/Weißflächen → Pergament-Familie (`surface`/`buttonPlate`/
   `fieldBg`); Schwarz/Dunkelgrau-Text → `ink`; Blau → Marine-Familie (`navigationBlue`);
   Gelb/Orange/Amber → Gold-Familie (`accentYellow`/`accentOrange`); Grün/Petrol → `agentPetrol`-
   Familie; Rot → unverändert (Funktionsfarbe); Lila → Gold (wie `PURPLE_*`).
4. Statusfarben (Erfolg/Warnung/Fehler) behalten ihre Bedeutung: Erfolg grün-petrol, Warnung
   Gold, Fehler Rot — nur im Theme-Ton.
5. Halbtransparenzen (`new Color(r,g,b,a)`): Token für die Grundfarbe, Alpha bleibt im Code und
   wird beim Malen kombiniert (E8-Regel).
6. Vom User gewählte Farben (ChatColorSettings, Farbwähler) sind KEINE Literale und bleiben
   unberührt (E6 regelt deren Defaults).
Test: `RawColorGuardTest` (wie E4) scannt beide Module auf `new Color(`/`new java.awt.Color(`/
`Color.(WHITE|BLACK|GRAY|LIGHT_GRAY|DARK_GRAY|RED|GREEN|BLUE|YELLOW|ORANGE|PINK|MAGENTA|CYAN)`
außerhalb von comic-controls; Allowlist leer oder einzeln begründet.

Regeln für T6: rein mechanisch (Konstruktor → Fabrik/Subklasse, gleiche Argumente). KEINE
Layout-, Text- oder Logikänderung. Wo ein Control danach explizit `setBackground`/`setForeground`/
`setBorder` mit einer HARDKODIERTEN Farbe bekommt, die den Comic-Look überschreibt: Zeile entfernen,
wenn sie nur „Swing-Standard nachbaut“ (weiß, grau, schwarz); sonst stehen lassen und unter
„Implementation notes“ auflisten.

## 5. Nicht in diesem Auftrag

Nur Dinge, die der User NICHT gefordert hat:
- **Rahmenlose Fenster** (`ComicWindow*`).
- **Fremdbefunde aus dem GUI-Gate**, je eigenes Ticket: deutsche statische Texte in
  `ResearchSourcesView` (Titel, Status, Relevanz, Speichern, Neu laden, Ausschließen, Volltext …,
  Verstoß gegen die UI-Englisch-Regel) und Mojibake in Quellentiteln (`Ã¢Â€Â¦` statt `…`).

## 6. Leitplanken (verbindlich)

1. Java 8, keine neuen Dependencies. comic-controls bleibt nur von `java.*`/`javax.*` abhängig.
2. Alle statischen UI-Texte Englisch, Kommentare/Javadoc Englisch.
3. Die App NIE selbst starten/beenden. GUI-Gates macht der User.
4. Gezielte Builds: `:comic-controls:test`, die neuen/geänderten Testklassen in `:askai-app:test`
   und `:research-agent-ui-plugin:test` per `--tests`, `compileJava` beider Module. KEINE vollen
   Suiten (Live-Tests treffen Ollama automatisch). Plugin geändert → am Ende einmal
   `:research-agent-ui-plugin:installDevPlugin`.
5. Jeder Commit auf `arch`, sofort pushen. NICHT auf main (Merge erst nach Review + GUI-Gate).
6. UIManager-Installation nur im Host. Das Plugin bekommt Comic-Controls über `compileOnly`
   (comic-controls ist host-provided und darf NICHT in `lib/` des Plugins landen).
7. Kontrast: ein Test mit `ColorContrast` prüft für Arcane und Classic, dass Text auf Fläche
   (ink/surface, ink/fieldBg, userForeground/userBackground, assistantForeground/assistantBackground,
   activityForeground/activityBackground, CLOUD_TEXT/CLOUD_SURFACE, TEXT_PRIMARY/DARK_BASE)
   mindestens WCAG 4.5:1 erreicht. Scheitert ein Paar, Wert dokumentieren, NICHT eigenmächtig ändern.

## 7. Review-Checkliste

- [ ] `RawSwingGuardTest` grün, Allowlist leer oder jeder Eintrag begründet.
- [ ] Portierte Klassen: kein `com.aresstack.enterpriseai`, keine hartkodierten Farben, nur Token.
- [ ] Classic pixelgleich zu heute (Pins inkl. Bubble-Gruppe, `SKY_MID == SKY_TOP`).
- [ ] Theme-Wechsel in „Appearance“ wirkt SOFORT in allen offenen Fenstern (ThemeSwitcher),
      wird als `ui.theme` gespeichert und beim Start wieder aktiv; kein Neustart nötig.
- [ ] `ResearchUiPalette`, `ComicPalette.defaultPalette()`, Bubble-Defaults und alle T7-Farben
      sind `ThemeColor`s; keine abgeleiteten Farben gecacht; Live-Test grün.
- [ ] `RawColorGuardTest` grün (T7 vollständig).
- [ ] Bubble-Migrationsregel getestet; Reset → Theme-Defaults.
- [ ] Himmel-Verlauf aus `SKY_TOP` → `SKY_MID`, Classic unverändert.
- [ ] Kontrasttest vorhanden.
- [ ] T6 rein mechanisch: keine Text-/Layout-/Logikänderung im Diff.
- [ ] comic-controls nicht im Plugin-`lib/`; nur gezielte Builds; alles auf `arch`; keine App-Starts.
- [ ] „Implementation notes“ ergänzt.

## Implementation notes

### T1
- `AskAiTheme` internally became a flat token table (`group.role` keys, `color(String)`), so later packages can
  add groups without new typed fields; the v1 typed accessors stay as views. One new comic token was needed for
  the port: `comic.placeholderText` (classic `0x6B6F76` = the enterprise `PlaceholderTextArea` literal; arcane
  `0x7A7262` = the arcane `infoForeground` of 3.3).
- White plates/tracks of the ported controls map to `comic.buttonPlate` (buttons, toggles, badge digits, sky ghost
  clouds) or `comic.fieldBg` (check box, slider track, spinner plate — field-like controls).
- Ported `ResearchUiPalette.X` reads became `token("research.X")` via one private helper per class; T3 makes those
  helpers live (`ThemeColor`).
- German comments of `SkyClouds`/`ColorContrast` translated (rule 6.2).
- `ResearchPillDropdown` is now identical to the enterprise version (only additions); `ComicOverlayPanel` keeps its
  own `CloseButton` (enterprise extends the non-ported `ComicWindowCloseButton`); only `setPlateLimit`/`closeButton()`
  were merged.

### T2
- Runtime switching (E1 step 3, `updateComponentTreeUI`) would reset every `setUI(...)` delegate to the look and
  feel's. The `ComicControls` factories therefore return instances (anonymous subclasses or small package-private
  subclasses of the Swing type) whose `updateUI()` re-applies the comic delegate; field/parameter types stay the
  Swing types. Combo boxes keep a caller-installed renderer across `updateUI()`.
- Text/password fields: `ComicTextFieldUI`/`ComicPasswordFieldUI` + `ComicFieldBorder` (rounded, `fieldBorder`,
  focus `navigationBlue`); the field is non-opaque and paints a rounded plate; read-only/disabled → `fieldBgQuiet`.
  Caller-set colors/borders win (only `UIResource` values are replaced). The defaults are applied in `installUI`
  (after `BasicTextUI` re-applies its own editable/enabled background).
- `ComicTextArea` (new, as table 1.1 asks): `fieldBg` paper, ink text/caret, yellow selection, no contour (text
  areas live in scroll panes).
- `ComicTheme.install` additionally sets `TextField/TextArea/PasswordField/FormattedTextField.inactiveBackground`
  and `.disabledBackground` = `fieldBgQuiet` (Swing switches to them on `setEditable(false)`/`setEnabled(false)`;
  without them Metal grey shows through).
- `ComicListSupport.style` uses the table's selection wash (navigation blue mixed into the paper), as
  `ComicTableSupport` does, rather than `accentYellow`.
- Factory overloads mirror the Swing constructors actually used at the call sites plus the remaining standard
  ones; `checkBox(Icon)` gets the comic glyph (the check box icon is the comic glyph by design).

### T3
- `AskAiTheme.setActive` is deliberately SILENT; listeners fire through `AskAiTheme.fireChanged()`, which the
  host switch gate (`ThemeSwitcher`, T4) calls after UIManager re-install and window refresh — so listeners see a
  fully applied theme (E1 order 1 → 4) and `ThemeSwitcher.apply` fires exactly once.
- `ThemeColor` also offers live DERIVED colors (`withAlpha(base, a)`, `mix(base, target, f)`): they are the
  mechanism used for E8's "derive at paint time" wherever a derived color has to be handed to a component as a
  value (e.g. `ComicTableSupport` grid/selection/header colors, `ComicListSupport` selection wash).
- `ThemeColor.hashCode()` stays `Color`'s (as E8 says) and therefore reflects the value at creation time while
  `equals` is live — `ThemeColor`s should not be used as hash keys across theme switches.
- comic-controls itself had further cached literals that v1 did not list and that would not follow a live switch:
  `ComicSectionPanel` default plate (`Color.WHITE` → `comic.buttonPlate`), `ResearchPillButton` pressed foreground
  (`Color.WHITE` → `comic.buttonPlate`), `ComicTableSupport` (`Color.WHITE` → `comic.fieldBg`, mixes live); the
  search/find bars now receive `ThemeColor`s.
- Contrast test (6.7) is in `ThemeContrastTest`: all 14 pairs (7 per theme) reach ≥ 4.5:1 — lowest classic
  user bubble 4.61, classic assistant bubble 4.66; arcane minimum 5.54 (assistant bubble).

### T4
- `ThemeSwitcher` lives in `askai-app` (`ui.theme.ThemeSwitcher`): `apply` = `setActive` → `ComicTheme.install` →
  `updateComponentTreeUI` per window (+ JMenuBar) + `invalidate/validate/repaint` → `AskAiTheme.fireChanged()`
  (exactly once). `startTheme(property, config)` is the pure precedence (known property value > known stored
  value > default arcane); an unknown property value counts as absent.
- `AskAiThemeService` registers itself as `AskAiTheme` change listener in its constructor and forwards to its plugin
  listeners; `color(key)`/`isDark()` read the UIManager defaults, which `ThemeSwitcher` re-installs first.
- Persistence: `AppConfiguration.uiTheme` (`""` = not chosen) + `withUiTheme`, all `with*` copies carry it,
  `AskAiModel` buffers it like the chat colors. Found on the way (pre-existing): `ProxyPanel` and
  `OllamaInstallPanel` save through the plain `AppConfiguration` constructor, which drops fields such as chat colors
  and model selections. So that this can never wipe the theme, `AppConfigurationRepository.save` keeps the stored
  `ui.theme` when the saved configuration carries none (pinned). The pre-existing loss of the other fields is NOT
  fixed here (outside this order; separate ticket).
- "Appearance" is inserted after "General"; the existing jump to "Audio & Dictation" (`openSpeechOutputSettings`)
  was moved to the new index. The theme combo is a `ComicControls.comboBox`; choosing an entry applies it at once
  and saves.

### T5
- Bubble defaults are living (`ThemeColor` of `bubble.*`), so the save path must not freeze them: the repository
  persists a `chat.color.*` value ONLY when it is a real user choice (not a `ThemeColor`); theme defaults stay
  absent and keep following the theme. Load applies the E6 migration rule (`ChatColorSettings.fromStored`):
  missing/invalid or exactly the OLD classic default of that role → theme default; anything else stays.
- `BubblePalette.windowsPhoneInspired()` keeps its classic values but now reads them from
  `AskAiTheme.classic()` (no literals); new `BubblePalette.themed()` (living) is the transcript default and the
  base of `ChatTranscript.paletteFrom` (activity/failure/info roles follow the theme).
- Sky: the pure gradient logic lives in comic-controls `SkyClouds.skyFractions/skyColors/ghostCloudColor`; both the
  plugin `ResearchOutOfScopeSky` and `SkyClouds.paintSky` use it. Classic (`SKY_MID == SKY_TOP`) yields the historic
  stops exactly (pinned); the faint background clouds use the light plate (white in classic).

### T6 (all groups)
- Migration is a script-driven 1:1 constructor swap (same arguments): `new JButton(` → `new ComicButton(`,
  `new JToggleButton(` → `new ComicToggleButton(`, the other types → `ComicControls.<factory>(` (explicit generic
  witness `ComicControls.<T>comboBox(`/`list(` where the call site named the type argument), titled borders →
  `ComicControls.titledBorder(`. Anonymous field subclasses → `new ComicTextField(..) {..}` /
  `new ComicPasswordField(..) {..}` (new public subclasses that pin the comic delegate; the factories return them too).
- So that the swap does not change layout or behavior, `ComicButton` got (in the T6a commit): a no-arg and an
  `Icon` constructor; an explicit caller `setMargin(...)` drives its padding (default stays 5/14); a caller's
  `setContentAreaFilled(false)` makes it a FLAT button without plate (as for a plain `JButton`); a caller-set
  background is used as the resting plate.
- Rule "hardcoded color on a converted control": removed where it only rebuilt the Swing default; kept (listed
  here, tokenized in T7) where it carries meaning.
  - T6a removed: `ResearchSourcesView` `commentArea.setBackground(WHITE)`.
  - T6a kept: `LegacyBrowserSearchSettingsPanel.problems` red/green status foreground (error/success meaning);
    `ResearchOutOfScopeSky.addField` cloud text/caret colors (tokens, not literals) and its empty border/opaque=false
    (the field sits inside a cloud-styled row by design).
- T6a also pins `ResearchSkyHostGeometryTest` to the classic theme: its paint-chain proofs assert classic colors
  ("white wash", "cool blue bar"), which the T3 arcane default (sunset sky) legitimately changed; the plugin tests
  of the changed classes ran for the first time in T6a.
  - T6b (`OllamaChatPanel`, 58 sites): no hardcoded color was set on a converted control; nothing removed or kept.
  - T6c (video/record dialogs, audio panels, batch transcription; 105 sites): no hardcoded color on a converted
    control; nothing removed or kept.
  - T6d (Ollama panels, `AiModelsPanel`, `ProxyPanel`, `FilterDialog`; 121 sites): `ComicToggleButton` got the
    `()` and `(String, boolean selected)` constructors of `JToggleButton` (used by the library/variant toggles).
    Kept (meaningful, tokenized in T7): `OllamaModelCard` "Test reranker"/"Use in chat" primary-blue foreground and
    "Delete" red foreground; `OllamaInstallPanel` token "Clear" red foreground and the invalid-file install button
    red foreground; `FilterDialog` orphan catalog check boxes in amber. Nothing removed.
  - T6e (remaining 17 files, 24 sites incl. bubble/markdown text areas, composer progress, slash/mention popups):
    flat buttons (`ChatAttachmentStrip`/`MarkdownPreviewTabs` "×", `CollapsiblePanel` header) stay flat through the
    `ComicButton` compatibility. Kept (tokenized in T7): `CollapsiblePanel` header slate foreground. Bubble text areas
    keep their explicit bubble text colors (they override the `ComicTextArea` defaults by design).
  - `RawSwingGuardTest` (askai-app, `ui.theme`) scans `askai-app/src/main` and `research-agent-ui-plugin/src/main`
    (root found by walking up from `user.dir`); the allowlist is EMPTY — no raw control is left.

### T7 (all groups)
- Group naming: plugin role tokens are `research.<lowerCamelRole>`; UPPER_SNAKE names under `research.` stay
  reserved for the `ResearchUiPalette` constants (`AskAiTheme.researchTokens()` lists only those). App groups:
  `chat.*`, `models.*`, `markdown.*`, `settings.*`, ... per area (see the token blocks in `AskAiTheme`). Every new
  token: classic = the former literal exactly; arcane = COPY of the arcane value of the family representative
  (rule 3), commented with the representative in `AskAiTheme.arcane()`.
- `ThemeColor` gained (T7a) `average(a, b)` (truncating, the exact arithmetic of the historic hand-written blends, so
  classic stays pixel-identical where `mix` would round 1 bit differently), `derive(description, supplier)` (any
  live formula, e.g. `ink.brighter()`), and `toHtmlHex(color)` for colors inside HTML strings (built at render time).
- T7a (plugin, 55 literals in 13 files, grep at zero, no allowlist):
  - National flag colors of the language flag icons (`research.flagGermany*`, `research.flagUnion*`) got their own
    tokens with arcane = classic: rule 3 would recolor the flags (navy/gold/petrol) and destroy their identity —
    deviation from rule 3, listed here for review (alternative: allowlist the flag painter).
  - White text on accent fills (petrol/red plates) maps to `bubble.assistantForeground` (the classic-white text role
    of the theme); white mix targets map to `comic.buttonPlate` (lifting a chip) or `comic.fieldBg` (fading to paper).
  - `research.engineListSelectionBackground/Foreground` follow rule 3 literally (navy/ink, weak contrast in arcane);
    only reached when the UIManager has no list selection colors (practically never, `ComicTheme` installs them).
  - `research.conceptCardMutedText` (0x999999, between hint and soft hint) → `comic.hintTextSoft`.
  - E8 cache fixed: `ResearchStateView` inactive title `ink.brighter()` is now `ThemeColor.derive(...)`.
- T7b (`askai-app/ui` without markdown/bubble; 123 literals + 5 HTML hex in 27 files, grep at zero, no allowlist).
  Groups `chat.*` (chat window, composer, workspace, history, attachments, popups, waveform), `audio.*` (pipeline
  canvas/inspector fallbacks), `app.*` (connection widget/status, collapsible header, icons), `models.*` (model
  browser, install, library, repository, filter, actions, about, speech-output models). The colors kept in T6d/T6e
  are tokens now. `ConnectionStatus` carries a token key (`getColorToken()`) instead of an int RGB. Stored mixes
  (history-row washes, delete-all pressed fill, rename border) are live `ThemeColor.mix`. HTML colors are built at
  render time via `ThemeColor.toHtmlHex`; the filter "coming soon" text uses the label's living foreground instead
  of a CSS color so it follows a live switch.
  Rule-3 deviations / judgment calls for review (all arcane values, classic untouched):
  - `chat.mentionLoaded*` (light green highlight behind ink text) keep the classic values: `agentPetrol` as a
    background would make the handles unreadable and no light-petrol token exists.
  - `models.statusWarningText` keeps the classic dark amber: the gold representatives reach only ~2.8–3.6:1 as text
    on parchment (classic ~4.8:1).
  - `models.capabilityTools` (brown) and `models.capabilityImage` (crimson) keep classic: no rule-3 family fits.
  - Readability substitutes instead of the literal family: `models.voiceHighlightBackground` and
    `models.addOnBannerBackground` (light tint washes) → `comic.warmFocus`; `models.unavailableBadge` (under white
    text) → `comic.hintText`; `models.installedBadge` (under white text) → `comic.ink`;
    `chat.composerRestBackground` → `comic.fieldBgQuiet` (stays distinguishable from the focused `fieldBg`);
    `chat.composerButtonDisabledFill` (under white text) → `comic.fieldBorder`.
  - `app.iconGlyph` (slate 0x426077) → `comic.ink`; black shade tokens (`chat.composerButtonHoverShade`,
    `chat.deleteAllPressedShade`) → `comic.ink`, so the pressed delete-all red gets a slight navy tint in arcane.
  - Arcane collisions: capability icons Audio/Insert both petrol, Text/Cloud both hint grey (shapes differ).
  - Literals equal by VALUE to a token of another role got their own role token (rule 2 over rule 1 when the role
    differs), e.g. model status green vs. connection dot green.
- T7c (`ui/markdown`, 23 literals in 5 files, grep at zero, no allowlist): group `markdown.*`. `MarkdownTheme`
  shades (code background, quote border, separators) are live mixes; its UIManager reads (`Label.foreground`,
  `TextArea.background`, ...) are live too (`ThemeColor.derive`), since `ThemeSwitcher` re-installs the defaults
  before repainting. Deviation for review: `markdown.viewerCanvasBackground` (the dark letterbox of the Mermaid
  viewer dialog, classic 0x1E1E1E) maps to the dark-side `research.WORKSPACE_SURFACE` in arcane instead of a
  parchment surface (rule 3 literally would turn the dark viewer light).
- T7d (`ui/bubble`, `notify`, `config`; `video`/`batch` had no literals): bubble `withAlpha` helpers are live
  `ThemeColor.withAlpha`; the floating-summary accent fallback is `chat.floatingSummaryAccent`; the info-line
  outcome marks (✔ success / ⟳ retry, HTML) are `chat.infoLineSuccessMark`/`chat.infoLineRetryMark` and are
  re-rendered in `BubbleTranscriptPanel.updateUI()` — `ThemeSwitcher` calls it on every switch, no listener needed.
  The tray fallback icon (`DesktopNotifier`) uses `research.ACCENT_BLUE` and `comic.buttonPlate`; the OS keeps an
  already created tray image until it is recreated (not a Swing component).
- `RawColorGuardTest` (askai-app, `ui.theme`) scans both modules for `new Color(`, `new java.awt.Color(`, the
  `Color` constants and `#RGB`/`#RRGGBB` inside string literals. Allowlist: ONE entry —
  `config/ChatColorSettings.parseHex` (parses the user-chosen chat colors from the settings file, rule 6).

### Verification at the end of T7d
- Green: `:comic-controls:test`; askai-app tests of `ui.*`, `config.*`, `notify.*` (64 classes incl.
  `RawSwingGuardTest`, `RawColorGuardTest`, `ThemeSwitcherTest`, `UiThemePersistenceTest`, `ChatColorSettingsTest`);
  plugin tests of every class touched in T6a/T7a plus the color/pixel tests. No full suites (live Ollama).
- `:research-agent-ui-plugin:installDevPlugin` ran once; comic-controls is not in any plugin `lib/`.
- Open: the user GUI gate (Appearance switch live in all windows, `ui.theme` persisted, both themes; the
  rule-3 judgment calls listed under T7a–T7c for review).

