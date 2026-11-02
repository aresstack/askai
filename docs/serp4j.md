# Websuche über Serp4J

Seit WP5 laufen die Websuche-Provider **Brave**, **Bright Data** und **DataForSEO** in AskAI über die
Serp4J-Provider-Module (`com.aresstack:serp4j-provider-*`). AskAI bleibt der Host:

| Thema | Wer | Wo |
| --- | --- | --- |
| Konfiguration (`~/agents/research/providers/*.json`) | AskAI | unverändert, `ProviderConfigurationService` |
| API-Keys/Passwörter (AES-GCM) | AskAI | `HostCredentialProvider` entschlüsselt erst beim Request, Serp4J schließt den `SecretValue` |
| HTTP-Transport (`AhcHttpTransport`) | AskAI erzeugt und schließt ihn | `Serp4jProviderFactory`, `Serp4jWebSearchProvider.close()` |
| HTTP-Aufruf, Auth-Header, Mapping, Fehlerkategorien | Serp4J | Provider-Module |

Zugangsdaten gehen ausschließlich als HTTP-Header raus (Brave `X-Subscription-Token`, Bright Data
`Authorization: Bearer`, DataForSEO Basic). Serp4J hat keine Abhängigkeit zu AskAI.

## Was sich gegenüber der alten Implementierung ändert

- **Engine-Etikett:** Die Engine kommt aus der Serp4J-Antwort (Metadaten und Herkunft je Treffer). Bright Data und
  DataForSEO werden nicht mehr pauschal als `GOOGLE` etikettiert; eine DataForSEO-Bing-Suche heißt jetzt `BING`.
  Bright Data unterstützt in Serp4J vorerst nur Google; eine andere konfigurierte Engine endet mit einer
  Konfigurationsfehlermeldung statt mit falsch etikettierten Google-Treffern.
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
Gradle 8 mit Kotlin-DSL-Build-Logik. Hinweis: Das Offline-ZIP aus `chatgpt-cache.yml` enthält `~/.m2` nicht; offline
muss `scripts/install-serp4j.sh` vorher einmal mit Netz gelaufen sein.

Sobald Serp4J auf Central veröffentlicht ist, entfallen `mavenLocal` und das Skript.
