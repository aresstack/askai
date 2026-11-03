# Audio-DSP — known problems and remaining work

This file records blockers and honestly-scoped gaps found while implementing Slices 6–13D of the
Audio-DSP roadmap. Only buildable states are committed; anything not fully deliverable is documented here
with slice, affected function, concrete blocker, tried approaches, impact and possible later solution.

## Slice 6 — Noise Profiler / Adaptive Noise Suppression: on-disk NoiseProfile persistence deferred

- **Slice:** 6
- **Affected function:** "Speicherung und erneute Verwendung gelernter Noise Profiles" (persist a learned
  `NoiseProfile` on disk and reference it by `noiseProfileId` across runs) + editor UI for that.
- **Blocker / decision:** The core requirement — *Noise Profiler learns a noise model → Adaptive Noise
  Suppression consumes it* — is implemented fully via the in-run `AudioProcessingContext` metadata channel
  (typed `setNoiseProfile`/`getNoiseProfile`). A learned profile flows Profiler → Suppressor (mode
  "Use a learned noise profile") within one pipeline execution, exactly like the VAD speech track. What is
  deferred is only the *persistent artifact*: writing a `NoiseProfile` to a file, giving it an id, and
  resolving `noiseProfileId` at process time from a repository, plus the editor picker for it.
- **Why deferred:** A persisted `NoiseProfile` repository + id-resolution at block-process time + editor UI
  is a self-contained sub-feature that does not block any later slice; the transient flow already satisfies
  the fresh-per-run reproducibility rule (adaptive state is never persisted as a profile parameter).
- **Impact:** Learned noise models are per-run only; they are not yet saved/reused across app restarts.
  Automatic and Learn-from-silence suppression are fully functional and need no stored artifact.
- **Remaining work:** `NoiseProfile` (de)serialization (Gson), a file repository under the AskAI config
  dir, a resolver placed into the context before processing, and an editor field to pick a stored profile.
- **Possible later solution:** Mirror the JSON profile-transfer infrastructure (versioned envelope, atomic
  write) for `NoiseProfile`/`RoomProfile`/`MicrophoneArrayProfile` in one artifact-persistence slice.

## Slice 9D — Streaming WPE runs inside the batch pipeline, not a true real-time stream

- **Slice:** 9D
- **Affected function:** Streaming-adaptive WPE dereverberation.
- **Status:** Implemented and functional. The STREAMING mode uses a sliding, bounded-history window
  (~64 frames) with a short look-ahead (~8 frames) and a continuously carried, EMA-smoothed prediction
  filter, so it is causal-with-look-ahead and never needs the whole signal. It measurably reduces the late
  reverberant tail (verified by test), but intentionally more gently than the offline pass because it only
  sees bounded history.
- **Honest limitation:** The whole DSP pipeline is still a batch adapter — a block receives one complete
  `AudioBuffer` per run. STREAMING here means the *algorithm's* causality/state model, not a frame-by-frame
  real-time transport through the block. A genuine real-time streaming transport (push frames in, pull
  frames out with a fixed latency) would require a streaming pipeline runner, which is out of scope of the
  current buffer-in/buffer-out architecture and is not required by any later slice.
- **Possible later solution:** Add a streaming pipeline runner that feeds frames through stateful processors
  incrementally; the WPE STREAMING core already keeps bounded state and would slot into it.

## Slice 11 — MicrophoneArrayProfile persistence by id / editor picker deferred

- **Slice:** 11
- **Affected function:** Persisting a `MicrophoneArrayProfile` on disk and referencing it by
  `microphoneArrayProfileId`, plus an editor picker for it.
- **Status:** The `MicrophoneArrayProfile` type and geometry validation exist and the Delay-and-Sum
  Beamformer is fully functional — it takes the microphone positions inline (a `micPositionsMm` text
  parameter, "x,y,z" per mic in millimetres). With a valid geometry it aligns + sums toward a target
  direction; without one it refuses to run (validator ERROR) and never invents positions, exactly as
  required.
- **Deferred:** A persisted array-profile repository + id resolution + editor picker (same shape as the
  deferred `NoiseProfile`/`RoomProfile` persistence). Not required by any later slice.
- **Possible later solution:** One shared artifact-persistence slice for
  `NoiseProfile`/`RoomProfile`/`MicrophoneArrayProfile` (versioned JSON envelope, atomic write, id
  resolver placed into the processing context before a run).

## Slice 13A — RNNoise native runtime not bundled

- **Slice:** 13A
- **Affected function:** Running RNNoise as a Speech Enhancer backend.
- **Blocker:** RNNoise is a native (C) library and needs a JNI binding + the native `.dll/.so/.dylib` at
  runtime. Neither the native library nor a Java 8 JNI binding is bundled in this repository (and the core
  fat JAR must stay free of native binaries). There is no vetted, license-clean, Java-8-compatible RNNoise
  binding available to embed here without shipping platform binaries.
- **What is implemented:** The full backend abstraction (`SpeechEnhancementBackend` SPI +
  `SpeechEnhancementBackends` registry with ServiceLoader discovery), an always-available pure-Java backend
  (adaptive spectral suppression) that makes the Speech Enhancer block functional, and an `RnnoiseSpeechEnhancer`
  adapter that probes a runtime system property (`askai.audio.rnnoise.runtime`) and reports `NOT_INSTALLED`.
  When the backend is unavailable the block passes audio through unchanged and the validator shows the status;
  the core never depends on the native backend.
- **Impact:** No live RNNoise processing in this environment. Everything around it (selection, availability,
  graceful-missing, editability) works.
- **Possible later solution:** Add a separate optional Gradle module `audio-dsp-rnnoise` that ships the JNI
  binding + downloads/loads the native library on explicit user opt-in (like the OpenAL add-on) and registers
  a real backend through the ServiceLoader. The core needs no change.

## Slice 13B — DeepFilterNet runtime + model not bundled

- **Slice:** 13B
- **Affected function:** Running DeepFilterNet as a Speech Enhancer backend.
- **Blocker:** DeepFilterNet is a neural model requiring a runtime (native/ONNX) and a model file; neither is
  bundled, and there is no license-clean Java-8 embedding to ship in-core without platform binaries/models.
- **What is implemented:** `DeepFilterNetSpeechEnhancer` adapter registered in the backend registry; probes
  runtime (`askai.audio.deepfilternet.runtime`) + model (`askai.audio.deepfilternet.model`) properties and
  reports NOT_INSTALLED / MISSING_MODEL / INVALID_SAMPLE_RATE (DeepFilterNet is 48 kHz). The Speech Enhancer
  block passes audio through when it is unavailable; the profile stays editable.
- **Impact:** No live DeepFilterNet processing here. Selection/availability/graceful-missing all work.
- **Possible later solution:** Optional Gradle module `audio-dsp-deepfilter` bundling an ONNX-runtime binding
  + model loader, registered via ServiceLoader on explicit user opt-in.

## Slice 13D — Voice Isolation is an approximation; true speaker isolation needs a model

- **Slice:** 13D
- **Affected function:** Isolating a target/dominant voice from other voices and music.
- **What is implemented:** A `Voice Isolation` block with two backends. The pure-Java center backend is a
  real but limited stereo approximation (emphasize centred speech, reduce laterally-panned voices/music via
  mid/side) — genuinely useful for suitable stereo but not true source separation. A neural backend is
  offered but reports not-installed and passes audio through. Mono input passes through (the center approach
  needs stereo). The validator warns about the limitation and the missing neural backend.
- **Honest limitation:** True target-speaker isolation / voice enrollment needs a neural model (and
  enrollment data), which is not bundled and not implementable in pure Java in-core. Target Speaker
  Enrollment / Voice Enrollment are therefore not implemented (not faked), as the roadmap allows.
- **Possible later solution:** An optional neural voice-isolation module (same ServiceLoader/optional-module
  pattern as the other model backends), plus a speaker-enrollment artifact if/when a model supports it.


---

# Research Agent restarbeiten (Commits 14–20) — problem log

Fortlaufende IDs `RA-Pnnn`. Status: OPEN | WORKAROUND | DEFERRED | RESOLVED.

## RA-P001 — Produktive Quellen-/Projektpersistenz noch nicht angebunden

**Erkannt in:** Commit 15 (structured source management)
**Status:** DEFERRED
**Schweregrad:** MEDIUM
**Betroffene Module:** research-agent-ui-plugin (sources, agent)

