package com.aresstack.askai.tts;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * The host-side Windows OS voice (System.Speech / SAPI via an external PowerShell process),
 * culture-matched per language — the same proven external-process route the whole speech output
 * uses (a child process has its own audio session; the app JVM's per-app routing cannot mute it).
 * One utterance at a time; {@link #stop()} kills the process.
 */
public final class WindowsSapiVoice {

    private final Object lock = new Object();
    private Process process;

    /**
     * Speak BLOCKING until the voice finished (or {@link #stop()}).
     *
     * @return "" when spoken, otherwise a human-readable reason
     */
    public String speak(String text, String languageCode, int timeoutSeconds) {
        return speak(text, languageCode, timeoutSeconds, 0);
    }

    /** @param rate SAPI speaking rate -10..10 (0 = default; negative = slower, more deliberate). */
    public String speak(String text, String languageCode, int timeoutSeconds, int rate) {
        int clampedRate = Math.max(-10, Math.min(10, rate));
        String culture = "de".equals(languageCode) ? "de-DE"
                : "en".equals(languageCode) ? "en-US" : null;
        String selectVoice = culture == null ? "" :
                "try { $s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::NotSet, "
                        + "[System.Speech.Synthesis.VoiceAge]::NotSet, 0, "
                        + "(New-Object System.Globalization.CultureInfo('" + culture + "'))) } "
                        + "catch { }; ";
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "[Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false; "
                            + "Add-Type -AssemblyName System.Speech; "
                            + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                            + selectVoice
                            + (clampedRate == 0 ? "" : "$s.Rate = " + clampedRate + "; ")
                            + "$s.Speak([Console]::In.ReadToEnd())");
            builder.redirectErrorStream(true);
            Process current = builder.start();
            synchronized (lock) {
                process = current;
            }
            OutputStream stdin = current.getOutputStream();
            stdin.write(text.getBytes("UTF-8"));
            stdin.close();
            boolean finished = current.waitFor(Math.max(10, timeoutSeconds), TimeUnit.SECONDS);
            synchronized (lock) {
                if (process == current) {
                    process = null;
                }
            }
            if (!finished) {
                current.destroy();
                return "Windows voice did not finish within " + timeoutSeconds + "s";
            }
            return current.exitValue() == 0 ? ""
                    : "Windows voice failed (powershell exit " + current.exitValue() + ")";
        } catch (Exception failed) {
            return "Windows voice unavailable: " + failed;
        }
    }

    /** Kill the current utterance; no-op when silent. */
    public void stop() {
        Process toKill;
        synchronized (lock) {
            toKill = process;
            process = null;
        }
        if (toKill != null) {
            toKill.destroy();
        }
    }
}
