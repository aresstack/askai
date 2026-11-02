# Manual acceptance — format-neutral batch audio

Covers what cannot be asserted in the JVM unit tests: the compressed codec providers
(`javasound-mp3/vorbis/flac/aac` are `runtimeOnly`) and their survival in the fat jar via
`mergeServiceFiles`. The automated tests (`AudioProfileFormatPreservationTest`,
`BatchAudioPreparationServiceTest`) already cover WAV decoding, format preservation and pass-through.

## Build

Run once online (or with `--refresh-dependencies`) on Java 8, 11 or 17 so the codec artifacts land in
the Gradle cache:

```bash
./gradlew --refresh-dependencies clean :askai-app:test :askai-app:fatJar
```

Then launch the fresh fat jar:

```bash
java -jar askai-app/build/libs/askai-*.jar
```

## Checks

Prerequisite: an Ollama server with at least one `audio`-capable model.

### Codec providers (Chat)

Open **Chat → Transcribe an audio file** and transcribe one file of each format. Each must upload
without a "no decoder"/"unsupported format" error:

- [ ] MP3
- [ ] M4A/AAC
- [ ] OGG
- [ ] FLAC
- [ ] WAV

(Chat sends the original file untouched, so this proves the fat jar can at least *select* every format
and that the STT backend accepts the container.)

### Format neutrality (Batch)

Add files in the **Batch** view and run *Start batch*:

1. [ ] MP3 with the **Off** profile → the original file is sent untouched (no temp WAV).
2. [ ] MP3 with a **Gain**-only profile → transcription succeeds; audio is decoded, gained, re-exported.
3. [ ] 48 kHz **stereo** WAV with a profile **without** a resampler → the processed audio stays 48 kHz
   stereo (inspect the intermediate temp WAV if needed; no forced 16 kHz/mono).
4. [ ] 48 kHz stereo WAV with a profile containing an explicit **Resampler (16000)** → output is 16 kHz.
5. [ ] Stereo file **without** a channel block → stays stereo; **with** a **Channel mixer** → becomes mono.
6. [ ] One OGG, one FLAC and one M4A each transcribe via a DSP profile.
7. [ ] A batch with several files × models × profiles completes; each `<audio-file>.md` is written next
   to its source and temporary `askai-batch-*.wav` files are gone afterwards (success and on failure).

### Provider survival in the jar (optional, direct)

Confirm the merged descriptor lists the compressed readers:

```bash
unzip -p askai-app/build/libs/askai-*.jar \
  META-INF/services/javax.sound.sampled.spi.AudioFileReader
```

It should contain the tianscar mp3/vorbis/flac/aac reader classes alongside the JDK WAVE reader.
