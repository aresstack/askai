# Websuche über Serp4J

Seit WP5 laufen die Websuche-Provider **Brave**, **Bright Data**, **DataForSEO** und **SerpApi** in AskAI über die
Serp4J-Provider-Module (`com.aresstack:serp4j-provider-*`). AskAI bleibt der Host:

| Thema | Wer | Wo |
| --- | --- | --- |
| Konfiguration (`~/agents/research/providers/*.json`) | AskAI | unverändert, `ProviderConfigurationService` |
| API-Keys/Passwörter (AES-GCM) | AskAI | `HostCredentialProvider` entschlüsselt erst beim Request, Serp4J schließt den `SecretValue` |
| HTTP-Transport (`AhcHttpTransport`) | AskAI erzeugt und schließt ihn | `Serp4jProviderFactory`, `Serp4jWebSearchProvider.close()` |
| HTTP-Aufruf, Auth-Header, Mapping, Fehlerkategorien | Serp4J | Provider-Module |

Zugangsdaten gehen ausschließlich als HTTP-Header raus (Brave `X-Subscription-Token`, Bright Data
`Authorization: Bearer`, DataForSEO Basic). Einzige Ausnahme ist SerpApi: SerpApi akzeptiert den Key nur als
URL-Parameter `api_key`; Serp4J maskiert ihn in jeder URL-, Log- und Fehlerausgabe (`api_key=***`). Serp4J hat keine
Abhängigkeit zu AskAI.

## SerpApi

Konfiguration `~/agents/research/providers/serpapi.json` (Beispiel: `web-search-providers/examples/providers/serpapi.json`),
Key verschlüsselt über `ProviderConfigurationService.saveSerpApi(configuration, char[] apiKey)`.

- `searchEngine`: `GOOGLE` (Default) oder `BING`; pro Datei eine Engine, wie bei DataForSEO.
- `country`/`language`/`location`/`googleDomain`/`noCache` gehen als Serp4J-Defaults (`SerpApiSearchOptions`) durch.
  Bing braucht zur Sprache ein Land; Sprache ohne Land liefert in Serp4J eine Warnung bzw. `UNSUPPORTED`.
- `resolveGoogleGotoUrls` (Default `true`, nur Google) und `urlResolveTimeoutMillis` (0 = Serp4J-Default) steuern die
  Auflösung der Google-`/goto`-Links in Serp4J; AskAI wertet `serp4j:url_resolution`/`serp4j:original_url` wie bei
  DataForSEO aus.
- In der Research-Runtime ist `SERP_API` jetzt ein migrierter Provider (Auswahl „SerpApi“ in den Sucheinstellungen).

## Google über den Serp4J-Suchbrowser (askai #31)

Die Google-Suche ohne Such-API läuft vollständig in Serp4J (`serp4j-engine-google-browser`, Provider-Typ
`google-browser`): Serp4J baut die Such-URL (nie `udm=50`), steuert einen eigenen Suchbrowser über seinen
Browser-Port (`serp4j-browser-playwright` mit Node-Treiber) und parst die Ergebnisseite. AskAI enthält dafür keinen
HTTP- oder HTML-Parsing-Code, sondern wählt nur den lokalen Browser.

- Konfiguration `google-browser.json` (Beispiel unter `web-search-providers/examples/providers/`): `browserType`
  (chromium/firefox), `channel` (Standard `msedge`), optional `executablePath`, `headless`, Sprache, Land,
  `resolveGotoUrls`. Keine Secrets. Auswahl in den Sucheinstellungen als „Google (Serp4J search browser)“.
- Provider-ID `GOOGLE_BROWSER`; läuft über denselben Pfad wie die API-Provider (`SingleProviderSearchStrategy`), also
  auch für die manuelle Suche.
- **Suchbrowser und Research-Browser sind getrennt:** der Suchbrowser ist ein eigener Playwright-Prozess im
  Research-Agent mit flüchtigem Profil je Suche; der Research-Browser (MCP-Sidecar) öffnet weiterhin die gefundenen
  Seiten. Der Suchbrowser kann keine Sidecar-Sitzung schließen oder überschreiben.
- Google-`/goto`-Treffer löst Serp4J per HTTP auf (`serp4j:url_resolution`/`serp4j:original_url`); was nicht
  auflösbar ist, bleibt wie bei SerpApi/DataForSEO als unaufgelöster Treffer markiert und wird nie Quelle.