### Erwartung
Quellen (und Artefakte) werden dauerhaft in einem lokalen Projekt-Store gehalten und überstehen
einen Neustart; optional ein Lucene-Index als abgeleitete, neu aufbaubare Sicht.

### Beobachtung
Aktuell existiert nur eine deterministische In-Memory-Implementierung
(`InMemoryResearchSourceRepository`); es gibt keine belastbare Projekt-Store-/Lucene-Infrastruktur
im Repository, an die adaptiert werden könnte.

### Analyse
Der Port `ResearchSourceRepository` ist bewusst frei von Lucene-/Swing-Typen und damit adapterfähig.
Die produktive Persistenz ist als eigener Slice (Commit 19) vorgesehen.

### Gewähltes Zwischenverhalten
Port + strukturierte UI + In-Memory-Adapter vollständig geliefert und getestet. Kein spekulativer
Persistenz-Unterbau.

### Auswirkung
Quellen-/Artefaktänderungen sind pro Session flüchtig (kein Neustart-Persist), bis Commit 19 den
Datei-/Index-Store liefert.

### Spätere Entscheidung
Commit 19: Projekt-Store (Markdown-Dateien + sources + state json, atomic write, Revisionen, Checksums);
Lucene nur hinter Adapter, aus dem Projekt-Store rebuildbar.

## RA-P002 — File-backed project store implemented and tested, not yet wired into the live session

**Erkannt in:** Commit 19 (persist research sources and artifact metadata)
**Status:** WORKAROUND
**Schweregrad:** MEDIUM
**Betroffene Module:** research-agent-ui-plugin (store, agent)

### Erwartung
Die laufende Research-Session schreibt Artefakte/Quellen/State in einen Projekt-Store und stellt
sie nach einem Neustart wieder her.

### Beobachtung
Ein vollständiger, getesteter Datei-Store existiert (`ResearchProjectStore`: `FileArtifactStore`,
`FileResearchSourceRepository`, `SessionStateFileStore`) mit atomarem Write (temp+rename), UTF-8,
Revisionen, Checksums, Restart-Restore, Korruptions-Isolation und Projekt-Isolation. Die
`ResearchAgentSession` nutzt jedoch weiterhin die In-Memory-Adapter.

### Analyse
Die deterministischen Session-Tests (und der `FakeHost`, dessen `getWorkspaceDirectory` denselben
Temp-Pfad liefert) erwarten frisches, kollisionsfreies In-Memory-Verhalten pro Lauf. Ein Umschalten
auf den Datei-Store erfordert eine per-Session/Projekt eindeutige Wurzel und Seed-if-empty-Logik, um
diese Tests stabil zu halten.

### Gewähltes Zwischenverhalten
Store-Rahmen vollständig implementiert und über `ResearchProjectStoreTest` (Restart-Restore inkl.)
abgesichert. Live-Session bleibt In-Memory (RA-P001).

### Auswirkung
Artefakt-/Quellen-/State-Änderungen der laufenden Session sind weiterhin flüchtig; die
Persistenz-Bausteine sind aber vorhanden und produktiv anschließbar.

### Spätere Entscheidung
`ResearchAgentSession` an `ResearchProjectStore` verdrahten (Wurzel = host workspace dir + projectId,
seed-if-empty, Memento bei jedem State-Change speichern, bei `activate()` wiederherstellen). Danach
optionaler Lucene-Index als aus dem Store rebuildbare, abgeleitete Sicht.

### Stand nach Commit 35b
ACP runtime integration works (echter Prozess-Round-trip inkl. Research-MCP-Readiness). **Restart
restoration remains incomplete until RA-P002 is resolved** — 35b liefert bewusst nur fresh sessions;
`session/load`/Memento-Restore folgt mit der Store-Verdrahtung.

---

# Research Agent — Endaudit-Korrekturen (Commits 21–29) — problem log

Fortlaufende IDs `RA-Pnnn`. Status: OPEN | WORKAROUND | PARTIALLY_RESOLVED | DEFERRED | RESOLVED.
Diese IDs adressieren die beim Endaudit nach Commit 20 gefundenen Architektur- und
Datenkonsistenzlücken. Reihenfolge und Zielinvarianten siehe Gesamtauftrag.

## RA-P003 — Plugin refresh leaks previous runtime generation

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 21 + Korrekturcommit 21b)
**Schweregrad:** HIGH
**Betroffene Module:** agent-plugin-host, askai-app

### Erwartung
Genau eine aktive PF4J-Runtime-Generation. Ein Refresh vergisst nie einen alten `AskAiPluginManager`;
die alte Generation wird erst nach nachweislich geschlossenen Sessions gestoppt und entladen; jeder
Teilfehler ist sichtbar und wird erneut versucht.

### Beobachtung
`WorkspacePluginService.discover()` überschrieb `pluginManager` mit einem neuen Manager ohne Stop/Unload
des vorherigen (Commit 21 behob das grundsätzlich). Ein Nachaudit fand jedoch verbleibende
Lifecycle-Lücken: (a) ein nach `managerFactory.create()` teilweise geladener Manager wurde bei einem
`loadPlugins()`-Fehler nicht bereinigt; (b) Session-Close und Stop-/Unload-Fehler wurden per
Best-Effort verschluckt, sodass die alte Generation trotz fehlgeschlagenem Session-Close entladen werden
konnte; (c) Close/Stop/Unload liefen teils auf dem EDT.

### Ursache
Best-Effort-Exception-Schlucken an sicherheitskritischen Stellen; kein strukturiertes Ergebnis für
Retirement/Session-Close; kein Tracking unvollständig retirierter Generationen.

### Korrektur
Commit 21: immutables `PluginRuntimeGeneration` + `activeGeneration`, off-EDT-Candidate, atomarer Swap,
`PluginCatalogSnapshot`. Korrekturcommit 21b: (a) `buildCandidate` bereinigt einen halbfertigen Manager
bei jedem Fehler (`cleanUpHalfBuilt`); (b) Generationswechsel über `GenerationSwapHook` — EDT detachiert
nur die Sessions, off-EDT werden sie geschlossen; die alte Generation wird **nur** bei erfolgreichem
Session-Close (`SessionCloseResult.isSuccessful()`) retiriert, sonst wird der Candidate verworfen und die
vorherige Generation aktiv gelassen; (c) `retire()` liefert `GenerationRetirementResult`
(stopped/unloaded/stop-/unload-Fehler/complete); unvollständige Generationen bleiben in
`retiringGenerations` und werden bei Refresh und Shutdown erneut retiriert; Fehler erscheinen als globale
Lifecycle-Fehler im Snapshot. Alle blockierenden Schritte laufen über `runOnEdtAndWait` außerhalb des EDT.

### Verifikation
`WorkspacePluginTransactionalRefreshTest` (Generation-Monotonie/Single-Active, JAR-Release),
`WorkspacePluginRetirementTest` (halbfertiger Manager bereinigt + vorherige Generation behalten;
fehlgeschlagener Session-Close bricht Swap ab und lässt alte Generation geladen; Close läuft off-EDT;
Startfehler → `START_FAILED`), `PluginRuntimeGenerationRetireTest` (Stop-/Unload-Fehler isoliert,
gemeldet, `complete=false` für Retry). `./gradlew clean build` grün.

### Nachhärtung (Korrekturcommit 22b)
Ein Nachaudit fand Restrisiken im Swap/Shutdown: (a) fehlgeschlagene `session.close()` verloren die
Session-Referenz (nur `List<String>`); (b) `runOnEdtAndWait` schluckte EDT-Task-Exceptions und hatte kein
Timeout; (c) `shutdown()` retirierte auf dem EDT; (d) `retiringGenerations` ohne Dedup. Behoben:
`AgentSessionCoordinator` hält fehlgeschlagene Sessions in `unclosed` und schließt sie bei nächstem Detach
und bei Shutdown erneut; `runOnEdtAndWait` liefert `UiCallResult` (propagiert Exceptions, `EDT_WAIT_TIMEOUT`),
fehlgeschlagener Detach bricht den Swap ab (vorherige Generation bleibt); `shutdown()` ist zweistufig
(EDT-Detach → off-EDT Close/Stop/Unload, `SHUTDOWN_TIMEOUT`-begrenzt); `retiringGenerations` ist nach
Generation-ID dedupliziert mit letztem `GenerationRetirementResult` (Plugin-ID + Stop/Unload-Phase im
Katalog).

