# Manual acceptance & smoke test — audio DSP profiles

Scope: the configurable audio-processing profile slice (branch `feature/audio-capture-dsp`,
integration commit `764615c`). This checklist verifies the profile editor, persistence, the
transcription-profile selection, the microphone runtime path, and that packaging (Fat JAR + merged
GraalJS service files) stays intact for PAC and future GraalJS users.

Every item is tagged with how far it has been verified:

- **[AUTO]** — covered by an automated unit test in this repository (green in `./gradlew build`).
- **[PACKAGING]** — verified by inspecting the built Fat JAR, not by running the app.
- **[MANUAL]** — still to be executed by a human against the running application; not yet done here.

A green unit run alone is **not** sufficient for the `[MANUAL]` items, because the Fat JAR's
`META-INF/services` merge and the real microphone / PAC paths are only exercised at runtime.

---

## 0. Build the artifact under test

Use the repository's Gradle wrapper (Java 8). In the sandbox/offline workflow:

```bash
ROOT_DIR="$(pwd)"
export GRADLE_USER_HOME="$ROOT_DIR/.chatgpt/gradle-home"
bash "$ROOT_DIR/gradlew" :askai-app:test
bash "$ROOT_DIR/gradlew" :askai-app:build
```

Result of the full build in this integration: **BUILD SUCCESSFUL** (all modules, including
`mergeServiceFiles` and `fatJar`). The Fat JAR is:

```
askai-app/build/libs/askai-0.1.0.jar
```

Inspect the resolved runtime classpath if a Graal version conflict is suspected:

```bash
bash "$ROOT_DIR/gradlew" :askai-app:dependencies --configuration runtimeClasspath
```

---

## Checklist

### 1. Start the application from the actually built Fat JAR — [MANUAL]
- Run `java -jar askai-app/build/libs/askai-0.1.0.jar`.
- Expect the AskAI main window to open with the FlatLaf look and feel installed.
- Do **not** substitute an IntelliJ run or a plain Gradle-classpath launch — the point is to exercise
  the merged `META-INF/services` inside the Fat JAR.

### 2. Open the audio-processing dialog — [MANUAL]
- Menu `Configuration → Audio processing` opens the pipeline editor page.
- The same page must also open from Chat settings via the `Edit profiles…` button.

### 3. Select the built-in profile `default-speech` — [MANUAL] / [AUTO for shape]
- The profile dropdown lists `Default speech` and any user profiles.
- **[AUTO]** The default profile's block order is asserted by
  `audio-dsp .../profile/AudioProcessingProfilesTest` and matches:
  `Channel mixer → Low-pass → Resampler → DC offset removal → High-pass → Noise gate → Compressor → Limiter`.

### 4. Default profile: editable as a working copy, but never overwritten or deleted — [AUTO] + [MANUAL]
- **[AUTO]** `askai-app .../audio/FileAudioProfileRepositoryTest` and the repository code
  (`FileAudioProfileRepository.save` / `.delete`) throw `IllegalArgumentException` for
  `default-speech` / any `builtIn` profile. The guard is enforced in the **repository**, not only in
  the UI.
- **[MANUAL]** In the editor, change a parameter on the default profile; confirm `Save` is redirected
  to `Save as…` (no silent overwrite) and that `Delete` is disabled for the default profile.
- **[MANUAL]** `Reset changes` reloads the built-in definition.

### 5. `Save as…` creates a new user profile with its own id — [AUTO] + [MANUAL]
- **[AUTO]** `FileAudioProfileRepositoryTest` verifies `saveAs` produces a non-built-in profile with a
  fresh id and that it can be reloaded.
- **[MANUAL]** In the editor, `Save as… "Speech test"` creates a new selectable profile; the default
  profile is unchanged.

### 6. Add / remove / reorder / reconnect blocks — [MANUAL]
- `Add block` inserts a new Low-pass block; `Remove block` removes only the selected block.
- Drag a block horizontally to reorder it; the arrows re-draw between neighbours.
- No automatic re-sorting occurs — the manual order is preserved.

