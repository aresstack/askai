package com.aresstack.askai.tts;

/**
 * The settings dialog's "Test" next to each speech-output selector: speaks a fixed sample with
 * EXACTLY the persisted selection of that language — the Piper model voice or the culture-matched
 * Windows voice — inside the running app, and returns a human-readable REASON when nothing could
 * be spoken. Diagnosis first: a silent failure is the one outcome this class must never produce.
 */
public final class SpeechOutputTester {

    private final TtsSettingsStore settings;
    private final PiperTtsStore store;
    private final PiperSpeechSynthesizer synthesizer = new PiperSpeechSynthesizer();
    private volatile String lastDetail = "";

    public SpeechOutputTester(TtsSettingsStore settings, PiperTtsStore store) {
        this.settings = settings;
        this.store = store;
    }

    /** What the last test actually did (bytes/rate/engine log) — for the Technical Details. */
    public String lastDetail() {
        return lastDetail;
    }

    /**
     * Blocking (run OFF the EDT): speak the language's sample with the persisted selection.
     *
     * @return "" when audio was produced, otherwise the reason why not
     */
    public String speakSample(String languageCode) {
        TextToSpeechSettings current = settings.load();
        TextToSpeechSettings.Selection selection = current.selectionFor(languageCode);
        String sample = "de".equals(languageCode)
                ? "Dies ist ein Test der Sprachausgabe."
                : "This is a test of the speech output.";
        if (selection.getEngine() == TextToSpeechSettings.Engine.PIPER) {
            PiperVoice voice = PiperVoiceCatalog.findById(selection.getVoiceId());
            if (voice == null) {
                return "Selected voice id is unknown: \"" + selection.getVoiceId() + "\"";
            }
            if (!voice.getLanguageCode().equals(languageCode)) {
                return "Selected voice " + voice.getId() + " does not match language "
                        + languageCode;
            }
            if (!store.isEngineInstalled()) {
                return "Piper engine is missing: " + store.engineExecutable();
            }
            if (!store.isVoiceInstalled(voice)) {
                return "Voice files are missing: " + store.voiceDirectory(voice);
            }
            try {
                PiperSpeechSynthesizer.Utterance utterance = synthesizer.speak(
                        store, voice, sample, current.getStartupTimeoutSeconds());
                lastDetail = voice.getId() + ": " + utterance
                        + (utterance.getEngineLogTail().isEmpty() ? ""
                                : " | piper log: " + utterance.getEngineLogTail());
                if (utterance.getPcmBytes() == 0) {
                    return "Engine ran but produced NO audio. Piper log: "
                            + utterance.getEngineLogTail();
                }
                return "";
            } catch (Exception failed) {
                lastDetail = voice.getId() + ": " + failed;
                return "Model voice failed: " + failed;
            }
        }
        // The Windows OS voice of that language (culture-matched, like the read-aloud fallback).
        return new WindowsSapiVoice().speak(sample, languageCode,
                current.getStartupTimeoutSeconds());
    }
}