### Verifikation (Nachhärtung)
`AgentSessionCoordinatorSwapTest` (fehlgeschlagener Close bleibt referenziert + Retry bei Detach/Shutdown),
`WorkspacePluginRetirementTest#detachFailureAbortsSwapAndKeepsPreviousGeneration`. `clean build` grün, 753.

### Nachhärtung 2 (Korrekturcommit 21c)
Vier weitere Lifecycle-Sicherheitsfehler behoben: (1) `runOnEdtAndWait` ist jetzt cancel-sicher — ein
EDT-Runnable mutiert nur nach atomarem `PENDING→RUNNING`; bei Timeout setzt der Lifecycle-Thread
`PENDING→CANCELLED`, sodass ein spät laufender Runnable nichts mehr tut (keine „Geister“-Detach/-Publish);
ein abgebrochener Publish verwirft den Candidate und behält die vorherige Generation; Timeout injizierbar.
(2) `PluginRuntimeGeneration.retire()` arbeitet pro Plugin: ein gestartetes Plugin wird erst nach
bestätigtem Stop entladen (Stop-Fehler ⇒ kein Unload), der `unloadPlugin`-Boolean wird geprüft, und
`complete=true` gilt nur, wenn der Manager für jedes ursprünglich geladene Plugin „nicht gestartet und nicht
geladen“ bestätigt. (3) Einziger produktiver Shutdown-Pfad: `ChatWorkspaceHostPanel.shutdown()` schließt
keine Sessions mehr selbst; `WorkspacePluginService.shutdown()` detachiert auf dem EDT und schließt/stoppt/
entlädt off-EDT auf einem dedizierten Non-Daemon-Thread (kein `join` auf dem EDT); ein fehlgeschlagener
Session-Close verhindert das Unload. (4) `fireChange()` isoliert jeden Listenerfehler, sodass ein kaputter
UI-Listener einen bereits ausgeführten Detach nicht in einen Fehler verwandelt oder Session-Referenzen verliert.

### Verifikation (Nachhärtung 2)
`EdtCancelSafetyTest` (timed-out Runnable mutiert nicht; rechtzeitig gepumpt läuft),
`PluginRuntimeGenerationRetireTest` (Stop-Fehler ⇒ kein Unload; `unload`-false/unbestätigt ⇒ incomplete;
Retry nur Rest; complete nur bei Manager-Bestätigung), `ShutdownOrderingTest` (Shutdown detachiert auf EDT,
schließt off-EDT via Coordinator), `AgentSessionCoordinatorSwapTest#aBrokenChangeListener…`. `clean build`
grün, 766 Tests.

### Restwirkung
Bei jeder erfolgreichen Generation werden alle AgentSessions geschlossen und lazy neu erzeugt
(bewusst einfach + sicher, RA-P010/RA-P002 liefern später den Restore aus dem Project Store).

## RA-P004 — Disabled plugins are still started

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 21)
**Schweregrad:** HIGH
**Betroffene Module:** agent-plugin-host

### Erwartung
Ein deaktiviertes Plugin wird geladen, aber `Plugin.start()` wird nicht aufgerufen; es liefert keine
selektierbare Extension; der Katalog zeigt `Enabled=false` und einen ehrlichen PF4J-State.

### Beobachtung
Zuvor wurden alle Plugins via `startPlugins()` gestartet und erst danach über den Enablement-Service
aus dem auswählbaren Katalog gefiltert — die Plugin-Instanz lief also trotz „disabled“.

### Ursache
Start-Entscheidung erfolgte nach dem Start, nicht davor.

### Korrektur
Nach `loadPlugins()` wird pro geladenem Plugin anhand der stabilen PF4J-Plugin-ID die Aktivierung
geprüft; nur aktivierte werden einzeln via `startPlugin(id)` gestartet. Deaktivierte bleiben RESOLVED,
erhalten eine ehrliche `Enabled=false`-Zeile und erscheinen in keiner selectable Map.

### Verifikation
`WorkspacePluginTransactionalRefreshTest#disabledPluginIsLoadedButNotStartedAndNotSelectable`
(Disable→RESOLVED+nicht selektierbar, Re-Enable→STARTED, Re-Disable→nicht selektierbar).

### Restwirkung
Keine.

## RA-P005 — Live backend still uses legacy phase/run-state pair

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 22)
**Schweregrad:** HIGH
**Betroffene Module:** research-agent-ui-plugin (state, backend, agent)

### Erwartung
`ResearchStateMemento` ist die einzige Live-Wahrheit; `PAUSED/BLOCKED/FAILED` erhalten den exakten
`continuationStateId` und die Approval-ID.

### Beobachtung
Der Live-Backend-State war `ResearchPhase + ResearchRunState`; vor jedem Dispatch wurde ein OO-State
rekonstruiert und der exakte Continuation-State durch `defaultContinuationStateId` ersetzt; die Approval-ID
ging bei Unterbrechungen verloren (`interrupt` übergab `null`, `continueInto` erzeugte eine neue ID).

### Ursache
Kein memento-basierter Live-Port; die OO-Interruptions trugen die Approval-ID nicht durch.

### Korrektur
Neuer nativer Port `ResearchStateMachinePort` + `OoResearchStateMachine`
(`dispatch(ResearchStateMemento) → ResearchStateTransitionResult{accepted,nextMemento,events,reason}`).
Die OO-Interruptions (`PausedState/BlockedState/FailedState`) tragen jetzt die `pendingApprovalId`;
`interrupt()` übernimmt sie vom unterbrochenen Approval-Gate, `continueInto()` stellt exakt dieselbe ID
wieder her. `ResearchStateFactory.state()` validiert Approval-IDs (nur am Gate bzw. bei einem in ein Gate
fortsetzenden Interrupt). `FakeResearchSessionBackend.FakeSession` hält ausschließlich ein
`ResearchStateMemento`; `ResearchBackendEvent` transportiert das Memento (Legacy-Getter daraus abgeleitet);
`ResearchAgentSession` leitet `AgentStateSnapshot`, State-View und Allowed-Commands allein aus dem Memento
ab (kein Default-Continuation-Raten). `DefaultResearchStateMachine` ist `@Deprecated` (Legacy-Adapter, von
keinem Live-Backend verwendet).

### Verifikation
`OoResearchStateMachineTest` (Block/Fail aus WAITING_APPROVAL → exakte Continuation + Approval-ID über
Unblock/Retry erhalten; Pause/Resume; Snapshot-Round-Trip; ungültiges Memento abgelehnt; Revision nur bei
Akzeptanz), `MementoBackendEventTest` (State-Events tragen konsistentes Memento; Approval-ID überlebt
Block/Unblock im Live-Backend). `./gradlew clean build` grün, 747 Tests.

### Nachhärtung (Korrekturcommit 22b)
Nachaudit-Punkte behoben: die OO-State-Machine erhält jetzt den injizierten `ResearchIdGenerator`/
`ResearchClock` des Backends (deterministische Approval-IDs/Mementos); `canExecute()` ist rein
(`ResearchStateMachinePort.allowedCommands(memento)`, keine Probe-Transition, kein ID-Verbrauch);
Approval-/Reject-IDs werden erst nach akzeptierter Transition als `processed` markiert und die
„Changes requested“-Meldung erst danach ausgegeben (ein No-op-Reject blockiert das Gate nicht mehr).

### Verifikation (Nachhärtung)
`BackendHardeningTest` (identische Läufe deterministisch; `canExecute` verbraucht keine IDs; No-op-Reject
lässt das Gate aktionierbar). `clean build` grün, 753 Tests.

### Nachhärtung 2 (Korrekturcommit 21c) — strikte Approval-Mementos
`ResearchStateFactory` ist jetzt streng: `WAITING_APPROVAL` verlangt zwingend eine nichtleere
`pendingApprovalId`, ebenso eine Interruption, deren `continuationStateId == WAITING_APPROVAL` ist; sonst
Ablehnung. `continueInto()` erzeugt im nativen Pfad keine neue ID mehr (die Interruption trägt die
Original-ID per Invariante). Die Reparatur alter Legacy-Daten (legacy phase/run-state ohne Approval-ID)
liegt ausschließlich im ausdrücklichen `LegacyResearchStateMigration`, das eine synthetische ID erzeugt;
`DefaultResearchStateMachine` nutzt dieses Adapter statt die Factory mit `null` aufzurufen.