### 7. Configure the resampler as a visible block — [AUTO] + [MANUAL]
- **[MANUAL]** The Resampler appears as a normal block (not a hidden special case) and its
  `targetRateHz` / `quality` / `hiddenAntiAliasing` parameters are editable in the inspector.
- **[AUTO]** `audio-dsp .../dsp/AudioProfileProcessorTest` asserts the resampler updates the output
  sample rate; `Pcm16ResamplerTest` covers `FAST` / `BALANCED` / `HIGH` and the 3-arg back-compat entry
  point.

### 8. Inspector edits keep canvas and profile model in sync — [MANUAL] + [AUTO for canvas render]
- **[MANUAL]** Change a block type or parameter in the inspector and `Apply`; the canvas label and the
  underlying block definition reflect the change (stable block id preserved across a type switch).
- **[AUTO]** `askai-app .../ui/AudioPipelineCanvasRenderTest` renders the canvas headless into a
  `BufferedImage` (default profile and empty pipeline) without throwing.

### 9. Persist a user profile, restart, verify persistence — [AUTO] + [MANUAL]
- **[AUTO]** `FileAudioProfileRepositoryTest` verifies a saved profile reloads with its block order and
  parameters intact (atomic `.properties` write with `REPLACE_EXISTING` fallback under
  `.askai/audio-profiles/`).
- **[MANUAL]** Save `Speech test`, close the app, restart from the Fat JAR, and confirm the profile is
  still listed and unchanged.

### 10. Select the profile in the transcription area — [AUTO for persistence] + [MANUAL]
- **[MANUAL]** Chat settings → `Transcription profile` lists the profiles; selecting `Speech test`
  persists the choice.
- **[AUTO]** `askai-app .../stt/SpeechToTextConfigurationProfileTest` covers the
  `stt.audioProcessingProfile` id round-trip and its accessor/`withAudioProcessingProfileId`.

### 11. A profile change takes effect on the next recording without a restart — [MANUAL] + [AUTO for wiring]
- **[AUTO]** `DefaultRecordingNormalizer` resolves the profile through a `Supplier` on **every**
  `normalize(...)` call (verified by construction and exercised in the normalizer tests).
- **[MANUAL]** Record once, edit + save the selected profile, record again **without restarting**, and
  confirm the new profile version is applied (visible e.g. in a changed target format/level).

### 12. Full microphone path: capture → DSP → normalized WAV → transcription — [MANUAL] + [AUTO for DSP stage]
- **[MANUAL]** Perform a real dictation with an audio-capable Ollama model reachable; confirm text is
  inserted at the caret.
- **[AUTO]** The DSP stage itself (`SpeechAudioNormalizer` → `AudioProfileProcessor`) is covered by
  `audio-dsp .../application/SpeechAudioNormalizerTest` and `AudioProfileProcessorTest`; the default
  profile reproduces the previous normalization (canonical 16 kHz mono 16-bit WAV).

### 13. Quality analysis still evaluates the RAW signal — [AUTO] + [MANUAL]
- **[AUTO]** `SpeechAudioNormalizer` measures level/clipping on the raw samples **before** running the
  profile, so the limiter cannot mask input clipping (covered by the normalizer test).
- **[MANUAL]** Record an intentionally clipped input and confirm the quality warning still fires.

### 14. Delete a user profile → fallback to `default-speech` — [AUTO] + [MANUAL]
- **[AUTO]** `FileAudioProfileRepository.findById` returns the built-in default for a missing id; the
  chat panel's profile combo falls back to the first entry when the stored id is gone
  (`refreshAudioProfiles`).
- **[MANUAL]** With `Speech test` selected for transcription, delete it in the editor; confirm the
  transcription profile falls back to `Default speech` and recording still works.

### 15. Unknown profile id in the configuration → default fallback — [AUTO] + [MANUAL]
- **[AUTO]** `SpeechToTextConfigurationProfileTest` covers loading an old/absent id as `default-speech`;
  `findById(unknown)` resolves to the default.
- **[MANUAL]** Edit `.askai/askai.properties`, set `stt.audioProcessingProfile=does-not-exist`,
  start the Fat JAR, and confirm the app uses `Default speech` without error.

