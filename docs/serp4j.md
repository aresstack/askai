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

## Google-`/goto`-Treffer: Auflösung erst bei der Navigation (askai #41)

Google liefert organische Treffer oft als `https://www.google.com/goto?url=…`. Für die Google-Suche im Suchbrowser
(`serp4j-playwright-google`) gilt:

- Während der Suchergebnisbeschaffung wird **keine** `/goto`-URL aufgelöst: keine GET-/HEAD-Anfragen, keine
  Resolver-Schleife, kein `ResultUrlEnricher`. Je Suche geht genau eine Anfrage an Google (die Trefferseite).
- Jeder organische Treffer bleibt mit Provider-URL, Titel, Snippet, Breadcrumb und Sitelinks erhalten. Ein
  `/goto`-Treffer ist `UNRESOLVED`: Recherchekandidat, aber keine verifizierte Zieladresse (wird nicht geparkt).
- Erst wenn die Recherche den Treffer öffnet, navigiert der Browser die `/goto`-URL und folgt der Weiterleitung.
  Die Ziel-URL aus dem Browser ist Quellenidentität und Grundlage für die Deduplizierung; die `/goto`-URL bleibt als
  Provenienz (`AcceptedSource.getOriginalUrl()`, Status `RESOLVED`). Die URL-Sicherheitsprüfung nach dem Redirect
  greift wie für jede Seite. Bleibt der Browser auf Google, ist die Seite Transit und nie Quelle. Ein
  Navigationsfehler beendet nur diesen Besuch; der Treffer bleibt im Suchergebnis.
- Die HTTP-Resolver in Serp4J (`GoogleGotoUrlResolver`, `ResultUrlEnricher`) bleiben für API-Provider wie SerpApi
  erhalten (`resolveGoogleGotoUrls`); AskAI hat keine eigene zweite Auflösungslogik.

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
./gradlew build                        # AskAI wie gewohnt (Gradle 8.14.3, JDK 21)
```

Serp4Js Gradle braucht JDK 17+ (`SERP4J_JAVA_HOME`, sonst `JAVA_HOME_21_X64`/`JAVA_HOME`). Eine andere Version:
`./gradlew build -Pserp4jVersion=…`. Die Workflows `release.yml` und `chatgpt-cache.yml` führen das Skript vor dem
Build aus. Ein Composite Build (`includeBuild`) ist noch nicht eingerichtet. Das Offline-ZIP aus `chatgpt-cache.yml` bringt die Serp4J-Artefakte unter
`.chatgpt/m2` mit; offline: `./gradlew --offline -Dmaven.repo.local=$PWD/.chatgpt/m2/repository build`.

Sobald Serp4J auf Central veröffentlicht ist, entfallen `mavenLocal` und das Skript.