### Verifikation (Nachhärtung 2)
`ApprovalMementoStrictnessTest` (Approval-Gate/Interruption ohne ID abgelehnt; Restore erfindet nie eine ID;
exakter Round-Trip; Migration synthetisiert ID für Legacy-Daten). `clean build` grün, 766 Tests.

### Restwirkung
`problemCode`/`publicProblemMessage` werden derzeit noch über Backend-Events (BLOCKED/ERROR) transportiert,
nicht im Memento. Die vollständig atomare Persistenz von State + Problem folgt in Commit 27 (RA-P002).
Persistenz/Restore des Mementos folgt ebenfalls in Commit 27.

## RA-P006 — Store compare-and-write is not atomic under concurrency

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 25)
**Schweregrad:** HIGH
**Betroffene Module:** research-agent-ui-plugin (store)

### Beobachtung
Read→expectedRevision→write findet nicht durchgängig unter demselben Lock pro Ressource statt.

### Korrektur
Siehe Commit 25 (Lock über den gesamten CAS-Ablauf, JVM-weit + File-Lock soweit Java-8/Windows-tauglich).

## RA-P007 — UTF-8/source persistence and error classification

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 25)
**Schweregrad:** MEDIUM
**Betroffene Module:** research-agent-ui-plugin (store, sources)

### Beobachtung
Selbstgeschriebenes `.properties`-Format via `Properties.load(InputStream)` ist nicht Unicode-sicher;
IO-Fehler können als Revisionskonflikt erscheinen.

### Korrektur
Siehe Commit 25 (versioniertes UTF-8-Format, deterministische Round-Trips, `ERROR`≠`CONFLICT`).

## RA-P008 — Unsaved artifact/source drafts can be lost

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 26)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host (artifact area), research-agent-ui-plugin (views)

### Beobachtung
Bei Artifact-Rebuild, Moduswechsel oder Konflikt können lokale ungespeicherte Texte überschrieben werden;
der Markdown-Konfliktpfad behauptet, lokal zu behalten, setzt aber den Store-Text in den Editor.

### Korrektur
Siehe Commit 26 (View-Identität, Draft-Cache, Konfliktoptionen ohne stille Überschreibung).

## RA-P009 — Composer availability does not track session state correctly

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 24)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host, askai-app (composer)

### Beobachtung
`AgentSessionCoordinator` meldet nicht bei jedem Backend-State-Event; `setChatBusy(true)` deaktiviert den
gesamten Editor, sodass Slash Commands (`/status`, `/pause`, `/cancel`) nicht zuverlässig eingebbar sind.

### Korrektur
Siehe Commit 24 (Session-Change-Listener; getrennte Prompt-/Command-Verfügbarkeit).

## RA-P010 — Session identity and background-event routing are underspecified

**Erkannt in:** Audit nach Commit 20
**Status:** OPEN (Ziel: Commit 23, vollständig mit Commit 27)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host, research-agent-ui-plugin

### Beobachtung
Sessions werden nur nach Agent-ID unterschieden; Hintergrundsessions können den sichtbaren Chat
unmarkiert mit transienten Ereignissen überfluten.

### Korrektur
Siehe Commit 23 (`AgentSessionKey = agentId+conversationId+projectId`, sessionbezogene Puffer) und
Commit 27 (Restore aus Project Store).

## RA-P011 — Global discovery failures are not visible in Plugin Management

**Erkannt in:** Audit nach Commit 20
**Status:** RESOLVED (Commit 21)
**Schweregrad:** MEDIUM
**Betroffene Module:** agent-plugin-host

### Erwartung
`PluginManagementPanel` zeigt sowohl Plugin-Fehlerzeilen als auch globale Discovery-/Startfehler und bei
globalem Refresh-Fehler „Refresh failed; previous plugin generation remains active.“

### Korrektur
`PluginCatalogSnapshot` trägt `globalFailures` + `generationFailed`; `PluginManagementPanel` rendert eine
globale Statuszeile und die o. g. Meldung. `WorkspaceCatalogListener.onCatalogSnapshot` liefert den
Snapshot (mit Default auf die Legacy-Form, damit bestehende Listener unverändert bleiben).

### Verifikation
`WorkspacePluginTransactionalRefreshTest#globalFailureKeepsPreviousGeneration` prüft das
`generationFailed`-Flag und nicht-leere `globalFailures`; Panel-Rendering ist ein reiner Swing-View.

### Restwirkung
Keine.

### Enablement-Key-Migration (RA-P004, Zusatz)
Der Enablement-Key ist die stabile PF4J-Plugin-ID (`Plugin-Id` aus dem Manifest). Für kompatible Plugins
erzwingt `PluginCompatibilityChecker` `manifestId == descriptor.getId()`, d. h. die bisher persistierten
Keys (Descriptor-ID) sind bereits identisch mit der PF4J-ID — die Migration ist ein verifizierter No-op.
Keine stillen Mehrdeutigkeiten.
# Research MVP (Solon MCP / ACP / Playwright4j, Commits 30–39) — problem log

Fortlaufende IDs `MCP-Pnnn`. Feasibility probes gegen Maven Central durchgeführt.

## MCP-P001 — playwright4j:0.1.0 ist Java-21-Bytecode → Java-21-Browser-MCP-Sidecar (kein Host-Blocker)

**Erkannt in:** Commit 32 (browser MCP) — Vorab-Feasibility
**Status:** WORKAROUND
**Schweregrad:** MEDIUM
**Betroffene Module:** browser-mcp-server-java21 (neu), browser-api

### Erwartung
Browserzugriff über MCP; `com.aresstack:playwright4j:0.1.0` verwenden.

### Beobachtung
`playwright4j:0.1.0` ist **Bytecode-Major 65 (Java 21)** und zieht `com.microsoft.playwright:driver-bundle`
(Node + Browser-Binaries) nach. Es gehört daher **nicht in ein Java-8-Modul** und nicht in den AskAI-Host.

### Analyse (korrigiert)
Das ist **kein** MVP-Blocker. Da der Browserzugriff ohnehin über MCP (Prozessgrenze) läuft, ist die saubere
Lösung ein separater **Java-21-Browser-MCP-Sidecar-Prozess**, den der Java-8-Host nur über den MCP-Client
anspricht. Vorteile: kein Java-21-Bytecode im Host, Browserabsturz reißt AskAI nicht mit, Ressourcen
pro-Prozess schließbar, keine Classloader-Konflikte.

### Gewähltes Zwischenverhalten
Neues Modul `:browser-mcp-server-java21` (Java-21-Toolchain, `playwright4j` + `solon-ai-mcp`), separates
ausführbares Artefakt, **nicht** im AskAI-Fat-JAR, nur über den Java-8-MCP-Client angesprochen. Der Host
kennt nur den Browser-MCP-Endpoint + die flachen Tools (`web_search/open/read/links/follow/back`).
Optionaler `HttpURLConnection`+jsoup-Fallback für statische Seiten hinter demselben Browser-Port (nicht als
Ersatz für den Sidecar, nicht als zweiter Tool-Satz).

### Spätere Entscheidung
Browser-Binary-/Node-Runtime-Bedarf des `driver-bundle` ehrlich dokumentieren (Packaging), Sidecar + Tools +
deterministische Tests implementieren (Commit 32).

## MCP-P002 — ACP-SDK ist veröffentlicht und Java-8-tauglich (frühere Aussage war falsch)

**Erkannt in:** Commit 34 — Vorab-Feasibility (korrigiert)
**Status:** RESOLVED
**Schweregrad:** —
**Betroffene Module:** acp-client-api, acp-solon-client

### Erwartung
`org.noear:acp-sdk` / Solon-ACP hinter einem gekapselten ACP-Adapter nutzen (keine Eigenimplementierung).

### Beobachtung / Korrektur
Die frühere „404"-Schlussfolgerung beruhte auf **frei geratenen Versionen** (0.1.0/0.9.0/1.0.0). Mit den
tatsächlichen Koordinaten lösen alle Artefakte auf und sind **Java-8-Bytecode**:

