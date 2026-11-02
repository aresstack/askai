package com.aresstack.askai.stt;

/**
 * The LOGARITHMIC level scale (dB) shared by the recording waveform, the level bar and the
 * voice-activation gate: 0 percent = the -60 dB floor, 100 percent = full scale. Linear percent
 * made normal speech a barely visible foothill next to a knock on the microphone — but for
 * PLACING the gate the QUIET sounds matter, not the loud ones: in dB, the noise floor and speech
 * spread over the display's height instead of huddling at the bottom.
 */
public final class DecibelLevelScale {

    /** The display floor: -60 dB (relative to 16-bit full scale) maps to 0 percent. */
    public static final double FLOOR_DB = 60.0;

    private DecibelLevelScale() {
    }

    /** @return the dB-scaled percent (0-100) for a 16-bit peak magnitude. */
    public static int percentFromPeak(int peakMagnitude) {
        if (peakMagnitude <= 0) {
            return 0;
        }
        double db = 20.0 * Math.log10(Math.min(peakMagnitude, Short.MAX_VALUE)
                / (double) Short.MAX_VALUE);
        double percent = (db + FLOOR_DB) * (100.0 / FLOOR_DB);
        return (int) Math.max(0, Math.min(100, Math.round(percent)));
    }

    /**
     * One-time migration of a threshold stored on the OLD linear scale: the same physical level,
     * expressed in dB percent (e.g. linear 8 → ≈63 — quiet sounds sit much higher in dB).
     */
    public static int dbPercentFromLinearPercent(int linearPercent) {
        if (linearPercent <= 0) {
            return 0;
        }
        double amplitude = Math.min(100, linearPercent) / 100.0;
        double db = 20.0 * Math.log10(amplitude);
        double percent = (db + FLOOR_DB) * (100.0 / FLOOR_DB);
        return (int) Math.max(0, Math.min(95, Math.round(percent)));
    }
}