- Consent-/Bot-Check-Seiten ohne Treffer klickt Serp4J nicht weg (ADR-016); die Suche scheitert dann sichtbar mit
  `BLOCKED`/`google_obstacle`.
- Die Google-Module veröffentlicht Serp4J nur nach mavenLocal (nicht nach Maven Central); `scripts/install-serp4j.sh`
  installiert sie mit.
- Die bisherige Browser-Suche („Web Search (multi-engine browser)“: Bing/Google/DuckDuckGo über den Sidecar mit
  Layout-Reparatur) bleibt als breite Multi-Engine-Suche erhalten und ist weiterhin der Standard.

## Was sich gegenüber der alten Implementierung ändert

- **Engine-Etikett:** Die Engine kommt aus der Serp4J-Antwort (Metadaten und Herkunft je Treffer). Bright Data und
  DataForSEO werden nicht mehr pauschal als `GOOGLE` etikettiert; eine DataForSEO-Bing-Suche heißt jetzt `BING`.
  Bright Data unterstützt in Serp4J vorerst nur Google; eine andere konfigurierte Engine endet mit einer
  Konfigurationsfehlermeldung statt mit falsch etikettierten Google-Treffern.
- **Nur organische Treffer:** Anzeigen und andere SERP-Elemente werden nie zu Rechercheergebnissen; ohne organische
  Treffer bleibt die Liste leer.
- **Rohantwort:** AskAI fordert `RawPayloadPolicy.ALWAYS` an. Die Rohantwort steht in `WebSearchResult.getRawResponse()`
  und jetzt auch in `SearchProviderResult.getRawResponse()` der Research-Runtime (Diagnosezeile nennt ihre Länge).
  Persistenz im Research-Domain-Modell ist noch offen.
- **URL-Dedup im Composite:** Der Query-String gehört zur Identität (`?id=1` ≠ `?id=2`); nur Fragment,
  Groß-/Kleinschreibung von Schema/Host, Default-Port und abschließender Slash werden normalisiert.
- **Fehler:** Serp4J-Kategorien werden auf die bisherigen Formen abgebildet (AUTHENTICATION → 401, RATE_LIMITED/
  QUOTA_EXHAUSTED → 429, UNAVAILABLE/BLOCKED → 503, CONFIGURATION/INVALID_REQUEST/UNSUPPORTED → Konfigurationsfehler,
  RESPONSE_INVALID → unbrauchbare Antwort); die Serp4J-Exception bleibt als Ursache erhalten.

Die alten Implementierungen bleiben vorerst als `SearchProviderFactory.createLegacy*` (deprecated) erhalten, bis
echte Antworten für alle drei Provider über den Serp4J-Pfad geprüft sind. Nicht übernommen werden alte
Konfigurationsfelder, die Serp4J noch nicht kennt (z. B. Brave-Goggles/Freshness, Bright-Data-Async-Modus,
DataForSEO-Gerät/Tiefe); sie werden derzeit ignoriert.

## Bauen

Serp4J ist noch nicht auf Maven Central. Die Root-`build.gradle` liest deshalb **nur** `com.aresstack:serp4j-*` aus
`mavenLocal` (Content-Filter); alles andere kommt weiter reproduzierbar von Central.

```bash
scripts/install-serp4j.sh              # klont Serp4J (Develop) nach build/serp4j-src und publiziert nach ~/.m2
scripts/install-serp4j.sh Develop ../Serp4J   # oder: vorhandenen Checkout verwenden
./gradlew build                        # AskAI wie gewohnt (Gradle 7.6.4, JDK 8–17)
```

Serp4Js Gradle braucht JDK 17+ (`SERP4J_JAVA_HOME`, sonst `JAVA_HOME_21_X64`/`JAVA_HOME`). Eine andere Version:
`./gradlew build -Pserp4jVersion=…`. Die Workflows `release.yml` und `chatgpt-cache.yml` führen das Skript vor dem
Build aus. Ein Composite Build (`includeBuild`) geht derzeit nicht, weil AskAI Gradle 7.6.4 nutzt und Serp4J
Gradle 8 mit Kotlin-DSL-Build-Logik. Das Offline-ZIP aus `chatgpt-cache.yml` bringt die Serp4J-Artefakte unter
`.chatgpt/m2` mit; offline: `./gradlew --offline -Dmaven.repo.local=$PWD/.chatgpt/m2/repository build`.

Sobald Serp4J auf Central veröffentlicht ist, entfallen `mavenLocal` und das Skript.