### 16. Corrupt / unreadable profile file does not crash the app — [AUTO for load tolerance] + [MANUAL]
- **[AUTO]** `FileAudioProfileRepository.findAll` skips unreadable/broken user files and always returns
  at least the built-in default (repository behaviour).
- **[MANUAL]** Put a malformed `.properties` file in `.askai/audio-profiles/`, start the Fat JAR,
  and confirm the app starts, lists the valid profiles + the default, and logs (not crashes) on the bad
  file.

### 17. Editor readable in FlatLaf Light and Dark — [MANUAL]
- **[MANUAL]** Switch the look and feel to FlatLaf Light and Dark (rebuild the chat/editor panels as
  needed) and confirm the canvas blocks, arrows, selection marker, "Bypassed" state, inspector fields,
  and status line stay readable (colors come from `UIManager`, no hard-coded light backgrounds).

### 18. PAC evaluation from the started Fat JAR with a real PAC config — [MANUAL]
- **[MANUAL]** Configure a real PAC URL / WScript discovery in `Configuration → Network`, trigger a
  proxied HTTP request (e.g. a Hugging Face search), and confirm PAC resolution still works from the
  Fat JAR (GraalJS evaluates the PAC script). This is the runtime proof that the DSP integration did not
  break the GraalJS provider path.

### 19. PAC / Mermaid / other GraalJS users are not broken by Fat JAR service files — [PACKAGING] + [MANUAL]
- **[PACKAGING]** Verified by inspecting `askai-0.1.0.jar`:
  - `uk/me/berndporr/iirj/*` present (the new IIR dependency; pure Java, no Truffle — no service-file
    conflict);
  - `com/aresstack/audio/profile/*` and `com/aresstack/audio/dsp/AudioProfileProcessor` present;
  - the merged GraalJS/Truffle service providers are retained, e.g.
    `META-INF/services/com.oracle.truffle.api.TruffleLanguage$Provider`,
    `META-INF/services/com.oracle.truffle.js.runtime.Evaluator`,
    `META-INF/services/org.graalvm.polyglot.impl.AbstractPolyglotImpl`.
  - Reproduce with:
    ```bash
    JAR=askai-app/build/libs/askai-0.1.0.jar
    unzip -l "$JAR" | grep -E "berndporr/iirj|audio/profile/AudioProcessingProfiles|META-INF/services/.*(truffle|polyglot)"
    ```
  Rules kept: `mergeServiceFiles` is not removed, there is no plain `DuplicatesStrategy.EXCLUDE` without
  merged providers, no second manual GraalJS loader, and no Graal dependency was force-added/excluded.
- **[MANUAL]** When the Mermaid slice lands, re-run this packaging check plus a live PAC + Mermaid smoke
  test together, since `mermaid-java` also uses GraalJS and shares the same `META-INF/services` merge.

---

## Summary of current verification status

- **[AUTO] (done, green):** default profile block order (3), repository default-profile protection (4),
  `saveAs` new id (5), resampler updates rate + quality/back-compat (7), canvas headless render (8),
  save/reload persistence (9), transcription-profile id round-trip (10), profile `Supplier` wiring (11),
  DSP normalization stage + raw-signal quality (12, 13), missing-id fallback (14, 15), corrupt-file
  tolerance (16). Full `./gradlew build` green including `fatJar`.
- **[PACKAGING] (done):** Fat JAR contains iirj + the new profile/DSP classes and retains the merged
  GraalJS/Truffle service providers (19).
- **[MANUAL] (not yet executed here):** starting the Fat JAR (1), opening the editor and driving it
  (2, 6, 8-manual), the live restart-persistence check (9-manual), the real microphone dictation path
  (11-manual, 12-manual), clipped-input quality warning (13-manual), delete→fallback in the UI
  (14-manual), the properties-edit fallbacks (15-manual, 16-manual), Light/Dark theme review (17), and
  the **live PAC evaluation from the Fat JAR** (18). These require a GUI + audio + network environment
  and are the remaining gate before marking audio non-experimental.