```
org.noear:acp-sdk:3.10.1        → resolved
org.noear:solon-ai-acp:3.10.1   → resolved
org.noear:solon-ai-mcp:3.10.1   → resolved
```

Bytecode-Prüfung des vollständigen Graphen (48 Jars) inkl. `reactor-core:3.7.4` und `jackson:2.19.4`:
**kein Jar mit Classfile-Major > 52** — d. h. alle produktiven Klassen sind Java 8. Solon selbst bewirbt
Java-8- bis Java-25-Kompatibilität.

### Auswirkung
Kein Blocker mehr. Commits 34–36 (ACP-Adapter, externer Agent, Loop) sind regulär umsetzbar; Reactor/ACP-SDK
bleiben im Adaptermodul gekapselt.

### Verifikation
`gradle`-Resolve + `unzip`/`od`-Bytecode-Major-Check aller 48 transitiven Jars (Ergebnis: alle ≤ 52).

## MCP-P003 — Marketplace-Quelle übernommen

**Erkannt in:** Commit 31 (marketplace)
**Status:** RESOLVED
**Schweregrad:** —
**Betroffene Module:** mcp-marketplace

### Auflösung
Die Marketplace-Quellen aus dem bereitgestellten `mcp-marketplace-swing-java8.zip` wurden in Commit
`5439076` verbatim als Modul `:mcp-marketplace` übernommen (nicht neu geschrieben) und um das neutrale
`McpServerConfiguration`-Modell + `McpInstallOptionMapper` ergänzt. Installation und Runtime-Aktivierung
sind strikt getrennt (gespeicherte Konfiguration immer `enabled=false`; kein Prozess-/Verbindungscode im
Modul). 7 Tests grün; Isolation verifiziert (kein Solon im Marketplace, kein Marketplace in der Runtime-API).

## MCP-P004 — Commit 30 vervollständigt: echter Solon streamable-HTTP-Transport

**Erkannt in:** Commit 30 (mcp runtime foundation)
**Status:** RESOLVED
**Schweregrad:** —
**Betroffene Module:** mcp-solon-runtime (neu)

### Auflösung
`:mcp-solon-runtime` implementiert `SolonMcpServerRuntime implements McpServerRegistry` mit einem echten
Solon streamable-HTTP-Server auf `127.0.0.1` (freier Port), per-Endpoint-Token im Pfad, dynamischem
Tool-Update und idempotentem Shutdown. `SolonMcpServerRuntimeTest` beweist den vollen Round-trip mit einem
**echten Solon-MCP-Client**: tools/list, `ping`→pong, `echo`→text, dynamisches Entfernen (frischer Client
sieht nur noch `ping`), Abweisung eines falschen Tokens, kontrollierter Shutdown. `InProcessMcpServerRegistry`
bleibt die deterministische Testimplementierung; die produktive Runtime ist Solon. (1 Test grün.)

### Verwendete Grundlage (Java 8, verifiziert)
```
org.noear:solon:3.10.1                  (Solon.start/stopBlock)
org.noear:solon-boot-jdkhttp:3.10.1     (JDK-HTTP-Server, Java 8)
org.noear:solon-ai-mcp:3.10.1           (McpServerEndpointProvider, McpClientProvider)
```

### Ursprüngliche Beobachtung (historisch, behoben)
Vor der Vervollständigung war nur die transportfreie `InProcessMcpServerRegistry` geliefert; die Begründung
„der reale Transport wird erst vom Agenten gebraucht" war nicht tragfähig — Commit 30 sollte den Transport
isoliert beweisen. Dies ist durch die obige Auflösung erledigt.

## MCP-P005 — Playwright-Treiber-Orchestrierung im Sidecar noch nicht implementiert

**Erkannt in:** Commit 32 (browser MCP sidecar)
**Status:** RESOLVED (Commit 36B)
**Schweregrad:** HIGH (historisch)
**Betroffene Module:** browser-mcp-server-java21

### Erwartung
Der Java-21-Sidecar bedient `web_*` real über Playwright (JS-Navigation, dynamische Seiten).

### Ursprüngliche Beobachtung (historisch, präzisiert in 36B)
Die erste Analyse sah in `playwright4j:0.1.0` nur die rohe GraalJS-Runtime (`GraalPlaywrightRuntime`,
`drainTransports()`/`runDueTimers()`) ohne `Browser`/`Page`-Fassade. Die Analyse der realen Projektquellen
(Commit 36B) hat das **korrigiert**: playwright4j behält das offizielle Playwright-Java-API-Modell bei.

### Auflösung (36B) — reales Nutzungsmodell von playwright4j 0.1.0
- Konsument nutzt das **offizielle** `com.microsoft.playwright:playwright:1.59.0`-API (`Playwright.create()`
  → `chromium().launch(channel)` → `BrowserContext`/`Page`), dabei werden dessen Module `driver` und
  `driver-bundle` **ausgeschlossen** (sonst zwei `Driver`-Klassen im Fat-Jar, Gewinner reihenfolgeabhängig).
- `playwright4j:0.1.0` liefert eine Ersatz-`com.microsoft.playwright.impl.driver.Driver`-Klasse: statt eines
  Node-Prozesses startet sie einen **zweiten Java-Prozess** (`com.aresstack.playwright4j.driver.GraalDriverMain`,
  gleiches `java.home`, gleicher Classpath), der den offiziellen Playwright-Core-**JS**-Driver auf GraalJS
  hostet. Kein Node.js nötig; die GraalJS-Orchestrierung liegt vollständig in playwright4j.
- Das JS-Driver-Paket kommt aus `com.microsoft.playwright:driver-bundle:1.59.0` (runtime-Abhängigkeit von
  playwright4j, Classpath-Ressourcen `driver/<platform>/package/...`).
- Browser: **lokal installiertes** Chrome/Edge über den Playwright-Channel-Mechanismus
  (`BROWSER_CHANNEL=chrome|msedge`); Firefox/WebKit werden von playwright4j 0.1.0 nicht unterstützt.

### Umsetzung im Sidecar (36B)
`PlaywrightDriver` (enge Naht, nur `PlaywrightPageState`-Werte) → `Playwright4jDriver` (offizielles API;
Popups sofort geschlossen, Downloads abgelehnt, Request-Interception gegen private Ziele bei strikter Policy,
Navigations-Timeouts aus `BrowserLimits`) → `PlaywrightBrowserSession` (URL-Policy **vor Navigation und auf
der finalen URL nach Redirects**; Text-/Link-Limits; snapshot-stabile `link-1..n`; `web_search` = Navigation
zu konfigurierbarem `--search-url={query}`-Provider hinter `WebSearchProvider`, ohne Konfiguration ehrlicher
Fehler). `PlaywrightCapabilityProbe` liefert strukturierte Status (`READY`/`INCOMPATIBLE_DRIVER`/
`DRIVER_BUNDLE_NOT_FOUND`/`BROWSER_NOT_INSTALLED`/`BROWSER_START_FAILED` — bewusst kein pauschales
NOT_INSTALLED; ein `NODE_RUNTIME_NOT_FOUND` existiert nicht, da playwright4j ohne Node arbeitet — das
Äquivalent ist `DRIVER_BUNDLE_NOT_FOUND`). Shutdown-Reihenfolge Page→Context→Browser→Playwright (beendet den
GraalJS-Kindprozess), idempotent, zusätzlich als JVM-Shutdown-Hook.

