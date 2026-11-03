# AskAI auf Java 21 – Migrationsplan (askai #31)

**Verbindliche Entscheidung (Angelo, 2026-11-02 14:44):** AskAI läuft vollständig auf Java 21. Die Aufteilung in eine
Java-8-Runtime und Java-21-Sidecars ist eine Altlast und wird abgebaut. Playwright läuft direkt in AskAI, ohne
Sidecar, ohne MCP-/HTTP-Brücke und ohne JSON-Serialisierung zwischen Java-Prozessen für die Suche. AskAI nutzt
`serp4j-core`, `serp4j-playwright` und das lokale Subprojekt `serp4j-playwright-google` direkt und übergibt bei
jeder Suche das aktuell gültige Playwright-`Browser`-Objekt (Serp4J ADR-020).

AskAI verwaltet zwei Camoufox-Instanzen:

| Browser | Modus | Zweck | Lebensdauer |
|---|---|---|---|
| Suchbrowser | headless | Google-Suche über Serp4J | dauerhaft, wiederverwendet, Neustart durch AskAI nach Absturz |
| Research-Browser | sichtbar | eigentliche Recherche, Seitennavigation | unabhängig vom Suchbrowser |

Serp4J startet keinen Browser und verwaltet keinen Lebenszyklus. Es wird keine neue Browserarchitektur entworfen:
Die vorhandene Implementierung wird umgezogen.

## Ist-Stand (main 5774bf6a)

### Prozesse
| Prozess | Java | Module | Gestartet von | Verbindung |
|---|---|---|---|---|
| AskAI-App (Swing, Plugin-Host) | 8 | `askai-app`, `research-agent-ui-plugin`, `agent-plugin-host` u. a. | Nutzer | – |
| Research-Runtime | 8 | `research-agent-runtime` (`ResearchAgentMain`, Jar `researchAgentJar`) | Research-Plugin (`ProductiveResearchBackendFactory`) | ACP über stdio zur App; MCP (HTTP, Loopback) zu Research-Control- und Browser-Endpunkt der App |
| Browser-Sidecar | 21 | `browser-mcp-server-java21` (Playwright, playwright4j, Camoufox) | Research-Plugin (`BrowserMcpSidecarProcess`, `LazyRestartableBrowserRuntime`) | MCP (HTTP, Loopback, Token) |
| Local-Model-Sidecar | 21 | `local-model-runtime-sidecar-java21` (win-directml-java) | App (`LocalModelRuntimeManager`) | HTTP (Loopback) |

### Browser-Suche heute
Die Research-Runtime ruft `web_search_prepare`/`web_search_apply_layout` über MCP am Browser-Endpunkt der App auf
(`LegacyBrowserSearchStrategy` → `McpLayoutRepairClient`). Die App reicht das an den Sidecar durch
(`BrowserBridgeEndpoint` → `LazyRestartableBrowserRuntime` → MCP). Der Sidecar navigiert seinen einzigen Browser zur
konfigurierten Such-URL und wertet die Seite mit `browser-search-analysis` aus (Layout-Erkennung, KI-gestützte
Layout-Reparatur). Such- und Research-Browser sind heute derselbe Browser. Die API-Provider (Brave, Bright Data,
DataForSEO, SerpApi) laufen bereits über Serp4J in der Runtime (`web-search-providers`).

### Build
- Gradle 7.6.4 (läuft nur auf JDK ≤ 19), Root-`build.gradle` setzt alle Module außer drei auf Java 8.
- CI (`release.yml`, `chatgpt-cache.yml`) baut mit JDK 8, JDK 21 nur für Serp4J.
- Gruppe `com.aresstack.askai.java8`, Hauptklasse `AskAiJava8App`, Artefakt `askai-java8-<version>.jar`,
  Modul `ollama4j-java8`, Datenordner `%APPDATA%\.askai-java8`.

