package com.aresstack.askai.stt.whisper;

import com.aresstack.askai.speech.AudioModelResolver;
import com.aresstack.askai.speech.SpeechTranscriber;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WhisperDictationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private WhisperStore store() {
        return new WhisperStore(temp.getRoot().toPath().resolve("stt"));
    }

    private static void install(WhisperStore store, WhisperModel model) throws Exception {
        Files.createDirectories(store.modelFile(model).getParent());
        Files.write(store.modelFile(model), new byte[]{1});
    }

    private static void installEngine(WhisperStore store) throws Exception {
        Files.createDirectories(store.engineDirectory());
        Files.write(store.engineExecutable(), new byte[]{1});
    }

    @Test
    public void whisperIsTheDefaultBackendAndANewFileKeepsIt() throws Exception {
        DictationSettingsStore settings = new DictationSettingsStore(temp.getRoot().toPath().resolve("stt.properties"));
        assertEquals(DictationSettings.Engine.WHISPER, settings.load().getEngine());
        settings.save(settings.load().withEngine(DictationSettings.Engine.OLLAMA).withWhisperModelId("base"));
        assertEquals(DictationSettings.Engine.OLLAMA, settings.load().getEngine());
        assertEquals("base", settings.load().getWhisperModelId());
        assertEquals(DictationSettings.Engine.WHISPER, DictationSettings.parseEngine("NONSENSE"));
    }

    @Test
    public void withoutEngineOrModelOllamaTranscribes() throws Exception {
        WhisperStore store = store();
        assertNull(store.modelFor(DictationSettings.defaults()));
        install(store, WhisperModelCatalog.findById("base"));
        assertNull("no engine yet", store.modelFor(DictationSettings.defaults()));
        installEngine(store);
        assertEquals("base", store.modelFor(DictationSettings.defaults()).getId());
        assertNull("Ollama chosen explicitly",
                store.modelFor(DictationSettings.defaults().withEngine(DictationSettings.Engine.OLLAMA)));
    }

    @Test
    public void theRecommendedInstalledModelWinsUnlessOneIsChosen() throws Exception {
        WhisperStore store = store();
        installEngine(store);
        install(store, WhisperModelCatalog.findById("base"));
        install(store, WhisperModelCatalog.findById("small"));
        assertEquals("small", store.modelFor(DictationSettings.defaults()).getId());
        assertEquals("base", store.modelFor(DictationSettings.defaults().withWhisperModelId("base")).getId());
        store.uninstallModel(WhisperModelCatalog.findById("base"));
        assertEquals("a removed choice falls back to an installed model", "small",
                store.modelFor(DictationSettings.defaults().withWhisperModelId("base")).getId());
    }

    @Test
    public void theHuggingFaceTreeYieldsTheModelsHashAndSize() {
        String json = "[{\"type\":\"file\",\"oid\":\"x\",\"size\":10,\"path\":\"README.md\"},"
                + "{\"type\":\"file\",\"oid\":\"y\",\"size\":487601967,\"lfs\":{\"oid\":\""
                + "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b\",\"size\":487601967,"
                + "\"pointerSize\":135},\"path\":\"ggml-small.bin\"}]";
        WhisperInstaller.FileMetadata metadata = WhisperInstaller.parseMetadata(json, "ggml-small.bin");
        assertEquals("1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", metadata.sha256);
        assertEquals(487601967L, metadata.sizeBytes);
        assertNull(WhisperInstaller.parseMetadata(json, "ggml-base.bin"));
    }

    @Test
    public void theCommandWritesPlainTextWithLanguageAndPrompt() {
        WhisperStore store = store();
        WhisperModel model = WhisperModelCatalog.recommended();
        Path base = temp.getRoot().toPath().resolve("rec-whisper");
        List<String> command = WhisperSpeechTranscriber.command(store, model, new File("rec.wav"), base,
                "de", "AskAI, Serp4J");
        assertEquals(store.engineExecutable().toString(), command.get(0));
        assertTrue(command.contains(store.modelFile(model).toString()));
        assertEquals("de", command.get(command.indexOf("-l") + 1));
        assertTrue(command.contains("-otxt"));
        assertEquals(base.toString(), command.get(command.indexOf("-of") + 1));
        assertEquals("AskAI, Serp4J", command.get(command.indexOf("--prompt") + 1));
        List<String> auto = WhisperSpeechTranscriber.command(store, model, new File("rec.wav"), base, "", "");
        assertEquals("auto", auto.get(auto.indexOf("-l") + 1));
        assertTrue(!auto.contains("--prompt"));
    }

    @Test
    public void routingSendsWhisperModelsToWhisperAndTheRestToOllama() throws Exception {
        final WhisperModel[] active = {null};
        AudioModelResolver ollamaResolver = requested -> AudioModelResolver.AudioModelResolution.resolved("gemma4:e2b");
        AudioModelResolver resolver = DictationBackendRouting.resolver(ollamaResolver, () -> active[0]);
        assertEquals("gemma4:e2b", resolver.resolve("Automatic").getModelName());
        active[0] = WhisperModelCatalog.recommended();
        assertEquals("@whisper:small", resolver.resolve("Automatic").getModelName());

        SpeechTranscriber transcriber = DictationBackendRouting.transcriber(fixed("ollama"), fixed("whisper"));
        assertEquals("whisper", transcriber.transcribe(new SpeechTranscriber.TranscriptionInput(
                new File("a.wav"), "@whisper:small", "de", "")));
        assertEquals("an Ollama model named whisper:* stays with Ollama", "ollama",
                transcriber.transcribe(new SpeechTranscriber.TranscriptionInput(new File("a.wav"), "whisper:small", "de", "")));
        assertEquals("ollama", transcriber.transcribe(new SpeechTranscriber.TranscriptionInput(
                new File("a.wav"), "gemma4:e2b", "de", "")));
    }

    @Test
    public void aMissingWhisperModelFailsWithAReason() {
        WhisperSpeechTranscriber transcriber = new WhisperSpeechTranscriber(store(), () -> 10);
        try {
            transcriber.transcribe(new SpeechTranscriber.TranscriptionInput(new File("a.wav"), "@whisper:small", "", ""));
            org.junit.Assert.fail("expected a failure");
        } catch (SpeechTranscriber.SpeechTranscriberException expected) {
            assertTrue(expected.getMessage().contains("not installed"));
        }
    }

    @Test
    public void whileWhisperTranscribesTheOllamaVersionIsNeverAsked() throws Exception {
        final WhisperModel[] active = {WhisperModelCatalog.recommended()};
        com.aresstack.askai.speech.ServerProbe unreachable = new com.aresstack.askai.speech.ServerProbe() {
            public String serverKey() {
                return "http://127.0.0.1:11434";
            }

            public String version() throws Exception {
                throw new java.io.IOException("unreachable");
            }

            public boolean endpointAvailable() throws Exception {
                throw new java.io.IOException("unreachable");
            }
        };
        com.aresstack.askai.speech.ServerProbe probe = DictationBackendRouting.probe(unreachable, () -> active[0]);
        assertEquals("", probe.version());
        active[0] = null;
        try {
            probe.version();
            org.junit.Assert.fail("Ollama is asked again once it transcribes");
        } catch (java.io.IOException expected) {
            // fine
        }
    }

    private static SpeechTranscriber fixed(final String text) {
        return new SpeechTranscriber() {
            public String transcribe(TranscriptionInput input) {
                return text;
            }

            public void cancel() {
            }

            public int lastHttpStatus() {
                return 0;
            }
        };
    }
}