### Keine impliziten Downloads/Installationen
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` und `PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1` sind fest gesetzt;
die Probe sucht Browser nur an Standard-Installationspfaden. Ressourcen-Extraktion durch playwright4j selbst:
Quelle = `driver-bundle`-Jar-Ressourcen, Ziel = `%TEMP%/playwright4j-driver` (Package-Assets + ggf.
`node.exe`-Kompatibilitäts-Launcher + Konfigdatei), bleibt zwischen Läufen bestehen (Marker-Datei), wird von
uns nicht gelöscht; gestartete Prozesse: Sidecar-JVM → GraalDriverMain-JVM → Chromium-Prozessbaum.

### Paketierungs-Ehrlichkeit (korrigiert in 36C)
Ein Fat-/Uber-Jar ist für den Sidecar **nicht tragfähig**: Beim Umpacken von GraalJS/Truffle geht das
`Multi-Release: true`-Manifest-Attribut verloren und doppelte `META-INF/services`-Providerdateien werden
von der Jar-Task kollabiert → zur Laufzeit `InternalError: Truffle could not be initialized ... Multi-Release
classes are not configured correctly` (real beobachtet im 36C-Test; der 36B-Livetest lief auf dem Gradle-
Classpath und konnte das nicht zeigen). Auslieferung daher als **Thin-Jar + `lib/`-Verzeichnis**
(`build/sidecar/browser-mcp-sidecar-<version>.jar` mit `Class-Path`-Manifest über 62 unveränderte
Dependency-Jars inkl. GraalJS und driver-bundle aller Plattformen); `java -jar` funktioniert, und der mit
`-cp <jar>` gestartete GraalDriverMain-Kindprozess erbt die Abhängigkeiten über denselben Jar-`Class-Path`-
Mechanismus. Es gibt genau **eine** `Driver.class` auf dem Classpath (upstream-`driver`-Modul ausgeschlossen).
**Nicht** enthalten und extern vorausgesetzt: ein lokal installiertes Chrome oder Edge. Temporär entstehen
die o. g. Dateien unter `%TEMP%/playwright4j-driver`.

### Verifikation
- `PlaywrightCapabilityProbeTest` (Status-Klassifikation mit Fakes + realer Classpath-Beweis, dass die
  playwright4j-`Driver`-Ersatzklasse effektiv ist und das JS-Bundle vorliegt).
- `PlaywrightBrowserSessionTest` (8 Tests, Fake-Driver: Mapping/Trunkierung, Link-IDs pro Snapshot,
  Policy vor Navigation und **nach Redirect** (geblockte Seite wird nicht current), unbekannte Link-ID,
  Suche ehrlich/konfiguriert, idempotenter Close, Request-Filter-Klassifikation inkl. 169.254.169.254).
- `PlaywrightLiveBrowserTest` — **erfolgreicher echter Browserlauf, dokumentierte Testumgebung:**
  Windows 11, lokal installiertes Google Chrome (stable), Gradle-Toolchain Java 21, JVM-Modus (Truffle-
  Fallback ohne JVMCI). Lokaler `com.sun.net.httpserver`-Testserver, dessen Text und Links **nur per
  JavaScript** entstehen: `web_open` → „Rendered by JavaScript" sichtbar, JS-erzeugter Link über
  `web_links`-Semantik, `follow` → zweite dynamische Seite, `back` → Startseite, 302-Redirect → Snapshot
  trägt finale URL, `file:`-Scheme geblockt, doppelter `close()` sauber. Laufzeit 10,2 s. Der Test ist
  environment-gated (JUnit-Assume auf Probe-READY): ohne installierten Browser SKIP mit Statusmeldung,
  kein stilles Grün.
- Full Build `./gradlew build --rerun-tasks` grün; ohne Browserruntime bleibt der Build grün (Probe-Status
  statt Fehlschlag).
- **36C — derselbe Loop gegen den echten Sidecar-Prozess:** `ResearchLoopPlaywrightSidecarIntegrationTest`
  spawnt das reale Sidecar-Thin-Jar mit Java 21 (Gradle-Toolchain), zwei lokale HTTP-Server (zwei Hosts über
  Ports) liefern Seiten, deren Text und Links **nur per JavaScript** entstehen; der **unveränderte**
  `ResearchLoop` mit dem **unveränderten** `SolonToolInvoker` erreicht SUFFICIENT_EVIDENCE über den
  produktiven Commit-37-Acceptance-Pfad (`CaptureStore`/`SourceAcceptanceService`/Repository/Index).
  Environment-gated (skippt lesbar ohne Java-21-Toolchain oder Browser), kein STATIC_HTTP-Fallback.

### Anmerkung (kein Blocker)
Ohne JVMCI läuft GraalJS im Truffle-Interpreter-Modus (Warnung im Log, geringere Geschwindigkeit). Für das
MVP akzeptiert; optional später JVMCI/GraalVM-JDK für den Sidecar.

## MCP-P006 — Produktiver Lucene-Indexadapter noch nicht verdrahtet

**Erkannt in:** Commit 37 (source lifecycle / indexing boundary)
**Status:** DEFERRED
**Schweregrad:** LOW
**Betroffene Module:** research-agent-ui-plugin (capture)

### Erwartung
Ein produktiver Lucene-Adapter hinter `ResearchSearchIndex`, als aus den Source-Records rebuildbare,
abgeleitete Sicht.

### Gewähltes Zwischenverhalten
Der Port ist final (`index`/`remove`/`rebuild`), die Indexgrenze steht (nur ACCEPTED wird indexiert; Index-
Fehler verliert nie die Source, markiert nur STALE; `rebuild()` beweist die Derived-View-Eigenschaft) und der
deterministische In-Memory-Adapter deckt Tests + MVP ab. Keine halbfertige Lucene-Integration in Commit 37.

### Spätere Entscheidung
Eigener Slice: Lucene-Adapter (Java-8-taugliche Lucene-Version prüfen) hinter demselben Port; Rebuild aus
`ResearchSourceRepository`.

## MCP-P007 — UI-Konfigurationsfläche für den produktiven Research-Modus noch nicht verdrahtet

**Erkannt in:** Commit 38 (produktives Host-Wiring)
**Status:** RESOLVED (Commit 40)
**Schweregrad:** MEDIUM (historisch)
**Betroffene Module:** research-agent-ui-plugin (agent), askai-app

### Erwartung
Der AskAI-Benutzer kann den produktiven Research-Modus in der UI aktivieren (Pfade zu Agent-Jar,
Java-21-Launcher, Sidecar-Jar, Browser-Channel, Search-Provider), und `ResearchAgentSessionFactory`
erzeugt dann Sessions über die produktive Kette statt über den Fake.

### Beobachtung
Das produktive Wiring existiert vollständig und ist end-to-end getestet
(`ResearchRuntimeGenerationSwitch` → `ProductiveResearchBackendFactory` →
`ProductiveResearchSessionResources` → `AcpResearchSessionBackend`;
`ProductiveResearchMvpEndToEndTest`). Was fehlt, ist ausschließlich die UI-Konfigurationsfläche:
`ResearchAgentSessionFactory` (der PF4J-Einstieg der Chat-Workspaces) instanziiert weiterhin den
sichtbaren, deterministischen `FakeResearchSessionBackend`, weil die `ResearchRuntimeConfig`-Pfade
im UI-Host noch nirgends erfasst/persistiert werden. Kein stiller Fallback: FAKE ist der dokumentierte
Clickdummy-Modus, nicht eine heimliche Degradation des produktiven Modus.

### Verifikation
`ProductiveResearchMvpEndToEndTest` beweist die produktive Kette ohne UI (echter Agentprozess, echter
Sidecar, echtes Chrome, JS-only-Inhalte, zwei Hosts, Datei-Repository, Generationswechsel + Rollback).

### Spätere Entscheidung
Eigener Slice: Konfigurationsdialog/Settings-Persistenz für `ResearchRuntimeConfig` + Umschaltung
`ResearchBackendMode` FAKE→ACP in `ResearchAgentSessionFactory` (inkl. Anzeige des Sidecar-Readiness-
Status im UI). Bis dahin bleibt der produktive Modus programmatisch/testseitig nutzbar.


### Auflösung (Commit 40)
Typisiertes, persistiertes `ResearchRuntimeSettings`-Modell (WorkspaceStateStore-Keys, keine freien Maps,
keine System-Properties) + `ResearchRuntimeSettingsPanel` als "Runtime"-View im Research-Workspace:
Backend-Modus (FAKE klar als Clickdummy-/Entwicklungsmodus gekennzeichnet / Productive ACP), Agent-Jar,
Java-8-/Java-21-Launcher, Sidecar-Jar, Browser-Channel, Headless, Such-URL. "Check requirements" läuft auf
einem Hintergrund-Thread (nie EDT, Busy-Anzeige, Ergebnis via SwingUtilities) und meldet jede Voraussetzung
einzeln — Browser-Status über die echte Sidecar-Probe (READY/INCOMPATIBLE_DRIVER/DRIVER_BUNDLE_NOT_FOUND/
BROWSER_NOT_INSTALLED/BROWSER_START_FAILED). `ResearchAgentSessionFactory` schaltet ausschließlich anhand
des validierten Modus (FAKE→Fake-Backend ohne Service-Lookup; ACP→ProductiveResearchBackendFactory über die
neutralen Host-Services `AgentHostContext.getService(McpServerRegistry/McpToolClientFactory/
AcpAgentConnector)`, die AskAI jetzt bereitstellt — Solon-MCP-Runtime lazy). Produktive Fehler schlagen
sichtbar fehl; kein Fallback in beide Richtungen. Verifikation: `ResearchRuntimeModeTest` (6 Tests) +
environment-gated `ProductiveModeFactorySmokeTest` (echte produktive Session aus persistierten Settings über
die reale Factory, RESEARCH_MCP_READY im Sink, idempotenter Close). `verifyFatJarExclusions` weiter grün
(keine Research-Klassen im Fat-Jar; die Solon/ACP-HOST-Runtime gehört bewusst zum Host).

## MCP-P008 — Chat-Kommandos treiben im produktiven Modus die Host-State-Machine noch nicht

**Erkannt in:** Commit 40 (UI-Konfigurationsfläche)
**Status:** RESOLVED (Commit 41)
**Schweregrad:** MEDIUM (historisch)
**Betroffene Module:** research-agent-ui-plugin (agent/host)

### Erwartung
Im produktiven Modus führen die Slash-Kommandos/Approval-Buttons der UI dieselben Zustandsübergänge aus wie
im FAKE-Modus (dort transportiert der Fake-Backend die Kommandos in die State-Machine).

### Beobachtung
Die produktive Session besitzt ihre autoritative State-Machine in `ProductiveResearchSessionResources`
(`dispatch()` inkl. Tool-Refresh — programmatisch voll funktionsfähig, im 38-E2E bewiesen). Der
`AcpResearchSessionBackend` ist bewusst ein reiner Adapter (`canExecute=false`), und die Brücke
UI-Session → `resources.dispatch(...)` ist noch nicht verdrahtet. Folge: Der produktive Modus lässt sich aus
AskAI wählen, validieren und starten (MCP-P007), aber Phasenübergänge (z. B. bis RESEARCH/running, Approve)
sind aus der Chat-UI noch nicht auslösbar; der Agent kann daher aus der UI heraus noch keine Sources
akzeptieren.

### Spätere Entscheidung
Eigener Slice: ResearchAgentSession erhält optional die Session-Resources und routet die typisierten
Kontrollmethoden (approve/requestChanges/pause/resume/cancel + Phasenkommandos) auf `dispatch()`; Events
SESSION_STATE_CHANGED aus Transitionen in den Event-Strom spiegeln (Memento), damit State-View und
Tool-Refresh synchron bleiben. Eng verwandt mit RA-P002 (Restore).


### Auflösung (Commit 41)
Neutraler Command-Port `ResearchSessionCommandPort` (`submitPrompt(text)` / `dispatch(ResearchCommandType,
argument)` mit strukturiertem `ResearchCommandDispatchResult`: ACCEPTED / COMMAND_NOT_AVAILABLE /
INVALID_PHASE / SESSION_NOT_ACTIVE / SESSION_CLOSED / DISPATCH_FAILED) — implementiert von
`ResearchAgentSession`, die im produktiven Modus ihre Session-Resources BESITZT und strukturierte Kommandos
auf `ProductiveResearchSessionResources.dispatch()` routet (State-Machine bleibt einzige Autorität, Tool-Set
wird mitpubliziert; das Memento wird über den UiExecutor in das Session-View-Modell gespiegelt und die
Observer werden isoliert benachrichtigt). Bestehende fachliche `ResearchCommandType`-Kommandos, keine zweite
Hierarchie; `AcpResearchSessionBackend` bleibt reiner Transport-Adapter (`canExecute=false`, per Test
fixiert). Chat-Oberfläche: `/do <command>` mit Completion exakt aus dem live erlaubten Set (Source of Truth =
State-Machine, keine UI-Phasenregeln); approve/request-changes/pause/resume/cancel routen produktiv über den
Port; keine synthetischen Chatnachrichten. PAUSE/CANCEL stoppen zusätzlich den laufenden Agent-Turn
(Transportbelang). Session-/Generationsbindung: nach Close/Retirement liefert der Port SESSION_CLOSED —
alte UI-Aktionen erreichen nie eine fremde Session.

### Verifikation
- `ProductiveCommandBridgeTest` (4 Tests): Kommandos erreichen die produktive Maschine (START→…→
  START_RESEARCH ⇒ RESEARCH/running, View gespiegelt), Text bleibt auf submitPrompt, INVALID_PHASE/
  COMMAND_NOT_AVAILABLE/SESSION_CLOSED strukturiert, PAUSE cancelt den Agent-Turn, kaputter Observer
  blockiert nichts, Backend-Adapter ohne Phasenlogik.
- `ProductiveModeFactorySmokeTest` (environment-gated, bestanden): der VOLLE Benutzerpfad über die Fassade —
  persistierte Settings → reale Factory → produktive Session → Phasenkommandos über den PORT (nie
  `resources.dispatch()` direkt) → autonomer Lauf gegen JS-only-Seiten auf zwei Hosts →
  `RESEARCH_RUN_STOPPED: SUFFICIENT_EVIDENCE`, genau ein PHASE_READY → Benutzeraktion
  REQUEST_EVIDENCE_REVIEW über den Port → Host-Maschine in waiting_approval → Session-View zeigt es.
- FAKE-Modus implementiert denselben Port (dispatch → Fake-Backend), Full Build `--rerun-tasks` grün.

## RA-P003 — Erster ACP-Prompt nach Session-Start geht gelegentlich verloren

**Erkannt in:** Commit 42 (Akzeptanztest-Läufe; Signatur bereits in einem Commit-41-Full-Build)
**Status:** OPEN
**Schweregrad:** MEDIUM
**Betroffene Module:** acp-solon-client / research-agent-runtime (Transportgrenze)

### Beobachtung
Selten (beobachtet ~1 von 3–4 vollen `--rerun-tasks`-Builds) erreicht der ERSTE `session/prompt` nach
`session/new` den Agentenprozess nicht: der Agent loggt keinen Turn-Start, es kommen keinerlei Updates und
kein Terminal-Callback; spätere Prompts derselben Session funktionieren. `session/new` selbst (inkl. realem
MCP-Readiness-Roundtrip) war zuvor erfolgreich. Reproduktion ist lastabhängig, nicht deterministisch.

### Präzisierte Beobachtung (Commit 46)
Neue Diagnose mit Agent-STDERR: Die Prompts KOMMEN beim Agenten an (Turn-Start wird geloggt) — es ist der
ANTWORTPFAD des ersten Turns, der sich verklemmt (keinerlei Updates werden zugestellt). Resends über
dieselbe Session verschlimmern das nur: Der Prompt-Dispatcher verwirft die Updates überlappender Prompts
korrekt als late-drop.

### Gewähltes Zwischenverhalten
Der Akzeptanztest sendet den ersten Prompt genau EINMAL (120 s); trifft die Race, wird der Lauf LAUT unter
dieser ID übersprungen (Assume) statt die Abnahme zu verfälschen. Produktiv gibt es bewusst KEIN
automatisches Prompt-Resend (Gefahr doppelter Turns).

### Spätere Entscheidung
Transport-Analyse an der acp-sdk/Stdio-Grenze (Initialisierungs-/Prompt-Race direkt nach `session/new`):
Request-Journal im Connector, ggf. Ready-Handshake vor dem ersten Prompt oder Upstream-Issue an acp-sdk.

---

# Research Agent — problems and open questions (2026-08-01)

## RA-P1 — BUG: agent timeout when opening a NEW research session

Symptom (user report): with an active research session the main chat runs into an agent timeout;
plain (non-agent) chat works. A fresh research session reproduces it.

Diagnosis so far: the headless end-to-end reproduction (`GuiChainReproductionTest`: real session
factory + ACP agent + sidecar + reranker on the app classpath) PASSES on
`feature/search-strategy-wiring` with freshly staged jars — session/new, greeting and activation
work. The bug is environmental or Swing/PF4J-side. Prime suspects, in order:

1. MIXED runtime paths in the Runtime tab: `agentJar` switched to
   `X:\Projects\askai-java8-main\build\research-runtime\research-agent-runtime.jar` while
   `sidecarJar`/`sidecarJava` may still point at the old checkout
   `C:\Projects\askai-java8\build\research-runtime\…` — mixed generations are untested. Both paths
   must come from the SAME dist directory.
2. The greeting turn (TeamAgent on the main model) exceeding the 180 s ACP request timeout when the
   main model cold-loads while the DirectML runtime occupies the GPU.
3. Plain `run` now also boots the local model runtime (since the DirectML staging fix) — GPU
   contention at session start.

Needed to close: the first `[research-agent] …` / `session/new` error line from the app console of a
failing start + the effective `agentJar`/`sidecarJar` values from the Runtime tab.

## RA-P2 — Settings: session-based gear-menu integration (DONE; open questions below)

Implemented as ordered (2026-08-01): the research settings are PLUGIN settings of the selected agent
in the HOST's existing gear menu at the composer — shown only while this tab's agent session is
active, session-based (per-tab values over `SessionScopedWorkspaceStateStore`: session scope wins,
agent-global values serve as read-only template for fresh sessions, restore finds its own values).
`/settings`, the settings chat card and the Runtime/Search-Settings ARTIFACT tabs are removed; the
artifact area holds work products only. The existing panels were MOVED, not reinvented.

Open questions (need an answer before further work here):

1. Natural-language settings changes ("nimm Brave statt Bing") as TeamAgent PROPOSALS with a host
   confirmation card — wanted as a follow-up, or out of scope?
2. Central management UI for provider credentials (today: encrypted files under
   `~/agents/research/providers/`, no UI) — where in the central AskAI configuration should it live?
3. A RUNNING session currently keeps its backend when settings change (next session applies them).
   Should a search-strategy change reconfigure the RUNNING session's next research run too
   (snapshot re-publish mid-session), or stay next-session-only?

## RA-P3 — Research methodology domain core (CORE IMPLEMENTED; integration next)

Implemented (2026-08-01) as `:research-domain`, `:research-knowledge-pipeline` and
`:research-text-opennlp` — pure Java 8, no Swing/ACP/MCP/Solon/Ollama/OpenNLP/Lucene in the core:
aggregates with stable ids + revisions, no-delete as a domain invariant (supersede/exclude/stale,
never delete), approval-gated confirmed-state changes, buildEvidenceReview + EvidenceBaseline-first,
structural+semantic passage segmentation (window similarity, min/max sizes), HAC topic clustering,
evidence-led concept/outline proposals, gap analysis producing detail-research plans. Proven by the
deterministic fachliche vertical slice test (brief → captures → sentences → passages → topics →
outline → claims/evidence → review → baseline) plus nine domain-invariant tests.

NEXT (separate integration commits, in order — each needs no open questions):
1. File repository adapter (project directory stays source of truth; Lucene/vector/cluster indexes as
   rebuildable derived views under `derived/`).
2. Embedding adapter over the central AskAI embedding selection (port exists; central seam may need a
   hand-off like the reranker snapshot).
3. Runtime orchestration: ResearchLoop/agent feed captures into the pipeline and consume
   DetailedResearchPlans as search orders (no if-cascades in the loop).
4. State-graph mapping + memento migration to SCOPING → ORIENTATION → CONCEPT → DETAILED_RESEARCH →
   EVIDENCE → DRAFT → REVIEW → FINALIZATION (deliberately NOT in the core slice; no second state
   machine).
5. Evidence-review UI on top of buildEvidenceReview (the real "Belege pruefen").

Open questions (blocking only the steps that need them):
- Embedding model hand-off: session snapshot file like reranker/inference, or direct port via
  AgentHostContext? (affects step 2)
- Where the pipeline RUNS — agent runtime process (owns captures) vs host (owns artifact stores)?
  (affects step 3)
- Migration of existing markdown artifacts (concept.md/outline.md/findings.md) to projections of the
  structured objects without breaking restored sessions. (affects step 4)

## RA-P4 — Recurring on-disk corruption on this machine (X:)

Six incidents in one working day: four corrupt `.class` files (zeroed/bit-flipped constant pools)
in four modules, one corrupt git blob (repaired via `git fetch --refetch` from origin), and one
SOURCE file (`ResearchProjectMetadata.java`) fully zero-filled in place — hidden from `git status`
by the stat cache (same size+mtime). A scan of all tracked files found no further zeroed file.
Action: `chkdsk X: /f`, SMART check, ideally a RAM test. Until then: push early, run `git fsck`
occasionally, treat impossible compile errors as possible corruption.

## RA-P5 — Role reversal: the AI assists, it does not govern the process (DONE)

Corrected (2026-08-01): commit f701ea had made the TeamAgent the "sole productive conversation
source" and framed it as a process controller (three stages, phase/run-state, exact allowed command
set, propose-a-command). That turned a research assistant into a gatekeeper. Reverted the ROLE
without rolling back the model/ACP infrastructure:

- System prompt rewritten to an ASSISTANT that sits beside the user: progressive assistance (accept
  concrete input, treat a short reply as an answer to the last question, ask ONE question when
  something is open, OFFER 2-5 defaults when the user does not know, user statements beat
  suggestions, summarize when enough). No stages, no commands, no phases, no output-protocol talk.
- State context no longer advertises phase/run-state/allowed-commands; it carries only the
  accumulated research context (confirmed vs working scope).
- Structured turn extended with understoodFacts/suggestedFacts/openQuestions/readyForBrief; the
  assistant history now records the model's own understood/open facts so short replies accumulate
  context (the "history bug").
- Command policing removed: no illegalCommand nudge, no COMMAND_REJECTED — a legacy proposedCommand
  the host does not allow is silently ignored. The scope→brief step is decided by CODE
  (readyForBrief + a working question emits the SCOPE_PROPOSAL wire); the host still re-validates and
  owns the transition. The state machine remains the invisible authority.
- Regression test scripts the exact reported failure (wearables → audio und video → smartwatches →
  keine ahnung): context accumulates, defaults offered on "no idea", no machinery/command talk, a
  ResearchBrief proposal results.

This role split now also governs the coming Concept, Gap Analysis, Evidence Review and Drafting
integration (RA-P3): the domain owns the process, the AI interprets/proposes/fills gaps.

## RA-P6 — Provider-specific search settings + DataForSEO editor/playground (in progress)

Done (2026-08-01, branch arch → dev):
- Correctness: DataForSEO Organic Live Advanced depth default 100 → 10 (max stays 200; 100/700 was
  wrong for this endpoint). The request mapper still caps effective depth at min(maximumResults,
  depth), which also caps request cost.
- Per-provider edit-draft model (DataForSeoSettingsDraft): loaded once, edited in place (switching
  provider cards never reloads/discards), saved through the existing ProviderConfigurationService.
  The stored secret is never decrypted into the UI; a freshly typed password is encrypted on save,
  otherwise the stored one is preserved. Playground backend (DataForSeoPlayground) runs ONE search
  over the SAME productive adapter, with an auth-free request preview.
- UI: the shared "Search engine / Search locale" fields are removed from the Runtime tab. A new
  "Search" gear tab (SearchProviderCardsPanel) has ONE provider picker + a CardLayout: Browser (SERP,
  default) and a full DataForSEO editor (Account / SERP / Advanced / Playground). Selecting a
  provider persists the strategy selection (browser default); the provider combo only picks the card.

Deferred to follow-ups (per the spec's own staging — Brave/BrightData only after DataForSEO is
solid), tracked here:
- The full DataForSEO Advanced grid (stop-crawl targets table, PAA click depth, calculate_rectangles
  + screen width/height/ratio, max_crawl_pages, search_param/se_domain/tag/target, find/ignore
  targets). The underlying DTO fields are preserved untouched; the Advanced tab currently states this.
- Searchable location/language pickers backed by the DataForSEO catalog endpoints
  (/serp/{engine}/locations, /languages). Current editor uses name + code fields with the Germany
  (2276) / German (de) defaults.
- Brave and Bright Data provider cards (their own host panels) after the DataForSEO slice is verified.
- The gear dialog has no explicit Save/Cancel hook; the panel currently commits the DataForSEO draft
  and the provider selection when the dialog stops showing. If the host adds a real Save/Cancel, wire
  save()/dispose() to those instead.

Not verifiable here: the Swing layout and the live DataForSEO playground HTTP call could not be
exercised headlessly. The non-Swing logic (depth/mapper, draft save/secret-preserve, playground
request preview) is unit-tested.