## Zielbild
Eine JVM (Java 21): die AskAI-App. Darin laufen Plugin-Host, Research-Plugin, Research-Logik, beide Browser und die
Suche. Die Google-Suche ist ein direkter Methodenaufruf
`GooglePlaywrightSearchProvider.search(request, context, suchbrowser)`.

## Schritte

### 1. Build auf Java 21 (dieser PR)
- Alle Module kompilieren mit Java-21-Toolchain; die Java-8/21-Sonderliste in der Root-`build.gradle` entfällt.
- Gradle-Wrapper 8.14.3 (läuft selbst auf JDK 21); CI nur noch JDK 21.
- Keine Verhaltensänderung, die Prozesse bleiben vorerst.

### 2. Browser in die AskAI-JVM
- Die Playwright-Klassen aus `browser-mcp-server-java21` (`PlaywrightBrowserSession`, `PlaywrightSessionFactory`,
  `CamoufoxProvisioning`, `RenderedPageDocumentCapture`, …) werden eine normale Bibliothek der App.
- `BrowserRuntimePort` bekommt eine In-Process-Implementierung, die die Browser-Session direkt aufruft (statt
  `BrowserMcpSidecarProcess` + MCP). Der Research-Browser bleibt so, wie er heute ist (sichtbar, Camoufox).
- Neuer Suchbrowser: zweite Camoufox-Instanz, headless, dauerhaft, mit Neustart nach Absturz, gestartet mit derselben
  vorhandenen `PlaywrightSessionFactory`/`CamoufoxProvisioning`.
- Entfällt: Sidecar-Prozess, Token/Port, MCP-Server im Sidecar, `sidecarJar`, Sidecar-Java-Einstellung.

### 3. Suche direkt über Serp4J
- Die Research-Logik, die heute in der Research-Runtime läuft (Suchstrategien, Recherche-Schleife), läuft in der
  AskAI-JVM. Die Google-Suche ruft `serp4j-playwright-google` direkt mit dem Suchbrowser auf; die API-Provider laufen
  wie bisher über Serp4J.
- `LegacyBrowserSearchStrategy` (MCP + Layout-Reparatur) wird durch die Serp4J-Google-Strategie ersetzt.
- Entfällt für die Suche: MCP-Aufrufe Runtime → App, `BrowserBridgeEndpoint`-Durchreichen von `web_search*`.

### 4. Local-Model-Runtime in die AskAI-JVM
- `local-model-runtime-sidecar-java21` wird Bibliothek der App, `LocalModelRuntimeManager` ruft die Engine direkt
  auf statt einen Prozess zu starten.

### 5. Altlasten entfernen
- Sidecar-Module, Prozessstarter, Watchdogs (`ParentDeathWatchdog`), Jar-Tasks und Einstellungen für Sidecar-Java.
- Java-8-Namen (#51, erledigt): Projekt/Artefakt `askai` (`askai-<version>.jar`), Hauptklasse `AskAiApp`, Modul
  `ollama4j`, Paket `com.aresstack.askai`, User-Agent `askai`, Datenordner `%APPDATA%\.askai`
  (`AppDirectoryMigration` verschiebt den alten Ordner `.askai-java8` beim ersten Start).
- Java-8-Bibliotheksreste, die es nur wegen Java 8 gibt.

## Offene Punkte fürs Review
1. Die Research-Runtime ist heute ein externer ACP-Agent. Für Schritt 3 läuft ihre Logik in der AskAI-JVM; ob die
   ACP-Agent-Schnittstelle für andere, externe Agenten erhalten bleibt, ist offen.
2. Die KI-gestützte Layout-Erkennung (`browser-search-analysis`) wird für Google durch den festen Parser aus
   `serp4j-playwright-google` ersetzt. Bing und DuckDuckGo über den Browser haben danach keinen Pfad mehr, solange sie
   nicht ebenfalls als Serp4J-Provider vorliegen.
3. Die Java-8-Namen sind umbenannt (#51). Die Module `browser-mcp-server-java21` und
   `local-model-runtime-sidecar-java21` tragen noch ihre alten Namen, obwohl sie keine Sidecars mehr sind.
